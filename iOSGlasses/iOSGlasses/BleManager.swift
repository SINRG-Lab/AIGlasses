import Foundation
import CoreBluetooth
import Observation

/// BLE Central for the SINRG Lab AI glasses (ESP32-S3, NimBLE peripheral).
///
/// GATT layout (service aa00):
///   aa01 AUDIO_TX  NOTIFY      mic -> phone   ['A'][seq u8][audio @16 kHz —
///                              µ-law while 'M' voice mode is on, else PCM16]
///   aa02 AUDIO_RX  WRITE_NR    phone -> spkr  ['A'][seq u8][µ-law @24 kHz]
///   aa03 CONTROL   WRITE+NOTIFY markers: 'M'/'m' voice mode, 'S'/'E' stream
///                              start/end, 'X' barge-in, 'P' ping echo,
///                              'F'/'f'→'N' WiFi bootstrap, 'T' fw stats,
///                              legacy image headers
///   aa04 IMAGE_TX  NOTIFY      ['H'][flags][len u32 LE], ['I'][seq][jpeg], ['J'][idx]
///
/// Hard-won CoreBluetooth rules baked in (from bench debugging on macOS —
/// the same stack as iOS):
///  1. Write payloads are clamped to maximumWriteValueLength(for:.withoutResponse)
///     (= negotiated ATT MTU − 3); audio payload = that − 2 header bytes.
///     One byte over = silent truncation.
///  2. Characteristics are resolved per service *instance*, tolerating
///     duplicate UUIDs from stale GATT caches (the newest complete instance wins).
///  3. Auto-reconnect: on ANY disconnect we rescan, reconnect, re-subscribe,
///     rewrite 'M' and reset the seq trackers — the OpenAI session lives
///     independently and survives radio drops.
///  4. Notifications above ~40/s get dropped by the central — that is WHY the
///     audio is µ-law (halves the packet rate). Do not "optimize" to PCM16.
@MainActor
@Observable
final class BleManager: NSObject {

    // MARK: UUIDs

    // CBUUID is immutable in practice but not marked Sendable — safe to share.
    nonisolated(unsafe) static let serviceUUID = CBUUID(string: "0000AA00-1234-5678-ABCD-0E5032C6B1E0")
    nonisolated(unsafe) static let audioTxUUID = CBUUID(string: "0000AA01-1234-5678-ABCD-0E5032C6B1E0")
    nonisolated(unsafe) static let audioRxUUID = CBUUID(string: "0000AA02-1234-5678-ABCD-0E5032C6B1E0")
    nonisolated(unsafe) static let controlUUID = CBUUID(string: "0000AA03-1234-5678-ABCD-0E5032C6B1E0")
    nonisolated(unsafe) static let imageTxUUID = CBUUID(string: "0000AA04-1234-5678-ABCD-0E5032C6B1E0")

    // Downlink pacing: burst 24 KB (covers the glasses' prebuffer fast), then
    // ~1.35x of the 24000 B/s µ-law consumption rate, sustained.
    private static let dlBurst = 24 * 1024
    private static let dlRate = 24000.0 * 1.35

    enum ConnectionState: String {
        case disconnected = "Disconnected"
        case scanning = "Scanning"
        case connecting = "Connecting"
        case connected = "Connected"
    }

    struct LinkStats {
        var recvPerSec = 0.0        // mic frames off the radio
        var acceptedPerSec = 0.0    // after dup/stale filtering
        var dupPerSec = 0.0
        var lostPerSec = 0.0
        var txBytesPerSec = 0.0     // response audio to the glasses
        var rxBytesPerSec = 0.0     // everything off the radio (audio+image+control)
        var rxBytesTotal = 0
        var txBytesTotal = 0
        // Ping / RTT ('P' echo on CONTROL)
        var rttMs = 0.0             // last sample; 0 = none yet
        var rttAvgMs = 0.0
        var rttMinMs = 0.0
        var rttMaxMs = 0.0
        var pingsSent = 0
        var pingsLost = 0
        var imageSeqGapsTotal = 0
        var reconnects = 0
    }

    // MARK: Observable state (read by the UI)

