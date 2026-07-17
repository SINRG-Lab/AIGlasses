package com.example.aiglasses

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiglasses.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val PREFS_NAME = "aiglasses_prefs"
        private const val KEY_API_KEY = "openai_api_key"
        private const val KEY_AUTO_SAVE_IMAGES = "auto_save_images"
        private const val KEY_REALTIME_ENGINE = "voice_engine_realtime"
        private const val MAX_LOG_ENTRIES = 100
        private const val GALLERY_DIR = "gallery"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var bleService: BleVoiceService? = null
    private var pipeline: VoiceAssistantPipeline? = null
    private var realtimeClient: RealtimeVoiceClient? = null
    // Assistant transcript accumulates delta-by-delta; main thread only.
    private val assistantTranscript = StringBuilder()

    // Permission callback bridge — set by MainActivity, called when scan needs permissions
    private var permissionRequestCallback: ((onGranted: () -> Unit) -> Unit)? = null

    private val _glassesStatus = MutableStateFlow(GlassesStatus())
    val glassesStatus: StateFlow<GlassesStatus> = _glassesStatus.asStateFlow()

    private val _pipelineStatus = MutableStateFlow(PipelineStatus())
    val pipelineStatus: StateFlow<PipelineStatus> = _pipelineStatus.asStateFlow()

    private val _logMessages = MutableStateFlow<List<LogEntry>>(emptyList())
    val logMessages: StateFlow<List<LogEntry>> = _logMessages.asStateFlow()

    private val _devModeEnabled = MutableStateFlow(false)
    val devModeEnabled: StateFlow<Boolean> = _devModeEnabled.asStateFlow()

    private val _apiKey = MutableStateFlow(loadApiKey())
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _autoSaveImages = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SAVE_IMAGES, false))
    val autoSaveImages: StateFlow<Boolean> = _autoSaveImages.asStateFlow()

    // Voice engine: GPT Realtime speech-to-speech (default) vs legacy STT→LLM→TTS.
    private val _realtimeEnabled = MutableStateFlow(prefs.getBoolean(KEY_REALTIME_ENGINE, true))
    val realtimeEnabled: StateFlow<Boolean> = _realtimeEnabled.asStateFlow()

    private val _savedImages = MutableStateFlow<List<SavedImage>>(emptyList())
    val savedImages: StateFlow<List<SavedImage>> = _savedImages.asStateFlow()

    private val galleryDir = File(application.filesDir, GALLERY_DIR).also { it.mkdirs() }

    init {
        loadSavedImages()
    }

    private fun loadApiKey(): String {
        val builtIn = try { BuildConfig.OPENAI_API_KEY } catch (_: Exception) { "" }
        return if (builtIn.isNotBlank()) builtIn
        else prefs.getString(KEY_API_KEY, "") ?: ""
    }

    fun setPermissionRequestCallback(cb: (onGranted: () -> Unit) -> Unit) {
        permissionRequestCallback = cb
    }

    fun setApiKey(key: String) {
        _apiKey.update { key }
        prefs.edit().putString(KEY_API_KEY, key).apply()
    }

    fun setDevMode(enabled: Boolean) {
        _devModeEnabled.update { enabled }
    }

    fun setAutoSaveImages(enabled: Boolean) {
        _autoSaveImages.update { enabled }
        prefs.edit().putBoolean(KEY_AUTO_SAVE_IMAGES, enabled).apply()
    }

    /** Switch voice engines. Takes effect immediately, even mid-session. */
    fun setRealtimeEnabled(enabled: Boolean) {
        _realtimeEnabled.update { enabled }
        prefs.edit().putBoolean(KEY_REALTIME_ENGINE, enabled).apply()
        bleService?.setRealtimeMode(enabled)
        if (enabled) {
            if (bleService != null) startRealtimeClient()
        } else {
            stopRealtimeClient()
            _pipelineStatus.update { it.copy(voiceState = VoiceState.Idle) }
        }
        addLog("ENGINE", if (enabled) "GPT Realtime engine selected" else "Legacy pipeline selected")
    }

    private fun startRealtimeClient() {
        if (realtimeClient != null) return
        val key = _apiKey.value
        if (key.isBlank()) return
        val client = RealtimeVoiceClient(key) { event -> handleRealtimeEvent(event) }
        realtimeClient = client
        bleService?.setRealtimeClient(client)
        client.connect()
    }

    private fun stopRealtimeClient() {
        bleService?.setRealtimeClient(null)
        realtimeClient?.close()
        realtimeClient = null
    }

    fun deleteImage(filename: String) {
        viewModelScope.launch(Dispatchers.IO) {
            File(galleryDir, filename).delete()
            loadSavedImages()
        }
    }

    fun getImageFile(filename: String): File = File(galleryDir, filename)

    private fun loadSavedImages() {
        viewModelScope.launch(Dispatchers.IO) {
            val images = galleryDir.listFiles()
                ?.filter { it.extension == "jpg" || it.extension == "mp4" }
                ?.sortedByDescending { it.lastModified() }
                ?.map { SavedImage(it.name, it.lastModified(), it.length().toInt(), it.extension == "mp4") }
                ?: emptyList()
            _savedImages.update { images }
        }
    }

    private fun saveImageToGallery(jpegBytes: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            val filename = "IMG_${System.currentTimeMillis()}.jpg"
            File(galleryDir, filename).writeBytes(jpegBytes)
            loadSavedImages()
            viewModelScope.launch(Dispatchers.Main.immediate) {
                addLog("GALLERY", "Image saved: $filename")
            }
        }
    }

    private fun saveVideoToGallery(frames: List<ByteArray>) {
        if (frames.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val firstBitmap = BitmapFactory.decodeByteArray(frames[0], 0, frames[0].size)
                    ?: return@launch
                val width = firstBitmap.width
                val height = firstBitmap.height
                firstBitmap.recycle()

                val filename = "VID_${System.currentTimeMillis()}.mp4"
                val outputFile = File(galleryDir, filename)
                encodeJpegsToMp4(frames, width, height, fps = 3, outputFile = outputFile)
                loadSavedImages()
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    addLog("GALLERY", "Video saved: $filename (${frames.size} frames)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Video save failed", e)
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    addLog("ERROR", "Video save failed: ${e.message}")
                }
            }
        }
    }

    private fun encodeJpegsToMp4(
        frames: List<ByteArray>,
        width: Int, height: Int,
        fps: Int,
        outputFile: File
    ) {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, 500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(mime)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()
        val frameIntervalUs = 1_000_000L / fps

        var frameIdx = 0
        var inputDone = false

        try {
            while (true) {
                if (!inputDone) {
                    val inputIdx = encoder.dequeueInputBuffer(10_000L)
                    if (inputIdx >= 0) {
                        if (frameIdx >= frames.size) {
                            encoder.queueInputBuffer(inputIdx, 0, 0,
                                frameIdx * frameIntervalUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val bitmap = BitmapFactory.decodeByteArray(
                                frames[frameIdx], 0, frames[frameIdx].size)
                            if (bitmap != null) {
                                val yuv = bitmapToNv12(bitmap, width, height)
                                bitmap.recycle()
                                val inputBuffer = encoder.getInputBuffer(inputIdx)!!
                                inputBuffer.clear()
                                inputBuffer.put(yuv)
                                encoder.queueInputBuffer(inputIdx, 0, yuv.size,
                                    frameIdx * frameIntervalUs, 0)
                            } else {
                                encoder.queueInputBuffer(inputIdx, 0, 0,
                                    frameIdx * frameIntervalUs, 0)
                            }
                            frameIdx++
                        }
                    }
                }

                val outputIdx = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outputIdx >= 0 -> {
                        if (muxerStarted && bufferInfo.size > 0 &&
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val outputBuffer = encoder.getOutputBuffer(outputIdx)!!
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outputIdx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            try { encoder.stop(); encoder.release() } catch (_: Exception) {}
            if (muxerStarted) try { muxer.stop(); muxer.release() } catch (_: Exception) {}
        }
    }

    private fun bitmapToNv12(bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val yuv = ByteArray(width * height * 3 / 2)

        // Y plane
        for (j in 0 until height) {
            for (i in 0 until width) {
                val p = argb[j * width + i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                yuv[j * width + i] =
                    (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte()
            }
        }

        // UV plane: NV12 — interleaved Cb, Cr, 2×2 downsampled
        var uvIdx = width * height
        for (j in 0 until height / 2) {
            for (i in 0 until width / 2) {
                val p = argb[(j * 2) * width + (i * 2)]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                yuv[uvIdx++] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                yuv[uvIdx++] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
            }
        }

        return yuv
    }

    fun startScan() {
        val key = _apiKey.value
        if (key.isBlank()) {
            addLog("ERROR", "Enter an OpenAI API key in Settings first")
            return
        }

        fun doStart() {
            try {
                val openAI = OpenAIService(key)
                val startMs = System.currentTimeMillis()
                pipeline = VoiceAssistantPipeline(
                    context = getApplication(),
                    openAIService = openAI,
                    onEvent = { event ->
                        viewModelScope.launch(Dispatchers.Main.immediate) {
                            when (event) {
                                is VoiceAssistantPipeline.PipelineEvent.Processing -> {
                                    addLog("PIPELINE", "Processing utterance...")
                                    _pipelineStatus.update { it.copy(isProcessing = true) }
                                }
                                is VoiceAssistantPipeline.PipelineEvent.Transcription -> {
                                    addLog("USER", event.text)
                                    _pipelineStatus.update { it.copy(lastTranscription = event.text) }
                                }
                                is VoiceAssistantPipeline.PipelineEvent.AiResponse -> {
                                    addLog("AI", event.text)
                                    _pipelineStatus.update {
                                        it.copy(
                                            lastAiResponse = event.text,
                                            isProcessing = false,
                                            lastInferenceMs = System.currentTimeMillis() - startMs
                                        )
                                    }
                                }
                                is VoiceAssistantPipeline.PipelineEvent.TtsSynthesizing -> {
                                    addLog("TTS", "Synthesizing speech...")
                                    _pipelineStatus.update { it.copy(isSynthesizing = true) }
                                }
                                is VoiceAssistantPipeline.PipelineEvent.Error -> {
                                    addLog("ERROR", event.message)
                                    _pipelineStatus.update { it.copy(isProcessing = false, isSynthesizing = false) }
                                }
                            }
                        }
                    }
                )
                pipeline?.initTts()

                val currentPipeline = pipeline ?: run {
                    addLog("ERROR", "Failed to create pipeline")
                    return
                }

                bleService = BleVoiceService(
                    context = getApplication(),
                    pipeline = currentPipeline,
                    onEvent = { event ->
                        viewModelScope.launch(Dispatchers.Main.immediate) {
                            handleBleEvent(event)
                        }
                    }
                )
                if (_realtimeEnabled.value) {
                    // The realtime session owns the conversation and outlives any
                    // number of BLE drops — bring it up before the radio.
                    bleService?.setRealtimeMode(true)
                    startRealtimeClient()
                }
                bleService?.startScan()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start BLE", e)
                addLog("ERROR", "Start failed: ${e.message}")
            }
        }

        permissionRequestCallback?.invoke { doStart() } ?: doStart()
    }

    fun stopScan() {
        stopRealtimeClient()
        bleService?.disconnect()
        bleService = null
        pipeline?.destroy()
        pipeline = null
        _glassesStatus.update {
            it.copy(connectionState = ConnectionState.Disconnected, deviceName = "", mtu = 0)
        }
        _pipelineStatus.update { PipelineStatus() }
        addLog("BLE", "Disconnected")
    }

    fun copyLogsToClipboard(): String {
        return _logMessages.value.joinToString("\n") { entry ->
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(entry.timestamp))
            "[$time][${entry.tag}] ${entry.message}"
        }
    }

    private fun handleBleEvent(event: BleVoiceService.BleEvent) {
        when (event) {
            is BleVoiceService.BleEvent.ScanStarted -> {
                _glassesStatus.update { it.copy(connectionState = ConnectionState.Scanning) }
                addLog("BLE", "Scanning for ESP32...")
            }
            is BleVoiceService.BleEvent.ScanStopped -> {
                addLog("BLE", "Scan stopped")
            }
            is BleVoiceService.BleEvent.DeviceFound -> {
                addLog("BLE", "Found: ${event.name} [${event.address}]")
            }
            is BleVoiceService.BleEvent.Connected -> {
                _glassesStatus.update {
                    it.copy(
                        connectionState = ConnectionState.Connected,
                        deviceName = event.name,
                        deviceAddress = ""
                    )
                }
                addLog("BLE", "Connected to ${event.name}")
            }
            is BleVoiceService.BleEvent.Disconnected -> {
                _glassesStatus.update {
                    it.copy(
                        connectionState = ConnectionState.Disconnected,
                        deviceName = "",
                        mtu = 0
                    )
                }
                addLog("BLE", "Disconnected")
            }
            is BleVoiceService.BleEvent.MtuNegotiated -> {
                _glassesStatus.update { it.copy(mtu = event.mtu) }
                addLog("BLE", "MTU negotiated: ${event.mtu}")
            }
            is BleVoiceService.BleEvent.ReceivingAudio -> {
                addLog("AUDIO", "Receiving: ${event.chunks} chunks (${event.bytes} bytes)")
            }
            is BleVoiceService.BleEvent.ProcessingStarted -> {
                _glassesStatus.update { it.copy(connectionState = ConnectionState.Active) }
                _pipelineStatus.update { it.copy(isProcessing = true) }
                addLog("BLE", "Processing utterance...")
            }
            is BleVoiceService.BleEvent.SendingAudio -> {
                addLog("AUDIO", "Sending ${event.totalBytes} bytes to ESP32")
            }
            is BleVoiceService.BleEvent.AudioSent -> {
                _glassesStatus.update { it.copy(connectionState = ConnectionState.Connected) }
                _pipelineStatus.update { it.copy(isProcessing = false, isSynthesizing = false) }
                addLog("AUDIO", "Sent ${event.totalBytes} bytes")
            }
            is BleVoiceService.BleEvent.ImageReceived -> {
                addLog("CAMERA", "Image received (${event.jpegBytes.size} bytes)")
                // Always persist received photos (parity with video, which always
                // saves). A photo is a deliberate capture, so it should survive
                // even when no question follows.
                saveImageToGallery(event.jpegBytes)
                viewModelScope.launch(Dispatchers.IO) {
                    val bitmap = BitmapFactory.decodeByteArray(event.jpegBytes, 0, event.jpegBytes.size)
                    viewModelScope.launch(Dispatchers.Main.immediate) {
                        _glassesStatus.update {
                            it.copy(
                                lastImageBitmap = bitmap,
                                activeSource = InputSource.Vision,
                                imageByteCount = event.jpegBytes.size
                            )
                        }
                    }
                }
            }
            is BleVoiceService.BleEvent.VideoReceived -> {
                addLog("CAMERA", "Video received: ${event.frames.size} frames")
                saveVideoToGallery(event.frames)
            }
            is BleVoiceService.BleEvent.PlaybackCancelled -> {
                _glassesStatus.update { it.copy(connectionState = ConnectionState.Connected) }
                _pipelineStatus.update { it.copy(isProcessing = false, isSynthesizing = false) }
                addLog("AUDIO", "Playback cancelled on glasses (barge-in)")
            }
            is BleVoiceService.BleEvent.Error -> {
                addLog("ERROR", event.message)
            }
        }
    }

    /**
     * Realtime engine events. Called on OkHttp's WebSocket threads: audio is
     * forwarded to the BLE downlink synchronously (queueRealtimeAudio only
     * µ-law-encodes + enqueues — routing it through the main dispatcher would
     * add jitter); UI state is marshalled to the main thread.
     */
    private fun handleRealtimeEvent(event: RealtimeVoiceClient.RealtimeEvent) {
        when (event) {
            is RealtimeVoiceClient.RealtimeEvent.AudioDelta -> {
                bleService?.queueRealtimeAudio(event.pcm, event.first)
                if (!event.first) return   // only the first delta updates UI state
            }
            is RealtimeVoiceClient.RealtimeEvent.ResponseDone -> {
                bleService?.endRealtimeResponse()
            }
            else -> {}
        }
        viewModelScope.launch(Dispatchers.Main.immediate) {
            when (event) {
                is RealtimeVoiceClient.RealtimeEvent.Connected -> {
                    addLog("REALTIME", "GPT Realtime connected")
                    _pipelineStatus.update { it.copy(voiceState = VoiceState.Listening) }
                }
                is RealtimeVoiceClient.RealtimeEvent.Disconnected -> {
                    addLog("REALTIME", "Realtime link down (${event.reason}) — reconnecting")
                    _pipelineStatus.update { it.copy(voiceState = VoiceState.Idle) }
                }
                is RealtimeVoiceClient.RealtimeEvent.SpeechStarted -> {
                    _pipelineStatus.update { it.copy(voiceState = VoiceState.Hearing) }
                }
                is RealtimeVoiceClient.RealtimeEvent.SpeechStopped -> {
                    _pipelineStatus.update { it.copy(voiceState = VoiceState.Thinking) }
                }
                is RealtimeVoiceClient.RealtimeEvent.AudioDelta -> {
                    // First delta of a response: the glasses start speaking.
                    _pipelineStatus.update { it.copy(voiceState = VoiceState.Speaking) }
                }
                is RealtimeVoiceClient.RealtimeEvent.ResponseDone -> {
                    if (assistantTranscript.isNotEmpty()) {
                        addLog("AI", assistantTranscript.toString())
                        assistantTranscript.setLength(0)   // next response starts fresh
                    }
                    _pipelineStatus.update { it.copy(voiceState = VoiceState.Listening) }
                }
                is RealtimeVoiceClient.RealtimeEvent.AssistantTranscriptDelta -> {
                    assistantTranscript.append(event.text)
                    _pipelineStatus.update {
                        it.copy(lastAiResponse = assistantTranscript.toString())
                    }
                }
                is RealtimeVoiceClient.RealtimeEvent.UserTranscript -> {
                    addLog("USER", event.text)
                    _pipelineStatus.update { it.copy(lastTranscription = event.text) }
                }
                is RealtimeVoiceClient.RealtimeEvent.Error -> {
                    addLog("ERROR", "Realtime: ${event.message}")
                }
            }
        }
    }

    private fun addLog(tag: String, message: String) {
        _logMessages.update { current ->
            val updated = current + LogEntry(tag, message)
            if (updated.size > MAX_LOG_ENTRIES) updated.drop(updated.size - MAX_LOG_ENTRIES)
            else updated
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopRealtimeClient()
        bleService?.disconnect()
        pipeline?.destroy()
    }
}
