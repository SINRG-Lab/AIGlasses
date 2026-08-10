import Foundation
import Observation
import SwiftUI
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
/// Hands-free activation: the app connects BLE on launch and arms the glasses'
/// physical side button. The first mic/photo event requests a short-lived
/// credential from Supabase and opens the Realtime session. A bounded audio
/// prebuffer preserves the beginning of speech while those network operations
/// complete; the phone never becomes a push-to-talk control.
@MainActor
@Observable
final class AppModel {

    private struct VisionPreparation {
        let generation: Int
        let task: Task<PreparedRealtimeImage?, Never>
    }

    enum VoiceStatus: String {
        case off = "Voice off"
        case ready = "Ready"
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
    @ObservationIgnored private let credentialService: SupabaseRealtimeService

    // UI state
    private(set) var voiceEnabled = false
    private(set) var voiceStatus: VoiceStatus = .off {
        didSet { syncLiveActivity() }
    }
    private(set) var userTranscript = ""
    private(set) var assistantTranscript = ""
    private(set) var micGain = 4.0
    private(set) var micLevel = 0.0            // 0…1, post-HPF pre-gain peak
    var lastError: String?
    private(set) var eventLog: [LogEntry] = []
    private(set) var pendingPhoto: GalleryStore.Photo?
    private(set) var photoAttached = false

    // Hands-free activation
    /// True unless the user explicitly disabled glasses voice in Settings.
    @ObservationIgnored private var voiceWanted = true
    @ObservationIgnored private var voiceSessionTask: Task<Void, Never>?
    @ObservationIgnored private var voiceGeneration = 0
    @ObservationIgnored private var retryGate = VoiceRetryGate()
    @ObservationIgnored private var activityState = RealtimeActivityState()
    @ObservationIgnored private var idleCloseTask: Task<Void, Never>?
    /// A terminal WebSocket callback during one held-button stream must not
    /// turn every following mic frame into a fresh credential request. Only
    /// the firmware's next physical recording-start marker clears this gate.
    @ObservationIgnored private var terminalSessionBlockedUntilNextRecording = false
    // 15 seconds of PCM16 at 24 kHz. This covers auth + function + WebSocket
    // startup without letting microphone data grow without bound.
    @ObservationIgnored private var pendingMic = BoundedAudioBuffer(capacityBytes: 15 * 24_000 * 2)
    @ObservationIgnored private var didLogMicTrim = false
    @ObservationIgnored private var pendingVisionImage: PreparedRealtimeImage?
    @ObservationIgnored private var pendingVisionPreparation: VisionPreparation?
    @ObservationIgnored private var visionPreparationGeneration = 0

    private static let realtimeIdleTimeoutNanoseconds: UInt64 = 120_000_000_000

    var voiceAutoEnabled: Bool { voiceWanted }

    init() {
        credentialService = SupabaseRealtimeService(
            deviceID: Self.stableDeviceID(),
            appVersion: Self.appVersion()
        )
        voiceWanted = settings.glassesVoiceEnabled
        wireLink()
        log("app started")
        // Bring the radio up immediately. The secure cloud session is still
        // deferred until the physical glasses button produces mic/photo data.
        link.connect()
    }

    private static func stableDeviceID() -> String {
        let key = "client.device-id"
        if let existing = UserDefaults.standard.string(forKey: key), !existing.isEmpty {
            return existing
        }
        let identifier = UUID().uuidString
        UserDefaults.standard.set(identifier, forKey: key)
        return identifier
    }

    private static func appVersion() -> String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0"
        return "\(version)+\(build)"
    }

    // MARK: Link wiring (BLE + WiFi behind one facade)

