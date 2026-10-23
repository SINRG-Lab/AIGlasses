import Foundation
import Observation

// MARK: - Settings

@MainActor
@Observable
final class SettingsStore {
    static let efforts = ["minimal", "low", "medium", "high"]
    static let voices = ["marin", "cedar", "alloy"]

    var reasoningEffort: String {
        didSet { UserDefaults.standard.set(reasoningEffort, forKey: "realtime.effort") }
    }
    var voice: String {
        didSet { UserDefaults.standard.set(voice, forKey: "realtime.voice") }
    }
    var glassesVoiceEnabled: Bool {
        didSet { UserDefaults.standard.set(glassesVoiceEnabled, forKey: "voice.enabled") }
    }

    init() {
        LegacySecretCleanup.removeObsoleteOpenAICredential()
        let d = UserDefaults.standard
        reasoningEffort = d.string(forKey: "realtime.effort") ?? "low"
        voice = d.string(forKey: "realtime.voice") ?? Self.voices[0]
        glassesVoiceEnabled = d.object(forKey: "voice.enabled") as? Bool ?? true
    }
}