    private(set) var connectionState: ConnectionState = .disconnected
    private(set) var deviceName = ""
    private(set) var attMTU = 0            // negotiated ATT MTU (payload + 3)
    private(set) var stats = LinkStats()

    // MARK: Callbacks (wired by AppModel, all invoked on the main actor)

    @ObservationIgnored var onMicAudio: ((Data) -> Void)?   // PCM16 @16 kHz, seq-filtered
    @ObservationIgnored var onPhoto: ((Data) -> Void)?      // complete JPEG
    @ObservationIgnored var onVisionPhoto: ((Data) -> Void)? // vision-gesture photo → realtime
    /// Fires once per completed photo: (bytes, seconds) — for transfer records.
    @ObservationIgnored var onPhotoStats: ((Int, TimeInterval) -> Void)?
    /// Raw firmware 'T' stats packet from CONTROL (includes the tag byte).
    @ObservationIgnored var onStatsPacket: ((Data) -> Void)?
    @ObservationIgnored var onBargeIn: (() -> Void)?        // 'X' from the glasses
    @ObservationIgnored var onLog: ((String) -> Void)?
    @ObservationIgnored var onConnected: (() -> Void)?
    /// 'N' answer to our 'F': the glasses' SoftAP is up. (ssid, pass, host, port)
    @ObservationIgnored var onWifiInfo: ((String, String, String, UInt16) -> Void)?

    // MARK: Private BLE state

    @ObservationIgnored private var central: CBCentralManager?
    @ObservationIgnored private var peripheral: CBPeripheral?
    @ObservationIgnored private var audioTxChar: CBCharacteristic?
    @ObservationIgnored private var audioRxChar: CBCharacteristic?
    @ObservationIgnored private var controlChar: CBCharacteristic?
    @ObservationIgnored private var imageTxChar: CBCharacteristic?
    @ObservationIgnored private var notifyReadyCount = 0
    @ObservationIgnored private var shouldStayConnected = false
    @ObservationIgnored private var everConnected = false
    @ObservationIgnored private var voiceModeWanted = false
    // 10 s first-connect watchdog: central.connect() never times out on iOS,
    // and a wedged GATT setup (stale cache, notify-enable stall) looks
    // "connecting" forever. The watchdog cancels + rescans instead.
    @ObservationIgnored private var connectAttempt = 0
    @ObservationIgnored private var connectTimeoutTask: Task<Void, Never>?

    // Uplink (mic) sequence tracking
    @ObservationIgnored private var micSeq: Int? = nil

    // Downlink (response audio) queue + pacing
    @ObservationIgnored private var dlQueue = Data()
    @ObservationIgnored private var dlEndRequested = false
    @ObservationIgnored private var dlResponseOpen = false
    @ObservationIgnored private var dlGeneration = 0
    @ObservationIgnored private var dlSenderRunning = false
    @ObservationIgnored private var dlSent = 0
    @ObservationIgnored private var dlStart = Date()
    @ObservationIgnored private var txSeq: UInt8 = 0

    // Image reassembly
    @ObservationIgnored private var receivingImage = false
    @ObservationIgnored private var discardingVideoFrame = false   // legacy fw live video
    @ObservationIgnored private var pendingImageIsVision = false   // header flags 0x02
    @ObservationIgnored private var expectedImageSize = 0
    @ObservationIgnored private var imageBuffer = Data()
    @ObservationIgnored private var imageSeqExpected = 0
    @ObservationIgnored private var imageSeqGaps = 0
    @ObservationIgnored private var imageStarted = Date()
    @ObservationIgnored private var loggedLegacyVideo = false

    // Rolling stats counters (reset every 2 s by the stats loop)
    @ObservationIgnored private var statRecv = 0
    @ObservationIgnored private var statAccepted = 0
    @ObservationIgnored private var statDup = 0
    @ObservationIgnored private var statLost = 0
    @ObservationIgnored private var statTxBytes = 0
    @ObservationIgnored private var statRxBytes = 0
    @ObservationIgnored private var statsTask: Task<Void, Never>?

