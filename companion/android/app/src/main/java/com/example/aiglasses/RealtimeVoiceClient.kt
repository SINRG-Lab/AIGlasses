package com.example.aiglasses

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One OpenAI GPT Realtime session over an OkHttp WebSocket (GA endpoint, no
 * beta header). Mirror of iOS RealtimeSession.swift.
 *
 * SINGLE-SHOT: each instance opens exactly one socket and reports exactly one
 * [RealtimeEvent.Disconnected] when it dies. The reconnection policy
 * (exponential backoff + mic-audio revival) is owned by [GlassesController],
 * which builds a fresh client per attempt — exactly how iOS AppModel owns
 * RealtimeSession.
 *
 * Uplink:  conditioned mic audio arrives via [appendAudio] as PCM16 @24 kHz.
 *          The glasses stream mic audio only while the button is held
 *          (push-to-talk), so server VAD never hears the turn end by itself —
 *          when frames stop for >250 ms after speech, a watchdog appends
 *          600 ms of zeros ONCE (mirrors RealtimeSession.startSilenceWatchdog).
 * Downlink: response.output_audio.delta carries base64 PCM16 @24 kHz.
 *
 * Threading: all [onEvent] callbacks arrive on OkHttp's WebSocket reader
 * thread (or the silence-watchdog/writer threads for Disconnected edge
 * cases) — consumers marshal UI state to the main thread themselves but
 * should forward audio deltas to the BLE downlink directly. [appendAudio] /
 * [sendImage] are thread-safe: WebSocket.send() only enqueues onto OkHttp's
 * writer queue, and OkHttp buffers messages sent before the handshake
 * completes, so the first words of a question survive a reconnect.
 */
