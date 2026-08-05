import Foundation
@preconcurrency import ActivityKit   // Activity<…> isn't Sendable yet; all use stays on the main actor
import UIKit

/// Live Activity payload for the glasses voice session.
///
/// NOTE: an identically-named twin of this struct lives in the GlassesWidget
/// extension (ActivityKit matches app ↔ widget by attribute type name and
/// Codable shape). Change BOTH files together or the island renders nothing.
struct GlassesActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var status: String       // "Listening" / "Recording" / "Thinking" / "Speaking" / …
        var recording: Bool      // mic frames flowing right now (button held)
    }
    var deviceName: String
}

/// Owns the one Live Activity mirroring the voice session on the Dynamic
/// Island / Lock Screen. `sync` is idempotent: it starts, updates, or ends
/// the activity to match the desired state.
@MainActor
final class GlassesLiveActivityController {

    private var activity: Activity<GlassesActivityAttributes>?

    func sync(active: Bool, state: GlassesActivityAttributes.ContentState) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        guard active else {
            end()
            return
        }
        let content = ActivityContent(state: state, staleDate: nil)
        if let activity {
            Task { await activity.update(content) }
        } else {
            // iOS only allows STARTING an activity in the foreground; updates
            // and end are fine from the background. syncLiveActivity() is also
            // called on every foreground transition, which retries this.
            guard UIApplication.shared.applicationState == .active else { return }
            activity = try? Activity.request(
                attributes: GlassesActivityAttributes(deviceName: "AI Glasses"),
                content: content)
        }
    }

    func end() {
        guard let activity else { return }
        self.activity = nil
        Task { await activity.end(nil, dismissalPolicy: .immediate) }
    }
}