    // Live RTT ping ('P' on CONTROL, echoed verbatim by the firmware)
    @ObservationIgnored private var pingSeq: UInt32 = 0
    @ObservationIgnored private var pingSent: [UInt32: Date] = [:]
    @ObservationIgnored private var rttSum = 0.0
    @ObservationIgnored private var rttCount = 0
    @ObservationIgnored private var missedPings = 0

    override init() {
        super.init()
        statsTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                guard let self else { return }
                self.rollStats()
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
        // Liveness: a working link echoes in ms, so a ping still outstanding
        // when the next one goes out counts as missed. Three in a row means
        // the link is wedged (CoreBluetooth still says connected, nothing
        // moves) — force a disconnect so the normal rescan path recovers it.
        if !pingSent.isEmpty {
            missedPings += pingSent.count
            stats.pingsLost += pingSent.count
            pingSent.removeAll()
            if missedPings >= 3 {
                log("3 pings unanswered — BLE link is wedged, forcing reconnect")
                missedPings = 0
                if let p = peripheral {
                    central?.cancelPeripheralConnection(p)
                } else {
                    handleDisconnect(nil)
                }
                return
            }
        } else {
            missedPings = 0
        }
        pingSeq &+= 1
        let id = pingSeq
        pingSent[id] = Date()
        stats.pingsSent += 1
        var bytes: [UInt8] = [UInt8(ascii: "P")]
        withUnsafeBytes(of: id.littleEndian) { bytes.append(contentsOf: $0) }
        // Wait for buffer space like every other control write — a bare WWR
        // is silently dropped when CoreBluetooth's buffer is full (e.g. mid
        // TTS burst), which would fake a missed ping. The clock restarts at
        // the actual write so RTT measures the link, not our queue.
        Task {
            await self.awaitCanSendWWR()
            guard self.isConnected, self.pingSent[id] != nil else { return }
            self.pingSent[id] = Date()
            self.writeControlNow(bytes)
        }
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

    // MARK: Public API

    func connect() {
        shouldStayConnected = true
        if central == nil {
            central = CBCentralManager(delegate: self, queue: nil)
            // scan starts from centralManagerDidUpdateState once powered on
        } else {
            startScanIfPossible()
        }
    }

    func disconnect() {
        shouldStayConnected = false
        stopScan()
        cancelResponse()
        if let p = peripheral {
            central?.cancelPeripheralConnection(p)
        }
        connectionState = .disconnected
    }

    var isConnected: Bool { connectionState == .connected }

    /// 'M' enables realtime voice mode (µ-law mic streaming) on the glasses,
    /// 'm' disables it. Remembered so it is re-written after every reconnect.
    func setVoiceMode(_ on: Bool) {
        voiceModeWanted = on
        guard isConnected else { return }
        Task {
            await self.awaitCanSendWWR()
            self.writeControlNow([UInt8(ascii: on ? "M" : "m")])
            self.log("→ '\(on ? "M" : "m")' voice mode \(on ? "on" : "off")")
        }
    }

    /// Queue µ-law @24 kHz response audio. The paced sender writes the 'S2'
    /// start marker before the first frame of each response.
    func enqueueResponseAudio(ulaw: Data) {
        guard !ulaw.isEmpty else { return }
        dlQueue.append(ulaw)
        ensureSender()
    }

    /// The response is complete: flush the queue, then write 'E'.
    func finishResponse() {
        guard dlResponseOpen || !dlQueue.isEmpty else { return }
        dlEndRequested = true
        ensureSender()
    }

    /// Raw control write ('F'/'f' WiFi link on/off, …) with the same
    /// buffer-drain wait as every other control marker.
    func writeControlBytes(_ bytes: [UInt8]) {
        guard isConnected else { return }
        Task {
            await self.awaitCanSendWWR()
            self.writeControlNow(bytes)
        }
    }

    /// Barge-in / voice-off: drop everything immediately. Deliberately no 'E' —
    /// a late 'E' could land after the 'S' of the next response and kill it.
    func cancelResponse() {
        dlQueue.removeAll()
        dlEndRequested = false
        dlResponseOpen = false
        dlGeneration += 1
    }

    // MARK: Scanning / connecting

    private func startScanIfPossible() {
        guard let central, central.state == .poweredOn else { return }
        guard shouldStayConnected, peripheral?.state != .connected else { return }
        guard !central.isScanning else { return }
        central.scanForPeripherals(withServices: [Self.serviceUUID], options: nil)
        connectionState = .scanning
        log("scanning for glasses…")
    }

    private func stopScan() {
        if central?.isScanning == true { central?.stopScan() }
    }

    private func handleDisconnect(_ error: Error?) {
        let wasConnected = connectionState == .connected
        connectAttempt += 1                  // invalidates the connect watchdog
        connectTimeoutTask?.cancel()
        connectTimeoutTask = nil
        audioTxChar = nil
        audioRxChar = nil
        controlChar = nil
        imageTxChar = nil
        notifyReadyCount = 0
        peripheral = nil
        micSeq = nil
        cancelResponse()
        receivingImage = false
        discardingVideoFrame = false
        imageBuffer.removeAll()
        pingSent.removeAll()
        missedPings = 0
        connectionState = .disconnected
        if wasConnected {
            log("BLE dropped\(error.map { ": \($0.localizedDescription)" } ?? "") — reconnecting (voice session continues)")
        }
        if shouldStayConnected {
            if everConnected { stats.reconnects += wasConnected ? 1 : 0 }
            startScanIfPossible()
        }
    }

    // MARK: Characteristic resolution

    /// Resolve the four characteristics from the matching service *instances*.
    /// Stale GATT caches can present duplicate aa00 services; the last complete
    /// instance discovered wins (mirrors the validated Python client, which
    /// prefers the highest handle).
    private func adoptServiceIfComplete(_ service: CBService) {
        guard service.uuid == Self.serviceUUID, let chars = service.characteristics else { return }
        var tx: CBCharacteristic?
        var rx: CBCharacteristic?
        var ctrl: CBCharacteristic?
        var img: CBCharacteristic?
        for c in chars {
            switch c.uuid {
            case Self.audioTxUUID: tx = c
            case Self.audioRxUUID: rx = c
            case Self.controlUUID: ctrl = c
            case Self.imageTxUUID: img = c
            default: break
            }
        }
        guard let tx, let rx, let ctrl else {
            log("service instance incomplete (\(chars.count) chars) — ignoring")
            return
        }
        audioTxChar = tx
        audioRxChar = rx
        controlChar = ctrl
        imageTxChar = img
        if img == nil { log("IMAGE_TX (aa04) not found — photos unavailable") }

        guard let p = peripheral else { return }
        notifyReadyCount = 0
        p.setNotifyValue(true, for: tx)
        p.setNotifyValue(true, for: ctrl)
        if let img { p.setNotifyValue(true, for: img) }
    }

    /// Section-7 robustness: an attempt that stalls anywhere between connect()
    /// and full subscription is abandoned after 10 s. Canceling the peripheral
    /// forces a fresh discovery on the next attempt, which clears the stale
    /// GATT cache that causes most first-connect hangs.
    private func startConnectWatchdog() {
        connectAttempt += 1
        let attempt = connectAttempt
        connectTimeoutTask?.cancel()
        connectTimeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            guard let self, !Task.isCancelled,
                  self.connectAttempt == attempt, self.connectionState != .connected else { return }
            self.log("connect attempt timed out after 10 s — canceling and rescanning")
            self.failConnectAttempt()
        }
    }

