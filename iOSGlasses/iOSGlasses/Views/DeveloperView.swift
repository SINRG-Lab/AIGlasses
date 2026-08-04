import SwiftUI

struct DeveloperView: View {
    @Environment(AppModel.self) private var app

    private static let timeFormat: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    private static let appVersion: String = {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(v) (\(b))"
    }()

    /// When this binary was produced — changes on every install, so it is the
    /// ground truth for "is the phone running the build I just made?".
    private static let buildStamp: String = {
        guard let url = Bundle.main.executableURL,
              let date = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?
                  .contentModificationDate else { return "—" }
        let f = DateFormatter()
        f.dateFormat = "MMM d, HH:mm"
        return f.string(from: date)
    }()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    section("Link", linkGrid)
                    section("Throughput", throughputGrid)
                    section("Bluetooth", bleGrid)
                    section("Firmware ('T' stats)", firmwareGrid)
                    if !app.link.photoTransfers.isEmpty {
                        section("Photo transfers", photoTransferList)
                    }
                    section("App", appGrid)
                }
                .padding()
                Divider()
                logList
                    .frame(minHeight: 300)
            }
            .navigationTitle("Developer")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Clear") { app.clearLog() }
                }
            }
        }
    }

    private func section(_ title: String, _ content: some View) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title.uppercased())
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            content
        }
    }

    private var grid: [GridItem] { [GridItem(.adaptive(minimum: 100), spacing: 8)] }

    // MARK: Link (RTT + liveness, both transports)

    private var linkGrid: some View {
        let b = app.ble.stats
        let w = app.link.wifi.stats
        return LazyVGrid(columns: grid, spacing: 8) {
            // Route truth lives firmware-side ("WiFi only when measured faster
            // than BLE — when in doubt, BLE"); a merely-up socket says nothing
            // about the route, so show "—" until the first 'T' packet arrives
            // rather than guessing Wi-Fi while the firmware routes over BLE.
            statTile("Photo route", app.link.fwStats.map { $0.imageRoute.rawValue } ?? "—")
            statTile("BLE RTT", ms(b.rttMs))
            statTile("BLE avg/min/max", b.rttMs > 0
                     ? String(format: "%.0f/%.0f/%.0f", b.rttAvgMs, b.rttMinMs, b.rttMaxMs) : "—")
            statTile("BLE pings", "\(b.pingsSent) / \(b.pingsLost) lost")
            statTile("Wi-Fi RTT", ms(w.rttMs))
            statTile("Wi-Fi avg/min/max", w.rttMs > 0
                     ? String(format: "%.0f/%.0f/%.0f", w.rttAvgMs, w.rttMinMs, w.rttMaxMs) : "—")
            statTile("Wi-Fi pings", "\(w.pingsSent) / \(w.pingsLost) lost")
            statTile("Wi-Fi uptime", app.link.wifi.isConnected
                     ? String(format: "%.0f s", app.link.wifi.socketUptime) : "—")
        }
    }

    // MARK: Throughput

    private var throughputGrid: some View {
        let b = app.ble.stats
        let w = app.link.wifi.stats
        return LazyVGrid(columns: grid, spacing: 8) {
            statTile("BLE RX KB/s", kb(b.rxBytesPerSec))
            statTile("BLE TX KB/s", kb(b.txBytesPerSec))
            statTile("BLE RX total", bytes(b.rxBytesTotal))
            statTile("BLE TX total", bytes(b.txBytesTotal))
            statTile("Wi-Fi RX KB/s", kb(w.rxBytesPerSec))
            statTile("Wi-Fi TX KB/s", kb(w.txBytesPerSec))
            statTile("Wi-Fi RX total", bytes(w.rxBytesTotal))
            statTile("Wi-Fi TX total", bytes(w.txBytesTotal))
            statTile("Wi-Fi connects", "\(w.connects)")
            statTile("Wi-Fi redials", "\(app.link.wifiRedials)")
            statTile("Wi-Fi img frames", "\(w.rxImageFrames)")
            statTile("Wi-Fi img bytes", bytes(w.rxImageBytes))
        }
    }

    // MARK: Bluetooth detail

    private var bleGrid: some View {
        let s = app.ble.stats
        return LazyVGrid(columns: grid, spacing: 8) {
            statTile("MTU", app.ble.attMTU > 0 ? "\(app.ble.attMTU)" : "—")
            statTile("PHY", app.link.fwStats?.phyLabel ?? "—")
            statTile("Mic recv/s", String(format: "%.0f", s.recvPerSec))
            statTile("Mic accepted/s", String(format: "%.0f", s.acceptedPerSec))
            statTile("Mic dup/s", String(format: "%.0f", s.dupPerSec))
            statTile("Mic lost/s", String(format: "%.0f", s.lostPerSec))
            statTile("Img seq gaps", "\(s.imageSeqGapsTotal)")
            statTile("Reconnects", "\(s.reconnects)")
            statTile("Mic gain", String(format: "×%.1f", app.micGain))
        }
    }

    // MARK: Firmware truth (from the 5 s 'T' packet)

    @ViewBuilder private var firmwareGrid: some View {
        if let fw = app.link.fwStats {
            LazyVGrid(columns: grid, spacing: 8) {
                statTile("Uptime", "\(fw.uptimeMs / 1000) s")
                statTile("Free heap", bytes(Int(fw.freeHeap)))
                statTile("Free PSRAM", bytes(Int(fw.freePsram)))
                statTile("fw MTU / PHY", "\(fw.attMtu) / \(fw.phyLabel)")
                statTile("STA RSSI", fw.staRssi != 0 ? "\(fw.staRssi) dBm" : "—")
                statTile("fw BLE conns", "\(fw.bleConnectCount)")
                statTile("fw WiFi conns", "\(fw.wifiClientConnects)")
                statTile("fw WiFi uptime", fw.wifiSocketUptimeMs > 0
                         ? "\(fw.wifiSocketUptimeMs / 1000) s" : "—")
                statTile("Pings heard", "\(fw.pingsHeardBle) / \(fw.pingsHeardWifi)")
                statTile("fw BLE TX/s", bytes(Int(fw.bleTxPerSec)))
                statTile("fw BLE RX/s", bytes(Int(fw.bleRxPerSec)))
                statTile("fw WiFi TX/s", bytes(Int(fw.wifiTxPerSec)))
                statTile("fw WiFi RX/s", bytes(Int(fw.wifiRxPerSec)))
                statTile("fw BLE totals", "\(bytes(Int(fw.bleTxTotal))) / \(bytes(Int(fw.bleRxTotal)))")
                statTile("fw WiFi totals", "\(bytes(Int(fw.wifiTxTotal))) / \(bytes(Int(fw.wifiRxTotal)))")
                if fw.lastPhotoRoute != 0 {
                    statTile("Last photo (fw)",
                             "\(fw.lastPhotoRoute == 2 ? "Wi-Fi" : "BLE") · \(bytes(Int(fw.lastPhotoBytes))) · \(fw.lastPhotoMs) ms")
                }
                statTile("Packet age", String(format: "%.0f s", Date().timeIntervalSince(fw.receivedAt)))
            }
        } else {
            Text("No 'T' stats packet received yet.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    // MARK: Photo transfer records

    private var photoTransferList: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(app.link.photoTransfers) { t in
                HStack(spacing: 8) {
                    Text(Self.timeFormat.string(from: t.date))
                        .foregroundStyle(.secondary)
                    Text(t.route.rawValue)
                        .foregroundStyle(t.route == .wifi ? .blue : .primary)
                    Text(bytes(t.bytes))
                    Text(String(format: "%.0f ms", t.seconds * 1000))
                    Text(String(format: "%.1f KB/s", t.kbPerSec))
                        .foregroundStyle(.secondary)
                    Spacer()
                }
                .font(.system(size: 11, design: .monospaced))
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
    }

    private var appGrid: some View {
        LazyVGrid(columns: grid, spacing: 8) {
            statTile("Version", Self.appVersion)
            statTile("Built", Self.buildStamp)
        }
    }

    // MARK: Formatting helpers

    private func ms(_ v: Double) -> String { v > 0 ? String(format: "%.0f ms", v) : "—" }
    private func kb(_ v: Double) -> String { String(format: "%.1f", v / 1024) }
    private func bytes(_ v: Int) -> String {
        switch v {
        case ..<1024: "\(v) B"
        case ..<(1024 * 1024): String(format: "%.1f KB", Double(v) / 1024)
        default: String(format: "%.2f MB", Double(v) / (1024 * 1024))
        }
    }

    private func statTile(_ label: String, _ value: String) -> some View {
        VStack(spacing: 4) {
            Text(value)
                .font(.footnote.weight(.semibold).monospacedDigit())
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
    }

    private var logList: some View {
        ScrollViewReader { proxy in
            LazyVStack(alignment: .leading, spacing: 3) {
                ForEach(app.eventLog) { entry in
                    HStack(alignment: .top, spacing: 8) {
                        Text(Self.timeFormat.string(from: entry.date))
                            .foregroundStyle(.secondary)
                        Text(entry.text)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .font(.system(size: 11, design: .monospaced))
                    .id(entry.id)
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 8)
            .onChange(of: app.eventLog.count) {
                if let last = app.eventLog.last {
                    proxy.scrollTo(last.id, anchor: .bottom)
                }
            }
        }
    }
}
