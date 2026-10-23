import Foundation
import Network
import Observation

/// TCP client for the glasses' WiFi link — the opportunistic BULK data plane.
/// The glasses run a SoftAP + TCP server (wifi_link.cpp); the phone joins the
/// AP (see LinkManager) and this class owns the socket.
///
/// Transport policy (V2): BLE stays connected at all times and carries control
/// markers + realtime voice. WiFi exists only to move bulk data (photos)
/// faster when it is measurably faster — the firmware picks the route per
/// image, and the route is whichever transport the 'H' header arrives on.
///
/// Wire format (docs/WIFI_LINK.md): every message is
///     [channel u8][len u16 LE][inner packet]
/// and the inner packet is byte-identical to the matching BLE characteristic
/// payload:
///     channel 1 AUDIO   : legacy — voice rides BLE now; tolerated + dropped
///     channel 2 CONTROL : 'R' hello, 'P' ping echo, 'T' fw stats, …
///     channel 3 IMAGE   : 'H' header, 'I' fragments, 'J' end
/// One ordered TCP stream: no duplicate delivery, no seq gaps, no
/// header/fragment races — the parser is deliberately simpler than BleManager's.
@MainActor
@Observable
final class WifiLink {

    // Frame channels (must match wifi_link.h)
    private static let chAudio: UInt8 = 1
    private static let chControl: UInt8 = 2
    private static let chImage: UInt8 = 3

    /// Max inner packet the firmware accepts per frame (WIFI_FRAME_MAX).
    private static let frameMax = 4096

    enum State: String {
        case idle = "Off"
        case connecting = "Connecting"
        case connected = "Connected"
    }

    struct Stats {
        // Ping / RTT
        var rttMs = 0.0          // last sample; 0 = none yet
        var rttAvgMs = 0.0
        var rttMinMs = 0.0
        var rttMaxMs = 0.0
        var pingsSent = 0
        var pingsLost = 0
        // Throughput (rolled every 2 s) + lifetime totals
        var rxBytesPerSec = 0.0
        var txBytesPerSec = 0.0
        var rxBytesTotal = 0
        var txBytesTotal = 0
        // Per-channel receive accounting
        var rxAudioFrames = 0
        var rxControlFrames = 0
        var rxImageFrames = 0
        var rxImageBytes = 0
        // Lifecycle
        var connects = 0
    }

    // MARK: Observable state

    private(set) var state: State = .idle
    private(set) var endpointDescription = ""
    private(set) var stats = Stats()
    private(set) var connectedAt: Date?

    var socketUptime: TimeInterval { connectedAt.map { Date().timeIntervalSince($0) } ?? 0 }

    // MARK: Callbacks (wired by LinkManager)

    @ObservationIgnored var onPhoto: ((Data) -> Void)?
    @ObservationIgnored var onVisionPhoto: ((Data) -> Void)?
    /// Fires once per completed photo: (bytes, seconds) — for transfer records.
    @ObservationIgnored var onPhotoStats: ((Int, TimeInterval) -> Void)?
    /// Raw firmware 'T' stats packet (payload including the tag byte).
    @ObservationIgnored var onStatsPacket: ((Data) -> Void)?
    @ObservationIgnored var onLog: ((String) -> Void)?
    @ObservationIgnored var onConnected: (() -> Void)?
    @ObservationIgnored var onDisconnected: (() -> Void)?

    // MARK: Private

    @ObservationIgnored private var conn: NWConnection?
    @ObservationIgnored private var rxBuffer = Data()
    @ObservationIgnored private var generation = 0        // invalidates stale handlers

    // Image reassembly (one ordered stream — no gap/orphan recovery needed)
    @ObservationIgnored private var receivingImage = false
    @ObservationIgnored private var discardingVideoFrame = false
    @ObservationIgnored private var pendingImageIsVision = false
    @ObservationIgnored private var imageBuffer = Data()
    @ObservationIgnored private var expectedImageSize = 0
    @ObservationIgnored private var imageStarted = Date()

