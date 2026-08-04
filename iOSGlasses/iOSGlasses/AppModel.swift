import Foundation
import Observation
import UIKit

/// Top-level coordinator: owns the glasses link (BLE + WiFi), the OpenAI
/// Realtime session, the gallery and settings, and publishes UI state.
///
/// Data flow (mirrors the hardware-validated realtime_ble.py):
///   glasses mic --BLE 'A' µ-law@16k--> LinkManager --decode+AGC+resample-->
///     RealtimeSession (input_audio_buffer.append, PCM16@24k)
///   RealtimeSession (response.output_audio.delta, PCM16@24k) --µ-law encode-->
///     LinkManager/BLE ('S2' + paced 'A' frames + 'E') --> glasses speaker
///
/// One-step activation: the app connects BLE on launch and starts the voice
/// session by itself whenever an API key is saved and the glasses are
/// connected. The websocket reconnects with exponential backoff (2/4/8…30 s)
/// if it drops; the glasses interaction stays pure push-to-talk.
@MainActor
@Observable
final class AppModel {

    enum VoiceStatus: String {
        case off = "Voice off"
        case connecting = "Connecting…"
        case listening = "Listening"
        case hearing = "Hearing you…"
        case thinking = "Thinking…"
        case speaking = "Speaking"
    }

    struct LogEntry: Identifiable {
        let id = UUID()
        let date = Date()
        let text: String
    }

    // Sub-systems
    let settings = SettingsStore()
    let gallery = GalleryStore()
    let link = LinkManager()
    /// Convenience for views/diagnostics that show radio-level BLE state.
    var ble: BleManager { link.ble }
    @ObservationIgnored private var realtime: RealtimeSession?
    @ObservationIgnored private let mic = MicConditioner()

    // UI state
    private(set) var voiceEnabled = false
    private(set) var voiceStatus: VoiceStatus = .off
    private(set) var userTranscript = ""
    private(set) var assistantTranscript = ""
    private(set) var micGain = 4.0
    private(set) var micLevel = 0.0            // 0…1, post-HPF pre-gain peak
    var lastError: String?
    private(set) var eventLog: [LogEntry] = []
    private(set) var pendingPhoto: GalleryStore.Photo?
    private(set) var photoAttached = false

    // One-step activation
    /// True unless the user explicitly stopped voice (Settings). Auto-start
    /// fires whenever (wanted && key present && BLE connected && not running).
    @ObservationIgnored private var voiceWanted = true
    @ObservationIgnored private var reconnectDelay: TimeInterval = 2
    @ObservationIgnored private var reconnectTask: Task<Void, Never>?

    var voiceAutoEnabled: Bool { voiceWanted }

    init() {
        wireLink()
        log("app started")
        // One-step activation: bring the radio up immediately; voice follows
        // as soon as the glasses connect (if an API key is saved).
        link.connect()
    }

    // MARK: Link wiring (BLE + WiFi behind one facade)

    private func wireLink() {
        link.onLog = { [weak self] line in self?.log(line) }

        link.onMicAudio = { [weak self] pcm16k in
            guard let self, let rt = self.realtime else { return }
            let conditioned = self.mic.process(pcm16k)
            self.micGain = self.mic.gain
            self.micLevel = min(1.0, Double(self.mic.lastPeak) / 32767.0)
            rt.appendMic(pcm24k: Resampler.upsample16to24(conditioned))
        }

        link.onPhoto = { [weak self] jpeg in
            guard let self else { return }
            do {
                let photo = try self.gallery.save(jpeg: jpeg)
                self.pendingPhoto = photo
                self.photoAttached = false
                self.log("photo saved to gallery (\(jpeg.count) B)")
            } catch {
                self.log("photo save failed: \(error.localizedDescription)")
            }
        }

        // Vision gesture (tap-then-hold on the glasses): the photo arrives up
        // front and is injected straight into the live conversation; the voice
        // spoken during the hold is the question. Fully hands-free.
        link.onVisionPhoto = { [weak self] jpeg in
            guard let self else { return }
            if let photo = try? self.gallery.save(jpeg: jpeg) { self.pendingPhoto = photo }
            if self.voiceEnabled, let rt = self.realtime {
                rt.attachImage(jpeg: jpeg)
                self.photoAttached = true
                self.log("vision photo attached — answering your spoken question")
            } else {
                self.log("vision photo received but voice is off — saved to gallery")
            }
        }

        link.onBargeIn = { [weak self] in
            guard let self else { return }
            if self.voiceEnabled { self.voiceStatus = .listening }
        }

        link.onConnected = { [weak self] in
            guard let self else { return }
            self.mic.reset()
            self.maybeStartVoice()
        }
    }

    // MARK: Connection controls

    func connectGlasses() { link.connect() }

    func disconnectGlasses() {
        stopVoice()
        link.disconnectAll()
    }

    // MARK: Voice session (auto-started, self-healing)

    /// Start voice if everything it needs is in place. Called on launch, on
    /// every glasses connect, after saving an API key, and by the retry timer.
    private func maybeStartVoice() {
        guard voiceWanted, !voiceEnabled else { return }
        guard !settings.apiKey.isEmpty else { return }        // no key — chip explains
        guard ble.isConnected else { return }                 // voice rides BLE
        startVoiceSession()
    }