    /// Abandon the in-flight connect attempt and go back to scanning.
    private func failConnectAttempt() {
        if let p = peripheral {
            central?.cancelPeripheralConnection(p)
            // A canceled *pending* connect does not always call the
            // didDisconnect delegate — clean up directly. handleDisconnect is
            // idempotent if the delegate fires anyway.
        }
        handleDisconnect(nil)
    }

    private func finishSubscription() {
        // Need AUDIO_TX + CONTROL (+ IMAGE_TX when present) notifying.
        let wanted = imageTxChar == nil ? 2 : 3
        guard notifyReadyCount >= wanted, connectionState != .connected else { return }
        connectTimeoutTask?.cancel()
        connectTimeoutTask = nil
        connectionState = .connected
        let mtu = writePayloadMax + 3
        attMTU = mtu
        micSeq = nil            // fresh seq tracking after every (re)connect
        txSeq = 0
        let reconnected = everConnected
        everConnected = true
        log("\(reconnected ? "RECONNECTED" : "connected") to \(deviceName) — ATT MTU \(mtu), audio payload \(audioPayload) B")
        if voiceModeWanted { setVoiceMode(true) }
        onConnected?()
    }

    // MARK: Payload sizing

    /// Rule 1: clamp to what CoreBluetooth reports for this link.
    /// maximumWriteValueLength(for:.withoutResponse) == negotiated ATT MTU − 3.
    private var writePayloadMax: Int {
        guard let p = peripheral else { return 182 }
        return max(20, min(p.maximumWriteValueLength(for: .withoutResponse), 509))
    }

