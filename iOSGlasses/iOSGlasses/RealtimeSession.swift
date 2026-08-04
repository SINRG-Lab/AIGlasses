import Foundation

/// One OpenAI GPT Realtime session over a raw WebSocket
/// (URLSessionWebSocketTask). GA endpoint — no beta header.
///
/// The session owns the *conversation*; it deliberately outlives BLE drops
/// (BleManager reconnects the radio underneath it and resumes streaming).
///
/// Uplink:  µ-law mic frames are decoded/conditioned by AppModel, resampled
///          to 24 kHz PCM16 and appended here as input_audio_buffer.append.
///          Mic streams only while the glasses' button is held (push-to-talk),
///          so when frames stop for >250 ms after speech we append 600 ms of
///          silence ONCE so server VAD actually hears the turn end.
/// Downlink: response.output_audio.delta carries base64 24 kHz PCM16.
@MainActor
final class RealtimeSession: NSObject {

    enum State: String {
        case idle, connecting, open, closed
    }

    private(set) var state: State = .idle

    // Callbacks — all invoked on the main actor.
    var onOpen: (() -> Void)?
    var onAudioDelta: ((Data) -> Void)?          // PCM16 @24 kHz
    var onResponseDone: (() -> Void)?
    var onAssistantDelta: ((String) -> Void)?
    var onUserTranscript: ((String) -> Void)?
    var onSpeechStarted: (() -> Void)?
    var onSpeechStopped: (() -> Void)?
    var onError: ((String) -> Void)?
    var onClosed: ((String) -> Void)?
    var onLog: ((String) -> Void)?

    static let instructions =
        "You are a voice assistant built into a pair of smart glasses. " +
        "Keep answers to one or two spoken sentences — never lists or formatting. " +
        "Answer factual questions directly and accurately. " +
        "The microphone is imperfect: if you did not clearly understand the user, " +
        "say so and ask them to repeat — NEVER guess at what they said, and never " +
        "agree with or confirm a statement you only partially heard."

    private var ws: URLSessionWebSocketTask?
    private var session: URLSession?
    private var model = ""
    private var receiveTask: Task<Void, Never>?
    private var silenceTask: Task<Void, Never>?
    private var talking = false
    private var lastMicAt = Date.distantPast

    // MARK: Lifecycle

    func connect(apiKey: String, model: String, effort: String, voice: String) {
        guard state == .idle || state == .closed else { return }
        guard let url = URL(string: "wss://api.openai.com/v1/realtime?model=\(model)") else {
            onError?("bad model string")
            return
        }
        self.model = model
        var req = URLRequest(url: url)
        req.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        // Own session with self as delegate: the ONLY way to see why a
        // handshake failed (HTTP status) or why the server closed us (close
        // reason). URLSession.shared reports both as a bare ENOTCONN
        // "Socket is not connected", which is undebuggable in the field.
        let session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
        self.session = session
        let task = session.webSocketTask(with: req)
        task.maximumMessageSize = 1 << 24
        ws = task
        state = .connecting
        task.resume()

        // Exact GA session.update schema (verified working in realtime_ble.py).
        sendJSON([
            "type": "session.update",
            "session": [
                "type": "realtime",
                "instructions": Self.instructions,
                "reasoning": ["effort": effort],
                "output_modalities": ["audio"],
                "audio": [
                    "input": [
                        "format": ["type": "audio/pcm", "rate": 24000],
                        "transcription": ["model": "gpt-4o-mini-transcribe"],
                        "turn_detection": [
                            "type": "server_vad",
                            "threshold": 0.5,
                            "prefix_padding_ms": 300,
                            "silence_duration_ms": 200,
                            "create_response": true,
                            "interrupt_response": true,
                        ],
                    ],
                    "output": [
                        "format": ["type": "audio/pcm", "rate": 24000],
                        "voice": voice,
                    ],
                ],
            ],
        ])
        onLog?("realtime: connecting (model \(model), effort \(effort), voice \(voice))")
        startReceiveLoop()
        startSilenceWatchdog()
    }

    func close() {
        receiveTask?.cancel()
        receiveTask = nil
        silenceTask?.cancel()
        silenceTask = nil
        ws?.cancel(with: .normalClosure, reason: nil)
        ws = nil
        // Invalidate — the session retains its delegate (us) until told not to.
        session?.invalidateAndCancel()
        session = nil
        state = .closed
    }

    // MARK: Uplink

    /// Append conditioned mic audio (PCM16 @24 kHz little-endian).
    func appendMic(pcm24k: Data) {
        guard !pcm24k.isEmpty, ws != nil else { return }
        talking = true
        lastMicAt = Date()
        sendJSON([
            "type": "input_audio_buffer.append",
            "audio": pcm24k.base64EncodedString(),
        ])
    }

    /// Attach a photo from the glasses to the conversation; the user's next
    /// voice turn can reference it. If the API rejects input_image the error
    /// event surfaces through onError (photo is already safe in the gallery).
    func attachImage(jpeg: Data) {
        sendJSON([
            "type": "conversation.item.create",
            "item": [
                "type": "message",
                "role": "user",
                "content": [
                    [
                        "type": "input_image",
                        "image_url": "data:image/jpeg;base64,\(jpeg.base64EncodedString())",
                    ],
                ],
            ],
        ])
        onLog?("realtime: photo attached (\(jpeg.count) B)")
    }

