import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        @Bindable var settings = app.settings
        NavigationStack {
            Form {
                Section {
                    LabeledContent("AI service", value: "Secure")
                    LabeledContent("Authentication", value: "Automatic")
                } header: {
                    Text("Cloud")
                } footer: {
                    Text("The app signs in anonymously and requests a short-lived voice credential only after an interaction starts on the glasses. No OpenAI key is stored on the phone.")
                }

                Section("Voice") {
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
                        Button("Disable glasses voice", role: .destructive) { app.stopVoice() }
                    } else {
                        Button("Enable glasses voice") { app.retryVoiceNow() }
                    }
                } footer: {
                    Text("Use the physical side button on the glasses to start talking. The phone may remain in your pocket. Reasoning and voice changes apply to the next secure session.")
                }
            }
            .navigationTitle("Settings")
        }
    }
}