    /// Audio payload per 'A' frame = link payload − 2 header bytes.
    private var audioPayload: Int { writePayloadMax - 2 }

    // MARK: Uplink — mic frames

    private func handleMicFrame(_ data: Data) {
        guard data.count > 2, data[data.startIndex] == UInt8(ascii: "A") else { return }
        statRecv += 1
        let seq = Int(data[data.startIndex + 1])
        let payload = data.subdata(in: (data.startIndex + 2)..<data.endIndex)
        // Firmware µ-law-encodes mic audio only while realtime voice mode
        // ('M') is active; otherwise it streams raw PCM16 (V2 behavior).
        // Decode per OUR commanded mode — unconditional µ-law decoding turned
        // PCM16 frames into full-scale garbage.
        let pcm = voiceModeWanted ? ULaw.decode(payload) : payload
        if let last = micSeq {
            // Stale double-subscription on CoreBluetooth can deliver the stream
            // twice, interleaved with a lag. Anything at-or-behind the last
            // accepted seq (delta 0 or "negative" mod 256) is a stale copy: drop.
            let delta = (seq - last) & 0xFF
            if delta == 0 || delta >= 200 {
                statDup += 1
                return
            }
            let gap = delta - 1
            if gap > 0 && gap <= 8 {
                // Small real loss: zero-fill to keep server VAD timing sane
                // (sized in forwarded PCM16 bytes, whatever the wire codec).
                statLost += gap
                onMicAudio?(Data(count: gap * pcm.count))
            } else if gap > 8 {
                statLost += 1   // resync after a big jump, don't fill
            }
        }
        micSeq = seq
        statAccepted += 1
        onMicAudio?(pcm)
    }

    // MARK: Downlink — paced response audio

    private func ensureSender() {
        guard !dlSenderRunning else { return }
        dlSenderRunning = true
        Task { [weak self] in
            await self?.drainLoop()
            self?.dlSenderRunning = false
            // More audio may have arrived while we were finishing up.
            if let self, !self.dlQueue.isEmpty || self.dlEndRequested {
                self.ensureSender()
            }
        }
    }

    private func drainLoop() async {
        let gen = dlGeneration
        while gen == dlGeneration {
            if dlQueue.isEmpty {
                if dlEndRequested {
                    if dlResponseOpen {
                        await awaitCanSendWWR()
                        guard gen == dlGeneration else { return }
                        writeControlNow([UInt8(ascii: "E")])
                        log("→ 'E' response end (\(dlSent) B sent)")
                    }
                    dlEndRequested = false
                    dlResponseOpen = false
                }
                return
            }
            if !dlResponseOpen {
                dlResponseOpen = true
                dlSent = 0
                dlStart = Date()
                txSeq = 0
                await awaitCanSendWWR()
                guard gen == dlGeneration else { return }
                // 'S2' = response incoming at 24 kHz µ-law (glasses mute mic +
                // prebuffer). Matches the hardware-validated Python client.
                writeControlNow([UInt8(ascii: "S"), UInt8(ascii: "2")])
                log("→ 'S2' response start (µ-law @24 kHz)")
            }
            // Token bucket: burst then sustained rate.
            let chunkLen = min(audioPayload, dlQueue.count)
            while gen == dlGeneration,
                  Double(dlSent + chunkLen) > Double(Self.dlBurst) + Date().timeIntervalSince(dlStart) * Self.dlRate {
                try? await Task.sleep(nanoseconds: 10_000_000)
            }
            guard gen == dlGeneration else { return }
            await awaitCanSendWWR()
            guard gen == dlGeneration, !dlQueue.isEmpty else { continue }
            guard let p = peripheral, let rx = audioRxChar, p.state == .connected else {
                // Link down mid-response: drop the rest (the glasses reset via
                // their stall watchdog); reconnect logic will resume the session.
                cancelResponse()
                return
            }
            let take = min(min(chunkLen, audioPayload), dlQueue.count)
            var pkt = Data(capacity: take + 2)
            pkt.append(UInt8(ascii: "A"))
            pkt.append(txSeq)
            pkt.append(dlQueue.prefix(take))
            dlQueue.removeFirst(take)
            p.writeValue(pkt, for: rx, type: .withoutResponse)
            txSeq &+= 1
            dlSent += take
            statTxBytes += pkt.count
        }
    }