    /// User affordance: clear the error/backoff and try again right now.
    func retryVoiceNow() {
        voiceWanted = true
        reconnectDelay = 2
        reconnectTask?.cancel()
        reconnectTask = nil
        lastError = nil
        link.connect()
        maybeStartVoice()
    }

    /// Manual off switch (Settings) — stays off until retried.
    func stopVoice() {
        voiceWanted = false
        reconnectTask?.cancel()
        reconnectTask = nil
        guard voiceEnabled || realtime != nil else { return }
        voiceEnabled = false
        voiceStatus = .off
        link.setVoiceMode(false)
        link.cancelResponse()
        realtime?.close()
        realtime = nil
        log("voice OFF (manual)")
    }

    private func startVoiceSession() {
        lastError = nil
        userTranscript = ""
        assistantTranscript = ""

        let rt = RealtimeSession()
        wireRealtime(rt)
        realtime = rt
        voiceEnabled = true
        voiceStatus = .connecting
        rt.connect(apiKey: settings.apiKey,
                   model: settings.model,
                   effort: settings.reasoningEffort,
                   voice: settings.voice)
        link.setVoiceMode(true)
        log("voice ON (model \(settings.model))")
    }

    /// The websocket died while voice is wanted: exponential backoff
    /// (2/4/8…30 s cap), surfaced through the error banner.
    private func scheduleVoiceReconnect(reason: String) {
        guard voiceWanted else { return }
        let delay = reconnectDelay
        reconnectDelay = min(reconnectDelay * 2, 30)
        lastError = "\(reason) — retrying in \(Int(delay)) s"
        voiceStatus = .connecting
        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard let self, !Task.isCancelled else { return }
            self.maybeStartVoice()
        }
    }

    private func wireRealtime(_ rt: RealtimeSession) {
        rt.onLog = { [weak self] line in self?.log(line) }

        rt.onOpen = { [weak self] in
            guard let self, self.voiceEnabled else { return }
            self.voiceStatus = .listening
            self.reconnectDelay = 2        // healthy session → fresh backoff
            self.lastError = nil
        }

        rt.onAudioDelta = { [weak self] pcm24k in
            guard let self else { return }
            if self.voiceEnabled { self.voiceStatus = .speaking }
            self.link.enqueueResponseAudio(ulaw: ULaw.encode(pcm16: pcm24k))
        }

        rt.onResponseDone = { [weak self] in
            guard let self else { return }
            self.link.finishResponse()
            if self.voiceEnabled { self.voiceStatus = .listening }
        }

        rt.onAssistantDelta = { [weak self] delta in
            self?.assistantTranscript += delta
        }

        rt.onUserTranscript = { [weak self] text in
            guard let self else { return }
            self.userTranscript = text
            self.log("you: \(text)")
        }

        rt.onSpeechStarted = { [weak self] in
            guard let self else { return }
            if self.voiceEnabled { self.voiceStatus = .hearing }
        }

        rt.onSpeechStopped = { [weak self] in
            guard let self else { return }
            if self.voiceEnabled { self.voiceStatus = .thinking }
            self.assistantTranscript = ""      // a fresh answer is coming
        }

        rt.onError = { [weak self] message in
            guard let self else { return }
            self.lastError = message
            self.log("API error: \(message)")
            // Vision degradation: if input_image was rejected, the photo is
            // already safe in the gallery — just clear the attach state.
            if self.photoAttached {
                self.photoAttached = false
            }
        }

        rt.onClosed = { [weak self] reason in
            guard let self else { return }
            self.log("realtime connection closed: \(reason)")
            guard self.voiceEnabled else { return }
            self.voiceEnabled = false
            self.link.cancelResponse()
            self.realtime?.close()
            self.realtime = nil
            // Keep the glasses in voice mode ('M' stays set) — the session is
            // coming back; flapping 'M'/'m' across a 2 s retry buys nothing.
            self.scheduleVoiceReconnect(reason: "Voice connection closed: \(reason)")
        }
    }

    // MARK: Vision

    /// Attach the most recent glasses photo to the conversation so the next
    /// spoken question can reference it.
    func askAboutPendingPhoto() {
        guard let photo = pendingPhoto else { return }
        guard let rt = realtime, voiceEnabled else {
            lastError = "Voice isn't connected yet — retry voice first."
            return
        }
        guard let jpeg = try? Data(contentsOf: photo.url) else {
            lastError = "Could not reload the photo from disk."
            return
        }
        rt.attachImage(jpeg: jpeg)
        photoAttached = true
        log("photo attached — ask your question")
    }

    func dismissPendingPhoto() {
        pendingPhoto = nil
        photoAttached = false
    }

    // MARK: Logging

    func log(_ text: String) {
        eventLog.append(LogEntry(text: text))
        if eventLog.count > 500 {
            eventLog.removeFirst(eventLog.count - 500)
        }
    }

    func clearLog() { eventLog.removeAll() }
}
