import Foundation
import Observation

/// Unified glasses link: owns the BLE central and the WiFi TCP client and
/// presents one connection to the rest of the app.
///
/// Transport policy (V2 — BLE primary):
///   - BLE stays connected AT ALL TIMES. Control markers and realtime voice
///     (µ-law both directions) always ride BLE.
///   - WiFi is an opt-in BULK lane. The firmware picks the route per image
///     (only when its measurements say WiFi actually beats BLE) and the route
///     is simply whichever transport the 'H' header arrives on.
///
/// WiFi bring-up ("the bulk lane"):
///   1. app writes 'F' on BLE CONTROL
///   2. glasses start their SoftAP and answer 'N' + "ssid\npass\nip\nport"
///   3. the user joins the AP in iOS Settings (no Hotspot entitlement —
///      personal signing teams can't carry it), or already has; we dial the
///      socket regardless, and redial while the toggle stays on
///   4. WifiLink opens the socket, firmware says 'R' → bulk lane active
/// BLE is never released while the socket is up.
@MainActor
@Observable
final class LinkManager {

    enum Transport: String {
        case bluetooth = "Bluetooth"
        case wifi = "Wi-Fi"
    }

    enum WifiPhase: Equatable {
        case off
        case requesting          // 'F' sent, waiting for 'N'
        case joining(String)     // waiting for the phone to be on the AP (ssid)
        case connecting          // TCP dialing
        case active
        case failed(String)
    }

    /// One completed photo transfer, for the Developer view + log.
    struct PhotoTransfer: Identifiable {
        let id = UUID()
        let date = Date()
        let route: Transport
        let bytes: Int
        let seconds: TimeInterval
        var kbPerSec: Double { seconds > 0 ? Double(bytes) / seconds / 1024.0 : 0 }
    }

    /// Firmware-side truth, sent every 5 s as a compact binary 'T' packet on
    /// the BLE CONTROL characteristic. Layout v1 — must stay in lockstep with
    /// `sendStatsPacket()` in S3_App_V2/link.cpp (version bumps on any change):
    ///
    ///   [0]      'T'
    ///   [1]      layout version (1)
    ///   [2..5]   uptime, ms                 u32 LE
    ///   [6..9]   free heap, bytes           u32 LE
    ///   [10..13] free PSRAM, bytes          u32 LE
    ///   [14..15] ATT MTU                    u16 LE
    ///   [16]     PHY tx  (0 unknown, 1 = 1M, 2 = 2M, 3 = coded)
    ///   [17]     PHY rx  (same coding)
    ///   [18]     flags: b0 BLE connected, b1 SoftAP up, b2 WiFi client, b3 route=WiFi
    ///   [19]     STA client RSSI, dBm       i8 (0 = n/a)
    ///   [20..21] BLE connect count          u16 LE
    ///   [22..23] WiFi client connects       u16 LE
    ///   [24..25] pings heard, BLE           u16 LE
    ///   [26..27] pings heard, WiFi          u16 LE
    ///   [28..31] BLE tx B/s                 u32 LE
    ///   [32..35] BLE rx B/s                 u32 LE
    ///   [36..39] WiFi tx B/s                u32 LE
    ///   [40..43] WiFi rx B/s                u32 LE
    ///   [44..47] BLE tx total               u32 LE
    ///   [48..51] BLE rx total               u32 LE
    ///   [52..55] WiFi tx total              u32 LE
    ///   [56..59] WiFi rx total              u32 LE
    ///   [60]     last photo route (0 none, 1 BLE, 2 WiFi)
    ///   [61]     reserved
    ///   [62..65] last photo bytes           u32 LE
    ///   [66..69] last photo ms              u32 LE
    ///   [70..73] WiFi socket uptime, ms     u32 LE (0 = no client attached)
    struct FirmwareStats {
        var version = 0
        var uptimeMs: UInt32 = 0
        var freeHeap: UInt32 = 0
        var freePsram: UInt32 = 0
        var attMtu = 0
        var phyTx = 0
        var phyRx = 0
        var bleConnected = false
        var softApUp = false
        var wifiClientConnected = false
        var routeIsWifi = false
        var staRssi = 0
        var bleConnectCount = 0
        var wifiClientConnects = 0
        var pingsHeardBle = 0
        var pingsHeardWifi = 0
        var bleTxPerSec: UInt32 = 0
        var bleRxPerSec: UInt32 = 0
        var wifiTxPerSec: UInt32 = 0
        var wifiRxPerSec: UInt32 = 0
        var bleTxTotal: UInt32 = 0
        var bleRxTotal: UInt32 = 0
        var wifiTxTotal: UInt32 = 0
        var wifiRxTotal: UInt32 = 0
        var lastPhotoRoute = 0        // 0 none, 1 BLE, 2 WiFi
        var lastPhotoBytes: UInt32 = 0
        var lastPhotoMs: UInt32 = 0
        var wifiSocketUptimeMs: UInt32 = 0   // firmware-side truth (0 = no client)
        var receivedAt = Date()