class RealtimeVoiceClient(
    private val clientSecret: String,
    private val model: String,
    private val voice: String,
    private val effort: String,
    private val onEvent: (RealtimeEvent) -> Unit
) {
    companion object {
        private const val TAG = "RealtimeVoiceClient"
        private const val INSTRUCTIONS =
            "You are a voice assistant built into a pair of smart glasses. " +
            "Keep answers to one or two spoken sentences — never lists or formatting. " +
            "Answer factual questions directly and accurately. " +
            "The microphone is imperfect: if you did not clearly understand the user, " +
            "say so and ask them to repeat — NEVER guess at what they said, and never " +
            "agree with or confirm a statement you only partially heard."

        /** 600 ms of PCM16 silence @24 kHz — the push-to-talk → server-VAD bridge. */
        private const val SILENCE_TAIL_BYTES = 2 * 24000 * 600 / 1000
        private const val SILENCE_AFTER_MS = 250L

        // One shared client across sessions: reconnect cycles must not leak
        // dispatcher/connection-pool threads.
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)    // WS stays open indefinitely
                .pingInterval(20, TimeUnit.SECONDS)       // detect dead links promptly
                .build()
        }

        /** CPU-heavy camera normalization; callers prepare this away from the main thread. */
        internal fun prepareImage(jpegBytes: ByteArray): PreparedImage {
            require(jpegBytes.isNotEmpty())
            val base64 = try {
                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                if (bitmap != null) {
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    bitmap.recycle()
                    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                } else {
                    Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Image re-encode failed", e)
                Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            }
            return PreparedImage(base64, jpegBytes.size)
        }
    }

    internal data class PreparedImage(val base64: String, val sourceByteCount: Int)

    sealed class RealtimeEvent {
        /** The session is open (server acked with session.created/updated). */
        data object Connected : RealtimeEvent()
        /** Terminal — fired at most once per client; never after [close]. */
        data class Disconnected(
            val reason: String,
            /** Present for an HTTP 429 WebSocket handshake; controller enforces it locally. */
            val retryAfterSeconds: Long? = null
        ) : RealtimeEvent()
        /** 24 kHz PCM16 LE response audio. [first] marks the first delta of a response. */
        // Plain class: a data class with a ByteArray field generates broken
        // (reference-equality) equals/hashCode; consumers only pattern-match.
        class AudioDelta(val pcm: ByteArray, val first: Boolean) : RealtimeEvent()
        data object ResponseDone : RealtimeEvent()
        data class AssistantTranscriptDelta(val text: String) : RealtimeEvent()
        data class UserTranscript(val text: String) : RealtimeEvent()
        data object SpeechStarted : RealtimeEvent()
        data object SpeechStopped : RealtimeEvent()
        data class Error(val message: String) : RealtimeEvent()
    }

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var sessionOpen = false
    /** True once the client is dead (user close or socket death). */
    private val finished = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    /** True while an audio response is streaming — used to flag the first delta. */
    @Volatile private var respOpen = false

    // Silence-tail watchdog state
    @Volatile private var talking = false
    @Volatile private var lastMicAtMs = 0L
    @Volatile private var watchdog: Thread? = null

    // ── Public API ──

    fun connect() {
        if (!started.compareAndSet(false, true)) return
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=$model")
            .header("Authorization", "Bearer $clientSecret")
            .build()
        val ws = httpClient.newWebSocket(request, socketListener)
        webSocket = ws
        // OkHttp queues messages written before the handshake completes, so
        // the session config goes out first and any early mic audio lines up
        // behind it — same ordering trick as iOS RealtimeSession.connect.
        ws.send(buildSessionUpdate())
        startSilenceWatchdog()
        Log.i(TAG, "Realtime WS connecting (model $model, effort $effort, voice $voice)")
    }

    /** Deliberate teardown: no Disconnected event will follow. */
    fun close() {
        finished.set(true)
        sessionOpen = false
        respOpen = false
        watchdog?.interrupt()
        watchdog = null
        try { webSocket?.close(1000, "session ended") } catch (_: Exception) {}
        webSocket = null
    }

    fun isConnected(): Boolean = sessionOpen && !finished.get()

    /** Stream mic audio (PCM16 LE @24 kHz) into the server's input buffer. */
    fun appendAudio(pcm24k: ByteArray) {
        if (pcm24k.isEmpty() || finished.get()) return
        val ws = webSocket ?: return
        talking = true
        lastMicAtMs = System.currentTimeMillis()
        ws.send(
            JSONObject()
                .put("type", "input_audio_buffer.append")
                .put("audio", Base64.encodeToString(pcm24k, Base64.NO_WRAP))
                .toString()
        )
    }

    /**
     * Add a camera photo to the conversation as a user message. The spoken
     * question that accompanies it (already streaming via [appendAudio])
     * triggers the response — no explicit response.create needed.
     *
     * Re-encodes through Bitmap: ESP32 camera JPEGs have non-standard headers
     * that OpenAI rejects. Call from a background thread — encoding a photo
     * is not free.
     */
    fun sendImage(jpegBytes: ByteArray): Boolean {
        if (jpegBytes.isEmpty()) return false
        return sendPreparedImage(prepareImage(jpegBytes))
    }

    /** Enqueue an already-normalized image without doing bitmap work on the caller's thread. */
    internal fun sendPreparedImage(prepared: PreparedImage): Boolean {
        if (finished.get()) return false
        val ws = webSocket ?: return false
        val item = JSONObject()
            .put("type", "conversation.item.create")
            .put("item", JSONObject()
                .put("type", "message")
                .put("role", "user")
                .put("content", JSONArray().put(JSONObject()
                    .put("type", "input_image")
                    .put("image_url", "data:image/jpeg;base64,${prepared.base64}"))))
        Log.i(TAG, "Sending photo into realtime conversation (${prepared.sourceByteCount} bytes JPEG)")
        return ws.send(item.toString())
    }

    // ── Silence tail (push-to-talk → server VAD bridge) ──

    private fun startSilenceWatchdog() {
        watchdog = Thread {
            while (!finished.get() && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (talking && System.currentTimeMillis() - lastMicAtMs > SILENCE_AFTER_MS) {
                    talking = false
                    webSocket?.send(
                        JSONObject()
                            .put("type", "input_audio_buffer.append")
                            .put("audio", Base64.encodeToString(
                                ByteArray(SILENCE_TAIL_BYTES), Base64.NO_WRAP))
                            .toString()
                    )
                }
            }
        }.apply {
            name = "RealtimeSilence"
            isDaemon = true
            start()
        }
    }

    // ── Socket lifecycle ──

    /** Terminal transition — emits Disconnected exactly once. */
    private fun finish(reason: String, retryAfterSeconds: Long? = null) {
        if (!finished.compareAndSet(false, true)) return
        sessionOpen = false
        respOpen = false
        watchdog?.interrupt()
        watchdog = null
        webSocket = null
        onEvent(RealtimeEvent.Disconnected(reason, retryAfterSeconds))
    }

    private fun describeFailure(t: Throwable, response: Response?): String {
        // The handshake HTTP status names the real cause; the socket error
        // is just the aftermath (mirrors iOS RealtimeSession.describe).
        val code = response?.code
        if (code != null && code != 101) {
            return when (code) {
                401 -> "The short-lived assistant credential expired (HTTP 401)."
                403 -> "The assistant service cannot access $model (HTTP 403)."
                429 -> "The assistant service has reached its usage limit (HTTP 429)."
                else -> "OpenAI handshake failed (HTTP $code, model $model)."
            }
        }
        if (t is UnknownHostException) return "No internet connection on the phone."
        return t.message ?: t.javaClass.simpleName
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "Realtime WS open (handshake OK)")
        }

        override fun onMessage(ws: WebSocket, text: String) {
            handleServerEvent(text)
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            // Complete the close handshake, then onClosed fires.
            try { ws.close(1000, null) } catch (_: Exception) {}
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            finish("server closed the session (code $code)" +
                    if (reason.isEmpty()) "" else ": $reason")
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            val retryAfter = if (response?.code == 429) {
                response.header("Retry-After")?.toLongOrNull()?.coerceIn(60L, 3_600L) ?: 60L
            } else null
            finish(describeFailure(t, response), retryAfter)
        }
    }

    // ── Server events ──

    private fun handleServerEvent(raw: String) {
        try {
            val ev = JSONObject(raw)
            when (ev.optString("type")) {
                "session.created", "session.updated" -> {
                    if (!sessionOpen) {
                        sessionOpen = true
                        Log.i(TAG, "Realtime session open — model $model, effort $effort")
                        onEvent(RealtimeEvent.Connected)
                    }
                }
                "response.output_audio.delta" -> {
                    val b64 = ev.optString("delta")
                    if (b64.isEmpty()) return
                    val pcm = Base64.decode(b64, Base64.DEFAULT)
                    val first = !respOpen
                    respOpen = true
                    onEvent(RealtimeEvent.AudioDelta(pcm, first))
                }
                "response.done" -> {
                    respOpen = false
                    onEvent(RealtimeEvent.ResponseDone)
                }
                "response.output_audio_transcript.delta" -> {
                    val delta = ev.optString("delta")
                    if (delta.isNotEmpty()) onEvent(RealtimeEvent.AssistantTranscriptDelta(delta))
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    val text = ev.optString("transcript").trim()
                    if (text.isNotEmpty()) onEvent(RealtimeEvent.UserTranscript(text))
                }
                "input_audio_buffer.speech_started" -> onEvent(RealtimeEvent.SpeechStarted)
                "input_audio_buffer.speech_stopped" -> onEvent(RealtimeEvent.SpeechStopped)
                "error" -> {
                    val msg = ev.optJSONObject("error")?.optString("message") ?: raw
                    Log.e(TAG, "Realtime API error: $msg")
                    onEvent(RealtimeEvent.Error(msg))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bad server event", e)
        }
    }

    /** Exact GA session.update schema — byte-for-byte the iOS RealtimeSession one. */
    private fun buildSessionUpdate(): String {
        val turnDetection = JSONObject()
            .put("type", "server_vad")
            .put("threshold", 0.5)
            .put("prefix_padding_ms", 300)
            .put("silence_duration_ms", 200)
            .put("create_response", true)
            .put("interrupt_response", true)
        val input = JSONObject()
            .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000))
            .put("transcription", JSONObject().put("model", "gpt-4o-mini-transcribe"))
            .put("turn_detection", turnDetection)
        val output = JSONObject()
            .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000))
            .put("voice", voice)
        val session = JSONObject()
            .put("type", "realtime")
            .put("instructions", INSTRUCTIONS)
            .put("reasoning", JSONObject().put("effort", effort))
            .put("output_modalities", JSONArray().put("audio"))
            .put("audio", JSONObject().put("input", input).put("output", output))
        return JSONObject()
            .put("type", "session.update")
            .put("session", session)
            .toString()
    }
}
