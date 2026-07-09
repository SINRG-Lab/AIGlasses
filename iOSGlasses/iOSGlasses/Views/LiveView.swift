import SwiftUI

/// Live camera feed (motion-JPEG) streamed from the glasses.
///
/// The stream is glasses-initiated: **triple-tap** the glasses button to start,
/// **tap** to stop. Frames arrive over BLE as individual JPEGs (~QVGA, a few
/// fps) and are shown as they land. A dropped frame is simply skipped — the
/// next one is milliseconds away.
struct LiveView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                frame
                statusBar
                if !app.isReceivingVideo {
                    hint
                }
                Spacer()
            }
            .padding()
            .navigationTitle("Live View")
        }
    }

    @ViewBuilder private var frame: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 16)
                .fill(Color.black)
            if let img = app.liveFrame {
                Image(uiImage: img)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
            } else {
                VStack(spacing: 10) {
                    Image(systemName: "video.slash")
                        .font(.system(size: 44))
                        .foregroundStyle(.secondary)
                    Text(app.isReceivingVideo ? "waiting for frames…" : "no live feed")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .aspectRatio(4.0 / 3.0, contentMode: .fit)   // QVGA/VGA are 4:3
        .frame(maxWidth: .infinity)
    }

    private var statusBar: some View {
        HStack {
            Circle()
                .fill(app.isReceivingVideo ? Color.red : Color.gray)
                .frame(width: 10, height: 10)
            Text(app.isReceivingVideo ? "LIVE" : "idle")
                .font(.subheadline.weight(.semibold))
            Spacer()
            if app.isReceivingVideo {
                Text(String(format: "%.1f fps", app.videoFps))
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var hint: some View {
        VStack(spacing: 6) {
            Label("Triple-tap the glasses button to start", systemImage: "hand.tap")
            Label("Tap once to stop", systemImage: "stop.circle")
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
        .padding(.top, 4)
    }
}