        var imageRoute: Transport { routeIsWifi ? .wifi : .bluetooth }

        var phyLabel: String {
            func name(_ v: Int) -> String {
                switch v {
                case 1: "1M"
                case 2: "2M"
                case 3: "Coded"
                default: "?"
                }
            }
            guard phyTx != 0 || phyRx != 0 else { return "—" }
            return phyTx == phyRx ? name(phyTx) : "\(name(phyTx))/\(name(phyRx))"
        }

        static func parse(_ packet: Data) -> FirmwareStats? {
            let b = [UInt8](packet)
            // Length-guarded reads: a shorter (or future longer) packet
            // degrades to partial stats instead of garbage.
            guard b.count >= 2, b[0] == UInt8(ascii: "T") else { return nil }
            func u16(_ at: Int) -> Int {
                guard b.count >= at + 2 else { return 0 }
                return Int(b[at]) | (Int(b[at + 1]) << 8)
            }
            func u32(_ at: Int) -> UInt32 {
                guard b.count >= at + 4 else { return 0 }
                return UInt32(b[at]) | (UInt32(b[at + 1]) << 8)
                    | (UInt32(b[at + 2]) << 16) | (UInt32(b[at + 3]) << 24)
            }
            var s = FirmwareStats()
            s.version = Int(b[1])
            s.uptimeMs = u32(2)
            s.freeHeap = u32(6)
            s.freePsram = u32(10)
            s.attMtu = u16(14)
            if b.count > 16 { s.phyTx = Int(b[16]) }
            if b.count > 17 { s.phyRx = Int(b[17]) }
            if b.count > 18 {
                s.bleConnected = b[18] & 0x01 != 0
                s.softApUp = b[18] & 0x02 != 0
                s.wifiClientConnected = b[18] & 0x04 != 0
                s.routeIsWifi = b[18] & 0x08 != 0
            }
            if b.count > 19 { s.staRssi = Int(Int8(bitPattern: b[19])) }
            s.bleConnectCount = u16(20)
            s.wifiClientConnects = u16(22)
            s.pingsHeardBle = u16(24)
            s.pingsHeardWifi = u16(26)
            s.bleTxPerSec = u32(28)
            s.bleRxPerSec = u32(32)
            s.wifiTxPerSec = u32(36)
            s.wifiRxPerSec = u32(40)
            s.bleTxTotal = u32(44)
            s.bleRxTotal = u32(48)
            s.wifiTxTotal = u32(52)
            s.wifiRxTotal = u32(56)
            if b.count > 60 { s.lastPhotoRoute = Int(b[60]) }
            s.lastPhotoBytes = u32(62)
            s.lastPhotoMs = u32(66)
            s.wifiSocketUptimeMs = u32(70)
            return s
        }
    }

    // Defaults used when connecting without BLE bootstrap (user joined the
    // glasses' AP manually in iOS Settings). Must match firmware config.h.
    static let defaultHost = "192.168.4.1"
    static let defaultPort: UInt16 = 5005

    let ble = BleManager()
    let wifi = WifiLink()