    /// Wait until the peripheral can accept another write-without-response.
    /// CoreBluetooth SILENTLY DROPS .withoutResponse writes when its internal
    /// buffer is full — polling canSendWriteWithoutResponse is the retry.
    private func awaitCanSendWWR() async {
        var tries = 0
        while let p = peripheral, p.state == .connected,
              !p.canSendWriteWithoutResponse, tries < 500 {
            tries += 1
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
    }

    /// Control markers go write-WITHOUT-response: a with-response write can
    /// fail with ATT "Insufficient Resource" while the peripheral's buffers
    /// are full of mic notifications. ATT is sequential, so ordering versus
    /// the audio writes holds.
    private func writeControlNow(_ bytes: [UInt8]) {
        guard let p = peripheral, let ctrl = controlChar, p.state == .connected else { return }
        p.writeValue(Data(bytes), for: ctrl, type: .withoutResponse)
    }

    // MARK: Control notifications

    private func handleControlNotify(_ data: Data) {
        guard let first = data.first else { return }
        switch Character(UnicodeScalar(first)) {
        case "S":
            log("glasses: recording started")
        case "E":
            log("glasses: recording ended")
        case "X":
            // Barge-in: user pressed the button during playback. The glasses
            // already tore down their speaker — stop sending immediately.
            log("glasses: barge-in ('X') — aborting response audio")
            cancelResponse()
            onBargeIn?()
        case "V", "W":
            // Live video was removed; a stray marker from older firmware is
            // harmless — one log line and move on.
            log("legacy video marker '\(Character(UnicodeScalar(first)))' — ignored (feature removed)")
        case "P":
            // Our ping, echoed back: ['P'][id u32 LE]
            guard data.count >= 5 else { return }
            let id = data.subdata(in: (data.startIndex + 1)..<(data.startIndex + 5))
                .withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) }.littleEndian
            if let sent = pingSent.removeValue(forKey: id) {
                recordRtt(Date().timeIntervalSince(sent) * 1000.0)
            }
        case "T":
            // Firmware link-stats packet, every 5 s. Parsed upstairs.
            onStatsPacket?(data)
        case "N":
            // WiFi bootstrap answer: 'N' + "ssid\npass\nip\nport"
            guard let text = String(data: data.dropFirst(), encoding: .utf8) else { return }
            let parts = text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
            guard parts.count >= 4, let port = UInt16(parts[3]) else {
                log("malformed 'N' WiFi info: \(text)")
                return
            }
            log("glasses WiFi AP: \(parts[0]) @ \(parts[2]):\(parts[3])")
            onWifiInfo?(parts[0], parts[1], parts[2], port)
        case "I":
            // LEGACY (pre-in-band firmware): image header on CONTROL.
            // ['I'][flags][len u32 LE]
            let isVideoFrame = data.count >= 2 && data[data.startIndex + 1] == 0x01
            var expected = 0
            if data.count >= 6 {
                expected = Int(data.subdata(in: (data.startIndex + 2)..<(data.startIndex + 6))
                    .withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) })
            }
            startImageReceive(isVideoFrame: isVideoFrame, expectedSize: expected, legacy: true)
        case "J":
            // LEGACY still-image end marker on CONTROL. Guarded so a stray or
            // duplicate 'J' can't emit an empty image.
            if receivingImage { finishStillImage() }
        default:
            log("glasses: control 0x\(String(first, radix: 16))")
        }
    }

    // MARK: Image reassembly (in-band on IMAGE_TX)

    private func handleImageTx(_ data: Data) {
        guard let first = data.first else { return }
        switch Character(UnicodeScalar(first)) {
        case "H":
            guard data.count >= 6 else { return }
            let flags = data[data.startIndex + 1]
            let isVideoFrame = flags == 0x01
            pendingImageIsVision = (flags == 0x02)   // vision-gesture photo
            let expected = Int(data.subdata(in: (data.startIndex + 2)..<(data.startIndex + 6))
                .withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) })
            startImageReceive(isVideoFrame: isVideoFrame, expectedSize: expected, legacy: false)
        case "J":
            if discardingVideoFrame {
                discardingVideoFrame = false
                imageBuffer.removeAll()
            } else if receivingImage {
                finishStillImage()
            }
        case "I":
            guard data.count > 2 else { return }
            if discardingVideoFrame { return }   // legacy video payload — drop
            let payload = data.subdata(in: (data.startIndex + 2)..<data.endIndex)
            let seq = Int(data[data.startIndex + 1])
            if !receivingImage {
                // Recover a dropped 'H' header: fragments always start at seq 0,
                // so a seq-0 fragment with no open transfer means the header was
                // lost in its connection event — open an implicit transfer.
                // Integrity is still guarded at frame completion. A non-zero seq
                // is a genuine mid-stream orphan and stays dropped.
                if seq == 0 {
                    startImageReceive(isVideoFrame: false, expectedSize: 0, legacy: false)
                    log("image header missed — recovering from seq-0 fragment")
                } else {
                    log("stray image fragment (\(payload.count) B, seq \(seq)) — dropped")
                    return
                }
            }
            if seq != imageSeqExpected {
                let gap = (seq - imageSeqExpected) & 0xFF
                imageSeqGaps += gap
                stats.imageSeqGapsTotal += gap
                log("image SEQ gap: expected \(imageSeqExpected) got \(seq) (~\(gap) lost)")
            }
            imageSeqExpected = (seq + 1) & 0xFF
            imageBuffer.append(payload)
        default:
            break
        }
    }

    private func startImageReceive(isVideoFrame: Bool, expectedSize: Int, legacy: Bool) {
        // Live video was removed; a video-flagged transfer from older firmware
        // is swallowed whole (header + fragments + end) with one log line.
        discardingVideoFrame = isVideoFrame
        receivingImage = !isVideoFrame
        expectedImageSize = expectedSize
        imageSeqExpected = 0
        imageSeqGaps = 0
        imageBuffer.removeAll()
        imageStarted = Date()
        if isVideoFrame {
            if !loggedLegacyVideo {
                loggedLegacyVideo = true
                log("legacy video frame from firmware — discarding (feature removed)")
            }
        } else {
            log("photo incoming\(legacy ? " (legacy header)" : ""): \(expectedSize) B expected")
        }
    }

    private func finishStillImage() {
        receivingImage = false
        let jpeg = imageBuffer
        imageBuffer = Data()
        guard jpeg.count >= 2,
              jpeg[jpeg.startIndex] == 0xFF, jpeg[jpeg.startIndex + 1] == 0xD8 else {
            log("photo REJECTED: \(jpeg.count) B, missing JPEG SOI (seqGaps=\(imageSeqGaps))")
            return
        }
        let sizeNote = expectedImageSize > 0 && jpeg.count != expectedImageSize
            ? " (SIZE MISMATCH, expected \(expectedImageSize))" : ""
        let isVision = pendingImageIsVision
        pendingImageIsVision = false
        let elapsed = Date().timeIntervalSince(imageStarted)
        log(String(format: "%@ complete: %d B in %.0f ms, seqGaps=%d%@",
                   isVision ? "vision" : "photo", jpeg.count, elapsed * 1000.0, imageSeqGaps, sizeNote))
        onPhotoStats?(jpeg.count, elapsed)
        if isVision { onVisionPhoto?(jpeg) } else { onPhoto?(jpeg) }
    }

    // MARK: Stats

    private func rollStats() {
        stats.recvPerSec = Double(statRecv) / 2.0
        stats.acceptedPerSec = Double(statAccepted) / 2.0
        stats.dupPerSec = Double(statDup) / 2.0
        stats.lostPerSec = Double(statLost) / 2.0
        stats.txBytesPerSec = Double(statTxBytes) / 2.0
        stats.rxBytesPerSec = Double(statRxBytes) / 2.0
        stats.txBytesTotal += statTxBytes
        stats.rxBytesTotal += statRxBytes
        statRecv = 0
        statAccepted = 0
        statDup = 0
        statLost = 0
        statTxBytes = 0
        statRxBytes = 0
    }

    private func log(_ line: String) {
        onLog?(line)
    }
}