    // Rolling stats + ping/liveness
    @ObservationIgnored private var statRxBytes = 0
    @ObservationIgnored private var statTxBytes = 0
    @ObservationIgnored private var rttSum = 0.0
    @ObservationIgnored private var rttCount = 0
    @ObservationIgnored private var pingSeq: UInt32 = 0
    @ObservationIgnored private var pingSent: [UInt32: Date] = [:]
    @ObservationIgnored private var missedPings = 0
    @ObservationIgnored private var housekeepingTask: Task<Void, Never>?

    // Anti-doze keepalive: iOS aggressively power-saves the WiFi radio on
    // internet-less APs, which shows up as 1-3 s ACK stalls on the socket.
    // The radio stays awake while the phone is TRANSMITTING, so we trickle a
    // tiny 'K' frame.
    @ObservationIgnored private var keepaliveTask: Task<Void, Never>?

    var isConnected: Bool { state == .connected }

    init() {
        // Every 2 s: roll the rate counters and ping the glasses for live RTT.
        housekeepingTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                guard let self else { return }
                self.stats.rxBytesPerSec = Double(self.statRxBytes) / 2.0
                self.stats.txBytesPerSec = Double(self.statTxBytes) / 2.0
                self.statRxBytes = 0
                self.statTxBytes = 0
                self.sendPing()
            }
        }
    }

    // MARK: Ping / liveness

    private func sendPing() {
        guard isConnected else {
            stats.rttMs = 0
            pingSent.removeAll()
            missedPings = 0
            return
        }
        // Liveness: the previous ping is due back well before the next one
        // goes out (RTT on a working link is ms). Still outstanding = missed.
        if !pingSent.isEmpty {
            missedPings += pingSent.count
            stats.pingsLost += pingSent.count
            pingSent.removeAll()
            if missedPings >= 3 {
                log("3 pings unanswered — Wi-Fi link is dead, dropping the socket")
                teardown(notify: true)
                return
            }
        } else {
            missedPings = 0
        }
        pingSeq &+= 1
        let id = pingSeq
        pingSent[id] = Date()
        stats.pingsSent += 1
        var pkt = Data([UInt8(ascii: "P")])
        withUnsafeBytes(of: id.littleEndian) { pkt.append(contentsOf: $0) }
        sendFrame(channel: Self.chControl, packet: pkt)
    }

    private func recordRtt(_ ms: Double) {
        stats.rttMs = ms
        rttSum += ms
        rttCount += 1
        stats.rttAvgMs = rttSum / Double(rttCount)
        stats.rttMinMs = stats.rttMinMs == 0 ? ms : min(stats.rttMinMs, ms)
        stats.rttMaxMs = max(stats.rttMaxMs, ms)
        missedPings = 0
    }

    // MARK: Connect / disconnect

    func connect(host: String, port: UInt16) {
        disconnect()
        generation += 1
        let gen = generation
        guard let nwPort = NWEndpoint.Port(rawValue: port) else { return }

        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.connectionTimeout = 8
        // Detect a silently dead socket (iOS hopped off the AP back to a
        // network with internet) within seconds, not TCP's default minutes —
        // the fallback + retry logic in LinkManager depends on this firing.
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 3
        tcp.keepaliveInterval = 2
        tcp.keepaliveCount = 3
        let params = NWParameters(tls: nil, tcp: tcp)
        // The glasses are only reachable over WLAN — never let iOS try to
        // route this through cellular or anything else.
        params.requiredInterfaceType = .wifi

        let c = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: params)
        conn = c
        state = .connecting
        endpointDescription = "\(host):\(port)"
        log("connecting to \(host):\(port)…")

        c.stateUpdateHandler = { [weak self] newState in
            Task { @MainActor [weak self] in
                guard let self, self.generation == gen else { return }
                switch newState {
                case .ready:
                    self.log("socket up — waiting for glasses hello")
                    self.receiveLoop(c, gen: gen)
                case .failed(let error):
                    self.log("connection failed: \(error.localizedDescription)")
                    self.teardown(notify: true)
                case .cancelled:
                    break
                default:
                    break
                }
            }
        }
        c.start(queue: .main)

        // Hello watchdog: a socket that opens but never says 'R' is not our
        // firmware (or the AP handed us a stale connection) — give up loudly.
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            guard let self, self.generation == gen, self.state == .connecting else { return }
            self.log("no hello from glasses within 6 s — dropping")
            self.teardown(notify: true)
        }
    }

    func disconnect() {
        guard conn != nil else { return }
        teardown(notify: state == .connected)
    }

    private func startKeepalive() {
        keepaliveTask?.cancel()
        keepaliveTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, self.isConnected else { return }
                self.sendFrame(channel: Self.chControl, packet: Data([UInt8(ascii: "K")]))
                try? await Task.sleep(nanoseconds: 150 * 1_000_000)
            }
        }
    }

    private func teardown(notify: Bool) {
        generation += 1
        keepaliveTask?.cancel()
        keepaliveTask = nil
        conn?.cancel()
        conn = nil
        rxBuffer.removeAll()
        receivingImage = false
        discardingVideoFrame = false
        imageBuffer.removeAll()
        pingSent.removeAll()
        missedPings = 0
        connectedAt = nil
        let wasActive = state != .idle
        state = .idle
        if notify && wasActive { onDisconnected?() }
    }

    // MARK: Receive path

    private func receiveLoop(_ c: NWConnection, gen: Int) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            Task { @MainActor [weak self] in
                guard let self, self.generation == gen else { return }
                if let data, !data.isEmpty {
                    self.statRxBytes += data.count
                    self.stats.rxBytesTotal += data.count
                    self.rxBuffer.append(data)
                    self.parseFrames()
                }
                if isComplete || error != nil {
                    self.log("glasses closed the socket\(error.map { ": \($0.localizedDescription)" } ?? "")")
                    self.teardown(notify: true)
                    return
                }
                self.receiveLoop(c, gen: gen)
            }
        }
    }

    private func parseFrames() {
        while rxBuffer.count >= 3 {
            let base = rxBuffer.startIndex
            let channel = rxBuffer[base]
            let len = Int(rxBuffer[base + 1]) | (Int(rxBuffer[base + 2]) << 8)
            guard len <= Self.frameMax else {
                log("oversized frame (\(len) B) — protocol desync, dropping link")
                teardown(notify: true)
                return
            }
            guard rxBuffer.count >= 3 + len else { return }   // frame incomplete
            let packet = rxBuffer.subdata(in: (base + 3)..<(base + 3 + len))
            rxBuffer.removeSubrange(base..<(base + 3 + len))
            handlePacket(channel: channel, packet)
        }
    }

    private func handlePacket(channel: UInt8, _ packet: Data) {
        guard let first = packet.first else { return }
        switch channel {
        case Self.chAudio:
            // Voice rides BLE in the V2 policy. Tolerate a stray frame from
            // older firmware — count it, one log line, drop it.
            if stats.rxAudioFrames == 0 {
                log("audio frame on Wi-Fi (legacy firmware?) — ignoring; voice rides BLE")
            }
            stats.rxAudioFrames += 1

        case Self.chControl:
            stats.rxControlFrames += 1
            handleControl(first, packet)

        case Self.chImage:
            stats.rxImageFrames += 1
            stats.rxImageBytes += packet.count
            handleImage(first, packet)

        default:
            break
        }
    }

    private func handleControl(_ tag: UInt8, _ packet: Data) {
        switch Character(UnicodeScalar(tag)) {
        case "R":
            // Hello from the firmware: the link is real. Second byte = protocol version.
            let version = packet.count > 1 ? Int(packet[packet.startIndex + 1]) : 0
            state = .connected
            connectedAt = Date()
            stats.connects += 1
            missedPings = 0
            log("glasses hello (protocol v\(version)) — Wi-Fi bulk lane ACTIVE")
            startKeepalive()
            onConnected?()
        case "P":
            // Our ping, echoed back: ['P'][id u32 LE]
            guard packet.count >= 5 else { return }
            let base = packet.startIndex
            let id = packet.subdata(in: (base + 1)..<(base + 5))
                .withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) }.littleEndian
            if let sent = pingSent.removeValue(forKey: id) {
                recordRtt(Date().timeIntervalSince(sent) * 1000.0)
            }
        case "T":
            onStatsPacket?(packet)
        case "V", "W":
            // Live-video markers from older firmware — feature removed.
            log("legacy video marker '\(Character(UnicodeScalar(tag)))' on Wi-Fi — ignored")
        default:
            log("glasses: control 0x\(String(tag, radix: 16))")
        }
    }

    private func handleImage(_ tag: UInt8, _ packet: Data) {
        let base = packet.startIndex
        switch Character(UnicodeScalar(tag)) {
        case "H":
            guard packet.count >= 6 else { return }
            let flags = packet[base + 1]
            discardingVideoFrame = (flags == 0x01)   // legacy live video — drop
            receivingImage = !discardingVideoFrame
            pendingImageIsVision = (flags == 0x02)
            expectedImageSize = Int(packet.subdata(in: (base + 2)..<(base + 6))
                .withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) })
            imageBuffer.removeAll(keepingCapacity: true)
            imageStarted = Date()
            if receivingImage {
                log("photo incoming: \(expectedImageSize) B expected")
            }
        case "I":
            guard packet.count > 2, receivingImage else { return }
            imageBuffer.append(packet.subdata(in: (base + 2)..<packet.endIndex))
        case "J":
            if discardingVideoFrame {
                discardingVideoFrame = false
                imageBuffer.removeAll()
            } else if receivingImage {
                receivingImage = false
                let jpeg = imageBuffer
                imageBuffer = Data()
                let isVision = pendingImageIsVision
                pendingImageIsVision = false
                guard jpeg.count >= 2, jpeg[jpeg.startIndex] == 0xFF, jpeg[jpeg.startIndex + 1] == 0xD8 else {
                    log("photo REJECTED: \(jpeg.count) B, missing JPEG SOI")
                    return
                }
                let elapsed = Date().timeIntervalSince(imageStarted)
                let rate = elapsed > 0 ? Double(jpeg.count) / elapsed / 1024.0 : 0
                log(String(format: "%@ complete: %d B in %.0f ms (%.1f KB/s)",
                           isVision ? "vision" : "photo", jpeg.count, elapsed * 1000.0, rate))
                onPhotoStats?(jpeg.count, elapsed)
                if isVision { onVisionPhoto?(jpeg) } else { onPhoto?(jpeg) }
            }
        default:
            break
        }
    }

    // MARK: Send path

    /// Raw control packet ('f', 'P', 'K', …) — fire and forget.
    func sendControl(_ bytes: [UInt8]) {
        sendFrame(channel: Self.chControl, packet: Data(bytes))
    }

    private func sendFrame(channel: UInt8, packet: Data) {
        guard let conn, state != .idle, packet.count <= Self.frameMax else { return }
        var frame = Data(capacity: packet.count + 3)
        frame.append(channel)
        frame.append(UInt8(packet.count & 0xFF))
        frame.append(UInt8(packet.count >> 8))
        frame.append(packet)
        statTxBytes += frame.count
        stats.txBytesTotal += frame.count
        conn.send(content: frame, completion: .contentProcessed { _ in })
    }

    private func log(_ line: String) {
        onLog?(line)
    }
}