    private(set) var wifiPhase: WifiPhase = .off
    /// Last credentials the glasses sent — shown in the UI for manual joining.
    private(set) var wifiSsid = ""
    private(set) var wifiPass = ""
    /// Firmware-side truth from the last 'T' packet (nil until one arrives).
    private(set) var fwStats: FirmwareStats?
    /// Most recent photo transfers, newest first (capped).
    private(set) var photoTransfers: [PhotoTransfer] = []
    private(set) var wifiRedials = 0

    // Note: the photo route is the FIRMWARE's per-image decision ("WiFi only
    // when measured faster than BLE"); a merely-connected socket does not
    // imply the WiFi route. The app learns the route from fwStats ('T'
    // packet) and from which transport each 'H' header arrives on.
    var isConnected: Bool { ble.isConnected || wifi.isConnected }
    var deviceName: String { ble.deviceName }

    // MARK: Unified callbacks (wired once by AppModel)

    @ObservationIgnored var onMicAudio: ((Data) -> Void)?
    @ObservationIgnored var onPhoto: ((Data) -> Void)?
    @ObservationIgnored var onVisionPhoto: ((Data) -> Void)?
    @ObservationIgnored var onBargeIn: (() -> Void)?
    @ObservationIgnored var onLog: ((String) -> Void)?
    @ObservationIgnored var onConnected: (() -> Void)?

    /// Sticky user intent: once the toggle is on, the link self-heals — every
    /// drop triggers redials, and every BLE (re)connect re-requests the AP.
    /// Persisted so an app relaunch restores the preference.
    @ObservationIgnored private var wifiWanted = UserDefaults.standard.bool(forKey: "link.wifiWanted") {
        didSet { UserDefaults.standard.set(wifiWanted, forKey: "link.wifiWanted") }
    }
    @ObservationIgnored private var wifiHost = LinkManager.defaultHost
    @ObservationIgnored private var wifiPort = LinkManager.defaultPort
    @ObservationIgnored private var redialsLeft = 0
    @ObservationIgnored private var redialTask: Task<Void, Never>?
    @ObservationIgnored private var slowRetryTask: Task<Void, Never>?

    var wifiEnabled: Bool { wifiWanted }

    init() {
        wire(ble: ble)
        wire(wifi: wifi)
    }

    private func wire(ble: BleManager) {
        ble.onLog = { [weak self] in self?.onLog?("[ble] \($0)") }
        ble.onMicAudio = { [weak self] in self?.onMicAudio?($0) }
        ble.onPhoto = { [weak self] in self?.onPhoto?($0) }
        ble.onVisionPhoto = { [weak self] in self?.onVisionPhoto?($0) }
        ble.onPhotoStats = { [weak self] bytes, seconds in
            self?.recordPhotoTransfer(route: .bluetooth, bytes: bytes, seconds: seconds)
        }
        ble.onBargeIn = { [weak self] in self?.onBargeIn?() }
        ble.onStatsPacket = { [weak self] in self?.handleStatsPacket($0) }
        ble.onConnected = { [weak self] in
            guard let self else { return }
            self.onConnected?()
            // BLE is (back) up: if the user wants WiFi but the socket is down
            // (glasses rebooted, iOS wandered off the AP, app relaunched),
            // restart the whole bootstrap instead of silently staying BLE-only.
            if self.wifiWanted && !self.wifi.isConnected {
                self.onLog?("[link] BLE up + Wi-Fi wanted — re-requesting the AP")
                self.startWifiBootstrap()
            }
        }
        ble.onWifiInfo = { [weak self] ssid, pass, host, port in
            self?.handleWifiInfo(ssid: ssid, pass: pass, host: host, port: port)
        }
    }

