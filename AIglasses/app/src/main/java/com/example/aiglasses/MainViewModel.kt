package com.example.aiglasses

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.aiglasses.model.GlassesStatus
import com.example.aiglasses.model.LogEntry
import com.example.aiglasses.model.PipelineStatus
import com.example.aiglasses.model.SavedImage
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Thin UI adapter over [GlassesController] — the controller (kept alive by
 * [GlassesService]) owns the link, voice session and all long-lived state;
 * this ViewModel only re-exposes its flows and routes UI intents, so nothing
 * dies with the Activity.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val controller = GlassesController.getInstance(application)

    // Permission callback bridge — set by MainActivity, invoked before any
    // action that needs the runtime permissions.
    private var permissionRequestCallback: ((onGranted: () -> Unit) -> Unit)? = null

    // ── Link + voice state (owned by GlassesController) ──

    val glassesStatus: StateFlow<GlassesStatus> = controller.glassesStatus
    val pipelineStatus: StateFlow<PipelineStatus> = controller.pipelineStatus
    val logMessages: StateFlow<List<LogEntry>> = controller.logMessages
    val savedImages: StateFlow<List<SavedImage>> = controller.savedImages
    val lastError: StateFlow<String?> = controller.lastError
    val pendingPhoto: StateFlow<SavedImage?> = controller.pendingPhoto
    val photoAttached: StateFlow<Boolean> = controller.photoAttached
    /** Standing "voice wanted" preference — false only after a manual stop. */
    val voiceAutoEnabled: StateFlow<Boolean> = controller.voiceAutoEnabled
    /** Mic frames streaming right now (700 ms decay) — recording pulse. */
    val micStreaming: StateFlow<Boolean> = controller.micStreaming

    // ── Transport surface (types owned by the link package) ──

    val bleState = controller.link.bleState
    val wifiPhase = controller.link.wifiPhase
    val linkMetrics = controller.link.metrics
    val fwStats = controller.link.fwStats
    val photoTransfers = controller.link.photoTransfers
    val wifiAuto = controller.link.wifiAuto

    // ── Settings ──

    val apiKey: StateFlow<String> = controller.settings.apiKey
    val realtimeModel: StateFlow<String> = controller.settings.model
    val realtimeVoice: StateFlow<String> = controller.settings.voice
    val realtimeEffort: StateFlow<String> = controller.settings.effort

    fun setPermissionRequestCallback(cb: (onGranted: () -> Unit) -> Unit) {
        permissionRequestCallback = cb
    }

    // ── Connection ──

    /**
     * One-step activation: ensure the foreground service is up and the radio
     * is connecting. Voice auto-starts once the glasses connect (if an API
     * key is saved). Safe to call repeatedly.
     */
    fun startScan() {
        val doStart = {
            GlassesService.start(getApplication())
            controller.connect()
        }
        permissionRequestCallback?.invoke { doStart() } ?: doStart()
    }

    /** Full stop: voice off, both transports down, foreground service gone. */
    fun stopScan() {
        controller.disconnectAll()
        GlassesService.stop(getApplication())
    }

    // ── Voice ──

    /** Retry button: clear error/backoff and reconnect voice right now. */
    fun retryVoiceNow() {
        val doRetry = {
            GlassesService.start(getApplication())
            controller.retryVoiceNow()
        }
        permissionRequestCallback?.invoke { doRetry() } ?: doRetry()
    }

    /** Manual voice off (Settings) — stays off until retried. */
    fun stopVoice() = controller.stopVoice()

    // ── WiFi bulk lane ──

    fun setWifiAuto(on: Boolean) = controller.setWifiAuto(on)

    // ── Vision ──

    fun askAboutPendingPhoto() = controller.askAboutPendingPhoto()

    fun dismissPendingPhoto() = controller.dismissPendingPhoto()

    // ── Settings intents ──

    fun setApiKey(key: String) = controller.setApiKey(key)

    /** Model/voice/effort apply on the next voice (re)connect — iOS parity. */
    fun setRealtimeModel(model: String) = controller.settings.setModel(model)

    fun setRealtimeVoice(voice: String) = controller.settings.setVoice(voice)

    fun setRealtimeEffort(effort: String) = controller.settings.setEffort(effort)

    // ── Gallery ──

    fun deleteImage(filename: String) = controller.deleteImage(filename)

    fun getImageFile(filename: String): File = controller.getImageFile(filename)

    // ── Logs ──

    fun copyLogsToClipboard(): String {
        return logMessages.value.joinToString("\n") { entry ->
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(entry.timestamp))
            "[$time][${entry.tag}] ${entry.message}"
        }
    }
}
