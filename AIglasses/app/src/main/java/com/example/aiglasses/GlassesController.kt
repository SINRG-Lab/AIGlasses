package com.example.aiglasses

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.aiglasses.link.BleState
import com.example.aiglasses.link.LinkManager
import com.example.aiglasses.model.ConnectionState
import com.example.aiglasses.model.GlassesStatus
import com.example.aiglasses.model.LogEntry
import com.example.aiglasses.model.PipelineStatus
import com.example.aiglasses.model.SavedImage
import com.example.aiglasses.model.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Top-level coordinator — the Android port of iOS AppModel. Owns the glasses
 * link (BLE + WiFi behind [LinkManager]), the GPT Realtime voice session, the
 * gallery and settings, and publishes all UI-facing state.
 *
 * A process-wide singleton: [GlassesService] (the connectedDevice foreground
 * service) keeps the process alive and renders the live notification, while
 * MainViewModel is a thin adapter over the same instance — so the whole voice
 * + photo pipeline runs identically with the UI backgrounded or killed.
 *
 * Data flow (mirrors the hardware-validated realtime path):
 *   glasses mic --'A' µ-law@16k--> LinkManager --decode/seq-filter-->
 *     [onMicAudio PCM16@16k] --MicConditioner+upsample--> RealtimeVoiceClient
 *   RealtimeVoiceClient (PCM16@24k deltas) --µ-law encode-->
 *     LinkManager.enqueueResponseAudio (paced 'S'/'A'/'E') --> glasses speaker
 *
 * One-step activation: BLE connects on service start; the voice session
 * auto-starts whenever (voice wanted && API key present && BLE connected),
 * reconnects with exponential backoff 2/4/8…30 s when the socket dies, and is
 * revived immediately by arriving mic audio (iOS AppModel.onMicAudio parity).
 */