    private func wireLink() {
        link.onLog = { [weak self] line in self?.log(line) }

        link.onMicAudio = { [weak self] pcm16k in
            guard let self else { return }
            guard self.voiceWanted else { return }
            // BLE mic traffic exists only while the GLASSES button is held.
            // That hardware event—not a phone control—is the session trigger.
            self.extendBackgroundRuntime()
            self.noteRecordingActivity()
            let conditioned = self.mic.process(pcm16k)
            self.micGain = self.mic.gain
            self.micLevel = min(1.0, Double(self.mic.lastPeak) / 32767.0)
            let pcm24k = Resampler.upsample16to24(conditioned)
            if let rt = self.realtime,
               self.pendingVisionPreparation == nil,
               self.pendingVisionImage == nil {
                rt.appendMic(pcm24k: pcm24k)
            } else if self.realtime != nil || self.voiceSessionTask != nil {
                // Image preparation deliberately holds audio so the image
                // event is queued first without blocking CoreBluetooth.
                self.bufferPendingMic(pcm24k)
            } else if !self.terminalSessionBlockedUntilNextRecording,
                      !self.retryGate.isBlocked() {
                self.bufferPendingMic(pcm24k)
                self.startVoiceSessionFromGlasses()
            } else {
                // Do not retain speech recorded during a cooldown. It would be
                // stale by the time a later physical-button interaction starts.
            }
        }

        link.onPhoto = { [weak self] jpeg in
            guard let self else { return }
            Task { [weak self] in
                guard let self else { return }
                do {
                    let photo = try await self.gallery.save(jpeg: jpeg)
                    self.pendingPhoto = photo
                    self.photoAttached = false
                    self.log("photo saved to gallery (\(jpeg.count) B)")
                } catch {
                    self.log("photo save failed: \(error.localizedDescription)")
                }
            }
        }

        // Vision gesture (tap-then-hold on the glasses): the photo arrives up
        // front and is injected straight into the live conversation; the voice
        // spoken during the hold is the question. Fully hands-free.
        link.onVisionPhoto = { [weak self] jpeg in
            guard let self else { return }
            self.extendBackgroundRuntime()
            if self.voiceWanted {
                // Serialize off-main, buffer any following mic frames, then
                // queue image-before-audio. Auth can open in parallel.
                self.queueVisionImage(jpeg: jpeg, startSession: self.realtime == nil)
            } else {
                self.log("vision photo received but voice is off — saved to gallery")
            }
            Task { [weak self] in
                guard let self else { return }
                do {
                    let photo = try await self.gallery.save(jpeg: jpeg)
                    self.pendingPhoto = photo
                    self.log("vision photo saved to gallery (\(jpeg.count) B)")
                } catch {
                    self.log("vision photo save failed: \(error.localizedDescription)")
                }
            }
        }

        link.onBargeIn = { [weak self] in
            guard let self else { return }
            self.cancelIdleClose()
            self.activityState.responseActive = false
            if self.voiceEnabled { self.voiceStatus = .listening }
            self.scheduleIdleCloseIfPossible()
        }

        link.onRecordingStarted = { [weak self] in
            guard let self else { return }
            // A new physical press defines a new utterance. Never mix audio
            // retained for an older press into this one, even if auth for the
            // older press was still opening or cooling down.
            if self.realtime == nil {
                self.pendingMic.removeAll()
                self.didLogMicTrim = false
            }
            self.terminalSessionBlockedUntilNextRecording = false
        }

        link.onResponsePlaybackFinished = { [weak self] in
            guard let self else { return }
            self.activityState.responseActive = false
            if self.voiceEnabled { self.voiceStatus = .listening }
            self.scheduleIdleCloseIfPossible()
        }

        link.onConnected = { [weak self] in
            guard let self else { return }
            self.mic.reset()
            self.armGlassesVoiceIfPossible()
        }
    }

    // MARK: Connection controls

    func connectGlasses() { link.connect() }

    func disconnectGlasses() {
        tearDownVoice(disablePreference: false, logMessage: "voice session stopped (glasses disconnected)")
        link.disconnectAll()
    }

    // MARK: App lifecycle (background operation)

    /// `bluetooth-central` lets subscribed BLE traffic wake the app. iOS still
    /// controls suspension time and does not promise an indefinitely live
    /// network socket while locked; foregrounding therefore re-arms the link.
    func scenePhaseChanged(to phase: ScenePhase) {
        switch phase {
        case .background:
            link.enterBackground()
        case .active:
            link.enterForeground()
            releaseBackgroundRuntime()
            if voiceWanted { armGlassesVoiceIfPossible() }
            syncLiveActivity()   // activities can only be STARTED in foreground
        default:
            break
        }
    }

    // MARK: Background runtime (voice exchanges while backgrounded)

    @ObservationIgnored private var bgTask: UIBackgroundTaskIdentifier = .invalid
    @ObservationIgnored private var bgReleaseTask: Task<Void, Never>?

