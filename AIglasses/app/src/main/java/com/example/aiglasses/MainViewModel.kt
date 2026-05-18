package com.example.aiglasses

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
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
        private const val MAX_LOG_ENTRIES = 100
        private const val GALLERY_DIR = "gallery"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var bleService: BleVoiceService? = null
    private var pipeline: VoiceAssistantPipeline? = null

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
                ?.filter { it.extension == "jpg" }
                ?.sortedByDescending { it.lastModified() }
                ?.map { SavedImage(it.name, it.lastModified(), it.length().toInt()) }
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
                bleService?.startScan()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start BLE", e)
                addLog("ERROR", "Start failed: ${e.message}")
            }
        }

        permissionRequestCallback?.invoke { doStart() } ?: doStart()
    }

    fun stopScan() {
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
                if (_autoSaveImages.value) {
                    saveImageToGallery(event.jpegBytes)
                }
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
            is BleVoiceService.BleEvent.Error -> {
                addLog("ERROR", event.message)
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
        bleService?.disconnect()
        pipeline?.destroy()
    }
}