// MARK: - CBCentralManagerDelegate

// @preconcurrency conformance: CoreBluetooth calls these on the queue passed
// to CBCentralManager(delegate:queue:) — we pass nil, i.e. the main queue,
// which satisfies the MainActor isolation at runtime.
extension BleManager: @preconcurrency CBCentralManagerDelegate {

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            startScanIfPossible()
        case .unauthorized:
            log("Bluetooth permission denied — enable it in iOS Settings")
            connectionState = .disconnected
        case .poweredOff:
            log("Bluetooth is off")
            connectionState = .disconnected
        default:
            break
        }
    }

    func centralManager(_ central: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any],
                        rssi RSSI: NSNumber) {
        guard self.peripheral == nil else { return }
        stopScan()
        self.peripheral = peripheral       // must retain or the connect dies
        deviceName = peripheral.name ?? "AIGlasses"
        connectionState = .connecting
        log("found \(deviceName) (RSSI \(RSSI)) — connecting…")
        startConnectWatchdog()
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.delegate = self
        peripheral.discoverServices([Self.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager,
                        didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard peripheral === self.peripheral else { return }   // stale attempt
        log("connect failed: \(error?.localizedDescription ?? "unknown")")
        handleDisconnect(error)
    }

    func centralManager(_ central: CBCentralManager,
                        didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        guard peripheral === self.peripheral else { return }   // stale attempt
        handleDisconnect(error)
    }
}

// MARK: - CBPeripheralDelegate

extension BleManager: @preconcurrency CBPeripheralDelegate {

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let services = peripheral.services else {
            log("service discovery failed: \(error?.localizedDescription ?? "no services")")
            failConnectAttempt()
            return
        }
        let matches = services.filter { $0.uuid == Self.serviceUUID }
        guard !matches.isEmpty else {
            log("voice service not found on peripheral")
            failConnectAttempt()
            return
        }
        // Discover chars on EVERY matching instance; the last complete one wins.
        for svc in matches {
            peripheral.discoverCharacteristics(nil, for: svc)
        }
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil else {
            log("characteristic discovery failed: \(error!.localizedDescription)")
            return
        }
        adoptServiceIfComplete(service)
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didUpdateNotificationStateFor characteristic: CBCharacteristic,
                    error: Error?) {
        if let error {
            // A notify-enable failure means the GATT session is unusable
            // (usually a stale cache) — abandon the attempt; the rescan's
            // fresh discovery clears it.
            log("notify enable failed for \(characteristic.uuid): \(error.localizedDescription) — rescanning")
            failConnectAttempt()
            return
        }
        guard characteristic.isNotifying else { return }
        notifyReadyCount += 1
        finishSubscription()
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, let data = characteristic.value, !data.isEmpty else { return }
        statRxBytes += data.count
        switch characteristic.uuid {
        case Self.audioTxUUID: handleMicFrame(data)
        case Self.imageTxUUID: handleImageTx(data)
        case Self.controlUUID: handleControlNotify(data)
        default: break
        }
    }
}
