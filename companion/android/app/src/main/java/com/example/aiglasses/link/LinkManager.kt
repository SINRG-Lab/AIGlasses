package com.example.aiglasses.link

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Unified glasses link: owns the BLE central and the WiFi TCP client and
 * presents one connection to the rest of the app (mirror of iOS LinkManager).
 *
 * Transport policy (V2 — BLE primary):
 *  - BLE stays connected AT ALL TIMES. Control markers and realtime voice
 *    (µ-law both directions) always ride BLE.
 *  - WiFi is an opt-in BULK lane. The firmware picks the route per image
 *    (only when its measurements say WiFi actually beats BLE) and the route
 *    truth is simply whichever transport the 'H' header arrives on.
 *
 * WiFi bring-up (Android "Wi-Fi Direct experience"):
 *  1. app writes 'F' on BLE CONTROL                      → phase Requesting
 *  2. glasses answer 'N' + "ssid\npass\nip\nport"        → phase Approving
 *  3. local-only network request (one-time system dialog) → phase Connecting
 *  4. TCP socket via network.socketFactory, firmware 'R'  → phase Active
 * BLE is never released while the socket is up.
 *
 * The auto toggle is a STANDING preference (persisted): every socket death
 * redials 8× at 3 s then keeps probing every 30 s; every BLE (re)connect
 * with the toggle on and the socket down restarts the whole bootstrap.
 */
class LinkManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "aiglasses_prefs"
        private const val PREF_WIFI_AUTO = "wifi_auto"
        private const val PREF_WIFI_SSID = "wifi_ssid"
        private const val PREF_WIFI_PASS = "wifi_pass"
        private const val PREF_WIFI_HOST = "wifi_host"
        private const val PREF_WIFI_PORT = "wifi_port"

        // Defaults when connecting without a BLE bootstrap (must match
        // firmware config.h).
        private const val DEFAULT_HOST = "192.168.4.1"
        private const val DEFAULT_PORT = 5005
        private const val DEFAULT_PASS = "glasses-link"

        private const val TICK_MS = 2_000L
        private const val N_ANSWER_TIMEOUT_MS = 8_000L
        private const val REDIAL_DELAY_MS = 3_000L
        private const val REDIAL_BUDGET = 8
        private const val SLOW_RETRY_MS = 30_000L
        private const val MAX_PHOTO_RECORDS = 20
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Observable state ──
    private val _bleState = MutableStateFlow<BleState>(BleState.Disconnected)
    val bleState: StateFlow<BleState> = _bleState.asStateFlow()

    private val _wifiPhase = MutableStateFlow<WifiPhase>(WifiPhase.Off)
    val wifiPhase: StateFlow<WifiPhase> = _wifiPhase.asStateFlow()

    private val _metrics = MutableStateFlow(LinkMetrics())
    val metrics: StateFlow<LinkMetrics> = _metrics.asStateFlow()

    private val _fwStats = MutableStateFlow<FirmwareStats?>(null)
    val fwStats: StateFlow<FirmwareStats?> = _fwStats.asStateFlow()

    private val _photoTransfers = MutableStateFlow<List<PhotoTransfer>>(emptyList())
    val photoTransfers: StateFlow<List<PhotoTransfer>> = _photoTransfers.asStateFlow()

    private val _wifiAuto = MutableStateFlow(prefs.getBoolean(PREF_WIFI_AUTO, false))
    val wifiAuto: StateFlow<Boolean> = _wifiAuto.asStateFlow()

    // ── Callbacks (wired once by the app layer) ──
    /** PCM16 @16 kHz, seq-filtered, µ-law-decoded. Background thread — NEVER main. */
    var onMicAudio: ((ByteArray) -> Unit)? = null
    /** The physical side button began a recording. Background BLE callback thread. */
    var onRecordingStarted: (() -> Unit)? = null
    var onPhoto: ((ByteArray) -> Unit)? = null              // complete JPEG
    var onVisionPhoto: ((ByteArray) -> Unit)? = null
    var onBargeIn: (() -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    // ── Internals ──
    private val ble = BleLinkClient(appContext)
    private val wifi = WifiLinkClient(appContext)

    // All WiFi-lifecycle state lives on this single handler thread.
    private val linkThread = HandlerThread("LinkManager").apply { start() }
    private val handler = Handler(linkThread.looper)

    // Last credentials the glasses sent (persisted for BLE-less redials).
    private var wifiSsid = prefs.getString(PREF_WIFI_SSID, null)
    private var wifiPass = prefs.getString(PREF_WIFI_PASS, null) ?: DEFAULT_PASS
    private var wifiHost = prefs.getString(PREF_WIFI_HOST, null) ?: DEFAULT_HOST
    private var wifiPort = prefs.getInt(PREF_WIFI_PORT, DEFAULT_PORT)
    private var redialsLeft = 0
    private var wifiRedials = 0

    private val nAnswerWatchdog = Runnable {
        if (_wifiPhase.value == WifiPhase.Requesting) {
            _wifiPhase.value = WifiPhase.Failed("Glasses did not answer — is the firmware current?")
            scheduleSlowRetry()
        }
    }
    private val redialRunnable = Runnable {
        if (_wifiAuto.value && !wifi.isConnected) {
            log("[link] redialing Wi-Fi ($redialsLeft tries left)…")
            startWifiBootstrap()
        }
    }
    private val slowRetryRunnable = Runnable {
        if (_wifiAuto.value && !wifi.isConnected) {
            log("[link] auto Wi-Fi: retrying quietly…")
            redialsLeft = 1
            startWifiBootstrap()
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            val b = ble.tick()
            val w = wifi.tick()
            _metrics.value = LinkMetrics(
                ble = b.transport,
                wifi = w.transport,
                micRecvPerSec = b.micRecvPerSec,
                micAcceptedPerSec = b.micAcceptedPerSec,
                micDupPerSec = b.micDupPerSec,
                micLostPerSec = b.micLostPerSec,
                imageSeqGapsTotal = b.imageSeqGapsTotal,
                bleReconnects = b.reconnects,
                wifiConnects = w.connects,
                wifiRedials = wifiRedials,
                wifiSocketUptimeMs = w.socketUptimeMs,
                mtu = b.mtu,
                phyTx = b.phyTx,
                phyRx = b.phyRx,
            )
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        wireBle()
        wireWifi()
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    // ── Public API ──

    fun connect() {
        ble.connect()
    }

    fun disconnectAll() {
        handler.post {
            cancelWifiTimers()
            if (wifi.isConnected) wifi.sendControl(byteArrayOf('f'.code.toByte()))
            wifi.stop()
            _wifiPhase.value = WifiPhase.Off
            ble.disconnect()
        }
    }

    /** 'M'/'m' realtime voice mode — always Bluetooth (V2 policy). */
    fun setVoiceMode(on: Boolean) = ble.setVoiceMode(on)

    /** Queue µ-law @24 kHz response audio; the paced sender lives inside. */
    fun enqueueResponseAudio(ulaw: ByteArray) = ble.enqueueResponseAudio(ulaw)

    fun finishResponse() = ble.finishResponse()

    fun cancelResponse() = ble.cancelResponse()

    /**
     * The standing WiFi-bulk-lane preference. On: bootstrap now and self-heal
     * forever (redials + re-bootstrap on every BLE reconnect). Off: tear the
     * lane down and ask the firmware to power the SoftAP off ('f', ~100 mA).
     */
    fun setWifiAuto(on: Boolean) {
        prefs.edit().putBoolean(PREF_WIFI_AUTO, on).apply()
        _wifiAuto.value = on
        handler.post {
            if (on) {
                redialsLeft = REDIAL_BUDGET
                startWifiBootstrap()
            } else {
                cancelWifiTimers()
                if (wifi.isConnected) {
                    wifi.sendControl(byteArrayOf('f'.code.toByte()))
                } else if (ble.isConnected && _wifiPhase.value != WifiPhase.Off) {
                    ble.writeControl(byteArrayOf('f'.code.toByte()))
                }
                wifi.stop()
                _wifiPhase.value = WifiPhase.Off
            }
        }
    }

    // ── BLE wiring ──

    private fun wireBle() {
        ble.onLog = { line -> log("[ble] $line") }
        ble.onStateChange = { state -> _bleState.value = state }
        ble.onMicAudio = { pcm -> onMicAudio?.invoke(pcm) }
        ble.onRecordingStarted = { onRecordingStarted?.invoke() }
        ble.onPhoto = { jpeg -> onPhoto?.invoke(jpeg) }
        ble.onVisionPhoto = { jpeg -> onVisionPhoto?.invoke(jpeg) }
        ble.onBargeIn = { onBargeIn?.invoke() }
        ble.onPhotoStats = { bytes, millis -> recordPhotoTransfer(LinkRoute.Ble, bytes, millis) }
        ble.onStatsPacket = { packet -> handleStatsPacket(packet) }
        ble.onWifiInfo = { ssid, pass, host, port ->
            handler.post { handleWifiInfo(ssid, pass, host, port) }
        }
        ble.onConnected = {
            onConnected?.invoke()
            // BLE is (back) up: if the user wants WiFi but the socket is down
            // (glasses rebooted, phone wandered off the AP, app relaunched),
            // restart the whole bootstrap instead of silently staying BLE-only.
            handler.post {
                if (_wifiAuto.value && !wifi.isConnected) {
                    log("[link] BLE up + Wi-Fi wanted — re-requesting the AP")
                    redialsLeft = REDIAL_BUDGET
                    startWifiBootstrap()
                }
            }
        }
    }

    // ── WiFi wiring ──

    private fun wireWifi() {
        wifi.onLog = { line -> log("[wifi] $line") }
        wifi.onPhoto = { jpeg -> onPhoto?.invoke(jpeg) }
        wifi.onVisionPhoto = { jpeg -> onVisionPhoto?.invoke(jpeg) }
        wifi.onPhotoStats = { bytes, millis -> recordPhotoTransfer(LinkRoute.Wifi, bytes, millis) }
        wifi.onStatsPacket = { packet -> handleStatsPacket(packet) }
        wifi.onNetworkAvailable = {
            handler.post { if (_wifiPhase.value == WifiPhase.Approving) _wifiPhase.value = WifiPhase.Connecting }
        }
        wifi.onHello = {
            handler.post {
                _wifiPhase.value = WifiPhase.Active
                redialsLeft = REDIAL_BUDGET   // future drops get a full retry budget
                cancelWifiTimers()
                // BLE-primary policy: Bluetooth keeps carrying voice + control —
                // the socket is purely a bulk lane, nothing to hand over.
                onConnected?.invoke()
            }
        }
        wifi.onDown = { wasActive, reason ->
            handler.post {
                // Below Android 10 there is no WifiNetworkSpecifier — the lane
                // can NEVER come up, so retrying forever just drains battery.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    _wifiPhase.value = WifiPhase.Failed(reason)
                } else {
                    if (wasActive) {
                        log("[link] Wi-Fi bulk lane dropped ($reason) — photos ride Bluetooth while we redial")
                    }
                    scheduleRedial(reason)
                }
            }
        }
    }

    // ── WiFi lifecycle (link handler thread) ──

    /**
     * One bootstrap attempt. Over BLE when available (full 'F' → 'N' flow,
     * which also re-arms the SoftAP after a glasses reboot); otherwise reuse
     * the last known credentials — works when the AP is still up from a
     * previous session.
     */
    private fun startWifiBootstrap() {
        if (!_wifiAuto.value || wifi.isConnected) return
        if (ble.isConnected) {
            _wifiPhase.value = WifiPhase.Requesting
            log("[link] requesting WiFi link from glasses ('F')…")
            ble.writeControl(byteArrayOf('F'.code.toByte()))
            handler.removeCallbacks(nAnswerWatchdog)
            handler.postDelayed(nAnswerWatchdog, N_ANSWER_TIMEOUT_MS)
        } else {
            val ssid = wifiSsid
            if (ssid == null) {
                _wifiPhase.value = WifiPhase.Failed(
                    "Connect Bluetooth first — the glasses send their Wi-Fi credentials over BLE"
                )
                scheduleSlowRetry()
            } else {
                log("[link] no BLE — reusing last credentials for $ssid @ $wifiHost:$wifiPort")
                launchWifi(ssid, wifiPass, wifiHost, wifiPort)
            }
        }
    }

    /** 'N' answer arrived: the SoftAP is up — request the local-only network. */
    private fun handleWifiInfo(ssid: String, pass: String, host: String, port: Int) {
        handler.removeCallbacks(nAnswerWatchdog)
        wifiSsid = ssid
        wifiPass = pass
        wifiHost = host
        wifiPort = port
        prefs.edit()
            .putString(PREF_WIFI_SSID, ssid)
            .putString(PREF_WIFI_PASS, pass)
            .putString(PREF_WIFI_HOST, host)
            .putInt(PREF_WIFI_PORT, port)
            .apply()
        if (!_wifiAuto.value || wifi.isConnected) return
        redialsLeft = maxOf(redialsLeft, REDIAL_BUDGET)   // fresh bootstrap → fresh budget
        launchWifi(ssid, pass, host, port)
    }

    private fun launchWifi(ssid: String, pass: String, host: String, port: Int) {
        _wifiPhase.value = WifiPhase.Approving
        wifi.start(ssid, pass, host, port)
    }

    /**
     * A bring-up failed or the socket died. While the toggle is on, retry
     * every 3 s (8×) before settling on the failure hint — this rides out
     * slow AP association, glasses reboots, and the approval dialog. After
     * the fast budget, keep probing gently every 30 s: the toggle is a
     * standing auto-connect preference, not a one-shot action.
     */
    private fun scheduleRedial(reason: String) {
        if (!_wifiAuto.value) {
            _wifiPhase.value = WifiPhase.Off
            return
        }
        cancelWifiTimers()
        if (redialsLeft <= 0) {
            _wifiPhase.value = WifiPhase.Failed(
                "Photos are on Bluetooth ($reason). Auto-retry continues in the background."
            )
            scheduleSlowRetry()
            return
        }
        redialsLeft--
        wifiRedials++
        _wifiPhase.value = WifiPhase.Connecting
        handler.postDelayed(redialRunnable, REDIAL_DELAY_MS)
    }

    private fun scheduleSlowRetry() {
        handler.removeCallbacks(slowRetryRunnable)
        handler.postDelayed(slowRetryRunnable, SLOW_RETRY_MS)
    }

    private fun cancelWifiTimers() {
        handler.removeCallbacks(nAnswerWatchdog)
        handler.removeCallbacks(redialRunnable)
        handler.removeCallbacks(slowRetryRunnable)
    }

    // ── Metrics plumbing ──

    private fun handleStatsPacket(packet: ByteArray) {
        val parsed = FirmwareStats.parse(packet)
        if (parsed == null) {
            log("[link] unparseable 'T' stats packet (${packet.size} B)")
            return
        }
        _fwStats.value = parsed
    }

    private fun recordPhotoTransfer(route: LinkRoute, bytes: Int, millis: Long) {
        val record = PhotoTransfer(
            timestampMs = System.currentTimeMillis(),
            route = route,
            bytes = bytes,
            millis = millis,
        )
        handler.post {
            _photoTransfers.value =
                (listOf(record) + _photoTransfers.value).take(MAX_PHOTO_RECORDS)
        }
        log(String.format(
            "[link] photo via %s: %d B in %d ms (%.1f KB/s)",
            if (route == LinkRoute.Wifi) "Wi-Fi" else "Bluetooth", bytes, millis, record.kbPerSec
        ))
    }

    private fun log(line: String) {
        onLog?.invoke(line)
    }
}
