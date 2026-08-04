import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var app
    @State private var apiKeyDraft = ""
    @State private var keySaved = false

    var body: some View {
        @Bindable var settings = app.settings
        NavigationStack {
            Form {
                Section {
                    SecureField("sk-…", text: $apiKeyDraft)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button(keySaved ? "Saved ✓" : "Save Key") {
                        app.settings.setAPIKey(apiKeyDraft)
                        keySaved = true
                        app.retryVoiceNow()   // one-step: a fresh key starts voice
                        Task {
                            try? await Task.sleep(nanoseconds: 1_500_000_000)
                            keySaved = false
                        }
                    }
                    .disabled(apiKeyDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                } header: {
                    Text("OpenAI API Key")
                } footer: {
                    Text(app.settings.apiKey.isEmpty
                         ? "Stored securely in the iOS Keychain."
                         : "A key is saved in the Keychain. Enter a new one to replace it.")
                }

                Section("Realtime Model") {
                    Picker("Model", selection: $settings.model) {
                        ForEach(SettingsStore.models, id: \.self) { Text($0).tag($0) }
                    }
                    Picker("Reasoning effort", selection: $settings.reasoningEffort) {
                        ForEach(SettingsStore.efforts, id: \.self) { Text($0.capitalized).tag($0) }
                    }
                    Picker("Voice", selection: $settings.voice) {
                        ForEach(SettingsStore.voices, id: \.self) { Text($0.capitalized).tag($0) }
                    }
                }

                Section {
                    LabeledContent("Voice session", value: app.voiceStatus.rawValue)
                    if app.voiceEnabled || app.voiceAutoEnabled {
                        Button("Stop voice", role: .destructive) { app.stopVoice() }
                    } else {
                        Button("Start voice") { app.retryVoiceNow() }
                    }
                } footer: {
                    Text("Voice starts automatically when the glasses connect. Model/effort/voice changes apply on the next session — stop and start voice to pick them up.")
                }
            }
            .navigationTitle("Settings")
        }
    }
}
