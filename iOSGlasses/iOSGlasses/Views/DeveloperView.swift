import SwiftUI

struct DeveloperView: View {
    @Environment(AppModel.self) private var app

    private static let timeFormat: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                statsGrid
                    .padding()
                Divider()
                logList
            }
            .navigationTitle("Developer")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Clear") { app.clearLog() }
                }
            }
        }
    }

    private var statsGrid: some View {
        let s = app.ble.stats
        return LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 10)], spacing: 10) {
            statTile("MTU", app.ble.attMTU > 0 ? "\(app.ble.attMTU)" : "—")
            statTile("Mic frames/s", String(format: "%.0f", s.acceptedPerSec))
            statTile("Recv/s", String(format: "%.0f", s.recvPerSec))
            statTile("Dup/s", String(format: "%.0f", s.dupPerSec))
            statTile("Lost/s", String(format: "%.0f", s.lostPerSec))
            statTile("TX KB/s", String(format: "%.1f", s.txBytesPerSec / 1024))
            statTile("Reconnects", "\(s.reconnects)")
            statTile("Mic gain", String(format: "×%.1f", app.micGain))
        }
    }

    private func statTile(_ label: String, _ value: String) -> some View {
        VStack(spacing: 4) {
            Text(value)
                .font(.title3.weight(.semibold).monospacedDigit())
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
    }

    private var logList: some View {
        ScrollViewReader { proxy in
            ScrollView {
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
            }
            .onChange(of: app.eventLog.count) {
                if let last = app.eventLog.last {
                    proxy.scrollTo(last.id, anchor: .bottom)
                }
            }
        }
    }
}
