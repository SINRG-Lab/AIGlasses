package com.example.aiglasses

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * BLE connects on service start, but the short-lived credential and Realtime
 * socket are created only when the physical glasses button starts recording.
 * Mic audio is bounded-buffered during that startup so the first words survive.
 */
class GlassesController private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "GlassesController"
        private const val MAX_LOG_ENTRIES = 300
        private const val GALLERY_DIR = "gallery"
        private const val MIC_PULSE_DECAY_MS = 700L
        private const val SESSION_IDLE_MS = 120_000L
        /** Ten seconds of PCM16 mono @24 kHz while auth + the socket open. */
        private const val MAX_PREBUFFER_BYTES = 2 * 24_000 * 10

        @Volatile private var instance: GlassesController? = null

        fun getInstance(context: Context): GlassesController =
            instance ?: synchronized(this) {
                instance ?: GlassesController(context.applicationContext).also { instance = it }
            }
    }

    // ── Sub-systems ──

    val settings = RealtimeSettings(appContext)
    val link = LinkManager(appContext)
    private val credentialProvider = SupabaseRealtimeCredentialProvider(appContext)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mic = MicConditioner()
    @Volatile private var realtime: RealtimeVoiceClient? = null
    private val realtimeLock = Any()
    private val audioPrebuffer = BoundedAudioPrebuffer(MAX_PREBUFFER_BYTES)
    private var pendingVisionImage: RealtimeVoiceClient.PreparedImage? = null
    private var visionPreparationsInFlight = 0
    private var visionGeneration = 0L
    /** Guarded by [realtimeLock]; false while the ordered startup buffer is draining. */
    private var realtimeAcceptingLiveAudio = false
    private val photoMutex = Mutex()

    // ── One-step activation state (main thread, except where @Volatile) ──

    /** Durable privacy preference; honored even after a sticky-service process restart. */
    @Volatile private var voiceWanted = settings.glassesVoiceEnabled
    @Volatile private var voiceEnabled = false
    private var credentialJob: Job? = null
    /** Invalidates late results/finally blocks from cancelled credential coroutines. */
    private var credentialGeneration = 0L
    @Volatile private var credentialRetryNotBeforeUptimeMs = 0L
    @Volatile private var sessionRequiresNewPhysicalPress = false
    private var sessionIdleRunnable: Runnable? = null

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

    private val _voiceAutoEnabled = MutableStateFlow(voiceWanted)
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
        link.onRecordingStarted = {
            // This callback precedes the new turn's audio on the BLE stream. Clear stale frames
            // here, not later on main, or a busy main thread could erase the first new frames.
            val clearsTerminalGate = sessionRequiresNewPhysicalPress
            synchronized(realtimeLock) {
                // With no live socket, everything buffered predates this physical turn (whether
                // the prior cooldown is active or already expired). The newest S always wins.
                if (realtime == null || clearsTerminalGate) audioPrebuffer.clear()
            }
            mainHandler.post { handlePhysicalRecordingStarted(clearsTerminalGate) }
        }

        link.onPhoto = { jpeg -> queuePhotoProcessing(jpeg, vision = false) }
        link.onVisionPhoto = { jpeg -> queuePhotoProcessing(jpeg, vision = true) }

        link.onBargeIn = {
            if (voiceEnabled) setVoiceState(VoiceState.Listening)
        }

        link.onConnected = {
            mic.reset()
            mainHandler.post {
                link.setVoiceMode(voiceWanted)
                if (voiceWanted && realtime == null) setVoiceState(VoiceState.Listening)
            }
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
        if (state !is BleState.Connected) {
            if (realtime != null || credentialJob?.isActive == true) {
                cancelCredentialFetch()
            }
            cancelSessionIdleTimeout()
            voiceEnabled = false
            setVoiceState(VoiceState.Idle)
            // Audio may have accumulated while a local retry gate was active even though no
            // socket/job existed. Never carry that recording across a BLE disconnect.
            synchronized(realtimeLock) {
                realtimeAcceptingLiveAudio = false
                realtime?.close()
                realtime = null
                audioPrebuffer.clear()
                pendingVisionImage = null
                visionGeneration++
                visionPreparationsInFlight = 0
            }
            _photoAttached.value = false
        }
    }

    // ── Connection controls ──

    /** Bring the radio up (idempotent). Voice follows once glasses connect. */
    fun connect() {
        // Set the desired codec mode before GATT subscription completes so the
        // first physical press cannot land in the legacy PCM format window.
        link.setVoiceMode(voiceWanted)
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
        if (!voiceWanted) return
        noteMicActivity()
        val conditioned = mic.process(pcm16k)
        val pcm24k = AudioCodec.upsample16kTo24k(conditioned)
        var needsSession = false
        var liveClient: RealtimeVoiceClient? = null
        synchronized(realtimeLock) {
            val rt = realtime
            if (rt == null || !realtimeAcceptingLiveAudio || visionPreparationsInFlight > 0) {
                audioPrebuffer.add(pcm24k)
                needsSession = rt == null
            } else {
                liveClient = rt
            }
        }
        // Base64/JSON work stays off the shared ordering lock.
        liveClient?.appendAudio(pcm24k)
        // The firmware's 'S' marker normally arrives first. Treat mic traffic as
        // a fallback trigger so firmware timing or a lost control notify can never
        // silently discard the beginning of the question.
        if (needsSession) mainHandler.post { ensureVoiceSession() }
    }

    /** Physical side-button start marker. Main thread, including while the phone is locked. */
    private fun handlePhysicalRecordingStarted(clearsTerminalGate: Boolean) {
        if (!voiceWanted || link.bleState.value !is BleState.Connected) return
        if (clearsTerminalGate) {
            sessionRequiresNewPhysicalPress = false
        }
        cancelSessionIdleTimeout()
        if (realtime == null) setVoiceState(VoiceState.Connecting)
        ensureVoiceSession()
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
                cancelSessionIdleTimeout()
                mainHandler.removeCallbacks(micDecayRunnable)
                mainHandler.postDelayed(micDecayRunnable, MIC_PULSE_DECAY_MS)
            }
        }
    }

    // ── Photos ──

    private enum class VisionAttachment { Attached, Queued, VoiceOff, Stale, Failed }

    /**
     * The BLE notification callback only copies/enqueues work. In particular, camera file IO,
     * bitmap decoding and JPEG normalization must not delay the mic notifications that follow.
     */
    private fun queuePhotoProcessing(jpeg: ByteArray, vision: Boolean) {
        val generation = if (vision) synchronized(realtimeLock) {
            visionPreparationsInFlight++
            visionGeneration
        } else -1L
        scope.launch(Dispatchers.IO) {
            photoMutex.withLock { handlePhoto(jpeg, vision, generation) }
        }
    }

    /** Runs on a serialized IO coroutine, never the BLE callback or main thread. */
    private fun handlePhoto(jpeg: ByteArray, vision: Boolean, visionGenerationAtStart: Long) {
        val filename = "IMG_${System.currentTimeMillis()}.jpg"
        try {
            File(galleryDir, filename).writeBytes(jpeg)
        } catch (e: Exception) {
            Log.e(TAG, "Photo save failed", e)
            addLog("ERROR", "photo save failed: ${e.message}")
            if (vision) completeVisionPreparation(visionGenerationAtStart, null)
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
            val result = completeVisionPreparation(
                visionGenerationAtStart,
                RealtimeVoiceClient.prepareImage(jpeg)
            )
            when (result) {
                VisionAttachment.Attached ->
                    addLog("VISION", "photo attached — answering your spoken question")
                VisionAttachment.Queued ->
                    addLog("VISION", "photo queued while the secure session opens")
                VisionAttachment.VoiceOff ->
                    addLog("VISION", "vision photo received but voice is off — saved to gallery")
                else -> Unit
            }
        }
    }

    /**
     * Publishes a normalized image before releasing any audio that arrived after its BLE event.
     * Returns Stale after disconnect/dismiss so late background work cannot resurrect an attach.
     */
    private fun completeVisionPreparation(
        generation: Long,
        prepared: RealtimeVoiceClient.PreparedImage?
    ): VisionAttachment {
        var clientToFlush: RealtimeVoiceClient? = null
        var needsSession = false
        val result = synchronized(realtimeLock) {
            if (generation != visionGeneration) return VisionAttachment.Stale
            visionPreparationsInFlight = (visionPreparationsInFlight - 1).coerceAtLeast(0)
            val attachment = when {
                prepared == null -> VisionAttachment.Failed
                !voiceWanted -> VisionAttachment.VoiceOff
                realtime != null && realtime!!.sendPreparedImage(prepared) ->
                    VisionAttachment.Attached
                realtime == null -> {
                    pendingVisionImage = prepared
                    needsSession = true
                    VisionAttachment.Queued
                }
                else -> VisionAttachment.Failed
            }
            if (voiceWanted && visionPreparationsInFlight == 0) clientToFlush = realtime
            attachment
        }
        clientToFlush?.let { client ->
            scope.launch(Dispatchers.Default) { flushBufferedAudio(client) }
        }
        if (prepared != null && result != VisionAttachment.Stale && result != VisionAttachment.VoiceOff) {
            _photoAttached.value = true
        }
        if (needsSession) mainHandler.post { ensureVoiceSession() }
        return result
    }

    /** Attach the most recent glasses photo so the next spoken question can reference it. */
    fun askAboutPendingPhoto() {
        val photo = _pendingPhoto.value ?: return
        val generation = synchronized(realtimeLock) {
            visionPreparationsInFlight++
            visionGeneration
        }
        scope.launch(Dispatchers.IO) {
            val jpeg = try {
                File(galleryDir, photo.filename).readBytes()
            } catch (e: Exception) {
                Log.e(TAG, "Photo reload failed", e)
                null
            }
            if (jpeg == null || jpeg.isEmpty()) {
                completeVisionPreparation(generation, null)
                _lastError.value = "Could not reload the photo from disk."
                return@launch
            }
            val prepared = RealtimeVoiceClient.prepareImage(jpeg)
            val result = completeVisionPreparation(generation, prepared)
            addLog(
                "VISION",
                when (result) {
                    VisionAttachment.Attached -> "photo attached — ask your question"
                    VisionAttachment.Queued ->
                        "photo ready — hold the glasses button and ask your question"
                    VisionAttachment.VoiceOff -> "photo kept in gallery because glasses voice is disabled"
                    VisionAttachment.Failed -> "photo could not be attached"
                    VisionAttachment.Stale -> "photo attachment was cancelled"
                }
            )
        }
    }

    fun dismissPendingPhoto() {
        _pendingPhoto.value = null
        _photoAttached.value = false
        val clientToFlush = synchronized(realtimeLock) {
            pendingVisionImage = null
            visionGeneration++
            visionPreparationsInFlight = 0
            realtime
        }
        clientToFlush?.let { client ->
            scope.launch(Dispatchers.Default) { flushBufferedAudio(client) }
        }
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

    // ── Voice session (physical-button started, foreground-service owned) ──

    /** Enable voice and reconnect BLE. The next physical-button press starts a session. */
    fun retryVoiceNow() {
        settings.setGlassesVoiceEnabled(true)
        voiceWanted = true
        _voiceAutoEnabled.value = true
        mainHandler.post {
            _lastError.value = null
            link.setVoiceMode(true)
            link.connect()
            if (link.bleState.value is BleState.Connected) setVoiceState(VoiceState.Listening)
        }
    }

    /** Manual off switch (Settings) — stays off until retried. */
    fun stopVoice() {
        settings.setGlassesVoiceEnabled(false)
        voiceWanted = false
        _voiceAutoEnabled.value = false
        mainHandler.post {
            cancelCredentialFetch()
            cancelSessionIdleTimeout()
            voiceEnabled = false
            setVoiceState(VoiceState.Idle)
            link.setVoiceMode(false)
            link.cancelResponse()
            synchronized(realtimeLock) {
                realtimeAcceptingLiveAudio = false
                realtime?.close()
                realtime = null
                audioPrebuffer.clear()
                pendingVisionImage = null
                visionGeneration++
                visionPreparationsInFlight = 0
            }
            addLog("VOICE", "voice OFF (manual)")
        }
    }

    /** Main thread only. Coalesces button, vision and mic fallback triggers. */
    private fun ensureVoiceSession() {
        if (!voiceWanted || link.bleState.value !is BleState.Connected) return
        if (realtime != null || credentialJob?.isActive == true) return
        if (sessionRequiresNewPhysicalPress) return
        if (SystemClock.elapsedRealtime() < credentialRetryNotBeforeUptimeMs) return
        _lastError.value = null
        setVoiceState(VoiceState.Connecting)
        val generation = ++credentialGeneration
        val job = scope.launch {
            try {
                val credential = credentialProvider.fetchCredential()
                if (generation != credentialGeneration || !voiceWanted ||
                    link.bleState.value !is BleState.Connected
                ) return@launch
                credentialRetryNotBeforeUptimeMs = 0L
                openRealtimeSession(credential, generation)
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Normal teardown when voice/BLE is stopped while auth is in flight.
            } catch (failure: CredentialFailure) {
                if (generation == credentialGeneration) {
                    setCredentialRetryGate(failure)
                    handleCredentialFailure(failure)
                }
            } catch (failure: Exception) {
                Log.e(TAG, "Credential startup failed", failure)
                if (generation == credentialGeneration) {
                    val serviceFailure = CredentialFailure.ServiceUnavailable()
                    setCredentialRetryGate(serviceFailure)
                    handleCredentialFailure(serviceFailure)
                }
            } finally {
                // A cancelled generation may finish after its replacement. It must never clear
                // the replacement's coalescing slot.
                if (generation == credentialGeneration) credentialJob = null
            }
        }
        credentialJob = job
    }

    private fun cancelCredentialFetch() {
        credentialGeneration++
        credentialJob?.cancel()
        credentialJob = null
    }

    private fun setCredentialRetryGate(failure: CredentialFailure) {
        if (failure is CredentialFailure.Authentication) {
            // A fresh physical marker, rather than time, is the retry boundary for terminal auth.
            sessionRequiresNewPhysicalPress = true
            credentialRetryNotBeforeUptimeMs = 0L
            return
        }
        val delaySeconds = when (failure) {
            is CredentialFailure.RateLimited -> failure.retryAfterSeconds
            is CredentialFailure.RetryDeferred -> failure.retryAfterSeconds
            is CredentialFailure.Network, is CredentialFailure.ServiceUnavailable -> 2L
            is CredentialFailure.Authentication -> error("handled above")
        }.coerceAtLeast(1L)
        credentialRetryNotBeforeUptimeMs = SystemClock.elapsedRealtime() + delaySeconds * 1_000L
    }

    /** Main thread only. Session config, queued image and buffered audio retain their order. */
    private fun openRealtimeSession(credential: RealtimeCredential, generation: Long) {
        assistantTranscript.setLength(0)
        _pipelineStatus.update {
            it.copy(lastTranscription = "", lastAiResponse = "", voiceState = VoiceState.Connecting)
        }
        var holder: RealtimeVoiceClient? = null
        val rt = RealtimeVoiceClient(
            clientSecret = credential.clientSecret,
            model = credential.model,
            voice = settings.voice.value,
            effort = settings.effort.value
        ) { event -> holder?.let { handleRealtimeEvent(it, event) } }
        holder = rt
        synchronized(realtimeLock) {
            if (generation != credentialGeneration || !voiceWanted ||
                link.bleState.value !is BleState.Connected || realtime != null
            ) {
                rt.close()
                return
            }
            realtime = rt
            realtimeAcceptingLiveAudio = false
            voiceEnabled = true
            // connect() synchronously queues session.update first. The prepared image follows;
            // mic callbacks keep buffering until the background ordered drain completes.
            rt.connect()
            pendingVisionImage?.let {
                if (rt.sendPreparedImage(it)) pendingVisionImage = null
            }
        }
        scope.launch(Dispatchers.Default) { flushBufferedAudio(rt) }
        link.setVoiceMode(true)
        addLog("VOICE", "secure realtime session opening (model ${credential.model})")
    }

    /**
     * Drains without holding [realtimeLock] during Base64/JSON encoding. New BLE frames continue
     * entering the prebuffer until an empty drain and the live-audio handoff occur atomically.
     */
    private fun flushBufferedAudio(client: RealtimeVoiceClient) {
        while (true) {
            val batch = synchronized(realtimeLock) {
                if (client !== realtime) return
                // A camera event was observed before later mic frames. Its normalized image must
                // be enqueued first; completion restarts this drain.
                if (visionPreparationsInFlight > 0) return
                val drained = audioPrebuffer.drain()
                if (drained.isEmpty()) {
                    realtimeAcceptingLiveAudio = true
                    return
                }
                drained
            }
            batch.forEach(client::appendAudio)
        }
    }

    private fun handleCredentialFailure(failure: CredentialFailure) {
        synchronized(realtimeLock) {
            audioPrebuffer.clear()
            pendingVisionImage = null
        }
        _photoAttached.value = false
        _lastError.value = failure.message
        setVoiceState(if (voiceWanted) VoiceState.Listening else VoiceState.Idle)
        addLog("ERROR", failure.message ?: "Assistant unavailable")
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
            is RealtimeVoiceClient.RealtimeEvent.Disconnected -> {
                // Publish the gate before hopping to main so a nearly-simultaneous next physical
                // marker can synchronously discard stale audio at the exact turn boundary.
                sessionRequiresNewPhysicalPress = true
                event.retryAfterSeconds?.let { retryAfter ->
                    credentialRetryNotBeforeUptimeMs = maxOf(
                        credentialRetryNotBeforeUptimeMs,
                        SystemClock.elapsedRealtime() + retryAfter * 1_000L
                    )
                }
            }
            else -> {}
        }
        mainHandler.post {
            if (client !== realtime) return@post
            when (event) {
                is RealtimeVoiceClient.RealtimeEvent.Connected -> {
                    _lastError.value = null
                    if (voiceEnabled) {
                        setVoiceState(
                            if (_micStreaming.value) VoiceState.Hearing else VoiceState.Listening
                        )
                    }
                    if (_micStreaming.value) cancelSessionIdleTimeout()
                    else armSessionIdleTimeout()
                    addLog("VOICE", "realtime session open")
                }
                is RealtimeVoiceClient.RealtimeEvent.Disconnected -> {
                    handleRealtimeClosed(event.reason)
                }
                is RealtimeVoiceClient.RealtimeEvent.AudioDelta -> {
                    // First delta of a response: the glasses start speaking.
                    cancelSessionIdleTimeout()
                    if (voiceEnabled) setVoiceState(VoiceState.Speaking)
                }
                is RealtimeVoiceClient.RealtimeEvent.ResponseDone -> {
                    if (assistantTranscript.isNotEmpty()) {
                        addLog("AI", assistantTranscript.toString())
                    }
                    if (voiceEnabled) setVoiceState(VoiceState.Listening)
                    armSessionIdleTimeout()
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
                    cancelSessionIdleTimeout()
                    if (voiceEnabled) setVoiceState(VoiceState.Hearing)
                }
                is RealtimeVoiceClient.RealtimeEvent.SpeechStopped -> {
                    cancelSessionIdleTimeout()
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
        if (!voiceEnabled && realtime == null) return
        // A terminal WebSocket error (including OpenAI 401/429 or phone network loss) must not let
        // mic frames from the same held button mint session after session. Only the next physical
        // recording-start marker clears this gate.
        sessionRequiresNewPhysicalPress = true
        voiceEnabled = false
        cancelSessionIdleTimeout()
        link.cancelResponse()
        synchronized(realtimeLock) {
            realtimeAcceptingLiveAudio = false
            realtime?.close()
            realtime = null
        }
        // Keep the glasses in µ-law/button mode. New mic traffic from the next
        // physical press obtains a fresh credential; no battery-draining idle loop.
        _lastError.value = "Voice connection closed: $reason"
        setVoiceState(if (voiceWanted) VoiceState.Listening else VoiceState.Idle)
    }

    /** Close an idle socket to avoid indefinite radio pings; the next glasses press is seamless. */
    private fun armSessionIdleTimeout() {
        cancelSessionIdleTimeout()
        val runnable = Runnable {
            sessionIdleRunnable = null
            val activeState = _pipelineStatus.value.voiceState
            if (_micStreaming.value || activeState == VoiceState.Hearing ||
                activeState == VoiceState.Thinking || activeState == VoiceState.Speaking
            ) {
                // Never truncate a held-button recording or an in-flight answer. Check again only
                // after another full idle window if a terminal server event never arrives.
                armSessionIdleTimeout()
                return@Runnable
            }
            voiceEnabled = false
            link.cancelResponse()
            synchronized(realtimeLock) {
                realtimeAcceptingLiveAudio = false
                realtime?.close()
                realtime = null
            }
            if (voiceWanted) setVoiceState(VoiceState.Listening)
            addLog("VOICE", "realtime session closed after idle timeout")
        }
        sessionIdleRunnable = runnable
        mainHandler.postDelayed(runnable, SESSION_IDLE_MS)
    }

    private fun cancelSessionIdleTimeout() {
        sessionIdleRunnable?.let(mainHandler::removeCallbacks)
        sessionIdleRunnable = null
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