    // MARK: Silence tail (push-to-talk -> server VAD bridge)

    /// The glasses stream mic audio only while the button is held. Server VAD
    /// needs to HEAR silence to close the turn — when frames stop for >250 ms
    /// after speech, feed 600 ms of zeros once, then go quiet.
    private func startSilenceWatchdog() {
        silenceTask?.cancel()
        silenceTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 100_000_000)
                guard let self else { return }
                if self.talking, Date().timeIntervalSince(self.lastMicAt) > 0.25 {
                    self.talking = false
                    let silence = Data(count: 2 * 24000 * 6 / 10)   // 600 ms @24 kHz PCM16
                    self.sendJSON([
                        "type": "input_audio_buffer.append",
                        "audio": silence.base64EncodedString(),
                    ])
                }
            }
        }
    }

    // MARK: WebSocket plumbing

    private func sendJSON(_ obj: [String: Any]) {
        guard let ws else { return }
        guard let data = try? JSONSerialization.data(withJSONObject: obj) else {
            onError?("JSON encode failed")
            return
        }
        ws.send(.string(String(decoding: data, as: UTF8.self))) { [weak self] error in
            if let error {
                Task { @MainActor [weak self] in
                    self?.handleTransportError(error)
                }
            }
        }
    }

    private func startReceiveLoop() {
        receiveTask?.cancel()
        receiveTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, let ws = self.ws else { return }
                do {
                    let msg = try await ws.receive()
                    self.handleMessage(msg)
                } catch {
                    if !Task.isCancelled {
                        self.handleTransportError(error)
                    }
                    return
                }
            }
        }
    }

    private func handleTransportError(_ error: Error) {
        // The handshake HTTP status names the real cause; the socket error
        // ("Socket is not connected") is just the aftermath.
        let status = (ws?.response as? HTTPURLResponse)?.statusCode
        finish(reason: Self.describe(error: error, httpStatus: status, model: model))
    }

    private func finish(reason: String) {
        guard state != .closed else { return }
        state = .closed
        ws = nil
        session?.invalidateAndCancel()
        session = nil
        onClosed?(reason)
    }

    private static func describe(error: Error, httpStatus: Int?, model: String) -> String {
        if let code = httpStatus, code != 101 {
            switch code {
            case 401: return "OpenAI rejected the API key (HTTP 401) — re-paste it in Settings."
            case 403: return "OpenAI refused access (HTTP 403) — this key/org can't use \(model)."
            case 429: return "OpenAI rate/quota limit (HTTP 429) — check billing/credits."
            default:  return "OpenAI handshake failed (HTTP \(code), model \(model))."
            }
        }
        let ns = error as NSError
        if ns.domain == NSURLErrorDomain, ns.code == NSURLErrorNotConnectedToInternet {
            return "No internet connection on the phone."
        }
        return error.localizedDescription
    }

    // MARK: Event handling

    private func handleMessage(_ msg: URLSessionWebSocketTask.Message) {
        let text: String
        switch msg {
        case .string(let s): text = s
        case .data(let d): text = String(decoding: d, as: UTF8.self)
        @unknown default: return
        }
        guard let obj = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              let type = obj["type"] as? String else { return }

        switch type {
        case "session.created", "session.updated":
            if state != .open {
                state = .open
                onLog?("realtime: session open")
                onOpen?()
            }

        case "response.output_audio.delta":
            if let b64 = obj["delta"] as? String, let pcm = Data(base64Encoded: b64) {
                onAudioDelta?(pcm)
            }

        case "response.done":
            onResponseDone?()

        case "response.output_audio_transcript.delta":
            if let d = obj["delta"] as? String {
                onAssistantDelta?(d)
            }

        case "conversation.item.input_audio_transcription.completed":
            if let t = obj["transcript"] as? String {
                onUserTranscript?(t.trimmingCharacters(in: .whitespacesAndNewlines))
            }

        case "input_audio_buffer.speech_started":
            onSpeechStarted?()

        case "input_audio_buffer.speech_stopped":
            onSpeechStopped?()

        case "error":
            let message = ((obj["error"] as? [String: Any])?["message"] as? String) ?? text
            onError?(message)

        default:
            break
        }
    }
}

// MARK: - URLSessionWebSocketDelegate

// Delegate callbacks arrive on URLSession's queue; hop to the main actor.
extension RealtimeSession: URLSessionWebSocketDelegate {

    nonisolated func urlSession(_ session: URLSession,
                                webSocketTask: URLSessionWebSocketTask,
                                didOpenWithProtocol proto: String?) {
        Task { @MainActor in
            self.onLog?("realtime: websocket open (handshake OK)")
        }
    }

    nonisolated func urlSession(_ session: URLSession,
                                webSocketTask: URLSessionWebSocketTask,
                                didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
                                reason: Data?) {
        let why = reason.flatMap { String(data: $0, encoding: .utf8) } ?? ""
        Task { @MainActor in
            self.finish(reason: "server closed the session (code \(closeCode.rawValue))"
                        + (why.isEmpty ? "" : ": \(why)"))
        }
    }
}
