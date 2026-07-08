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
import java.util.concurrent.TimeUnit

/**
 * GPT Realtime speech-to-speech client over an OkHttp WebSocket.
 *
 * Mirrors the hardware-validated Python reference (HardwareTest/realtime_ble.py):
 * one long-lived session owns the conversation; it survives any number of BLE
 * drops, and reconnects itself with backoff if the socket fails.
 *
 * Threading: all callbacks in [onEvent] arrive on OkHttp's WebSocket reader
 * thread — consumers must marshal to the main thread for UI state, but should
 * forward audio deltas to the BLE downlink directly (never via the main
 * dispatcher). [appendAudio]/[sendImage] are thread-safe: WebSocket.send()
 * only enqueues onto OkHttp's writer queue.
 */
class RealtimeVoiceClient(
    private val apiKey: String,
    private val onEvent: (RealtimeEvent) -> Unit
) {
    companion object {
        private const val TAG = "RealtimeVoiceClient"
        private const val MODEL = "gpt-realtime-2.1"
        private const val WS_URL = "wss://api.openai.com/v1/realtime?model=$MODEL"
        private const val VOICE = "marin"
        private const val EFFORT = "low"
        private const val INSTRUCTIONS =
            "You are a voice assistant built into a pair of smart glasses. " +
            "Keep answers to one or two spoken sentences — never lists or formatting. " +
            "Answer factual questions directly and accurately. " +
            "The microphone is imperfect: if you did not clearly understand the user, " +
            "say so and ask them to repeat — NEVER guess at what they said, and never " +
            "agree with or confirm a statement you only partially heard."
        private const val RECONNECT_BASE_MS = 1000L
        private const val RECONNECT_MAX_MS = 30000L
    }

    sealed class RealtimeEvent {
        data object Connected : RealtimeEvent()
        data class Disconnected(val reason: String) : RealtimeEvent()
        /** 24 kHz PCM16 LE response audio. [first] marks the first delta of a response. */
        data class AudioDelta(val pcm: ByteArray, val first: Boolean) : RealtimeEvent()
        data object ResponseDone : RealtimeEvent()
        data class AssistantTranscriptDelta(val text: String) : RealtimeEvent()
        data class UserTranscript(val text: String) : RealtimeEvent()
        data object SpeechStarted : RealtimeEvent()
        data object SpeechStopped : RealtimeEvent()
        data class Error(val message: String) : RealtimeEvent()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)    // WS stays open indefinitely
        .pingInterval(20, TimeUnit.SECONDS)       // detect dead links promptly
        .build()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var closed = false          // user-requested teardown
    /** True while an audio response is streaming — used to flag the first delta. */
    @Volatile private var respOpen = false
    private val reconnectLock = Any()
    private var reconnecting = false               // guarded by reconnectLock
    private var reconnectDelayMs = RECONNECT_BASE_MS  // guarded by reconnectLock

    // ── Public API ──

    fun connect() {
        closed = false
        synchronized(reconnectLock) { reconnectDelayMs = RECONNECT_BASE_MS }
        openSocket()
    }

    fun close() {
        closed = true
        connected = false
        respOpen = false
        try { webSocket?.close(1000, "session ended") } catch (_: Exception) {}
        webSocket = null
    }

    fun isConnected(): Boolean = connected

    /** Stream mic audio (PCM16 LE @ 24 kHz) into the server's input buffer. */
    fun appendAudio(pcm24k: ByteArray) {
        if (!connected || pcm24k.isEmpty()) return
        val ws = webSocket ?: return
        val msg = JSONObject()
            .put("type", "input_audio_buffer.append")
            .put("audio", Base64.encodeToString(pcm24k, Base64.NO_WRAP))
        ws.send(msg.toString())
    }

    /**
     * Add a camera photo to the conversation as a user message. The spoken
     * question that accompanies it (already streaming via [appendAudio])
     * triggers the response — no explicit response.create needed.
     *
     * Re-encodes through Bitmap: ESP32 camera JPEGs have non-standard headers
     * that OpenAI rejects (same workaround as OpenAIService.visionChat).
     * Call from a background thread — encoding a photo is not free.
     */
    fun sendImage(jpegBytes: ByteArray): Boolean {
        if (!connected || jpegBytes.isEmpty()) return false
        val ws = webSocket ?: return false
        val base64: String = try {
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
        val item = JSONObject()
            .put("type", "conversation.item.create")
            .put("item", JSONObject()
                .put("type", "message")
                .put("role", "user")
                .put("content", JSONArray().put(JSONObject()
                    .put("type", "input_image")
                    .put("image_url", "data:image/jpeg;base64,$base64"))))
        Log.i(TAG, "Sending photo into realtime conversation (${jpegBytes.size} bytes JPEG)")
        return ws.send(item.toString())
    }

    // ── Socket lifecycle ──

    private fun openSocket() {
        val request = Request.Builder()
            .url(WS_URL)
            .header("Authorization", "Bearer $apiKey")
            .build()
        webSocket = client.newWebSocket(request, socketListener)
    }

    private fun scheduleReconnect(reason: String) {
        if (closed) return
        val delay: Long
        synchronized(reconnectLock) {
            if (reconnecting) return
            reconnecting = true
            delay = reconnectDelayMs
            reconnectDelayMs = minOf(reconnectDelayMs * 2, RECONNECT_MAX_MS)
        }
        Log.w(TAG, "WS down ($reason) — reconnecting in ${delay}ms")
        Thread {
            try { Thread.sleep(delay) } catch (_: InterruptedException) {}
            synchronized(reconnectLock) { reconnecting = false }
            if (!closed) openSocket()
        }.apply {
            name = "RealtimeReconnect"
            isDaemon = true
            start()
        }
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(TAG, "Realtime WS open — model $MODEL, effort $EFFORT")
            connected = true
            respOpen = false
            synchronized(reconnectLock) { reconnectDelayMs = RECONNECT_BASE_MS }
            ws.send(buildSessionUpdate())
            onEvent(RealtimeEvent.Connected)
        }

        override fun onMessage(ws: WebSocket, text: String) {
            handleServerEvent(text)
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            // Complete the close handshake, then onClosed fires.
            try { ws.close(1000, null) } catch (_: Exception) {}
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            connected = false
            respOpen = false
            onEvent(RealtimeEvent.Disconnected("closed: $code $reason"))
            scheduleReconnect("closed $code")
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            connected = false
            respOpen = false
            val detail = response?.let { "HTTP ${it.code}" } ?: (t.message ?: "failure")
            onEvent(RealtimeEvent.Disconnected(detail))
            scheduleReconnect(detail)
        }
    }

    // ── Server events ──

    private fun handleServerEvent(raw: String) {
        try {
            val ev = JSONObject(raw)
            when (ev.optString("type")) {
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

    /** Exact GA session schema validated by HardwareTest/realtime_ble.py. */
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
            .put("voice", VOICE)
        val session = JSONObject()
            .put("type", "realtime")
            .put("instructions", INSTRUCTIONS)
            .put("reasoning", JSONObject().put("effort", EFFORT))
            .put("output_modalities", JSONArray().put("audio"))
            .put("audio", JSONObject().put("input", input).put("output", output))
        return JSONObject()
            .put("type", "session.update")
            .put("session", session)
            .toString()
    }
}
