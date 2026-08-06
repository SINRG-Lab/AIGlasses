package com.example.aiglasses

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.aiglasses.link.BleState
import com.example.aiglasses.link.WifiPhase
import com.example.aiglasses.model.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * connectedDevice-type foreground service: keeps [GlassesController] (BLE +
 * WiFi link + GPT Realtime voice session) alive with the app backgrounded or
 * its UI killed, and renders the persistent notification as a live status
 * line — Android's answer to the iOS Dynamic Island Live Activity.
 *
 * The notification shows the voice state (Listening / Recording ● / Thinking /
 * Speaking), a device + transport line (BLE ✓ / WiFi ✓), and a Stop action.
 * Updates ride state transitions + the 700 ms mic pulse, throttled to at most
 * one notify() per second.
 */
class GlassesService : Service() {

    companion object {
        private const val CHANNEL_ID = "glasses_link"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_NOTIFY_INTERVAL_MS = 1_000L
        const val ACTION_STOP = "com.example.aiglasses.action.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, GlassesService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GlassesService::class.java))
        }
    }

    private data class NotifState(
        val voice: VoiceState,
        val micActive: Boolean,
        val ble: BleState,
        val wifiActive: Boolean,
        val error: String?
    )

    private lateinit var controller: GlassesController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var latest: NotifState? = null
    private var lastNotifyMs = 0L
    private var trailingScheduled = false

    override fun onCreate() {
        super.onCreate()
        controller = GlassesController.getInstance(this)
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            controller.disconnectAll()
            stopSelf()
            return START_NOT_STICKY
        }
        // START_STICKY restarts arrive with a null intent after process death:
        // a fresh process has a fresh (idle) controller, so reconnect here or
        // the service survives as a zombie notification with no link.
        controller.connect()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── State observation + ≥1/s throttle ──

    private fun observeState() {
        scope.launch {
            combine(
                controller.pipelineStatus,
                controller.micStreaming,
                controller.link.bleState,
                controller.link.wifiPhase,
                controller.lastError
            ) { pipeline, micActive, ble, wifi, error ->
                NotifState(
                    voice = pipeline.voiceState,
                    micActive = micActive,
                    ble = ble,
                    wifiActive = wifi is WifiPhase.Active,
                    error = error
                )
            }
                .distinctUntilChanged()
                .collect { state ->
                    latest = state
                    scheduleNotify()
                }
        }
    }

    private fun scheduleNotify() {
        val now = SystemClock.elapsedRealtime()
        val since = now - lastNotifyMs
        if (since >= MIN_NOTIFY_INTERVAL_MS) {
            lastNotifyMs = now
            notifyNow()
        } else if (!trailingScheduled) {
            trailingScheduled = true
            scope.launch {
                delay(MIN_NOTIFY_INTERVAL_MS - since)
                trailingScheduled = false
                lastNotifyMs = SystemClock.elapsedRealtime()
                notifyNow()
            }
        }
    }

    private fun notifyNow() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked mid-flight — the service keeps running.
        }
    }

    // ── Notification ──

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Glasses link", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live connection and voice status for the glasses"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun statusTitle(s: NotifState?): String = when {
        s == null -> "AI Glasses"
        s.micActive -> "Recording ●"
        else -> when (s.voice) {
            VoiceState.Idle -> "Voice off"
            VoiceState.Connecting -> "Connecting…"
            VoiceState.Listening -> "Listening"
            VoiceState.Hearing -> "Hearing you…"
            VoiceState.Thinking -> "Thinking…"
            VoiceState.Speaking -> "Speaking"
        }
    }

    private fun transportLine(s: NotifState?): String {
        if (s == null) return "Starting…"
        val ble = when (val b = s.ble) {
            is BleState.Connected ->
                (if (b.name.isBlank()) "Glasses" else b.name) + " · BLE ✓"
            is BleState.Scanning -> "Searching for glasses…"
            is BleState.Connecting -> "Connecting to glasses…"
            else -> "Glasses disconnected"
        }
        val wifi = if (s.wifiActive) " + WiFi ✓" else ""
        // RTT snapshot pulled at render time — metric ticks alone deliberately
        // don't wake the notification (state transitions + mic pulse do).
        val rtt = controller.link.metrics.value.ble.rttMs
        val rttText = if (s.ble is BleState.Connected && rtt > 0)
            " · ${rtt.toInt()} ms" else ""
        return ble + wifi + rttText
    }

    private fun buildNotification(): Notification {
        val s = latest
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, GlassesService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(statusTitle(s))
            .setContentText(s?.error ?: transportLine(s))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop", stopIntent)
            .build()
    }
}