    private func wire(wifi: WifiLink) {
        wifi.onLog = { [weak self] in self?.onLog?("[wifi] \($0)") }
        wifi.onPhoto = { [weak self] in self?.onPhoto?($0) }
        wifi.onVisionPhoto = { [weak self] in self?.onVisionPhoto?($0) }
        wifi.onPhotoStats = { [weak self] bytes, seconds in
            self?.recordPhotoTransfer(route: .wifi, bytes: bytes, seconds: seconds)
        }
        wifi.onStatsPacket = { [weak self] in self?.handleStatsPacket($0) }
        wifi.onConnected = { [weak self] in
            guard let self else { return }
            self.wifiPhase = .active
            self.redialsLeft = 8          // future drops get a full retry budget
            self.redialTask?.cancel()
            self.slowRetryTask?.cancel()
            // BLE-primary policy: Bluetooth keeps carrying voice + control —
            // the socket is purely a bulk lane, nothing to hand over.
            self.onConnected?()
        }
        wifi.onDisconnected = { [weak self] in
            guard let self else { return }
            switch self.wifiPhase {
            case .active:
                // Bulk lane died. Photos fall back to BLE automatically
                // (firmware routes per image); keep redialing while wanted.
                self.onLog?("[link] Wi-Fi bulk lane dropped — photos ride Bluetooth while we redial")
                self.scheduleRedial(reason: "Wi-Fi link dropped")
            case .connecting:
                self.scheduleRedial(reason: "couldn't reach the glasses")
            default:
                break
            }
        }
    }

    // MARK: Connection controls

    func connect() { ble.connect() }

    // MARK: App lifecycle

    @ObservationIgnored private var inBackground = false

    /// Backgrounded: iOS suspends us between BLE events (bluetooth-central
    /// background mode). The TCP bulk lane cannot survive suspension — close
    /// it deliberately so it doesn't die mid-photo, and skip redial churn
    /// that would just burn the background execution window. BLE stays up;
    /// photos ride Bluetooth while backgrounded.
    func enterBackground() {
        inBackground = true
        redialTask?.cancel()
        slowRetryTask?.cancel()
        ble.clearPingLiveness()
        if wifi.isConnected || wifiPhase == .connecting {
            onLog?("[link] backgrounded — closing Wi-Fi bulk lane (BLE carries everything)")
            wifiPhase = .off
            wifi.disconnect()
        }
    }

    func enterForeground() {
        inBackground = false
        ble.clearPingLiveness()
        if wifiWanted && !wifi.isConnected {
            redialsLeft = 8
            startWifiBootstrap()
        }
    }

    func disconnectAll() {
        disableWifiLink()
        ble.disconnect()
    }

    // MARK: Voice mode + response audio — always Bluetooth (V2 policy)

    func setVoiceMode(_ on: Bool) { ble.setVoiceMode(on) }
    func enqueueResponseAudio(ulaw: Data) { ble.enqueueResponseAudio(ulaw: ulaw) }
    func finishResponse() { ble.finishResponse() }
    func cancelResponse() { ble.cancelResponse() }

    // MARK: WiFi link lifecycle

    /// Bring the WiFi bulk lane up and keep it up (sticky until toggled off).
    func enableWifiLink() {
        wifiWanted = true
        guard !wifi.isConnected else { return }
        redialsLeft = 8
        startWifiBootstrap()
    }

