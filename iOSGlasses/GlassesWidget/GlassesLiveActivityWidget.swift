import WidgetKit
import SwiftUI
import ActivityKit

/// Twin of the struct in the app target (iOSGlasses/GlassesLiveActivity.swift).
/// ActivityKit matches app ↔ widget by attribute type name and Codable shape —
/// keep both definitions identical.
struct GlassesActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var status: String
        var recording: Bool
    }
    var deviceName: String
}

struct GlassesLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: GlassesActivityAttributes.self) { context in
            // Lock Screen / banner presentation
            HStack(spacing: 12) {
                statusIcon(context.state)
                    .font(.title2)
                VStack(alignment: .leading, spacing: 2) {
                    Text(context.attributes.deviceName)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(context.state.status)
                        .font(.headline)
                }
                Spacer()
                if context.state.recording {
                    Circle().fill(.red).frame(width: 10, height: 10)
                }
            }
            .padding()
            .activityBackgroundTint(Color.black.opacity(0.6))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    statusIcon(context.state)
                        .font(.title2)
                        .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(context.state.status)
                        .font(.headline)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if context.state.recording {
                        Circle().fill(.red).frame(width: 10, height: 10)
                            .padding(.trailing, 4)
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text(context.attributes.deviceName)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } compactLeading: {
                statusIcon(context.state)
            } compactTrailing: {
                if context.state.recording {
                    Circle().fill(.red).frame(width: 8, height: 8)
                }
            } minimal: {
                statusIcon(context.state)
            }
        }
    }

    @ViewBuilder
    private func statusIcon(_ state: GlassesActivityAttributes.ContentState) -> some View {
        if state.recording {
            Image(systemName: "mic.fill").foregroundStyle(.red)
        } else {
            switch state.status {
            case "Speaking":
                Image(systemName: "speaker.wave.2.fill").foregroundStyle(.blue)
            case "Thinking…":
                Image(systemName: "ellipsis.circle.fill").foregroundStyle(.orange)
            case "Hearing you…":
                Image(systemName: "waveform").foregroundStyle(.green)
            default:
                Image(systemName: "eyeglasses").foregroundStyle(.cyan)
            }
        }
    }
}
