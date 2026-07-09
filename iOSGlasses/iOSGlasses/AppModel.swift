import Foundation
import Observation
import UIKit

/// Top-level coordinator: owns the BLE link, the OpenAI Realtime session, the
/// gallery and settings, and publishes UI state.
///
/// Data flow (mirrors the hardware-validated realtime_ble.py):
///   glasses mic --BLE 'A' µ-law@16k--> BleManager --decode+AGC+resample-->
///     RealtimeSession (input_audio_buffer.append, PCM16@24k)
///   RealtimeSession (response.output_audio.delta, PCM16@24k) --µ-law encode-->
///     BleManager ('S2' + paced 'A' frames + 'E') --> glasses speaker
///
/// The realtime session outlives BLE drops: BleManager reconnects, rewrites
/// 'M' and the conversation continues.
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
    let ble = BleManager()
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

    // Live video (MJPEG frames from the glasses camera)
    private(set) var isReceivingVideo = false
    private(set) var liveFrame: UIImage?
    private(set) var videoFps = 0.0
    @ObservationIgnored private var frameTimes: [Date] = []

    init() {
        wireBle()
        log("app started")
    }

    // MARK: BLE wiring

    private func wireBle() {
        ble.onLog = { [weak self] line in self?.log("[ble] \(line)") }

        ble.onMicAudio = { [weak self] pcm16k in
            guard let self, let rt = self.realtime else { return }
            let conditioned = self.mic.process(pcm16k)
            self.micGain = self.mic.gain
            self.micLevel = min(1.0, Double(self.mic.lastPeak) / 32767.0)
            rt.appendMic(pcm24k: Resampler.upsample16to24(conditioned))
        }

        ble.onPhoto = { [weak self] jpeg in
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

        // Live video (triple-tap the glasses to start; tap to stop). BLE
        // callbacks fire on the main queue (CBCentralManager queue: nil), so
        // touching @Observable UI state directly here is safe.
        ble.onVideoStart = { [weak self] in
            self?.isReceivingVideo = true
            self?.frameTimes.removeAll()
            self?.log("live video started")
        }
        ble.onVideoEnd = { [weak self] in
            self?.isReceivingVideo = false
            self?.videoFps = 0
            self?.log("live video ended")
        }
        ble.onVideoFrame = { [weak self] jpeg in
            guard let self else { return }
            if let img = UIImage(data: jpeg) { self.liveFrame = img }
            let now = Date()
            self.frameTimes.append(now)
            self.frameTimes.removeAll { now.timeIntervalSince($0) > 2 }
            self.videoFps = Double(self.frameTimes.count) / 2.0
        }

        // Vision gesture (tap-then-hold on the glasses): the photo arrives up
        // front and is injected straight into the live conversation; the voice
        // spoken during the hold is the question. Fully hands-free.
        ble.onVisionPhoto = { [weak self] jpeg in
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

        ble.onBargeIn = { [weak self] in
            guard let self else { return }
            if self.voiceEnabled { self.voiceStatus = .listening }
        }

        ble.onConnected = { [weak self] in
            guard let self else { return }
            self.mic.reset()
        }
    }

    // MARK: Connection controls

    func connectGlasses() { ble.connect() }

    func disconnectGlasses() {
        if voiceEnabled { stopVoice() }
        ble.disconnect()
    }

    // MARK: Voice session

    func toggleVoice() {
        if voiceEnabled { stopVoice() } else { startVoice() }
    }

    func startVoice() {
        guard !voiceEnabled else { return }
        let key = settings.apiKey
        guard !key.isEmpty else {
            lastError = "Set your OpenAI API key in Settings first."
            return
        }
        lastError = nil
        userTranscript = ""
        assistantTranscript = ""

        let rt = RealtimeSession()
        wireRealtime(rt)
        realtime = rt
        voiceEnabled = true
        voiceStatus = .connecting
        rt.connect(apiKey: key,
                   model: settings.model,
                   effort: settings.reasoningEffort,
                   voice: settings.voice)

        // Bring the radio up too (idempotent) and switch the glasses into
        // realtime µ-law voice mode.
        ble.connect()
        ble.setVoiceMode(true)
        log("voice ON (model \(settings.model))")
    }

    func stopVoice() {
        guard voiceEnabled else { return }
        voiceEnabled = false
        voiceStatus = .off
        ble.setVoiceMode(false)
        ble.cancelResponse()
        realtime?.close()
        realtime = nil
        log("voice OFF")
    }

    private func wireRealtime(_ rt: RealtimeSession) {
        rt.onLog = { [weak self] line in self?.log(line) }

        rt.onOpen = { [weak self] in
            guard let self, self.voiceEnabled else { return }
            self.voiceStatus = .listening
        }

        rt.onAudioDelta = { [weak self] pcm24k in
            guard let self else { return }
            if self.voiceEnabled { self.voiceStatus = .speaking }
            self.ble.enqueueResponseAudio(ulaw: ULaw.encode(pcm16: pcm24k))
        }

        rt.onResponseDone = { [weak self] in
            guard let self else { return }
            self.ble.finishResponse()
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
            if self.voiceEnabled {
                self.lastError = "Voice connection closed: \(reason)"
                self.stopVoice()
            }
        }
    }

    // MARK: Vision

    /// Attach the most recent glasses photo to the conversation so the next
    /// spoken question can reference it.
    func askAboutPendingPhoto() {
        guard let photo = pendingPhoto else { return }
        guard let rt = realtime, voiceEnabled else {
            lastError = "Turn voice on first, then attach the photo."
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