    /// One bootstrap attempt. Over BLE when available (full 'F' → 'N' flow);
    /// otherwise dial the well-known SoftAP address directly, which works when
    /// the user already joined the glasses' network in iOS Settings.
    private func startWifiBootstrap() {
        guard wifiWanted, !wifi.isConnected else { return }
        if ble.isConnected {
            wifiPhase = .requesting
            onLog?("[link] requesting WiFi link from glasses ('F')…")
            ble.writeControlBytes([UInt8(ascii: "F")])
            // 'N' answer watchdog
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: 8_000_000_000)
                guard let self, self.wifiPhase == .requesting else { return }
                self.wifiPhase = .failed("Glasses did not answer — is the firmware current?")
            }
        } else {
            wifiPhase = .connecting
            onLog?("[link] no BLE — dialing \(wifiHost):\(wifiPort) directly")
            wifi.connect(host: wifiHost, port: wifiPort)
        }
    }

    /// A dial failed or the socket died. While the toggle is on, retry every
    /// 3 s (8×) before settling on the manual-join hint — this rides out slow
    /// AP association, glasses reboots, and iOS briefly wandering off to
    /// another network.
    private func scheduleRedial(reason: String) {
        guard wifiWanted else { wifiPhase = .off; return }
        guard !inBackground else { wifiPhase = .off; return }   // resumes on foreground
        if redialsLeft <= 0 {
            let ssid = wifiSsid.isEmpty ? "the glasses' Wi-Fi" : wifiSsid
            let pass = wifiPass.isEmpty ? "glasses-link" : wifiPass
            wifiPhase = .failed("Photos are on Bluetooth (\(reason)). Auto-retrying — join \(ssid) (password \(pass)) once in iOS Settings → Wi-Fi and it connects by itself.")
            scheduleSlowRetry()
            return
        }
        redialsLeft -= 1
        wifiRedials += 1
        wifiPhase = .connecting
        redialTask?.cancel()
        redialTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard let self, !Task.isCancelled, self.wifiWanted, !self.wifi.isConnected else { return }
            self.onLog?("[link] redialing Wi-Fi (\(self.redialsLeft) tries left)…")
            self.wifi.connect(host: self.wifiHost, port: self.wifiPort)
        }
    }

    /// Tear the WiFi link down and put the glasses' radio away.
    /// The toggle is a standing auto-connect preference, not a one-shot
    /// action: after the fast redial budget burns out, keep probing gently
    /// in the background. The moment the AP is reachable (user joined it in
    /// Settings, glasses rebooted, walked back in range) the lane comes up
    /// on its own — no further taps.
    private func scheduleSlowRetry() {
        slowRetryTask?.cancel()
        slowRetryTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 30_000_000_000)
            guard let self, !Task.isCancelled,
                  self.wifiWanted, !self.inBackground, !self.wifi.isConnected else { return }
            self.onLog?("[link] auto Wi-Fi: retrying quietly…")
            self.redialsLeft = 1
            self.startWifiBootstrap()
        }
    }

    func disableWifiLink() {
        wifiWanted = false
        redialTask?.cancel()
        slowRetryTask?.cancel()
        if wifi.isConnected {
            // Ask the firmware to shut the SoftAP down (saves ~100 mA).
            wifi.sendControl([UInt8(ascii: "f")])
        } else if ble.isConnected, wifiPhase != .off {
            ble.writeControlBytes([UInt8(ascii: "f")])
        }
        wifi.disconnect()
        wifiPhase = .off
    }

    // MARK: Bootstrap plumbing

    /// 'N' answer arrived: the SoftAP is up. No Hotspot entitlement (personal
    /// signing teams can't carry it), so joining is manual — dial anyway after
    /// a beat in case the phone is already on the AP from a previous session,
    /// and let the redial loop + failure hint walk the user through joining.
    private func handleWifiInfo(ssid: String, pass: String, host: String, port: UInt16) {
        wifiSsid = ssid
        wifiPass = pass
        wifiHost = host
        wifiPort = port
        redialsLeft = max(redialsLeft, 8)   // fresh bootstrap → fresh retry budget
        onLog?("[link] glasses AP up: \(ssid) @ \(host):\(port) — join it in iOS Settings if the dial fails")
        wifiPhase = .joining(ssid)
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard let self, self.wifiWanted, !self.wifi.isConnected else { return }
            self.wifiPhase = .connecting
            self.wifi.connect(host: host, port: port)
        }
    }

    // MARK: Metrics plumbing

    private func handleStatsPacket(_ packet: Data) {
        guard let parsed = FirmwareStats.parse(packet) else {
            onLog?("[link] unparseable 'T' stats packet (\(packet.count) B)")
            return
        }
        fwStats = parsed
    }

    private func recordPhotoTransfer(route: Transport, bytes: Int, seconds: TimeInterval) {
        let record = PhotoTransfer(route: route, bytes: bytes, seconds: seconds)
        photoTransfers.insert(record, at: 0)
        if photoTransfers.count > 20 { photoTransfers.removeLast(photoTransfers.count - 20) }
        onLog?(String(format: "[link] photo via %@: %d B in %.0f ms (%.1f KB/s)",
                      route.rawValue, bytes, seconds * 1000.0, record.kbPerSec))
    }
}