class GlassesController private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "GlassesController"
        private const val MAX_LOG_ENTRIES = 300
        private const val GALLERY_DIR = "gallery"
        private const val RECONNECT_BASE_MS = 2_000L
        private const val RECONNECT_MAX_MS = 30_000L
        private const val MIC_PULSE_DECAY_MS = 700L

        @Volatile private var instance: GlassesController? = null

        fun getInstance(context: Context): GlassesController =
            instance ?: synchronized(this) {
                instance ?: GlassesController(context.applicationContext).also { instance = it }
            }
    }

    // ── Sub-systems ──

    val settings = RealtimeSettings(appContext)
    val link = LinkManager(appContext)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mic = MicConditioner()
    @Volatile private var realtime: RealtimeVoiceClient? = null

    // ── One-step activation state (main thread, except where @Volatile) ──

    /** True unless the user explicitly stopped voice (Settings). */
    @Volatile private var voiceWanted = true
    @Volatile private var voiceEnabled = false
    private var reconnectDelayMs = RECONNECT_BASE_MS
    private var reconnectRunnable: Runnable? = null

    // Assistant transcript accumulates delta-by-delta; main thread only.
    private val assistantTranscript = StringBuilder()

    private val galleryDir = File(appContext.filesDir, GALLERY_DIR).also { it.mkdirs() }

    // ── UI state ──

    private val _glassesStatus = MutableStateFlow(GlassesStatus())
    val glassesStatus: StateFlow<GlassesStatus> = _glassesStatus.asStateFlow()

    private val _pipelineStatus = MutableStateFlow(PipelineStatus())
    val pipelineStatus: StateFlow<PipelineStatus> = _pipelineStatus.asStateFlow()

    private val _logMessages = MutableStateFlow<List<LogEntry>>(emptyList())
    val logMessages: StateFlow<List<LogEntry>> = _logMessages.asStateFlow()

    private val _savedImages = MutableStateFlow<List<SavedImage>>(emptyList())
    val savedImages: StateFlow<List<SavedImage>> = _savedImages.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _voiceAutoEnabled = MutableStateFlow(true)
    /** Standing "voice wanted" preference — false only after a manual stop. */
    val voiceAutoEnabled: StateFlow<Boolean> = _voiceAutoEnabled.asStateFlow()

    private val _pendingPhoto = MutableStateFlow<SavedImage?>(null)
    val pendingPhoto: StateFlow<SavedImage?> = _pendingPhoto.asStateFlow()

    private val _photoAttached = MutableStateFlow(false)
    val photoAttached: StateFlow<Boolean> = _photoAttached.asStateFlow()

    /** True while mic frames are streaming (700 ms decay) — notification pulse. */
    private val _micStreaming = MutableStateFlow(false)
    val micStreaming: StateFlow<Boolean> = _micStreaming.asStateFlow()

    init {
        loadSavedImages()
        wireLink()
        scope.launch { link.bleState.collect { applyBleState(it) } }
        addLog("APP", "controller started")
    }

    // ── Link wiring (BLE + WiFi behind one facade) ──

    private fun wireLink() {
        link.onLog = { line -> addLog("LINK", line) }

        // Called on LinkManager's notify thread — decode/conditioning stays
        // off the main thread; only the session revival hops to main.
        link.onMicAudio = { pcm16k -> handleMicAudio(pcm16k) }

        link.onPhoto = { jpeg -> handlePhoto(jpeg, vision = false) }
        link.onVisionPhoto = { jpeg -> handlePhoto(jpeg, vision = true) }

        link.onBargeIn = {
            if (voiceEnabled) setVoiceState(VoiceState.Listening)
        }

        link.onConnected = {
            mic.reset()
            mainHandler.post { maybeStartVoice() }
        }
    }

    private fun applyBleState(state: BleState) {
        _glassesStatus.update {
            when (state) {
                is BleState.Connected -> it.copy(
                    connectionState = ConnectionState.Connected,
                    deviceName = state.name,
                    mtu = state.mtu
                )
                is BleState.Scanning -> it.copy(connectionState = ConnectionState.Scanning)
                is BleState.Connecting -> it.copy(connectionState = ConnectionState.Scanning)
                else -> it.copy(
                    connectionState = ConnectionState.Disconnected,
                    deviceName = "",
                    mtu = 0
                )
            }
        }
    }

    // ── Connection controls ──

    /** Bring the radio up (idempotent). Voice follows once glasses connect. */
    fun connect() {
        link.connect()
    }

    /** Full teardown: voice off + both transports down (Settings/notification stop). */
    fun disconnectAll() {
        stopVoice()
        link.disconnectAll()
        _glassesStatus.update {
            it.copy(connectionState = ConnectionState.Disconnected, deviceName = "", mtu = 0)
        }
        addLog("LINK", "disconnected (manual)")
    }

    fun setWifiAuto(on: Boolean) = link.setWifiAuto(on)

    // ── Mic uplink ──

    private fun handleMicAudio(pcm16k: ByteArray) {
        noteMicActivity()
        // Revival: the websocket may have died (backoff pending, app dozing).
        // Mic audio arriving IS the wake signal — restart the session now.
        // OkHttp queues the frames sent while the handshake completes, so the
        // first words of the question survive (iOS AppModel.onMicAudio parity).
        if (realtime == null && voiceWanted && settings.apiKey.value.isNotBlank()) {
            mainHandler.post {
                cancelScheduledReconnect()
                maybeStartVoice()
            }
        }
        val rt = realtime ?: return
        val conditioned = mic.process(pcm16k)
        rt.appendAudio(AudioCodec.upsample16kTo24k(conditioned))
    }

    @Volatile private var lastMicMs = 0L
    private val micDecayRunnable = object : Runnable {
        override fun run() {
            val idle = System.currentTimeMillis() - lastMicMs
            if (idle >= MIC_PULSE_DECAY_MS) _micStreaming.value = false
            else mainHandler.postDelayed(this, MIC_PULSE_DECAY_MS - idle)
        }
    }

    private fun noteMicActivity() {
        lastMicMs = System.currentTimeMillis()
        if (!_micStreaming.value) {
            _micStreaming.value = true
            mainHandler.post {
                mainHandler.removeCallbacks(micDecayRunnable)
                mainHandler.postDelayed(micDecayRunnable, MIC_PULSE_DECAY_MS)
            }
        }
    }

    // ── Photos ──

    /** Runs on LinkManager's background thread — file IO + decode are fine here. */
    private fun handlePhoto(jpeg: ByteArray, vision: Boolean) {
        val filename = "IMG_${System.currentTimeMillis()}.jpg"
        try {
            File(galleryDir, filename).writeBytes(jpeg)
        } catch (e: Exception) {
            Log.e(TAG, "Photo save failed", e)
            addLog("ERROR", "photo save failed: ${e.message}")
            return
        }
        loadSavedImages()
        val saved = SavedImage(filename, System.currentTimeMillis(), jpeg.size, false)
        _pendingPhoto.value = saved
        _photoAttached.value = false
        addLog("CAMERA", (if (vision) "vision photo" else "photo") +
                " received (${jpeg.size} B) — saved $filename")

        val bitmap = try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        } catch (_: Throwable) { null }
        _glassesStatus.update {
            it.copy(
                lastImageBitmap = bitmap,
                imageByteCount = jpeg.size
            )
        }

        if (vision) {
            // Tap-then-hold on the glasses: the photo goes straight into the
            // live conversation; the voice spoken during the hold is the question.
            val rt = realtime
            if (voiceEnabled && rt != null) {
                if (rt.sendImage(jpeg)) {
                    _photoAttached.value = true
                    addLog("VISION", "photo attached — answering your spoken question")
                } else {
                    addLog("VISION", "photo attach failed — saved to gallery")
                }
            } else {
                addLog("VISION", "vision photo received but voice is off — saved to gallery")
            }
        }
    }

    /** Attach the most recent glasses photo so the next spoken question can reference it. */
    fun askAboutPendingPhoto() {
        val photo = _pendingPhoto.value ?: return
        val rt = realtime
        if (rt == null || !voiceEnabled) {
            _lastError.value = "Voice isn't connected yet — retry voice first."
            return
        }
        Thread {
            val jpeg = try {
                File(galleryDir, photo.filename).readBytes()
            } catch (e: Exception) {
                Log.e(TAG, "Photo reload failed", e)
                null
            }
            if (jpeg == null || jpeg.isEmpty()) {
                _lastError.value = "Could not reload the photo from disk."
                return@Thread
            }
            if (rt.sendImage(jpeg)) {
                _photoAttached.value = true
                addLog("VISION", "photo attached — ask your question")
            }
        }.apply {
            name = "PhotoAttach"
            isDaemon = true
            start()
        }
    }

    fun dismissPendingPhoto() {
        _pendingPhoto.value = null
        _photoAttached.value = false
    }

    // ── Gallery ──

    fun getImageFile(filename: String): File = File(galleryDir, filename)

    fun deleteImage(filename: String) {
        scope.launch(Dispatchers.IO) {
            File(galleryDir, filename).delete()
            if (_pendingPhoto.value?.filename == filename) dismissPendingPhoto()
            loadSavedImages()
        }
    }

    private fun loadSavedImages() {
        scope.launch(Dispatchers.IO) {
            // mp4s from the removed video feature may still exist on disk —
            // keep listing them so old captures stay reachable.
            val images = galleryDir.listFiles()
                ?.filter { it.extension == "jpg" || it.extension == "mp4" }
                ?.sortedByDescending { it.lastModified() }
                ?.map { SavedImage(it.name, it.lastModified(), it.length().toInt(), it.extension == "mp4") }
                ?: emptyList()
            _savedImages.value = images
        }
    }

    // ── Voice session (auto-started, self-healing) ──

    fun setApiKey(key: String) {
        settings.setApiKey(key)
        mainHandler.post { maybeStartVoice() }
    }

    /** Start voice if everything it needs is in place. Main thread only. */
    private fun maybeStartVoice() {
        if (!voiceWanted || voiceEnabled) return
        if (settings.apiKey.value.isBlank()) return       // no key — status chip explains
        if (link.bleState.value !is BleState.Connected) return   // voice rides BLE
        startVoiceSession()
    }

    /** User affordance: clear the error/backoff and try again right now. */
    fun retryVoiceNow() {
        voiceWanted = true
        _voiceAutoEnabled.value = true
        mainHandler.post {
            reconnectDelayMs = RECONNECT_BASE_MS
            cancelScheduledReconnect()
            _lastError.value = null
            link.connect()
            maybeStartVoice()
        }
    }

    /** Manual off switch (Settings) — stays off until retried. */
    fun stopVoice() {
        voiceWanted = false
        _voiceAutoEnabled.value = false
        mainHandler.post {
            cancelScheduledReconnect()
            if (!voiceEnabled && realtime == null) return@post
            voiceEnabled = false
            setVoiceState(VoiceState.Idle)
            link.setVoiceMode(false)
            link.cancelResponse()
            realtime?.close()
            realtime = null
            addLog("VOICE", "voice OFF (manual)")
        }
    }

    /** Main thread only. */
    private fun startVoiceSession() {
        _lastError.value = null
        assistantTranscript.setLength(0)
        _pipelineStatus.update {
            it.copy(lastTranscription = "", lastAiResponse = "", voiceState = VoiceState.Connecting)
        }
        var holder: RealtimeVoiceClient? = null
        val rt = RealtimeVoiceClient(
            apiKey = settings.apiKey.value,
            model = settings.model.value,
            voice = settings.voice.value,
            effort = settings.effort.value
        ) { event -> holder?.let { handleRealtimeEvent(it, event) } }
        holder = rt
        realtime = rt
        voiceEnabled = true
        rt.connect()
        link.setVoiceMode(true)
        addLog("VOICE", "voice ON (model ${settings.model.value})")
    }

    /** The websocket died while voice is wanted: 2/4/8…30 s backoff. Main thread. */
    private fun scheduleVoiceReconnect(reason: String) {
        if (!voiceWanted) return
        val delay = reconnectDelayMs
        reconnectDelayMs = minOf(reconnectDelayMs * 2, RECONNECT_MAX_MS)
        _lastError.value = "$reason — retrying in ${delay / 1000} s"
        setVoiceState(VoiceState.Connecting)
        cancelScheduledReconnect()
        val runnable = Runnable {
            reconnectRunnable = null
            maybeStartVoice()
        }
        reconnectRunnable = runnable
        mainHandler.postDelayed(runnable, delay)
    }

    private fun cancelScheduledReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    /**
     * Realtime events. Called on OkHttp's WebSocket reader thread: audio is
     * forwarded to the link synchronously (enqueueResponseAudio only µ-law
     * encodes + enqueues — the main dispatcher would add jitter); UI state is
     * marshalled to the main thread. Events from a superseded client are
     * dropped ([client] identity check) so a stale socket can't tear down a
     * fresh session.
     */
    private fun handleRealtimeEvent(client: RealtimeVoiceClient, event: RealtimeVoiceClient.RealtimeEvent) {
        if (client !== realtime) return
        when (event) {
            is RealtimeVoiceClient.RealtimeEvent.AudioDelta -> {
                link.enqueueResponseAudio(AudioCodec.ulawEncode(event.pcm))
                if (!event.first) return   // only the first delta updates UI state
            }
            is RealtimeVoiceClient.RealtimeEvent.ResponseDone -> {
                link.finishResponse()
            }
            else -> {}
        }
        mainHandler.post {
            if (client !== realtime) return@post
            when (event) {
                is RealtimeVoiceClient.RealtimeEvent.Connected -> {
                    reconnectDelayMs = RECONNECT_BASE_MS   // healthy session → fresh backoff
                    _lastError.value = null
                    if (voiceEnabled) setVoiceState(VoiceState.Listening)
                    addLog("VOICE", "realtime session open")
                }
                is RealtimeVoiceClient.RealtimeEvent.Disconnected -> {
                    handleRealtimeClosed(event.reason)
                }
                is RealtimeVoiceClient.RealtimeEvent.AudioDelta -> {
                    // First delta of a response: the glasses start speaking.
                    if (voiceEnabled) setVoiceState(VoiceState.Speaking)
                }
                is RealtimeVoiceClient.RealtimeEvent.ResponseDone -> {
                    if (assistantTranscript.isNotEmpty()) {
                        addLog("AI", assistantTranscript.toString())
                    }
                    if (voiceEnabled) setVoiceState(VoiceState.Listening)
                }
                is RealtimeVoiceClient.RealtimeEvent.AssistantTranscriptDelta -> {
                    assistantTranscript.append(event.text)
                    _pipelineStatus.update { it.copy(lastAiResponse = assistantTranscript.toString()) }
                }
                is RealtimeVoiceClient.RealtimeEvent.UserTranscript -> {
                    addLog("USER", event.text)
                    _pipelineStatus.update { it.copy(lastTranscription = event.text) }
                }
                is RealtimeVoiceClient.RealtimeEvent.SpeechStarted -> {
                    if (voiceEnabled) setVoiceState(VoiceState.Hearing)
                }
                is RealtimeVoiceClient.RealtimeEvent.SpeechStopped -> {
                    if (voiceEnabled) setVoiceState(VoiceState.Thinking)
                    assistantTranscript.setLength(0)   // a fresh answer is coming
                }
                is RealtimeVoiceClient.RealtimeEvent.Error -> {
                    _lastError.value = event.message
                    addLog("ERROR", "Realtime: ${event.message}")
                    // Vision degradation: if input_image was rejected, the photo
                    // is already safe in the gallery — just clear attach state.
                    _photoAttached.value = false
                }
            }
        }
    }

    /** Main thread only. */
    private fun handleRealtimeClosed(reason: String) {
        addLog("VOICE", "realtime closed: $reason")
        if (!voiceEnabled) return
        voiceEnabled = false
        link.cancelResponse()
        realtime?.close()
        realtime = null
        // Keep the glasses in voice mode ('M' stays set) — the session is
        // coming back; flapping 'M'/'m' across a 2 s retry buys nothing.
        scheduleVoiceReconnect("Voice connection closed: $reason")
    }

    private fun setVoiceState(state: VoiceState) {
        _pipelineStatus.update { it.copy(voiceState = state) }
    }

    // ── Logging ──

    fun addLog(tag: String, message: String) {
        _logMessages.update { current ->
            val updated = current + LogEntry(tag, message)
            if (updated.size > MAX_LOG_ENTRIES) updated.drop(updated.size - MAX_LOG_ENTRIES)
            else updated
        }
    }
}
