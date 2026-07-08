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
                    Text(app.ble.deviceName.isEmpty ? "AI Glasses" : app.ble.deviceName)
                        .font(.headline)
                    Text(app.ble.connectionState.rawValue)
                        .font(.subheadline)
                        .foregroundStyle(stateColor)
                }
                Spacer()
                Circle()
                    .fill(stateColor)
                    .frame(width: 12, height: 12)
            }
            if app.ble.attMTU > 0 {
                HStack(spacing: 16) {
                    statChip("MTU", "\(app.ble.attMTU)")
                    statChip("LINK", app.ble.attMTU >= 200 ? "High" : "Std")
                    Spacer()
                }
            }
            Button {
                if app.ble.connectionState == .disconnected {
                    app.connectGlasses()
                } else {
                    app.disconnectGlasses()
                }
            } label: {
                Text(app.ble.connectionState == .disconnected ? "Connect Glasses" : "Disconnect")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(app.ble.connectionState == .disconnected ? .blue : .red)
        }
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    private var stateColor: Color {
        switch app.ble.connectionState {
        case .disconnected: .secondary
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

    // MARK: Voice

    private var voiceCard: some View {
        VStack(spacing: 16) {
            Button {
                app.toggleVoice()
            } label: {
                ZStack {
                    Circle()
                        .fill(app.voiceEnabled ? voiceColor.opacity(0.2) : Color.gray.opacity(0.15))
                        .frame(width: 130, height: 130)
                    Circle()
                        .stroke(app.voiceEnabled ? voiceColor : .gray, lineWidth: 3)
                        .frame(width: 130, height: 130)
                    Image(systemName: app.voiceEnabled ? "mic.fill" : "mic.slash.fill")
                        .font(.system(size: 44))
                        .foregroundStyle(app.voiceEnabled ? voiceColor : .gray)
                }
            }
            .buttonStyle(.plain)

            Text(app.voiceStatus.rawValue)
                .font(.title3.weight(.semibold))
                .foregroundStyle(app.voiceEnabled ? voiceColor : .secondary)

            if app.voiceEnabled {
                Text("Hold the button on the glasses to talk")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                micMeter
            }
        }
        .frame(maxWidth: .infinity)
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    private var voiceColor: Color {
        switch app.voiceStatus {
        case .off: .gray
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
