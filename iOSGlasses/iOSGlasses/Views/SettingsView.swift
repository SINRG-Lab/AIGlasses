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
                    LabeledContent("Changes apply", value: "next voice session")
                } footer: {
                    Text("Turn voice off and on again after changing the model, effort, or voice.")
                }
            }
            .navigationTitle("Settings")
        }
    }
}
