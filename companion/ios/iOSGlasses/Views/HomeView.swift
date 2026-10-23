import SwiftUI

struct HomeView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    connectionCard
                    voiceCard
                    if let error = app.lastError {
                        errorBanner(error)
                    }
                    if app.pendingPhoto != nil {
                        photoCard
                    }
                    transcriptCard
                }
                .padding()
            }
            .navigationTitle("AI Glasses")
        }
    }

    // MARK: Connection

    private var connectionCard: some View {
        VStack(spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(app.link.deviceName.isEmpty ? "AI Glasses" : app.link.deviceName)
                        .font(.headline)
                    Text(connectionStatusText)
                        .font(.subheadline)
                        .foregroundStyle(stateColor)
                }
                Spacer()
                Circle()
                    .fill(stateColor)
                    .frame(width: 12, height: 12)
            }
            if app.link.isConnected {
                HStack(spacing: 16) {
                    if app.ble.stats.rttMs > 0 {
                        statChip("PING", String(format: "%.0f ms", app.ble.stats.rttMs))
                    }
                    if app.ble.attMTU > 0 {
                        statChip("MTU", "\(app.ble.attMTU)")
                    }
                    if app.link.wifi.isConnected {
                        statChip("BULK", "Wi-Fi")
                    }
                    Spacer()
                }
            }
            wifiLinkRow
            Button {
                if app.link.isConnected || app.ble.connectionState != .disconnected {
                    app.disconnectGlasses()
                } else {
                    app.connectGlasses()
                }
            } label: {
                Text(app.link.isConnected || app.ble.connectionState != .disconnected
                     ? "Disconnect" : "Connect Glasses")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(app.link.isConnected || app.ble.connectionState != .disconnected ? .red : .blue)
        }
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    /// Wi-Fi as the bulk lane: toggling on runs the whole bootstrap (BLE 'F'
    /// → SoftAP up → TCP), or dials the glasses' known address directly when
    /// Bluetooth is unavailable. Bluetooth stays connected regardless.
    private var wifiLinkRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            Toggle(isOn: Binding(
                get: { app.link.wifiEnabled || app.link.wifiPhase != .off },
                set: { on in
                    if on { app.link.enableWifiLink() } else { app.link.disableWifiLink() }
                }
            )) {
                Label("Auto Wi-Fi photo boost", systemImage: "wifi")
                    .font(.subheadline.weight(.medium))
            }
            if let detail = wifiPhaseDetail {
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(wifiPhaseIsError ? .orange : .secondary)
            }
        }
    }

    private var wifiPhaseDetail: String? {
        switch app.link.wifiPhase {
        case .off: nil
        case .requesting: "Asking the glasses to start Wi-Fi…"
        case .joining(let ssid): "Waiting for the phone to be on \(ssid)…"
        case .connecting: "Opening the data link…"
        case .active: "Fast photo lane active (voice stays on Bluetooth)"
        case .failed(let why): why
        }
    }

    private var wifiPhaseIsError: Bool {
        if case .failed = app.link.wifiPhase { return true }
        return false
    }

    private var connectionStatusText: String {
        if app.ble.isConnected, app.link.wifi.isConnected { return "Connected · +Wi-Fi" }
        return app.ble.connectionState.rawValue
    }

    private var stateColor: Color {
        switch app.ble.connectionState {
        case .disconnected: app.link.wifi.isConnected ? .green : .secondary
        case .scanning, .connecting: .orange
        case .connected: .green
        }
    }

    private func statChip(_ label: String, _ value: String) -> some View {
        HStack(spacing: 6) {
            Text(label)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(value)
                .font(.caption.weight(.semibold))
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
    }

    // MARK: Voice (one-step: starts itself — this is a status surface)

    private var voiceCard: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                ZStack {
                    Circle()
                        .fill(voiceColor.opacity(voiceArmed ? 0.2 : 0.08))
                        .frame(width: 54, height: 54)
                    Image(systemName: voiceArmed ? "mic.fill" : "mic.slash.fill")
                        .font(.system(size: 22))
                        .foregroundStyle(voiceArmed ? voiceColor : .gray)
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(app.voiceStatus.rawValue)
                        .font(.headline)
                        .foregroundStyle(voiceArmed ? voiceColor : .secondary)
                    Text(voiceDetail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if showRetry {
                    Button("Retry") { app.retryVoiceNow() }
                        .buttonStyle(.bordered)
                        .font(.subheadline.weight(.semibold))
                }
            }
            if app.voiceEnabled {
                micMeter
            }
        }
        .frame(maxWidth: .infinity)
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    private var voiceDetail: String {
        if app.voiceEnabled {
            return "Hold the button on the glasses to talk."
        }
        if !app.voiceAutoEnabled {
            return "Glasses voice is disabled in Settings."
        }
        if !app.ble.isConnected {
            return "Voice arms automatically once the glasses connect."
        }
        return "Hold the physical side button on the glasses to talk."
    }

    private var showRetry: Bool {
        app.lastError != nil
    }

    private var voiceArmed: Bool {
        app.voiceEnabled || app.voiceAutoEnabled && app.ble.isConnected
    }

    private var voiceColor: Color {
        switch app.voiceStatus {
        case .off: .gray
        case .ready: .green
        case .connecting: .orange
        case .listening: .green
        case .hearing: .blue
        case .thinking: .purple
        case .speaking: .teal
        }
    }

    private var micMeter: some View {
        VStack(spacing: 4) {
            HStack {
                Text("MIC")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                ProgressView(value: app.micLevel)
                    .tint(app.micLevel > 0.85 ? .red : .green)
                Text(String(format: "×%.1f", app.micGain))
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 8)
    }

    // MARK: Photo

    private var photoCard: some View {
        HStack(spacing: 12) {
            if let photo = app.pendingPhoto,
               let uiImage = UIImage(contentsOfFile: photo.url.path) {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 72, height: 72)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
            }
            VStack(alignment: .leading, spacing: 6) {
                Text(app.photoAttached ? "Photo attached — ask your question"
                                       : "Photo from glasses")
                    .font(.subheadline.weight(.semibold))
                if !app.photoAttached {
                    Button("Ask about this photo") {
                        app.askAboutPendingPhoto()
                    }
                    .font(.caption.weight(.semibold))
                    .buttonStyle(.bordered)
                }
            }
            Spacer()
            Button {
                app.dismissPendingPhoto()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
        }
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    // MARK: Transcripts

    private var transcriptCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            transcriptRow(tag: "YOU", color: .blue,
                          text: app.userTranscript.isEmpty ? "—" : app.userTranscript)
            Divider()
            transcriptRow(tag: "AI", color: .purple,
                          text: app.assistantTranscript.isEmpty ? "—" : app.assistantTranscript)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    private func transcriptRow(tag: String, color: Color, text: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text(tag)
                .font(.caption2.weight(.bold))
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(color.opacity(0.15), in: RoundedRectangle(cornerRadius: 6))
                .foregroundStyle(color)
            Text(text)
                .font(.callout)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func errorBanner(_ message: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
            Text(message)
                .font(.caption)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                app.lastError = nil
            } label: {
                Image(systemName: "xmark")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
        }
        .padding(12)
        .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
    }
}