    /// Request a finite, best-effort background execution window while a voice
    /// exchange is in flight. This improves locked-phone continuity but does
    /// not override iOS suspension policy. Released 15 s after the last audio.
    private func extendBackgroundRuntime() {
        if bgTask == .invalid {
            bgTask = UIApplication.shared.beginBackgroundTask(withName: "voice-exchange") { [weak self] in
                self?.releaseBackgroundRuntime()
            }
        }
        bgReleaseTask?.cancel()
        bgReleaseTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 15_000_000_000)
            guard let self, !Task.isCancelled else { return }
            self.releaseBackgroundRuntime()
        }
    }

    private func releaseBackgroundRuntime() {
        bgReleaseTask?.cancel()
        bgReleaseTask = nil
        if bgTask != .invalid {
            UIApplication.shared.endBackgroundTask(bgTask)
            bgTask = .invalid
        }
    }

    // MARK: Live Activity (Dynamic Island / Lock Screen)

    @ObservationIgnored private let liveActivity = GlassesLiveActivityController()
    @ObservationIgnored private var recordingDecayTask: Task<Void, Never>?

    private var micStreaming: Bool { activityState.microphoneActive }

    /// Mic frames are the only "recording" signal we have — pulse a flag that
    /// decays 700 ms after the stream stops (button released).
    private func noteRecordingActivity() {
        cancelIdleClose()
        recordingDecayTask?.cancel()
        if !micStreaming {
            activityState.microphoneActive = true
            syncLiveActivity()
        }
        recordingDecayTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 700_000_000)
            guard let self, !Task.isCancelled else { return }
            self.activityState.microphoneActive = false
            self.syncLiveActivity()
            self.scheduleIdleCloseIfPossible()
        }
    }

    func syncLiveActivity() {
        liveActivity.sync(
            active: voiceEnabled || voiceWanted && voiceStatus != .off,
            state: .init(status: micStreaming ? "Recording" : voiceStatus.rawValue,
                         recording: micStreaming))
    }

    // MARK: Voice session (physical-button triggered)

    /// Arms firmware voice mode so holding the glasses' physical side button
    /// emits µ-law mic frames. Auth is prewarmed, but no OpenAI credential and
    /// no quota reservation is requested until that hardware event arrives.
    private func armGlassesVoiceIfPossible() {
        guard voiceWanted, ble.isConnected else { return }
        link.setVoiceMode(true)
        if realtime == nil, voiceSessionTask == nil {
            voiceStatus = .ready
        }
        let service = credentialService
        Task {
            // A failure here is intentionally silent: the physical-button
            // request will retry and surface a concise, user-facing error.
            try? await service.prepareAuthentication()
        }
    }

    /// Re-arms the glasses after a user-visible failure or manual disable. It
    /// is not push-to-talk: speech still starts only from the glasses button.
    func retryVoiceNow() {
        voiceWanted = true
        settings.glassesVoiceEnabled = true
        lastError = nil
        link.connect()
        armGlassesVoiceIfPossible()
    }

    /// Manual off switch (Settings) — stays off until re-enabled.
    func stopVoice() {
        tearDownVoice(disablePreference: true, logMessage: "voice OFF (manual)")
    }

    private func tearDownVoice(disablePreference: Bool, logMessage: String) {
        if disablePreference {
            voiceWanted = false
            settings.glassesVoiceEnabled = false
        }
        voiceGeneration &+= 1
        voiceSessionTask?.cancel()
        voiceSessionTask = nil
        cancelIdleClose()
        recordingDecayTask?.cancel()
        recordingDecayTask = nil
        activityState.reset()
        terminalSessionBlockedUntilNextRecording = false
        pendingMic.removeAll()
        discardPendingVision()
        didLogMicTrim = false
        voiceEnabled = false
        voiceStatus = .off
        link.setVoiceMode(false)
        link.cancelResponse()
        let session = realtime
        realtime = nil
        session?.close()
        releaseBackgroundRuntime()
        liveActivity.end()
        log(logMessage)
    }

    private func bufferPendingMic(_ pcm24k: Data) {
        let trimmed = pendingMic.append(pcm24k)
        if trimmed, !didLogMicTrim {
            didLogMicTrim = true
            log("secure session startup exceeded the 15 s audio buffer; oldest audio trimmed")
        }
    }

    private func flushPendingMic(to rt: RealtimeSession) {
        let bufferedAudio = pendingMic.drain()
        didLogMicTrim = false
        if !bufferedAudio.isEmpty {
            rt.appendMic(pcm24k: bufferedAudio)
        }
    }

    private func queueVisionImage(jpeg: Data, startSession: Bool) {
        beginVisionPreparation(startSession: startSession) {
            RealtimeSession.prepareImage(jpeg: jpeg)
        }
    }

    private func queueVisionImage(fileURL: URL, startSession: Bool) {
        beginVisionPreparation(startSession: startSession) {
            guard let jpeg = try? Data(contentsOf: fileURL) else { return nil }
            return RealtimeSession.prepareImage(jpeg: jpeg)
        }
    }

    /// Starts JPEG Base64 + JSON preparation away from the main actor. While
    /// it runs, mic frames go to the bounded buffer. The ready image event is
    /// always queued before that audio is flushed.
    private func beginVisionPreparation(
        startSession: Bool,
        operation: @escaping @Sendable () -> PreparedRealtimeImage?
    ) {
        cancelIdleClose()
        visionPreparationGeneration &+= 1
        let generation = visionPreparationGeneration
        pendingVisionPreparation?.task.cancel()
        pendingVisionImage = nil
        photoAttached = true

        let task = Task.detached(priority: .userInitiated) {
            guard !Task.isCancelled else { return nil as PreparedRealtimeImage? }
            let image = operation()
            guard !Task.isCancelled else { return nil }
            return image
        }
        pendingVisionPreparation = VisionPreparation(generation: generation, task: task)

        Task { [weak self] in
            let image = await task.value
            guard let self,
                  self.pendingVisionPreparation?.generation == generation else { return }
            self.pendingVisionPreparation = nil
            guard let image else {
                self.pendingVisionImage = nil
                self.photoAttached = false
                self.lastError = "Could not prepare the photo for the voice session."
                self.log("vision photo preparation failed")
                if self.voiceSessionTask == nil, let rt = self.realtime {
                    // Preparation held mic frames to preserve image-before-
                    // audio ordering. If the image cannot be created, release
                    // those frames now before later direct frames can overtake.
                    self.flushPendingMic(to: rt)
                    self.scheduleIdleCloseIfPossible()
                }
                return
            }
            self.pendingVisionImage = image

            // An opening session owns image/audio ordering itself. For an
            // already-live session, attach now and release the held mic frames.
            guard self.voiceSessionTask == nil, let rt = self.realtime else { return }
            self.pendingVisionImage = nil
            rt.attachImage(image)
            self.flushPendingMic(to: rt)
            self.photoAttached = true
            self.log("vision photo attached — answering your spoken question")
            self.scheduleIdleCloseIfPossible()
        }

        if startSession {
            startVoiceSessionFromGlasses()
        }
    }

    /// Waits for the newest queued image, tolerating an older preparation task
    /// being superseded while the credential request is in flight.
    private func takePendingVisionImage() async -> PreparedRealtimeImage? {
        while let preparation = pendingVisionPreparation {
            let image = await preparation.task.value
            if pendingVisionPreparation?.generation == preparation.generation {
                pendingVisionPreparation = nil
                pendingVisionImage = image
                if image == nil {
                    photoAttached = false
                    lastError = "Could not prepare the photo for the voice session."
                    log("vision photo preparation failed")
                }
            }
        }
        let image = pendingVisionImage
        pendingVisionImage = nil
        return image
    }

    private func discardPendingVision() {
        visionPreparationGeneration &+= 1
        pendingVisionPreparation?.task.cancel()
        pendingVisionPreparation = nil
        pendingVisionImage = nil
        photoAttached = false
    }

    /// Called only by mic data or the vision gesture from the glasses.
    private func startVoiceSessionFromGlasses() {
        guard voiceWanted, ble.isConnected else { return }
        guard realtime == nil, voiceSessionTask == nil else { return }
        guard !terminalSessionBlockedUntilNextRecording else { return }
        guard !retryGate.isBlocked() else { return }

        voiceGeneration &+= 1
        let generation = voiceGeneration

        lastError = nil
        userTranscript = ""
        assistantTranscript = ""
        voiceEnabled = true
        voiceStatus = .connecting

        voiceSessionTask = Task { [weak self] in
            guard let self else { return }
            defer {
                if self.voiceGeneration == generation {
                    self.voiceSessionTask = nil
                }
            }
            do {
                let credential = try await self.credentialService.fetchCredential()
                guard !Task.isCancelled, self.voiceWanted,
                      self.voiceGeneration == generation else { return }

                let rt = RealtimeSession()
                self.realtime = rt
                self.wireRealtime(rt, generation: generation)
                rt.connect(
                    credential: credential,
                    effort: self.settings.reasoningEffort,
                    voice: self.settings.voice
                )
                guard self.isCurrentRealtime(rt, generation: generation) else { return }

                // Preserve event order: session.update, queued image, then all
                // buffered mic audio. Subsequent mic frames stream directly.
                let image = await self.takePendingVisionImage()
                guard !Task.isCancelled,
                      self.isCurrentRealtime(rt, generation: generation) else { return }
                if let image {
                    rt.attachImage(image)
                }
                self.flushPendingMic(to: rt)
                self.log("secure voice session opening (model \(credential.model))")
            } catch is CancellationError {
                // Manual disable/disconnect owns the visible state.
            } catch {
                guard self.voiceGeneration == generation else { return }
                let serviceError = error as? RealtimeCredentialServiceError ?? .serviceUnavailable
                let delay = self.retryGate.record(serviceError) ?? 0
                switch serviceError {
                case .authenticationUnavailable, .invalidResponse, .expiredCredential:
                    // These are terminal for this physical press. A still-held
                    // button must not repeat refresh/re-auth or invalid-session
                    // work every two seconds.
                    self.terminalSessionBlockedUntilNextRecording = true
                case .rateLimited, .networkUnavailable, .serviceUnavailable:
                    break
                }
                let message = serviceError.localizedDescription
                self.pendingMic.removeAll()
                self.discardPendingVision()
                self.didLogMicTrim = false
                self.voiceEnabled = false
                self.voiceStatus = self.voiceWanted && self.ble.isConnected ? .ready : .off
                self.lastError = message
                self.log("secure voice session unavailable; retry gated for \(Int(delay)) s: \(message)")
            }
        }
    }

    private func wireRealtime(_ rt: RealtimeSession, generation: Int) {
        rt.onLog = { [weak self, weak rt] line in
            guard let self, let rt, self.isCurrentRealtime(rt, generation: generation) else { return }
            self.log(line)
        }

        rt.onOpen = { [weak self, weak rt] in
            guard let self, let rt, self.isCurrentRealtime(rt, generation: generation),
                  self.voiceEnabled else { return }
            self.retryGate.reset()
            self.voiceStatus = .listening
            self.lastError = nil
            self.scheduleIdleCloseIfPossible()
        }

        rt.onAudioDelta = { [weak self, weak rt] pcm24k in
            guard let self, let rt, self.isCurrentRealtime(rt, generation: generation) else { return }
            self.cancelIdleClose()
            self.activityState.responseActive = true
            if self.voiceEnabled { self.voiceStatus = .speaking }
            self.extendBackgroundRuntime()
            self.link.enqueueResponseAudio(ulaw: ULaw.encode(pcm16: pcm24k))
        }

        rt.onResponseDone = { [weak self, weak rt] in
            guard let self, let rt, self.isCurrentRealtime(rt, generation: generation) else { return }
            self.link.finishResponse()
        }

        rt.onAssistantDelta = { [weak self, weak rt] delta in
            guard let self, let rt, self.isCurrentRealtime(rt, generation: generation) else { return }
            self.assistantTranscript += delta
        }

        rt.onUserTranscript = { [weak self, weak rt] text in
            guard let self, let rt, self.isCurrentRealtime(rt, generation: generation) else { return }
            self.userTranscript = text
            self.log("you: \(text)")
        }

        rt.onSpeechStarted = { [weak self, weak rt] in
            guard let self, let rt, self.isCurrentRealtime(rt, generation: generation) else { return }
            self.cancelIdleClose()
            if self.voiceEnabled { self.voiceStatus = .hearing }
        }

        rt.onSpeechStopped = { [weak self, weak rt] in
            guard let self, let rt, self.isCurrentRealtime(rt, generation: generation) else { return }
            self.cancelIdleClose()
            self.activityState.responseActive = true
            if self.voiceEnabled { self.voiceStatus = .thinking }
            self.assistantTranscript = ""      // a fresh answer is coming
        }

        rt.onError = { [weak self, weak rt] message in
            guard let self, let rt, self.isCurrentRealtime(rt, generation: generation) else { return }
            self.lastError = message
            self.log("API error: \(message)")
            // Vision degradation: if input_image was rejected, the photo is
            // already safe in the gallery — just clear the attach state.
            if self.photoAttached {
                self.photoAttached = false
            }
        }

        rt.onClosed = { [weak self, weak rt] reason in
            guard let self, let rt, self.isCurrentRealtime(rt, generation: generation) else { return }
            self.handleCurrentRealtimeClosed(rt, reason: reason)
        }
    }

    private func isCurrentRealtime(_ rt: RealtimeSession, generation: Int) -> Bool {
        realtime === rt && voiceGeneration == generation
    }

    private func handleCurrentRealtimeClosed(_ rt: RealtimeSession, reason: RealtimeCloseReason) {
        log("realtime connection closed: \(reason.message)")
        guard realtime === rt else { return }

        voiceGeneration &+= 1
        voiceSessionTask?.cancel()
        voiceSessionTask = nil
        cancelIdleClose()
        recordingDecayTask?.cancel()
        recordingDecayTask = nil
        activityState.reset()
        terminalSessionBlockedUntilNextRecording = true
        pendingMic.removeAll()
        discardPendingVision()
        didLogMicTrim = false
        voiceEnabled = false
        realtime = nil
        link.cancelResponse()
        rt.close()

        // Keep firmware voice mode armed. The next physical-button mic event
        // obtains a fresh credential after any local cooldown instead of
        // burning quota in an unattended reconnect loop.
        voiceStatus = voiceWanted && ble.isConnected ? .ready : .off
        guard voiceWanted else { return }
        if let serviceError = retryableClosureError(reason.kind) {
            let delay = retryGate.record(serviceError) ?? 0
            lastError = serviceError.localizedDescription
            log("realtime retry gated for \(Int(delay)) s")
        } else {
            lastError = "Voice connection closed: \(reason.message) Use the glasses button to try again."
        }
    }

    private func retryableClosureError(
        _ kind: RealtimeCloseReason.Kind
    ) -> RealtimeCredentialServiceError? {
        switch kind {
        case .rateLimited: .rateLimited
        case .serviceUnavailable: .serviceUnavailable
        case .networkUnavailable: .networkUnavailable
        case .authenticationRejected, .accessDenied, .credentialExpired, .other: nil
        }
    }

    // MARK: Realtime idle power policy

    private func cancelIdleClose() {
        idleCloseTask?.cancel()
        idleCloseTask = nil
    }

    /// Restarts the two-minute inactivity window. The task re-checks both
    /// activity flags on the main actor immediately before closing.
    private func scheduleIdleCloseIfPossible() {
        cancelIdleClose()
        guard voiceEnabled, voiceWanted, !activityState.isBusy, let rt = realtime else { return }
        let generation = voiceGeneration
        idleCloseTask = Task { [weak self, weak rt] in
            try? await Task.sleep(nanoseconds: Self.realtimeIdleTimeoutNanoseconds)
            guard let self, let rt, !Task.isCancelled,
                  self.isCurrentRealtime(rt, generation: generation),
                  !self.activityState.isBusy else { return }
            self.closeRealtimeForIdle(rt, generation: generation)
        }
    }

    private func closeRealtimeForIdle(_ rt: RealtimeSession, generation: Int) {
        guard isCurrentRealtime(rt, generation: generation), !activityState.isBusy else { return }
        voiceGeneration &+= 1
        idleCloseTask = nil
        activityState.reset()
        discardPendingVision()
        voiceEnabled = false
        realtime = nil
        link.cancelResponse()
        rt.close()
        voiceStatus = voiceWanted && ble.isConnected ? .ready : .off
        log("realtime connection closed after 2 minutes idle; glasses button remains armed")
    }

    // MARK: Vision

    /// Attach the most recent glasses photo to the conversation so the next
    /// spoken question can reference it.
    func askAboutPendingPhoto() {
        guard let photo = pendingPhoto else { return }
        queueVisionImage(fileURL: photo.url, startSession: false)
        lastError = nil
        log(realtime == nil
            ? "photo preparing — hold the glasses button to ask about it"
            : "photo preparing — ask your question after it attaches")
    }

    func dismissPendingPhoto() {
        pendingPhoto = nil
        discardPendingVision()
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
