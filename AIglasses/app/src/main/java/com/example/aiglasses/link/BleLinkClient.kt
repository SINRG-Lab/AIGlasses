package com.example.aiglasses.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.example.aiglasses.AudioCodec
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * BLE Central for the SINRG Lab AI glasses (ESP32-S3, NimBLE peripheral) —
 * the PRIMARY, always-on transport (docs/BLE_PROTOCOL.md).
 *
 * GATT layout (service aa00):
 *   aa01 AUDIO_TX  NOTIFY       mic -> phone   ['A'][seq u8][audio @16 kHz —
 *                               µ-law while 'M' voice mode is on, else PCM16]
 *   aa02 AUDIO_RX  WRITE_NR     phone -> spkr  ['A'][seq u8][µ-law @24 kHz]
 *   aa03 CONTROL   WRITE+NOTIFY markers: 'M'/'m' voice mode, 'S'/'E' stream
 *                               start/end, 'X' barge-in, 'P' ping echo,
 *                               'F'/'f'→'N' WiFi bootstrap, 'T' fw stats,
 *                               legacy image headers
 *   aa04 IMAGE_TX  NOTIFY       ['H'][flags][len u32 LE], ['I'][seq][jpeg], ['J'][idx]
 *
 * Policy baked in (mirrors iOS BleManager):
 *  - Auto-reconnect: on ANY disconnect (or wedged link) rescan, reconnect,
 *    re-subscribe, rewrite 'M' and reset the seq trackers. The voice session
 *    lives upstairs and survives radio drops.
 *  - Mic frames are seq-filtered (dup/stale drop, ≤8-frame gaps zero-filled)
 *    and delivered as PCM16 @16 kHz on the BLE binder thread.
 *  - Response audio is µ-law @24 kHz, paced by a token bucket (24 KB burst,
 *    then 1.35× the 24000 B/s consumption rate).
 *  - 'P' ping every tick; 3 outstanding-at-next-tick misses force a
 *    disconnect so the rescan path recovers a wedged link.
 */
@SuppressLint("MissingPermission")
class BleLinkClient(private val context: Context) {

    companion object {
        private const val TAG = "BleLinkClient"

        private val SERVICE_UUID = UUID.fromString("0000aa00-1234-5678-abcd-0e5032c6b1e0")
        private val AUDIO_TX_UUID = UUID.fromString("0000aa01-1234-5678-abcd-0e5032c6b1e0")
        private val AUDIO_RX_UUID = UUID.fromString("0000aa02-1234-5678-abcd-0e5032c6b1e0")
        private val CONTROL_UUID = UUID.fromString("0000aa03-1234-5678-abcd-0e5032c6b1e0")
        private val IMAGE_TX_UUID = UUID.fromString("0000aa04-1234-5678-abcd-0e5032c6b1e0")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val TARGET_MTU = 512
        private const val HEADER_SIZE = 2

        // Scan cadence: 20 s scan windows with an 8 s pause between restarts.
        // Android throttles apps that start scans >4×/30 s; this stays under.
        private const val SCAN_WINDOW_MS = 20_000L
        private const val SCAN_PAUSE_MS = 8_000L
        private const val RECONNECT_DELAY_MS = 1_000L
        // connectGatt → fully-subscribed watchdog: a wedged GATT setup (stale
        // cache, notify-enable stall) looks "connecting" forever without this.
        private const val CONNECT_TIMEOUT_MS = 15_000L

        // Liveness: pings ride the 2 s LinkManager tick; 3 consecutive
        // outstanding-at-next-tick misses = wedged link → force disconnect.
        private const val PING_MISS_LIMIT = 3

        // Downlink token bucket: µ-law @24 kHz consumes 24000 B/s on the
        // glasses; burst fills the firmware prebuffer fast, then ~1.35×
        // realtime sustains it. Values validated on hardware (realtime_ble.py).
        private const val DL_BURST = 24L * 1024L
        private const val DL_BPS = 32400L          // 1.35 × 24000 B/s of µ-law
    }

    /** Per-tick metrics snapshot handed to LinkManager (2 s window). */
    data class Snapshot(
        val transport: TransportMetrics,
        val micRecvPerSec: Double,
        val micAcceptedPerSec: Double,
        val micDupPerSec: Double,
        val micLostPerSec: Double,
        val imageSeqGapsTotal: Int,
        val reconnects: Int,
        val mtu: Int,
        val phyTx: Int,
        val phyRx: Int,
    )

    // ── Callbacks (wired once by LinkManager; invoked on BLE/binder threads) ──
    var onStateChange: ((BleState) -> Unit)? = null
    var onMicAudio: ((ByteArray) -> Unit)? = null          // PCM16 @16 kHz
    var onPhoto: ((ByteArray) -> Unit)? = null             // complete JPEG
    var onVisionPhoto: ((ByteArray) -> Unit)? = null       // header flags 0x02
    var onPhotoStats: ((Int, Long) -> Unit)? = null        // (bytes, millis)
    var onStatsPacket: ((ByteArray) -> Unit)? = null       // raw 'T' packet incl. tag
    var onWifiInfo: ((String, String, String, Int) -> Unit)? = null // ssid, pass, host, port
    var onBargeIn: (() -> Unit)? = null                    // 'X' from the glasses
    var onConnected: (() -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    // ── BLE plumbing ──
    private val bluetoothManager: BluetoothManager? =
        try { context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager }
        catch (_: Exception) { null }
    private val bluetoothAdapter: BluetoothAdapter? =
        try { bluetoothManager?.adapter } catch (_: Exception) { null }
    private var scanner: BluetoothLeScanner? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var audioTxChar: BluetoothGattCharacteristic? = null
    @Volatile private var audioRxChar: BluetoothGattCharacteristic? = null
    @Volatile private var controlChar: BluetoothGattCharacteristic? = null
    @Volatile private var imageTxChar: BluetoothGattCharacteristic? = null

    @Volatile private var isConnectedFlag = false
    @Volatile private var isScanning = false
    @Volatile private var shouldStayConnected = false
    @Volatile private var deviceName = "AIGlasses"
    @Volatile private var negotiatedMtu = 23
    @Volatile private var phyTx = 0
    @Volatile private var phyRx = 0
    private var everConnected = false                       // main handler thread
    private val reconnects = AtomicInteger(0)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var connectAttempt = 0                          // main handler thread

    // ── Voice mode + mic uplink seq tracking ──
    @Volatile private var voiceModeWanted = false
    @Volatile private var micSeqValid = false
    @Volatile private var micLastSeq = 0

    // ── Downlink (response audio) queue ──
    private sealed class DlItem {
        class Audio(val ulaw: ByteArray) : DlItem()
        data object End : DlItem()
    }
    private val dlQueue = LinkedBlockingQueue<DlItem>()
    private var dlThread: Thread? = null                    // guarded by dlQueue
    @Volatile private var dlCancelled = false
    // Serializes CONTROL writes across threads (mode marker, 'S'/'E', pings,
    // 'F'/'f') — writeCharacteristic shares one value slot per characteristic.
    private val controlLock = Any()
    // Signaled by onCharacteristicWrite: the stack accepted our last write
    // and can take another. Lets writeControlInternal wait instead of spin.
    private val writeMonitor = Object()

    // ── Image reassembly (BLE notify thread only) ──
    private val imageBuffer = ByteArrayOutputStream()
    @Volatile private var receivingImage = false
    @Volatile private var discardingVideoFrame = false      // legacy fw live video
    private var pendingImageIsVision = false
    private var expectedImageSize = 0
    private var imageSeqExpected = 0
    private var imageSeqGaps = 0
    private var imageStartMs = 0L
    private var loggedLegacyVideo = false
    private val imageSeqGapsTotal = AtomicInteger(0)

    // ── Ping / RTT state (guarded by pingLock) ──
    private val pingLock = Any()
    private var pingSeq = 0
    private val pingSent = HashMap<Int, Long>()
    private var missedPings = 0
    private var pingQueueFailed = false   // guarded by pingLock
    private var rttLast = 0.0
    private var rttSum = 0.0
    private var rttCount = 0
    private var rttMin = 0.0
    private var rttMax = 0.0
    private var pingsSentTotal = 0
    private var pingsLostTotal = 0

    // ── Rolling stat counters (reset every tick) ──
    private val statMicRecv = AtomicInteger(0)
    private val statMicAccepted = AtomicInteger(0)
    private val statMicDup = AtomicInteger(0)
    private val statMicLost = AtomicInteger(0)
    private val statRxBytes = AtomicLong(0)
    private val statTxBytes = AtomicLong(0)
    private val rxBytesTotal = AtomicLong(0)
    private val txBytesTotal = AtomicLong(0)

    // ── Public API ──

    val isConnected: Boolean get() = isConnectedFlag

    /** Sticky connect: scans, connects, and keeps reconnecting until [disconnect]. */
    fun connect() {
        shouldStayConnected = true
        mainHandler.post { startScanIfNeeded() }
    }

    fun disconnect() {
        shouldStayConnected = false
        if (isConnectedFlag && voiceModeWanted) {
            // Best effort: leave the glasses in the legacy protocol so an old
            // app connecting next doesn't feed PCM16 into the µ-law decoder.
            try { writeControlNoResponse(byteArrayOf('m'.code.toByte(), 0)) } catch (_: Exception) {}
        }
        isConnectedFlag = false          // stops senders/pings immediately
        mainHandler.post {
            mainHandler.removeCallbacks(scanRestartRunnable)
            mainHandler.removeCallbacks(scanWindowRunnable)
            stopScanQuietly()
            teardownGatt()
            setState(BleState.Disconnected)
        }
    }

    /**
     * 'M' enables realtime voice mode (µ-law mic streaming) on the glasses,
     * 'm' disables it. Remembered and re-written after every (re)connect —
     * the firmware keeps its µ-law flag across connections, so an explicit
     * marker un-sticks a stale mode from a previous session.
     */
    fun setVoiceMode(on: Boolean) {
        voiceModeWanted = on
        if (isConnectedFlag) {
            Thread({
                writeControlNoResponse(byteArrayOf((if (on) 'M' else 'm').code.toByte(), 0))
                log("→ '${if (on) 'M' else 'm'}' voice mode ${if (on) "on" else "off"}")
            }, "BleModeMarker").apply { isDaemon = true; start() }
        }
    }

    /**
     * Queue µ-law @24 kHz response audio. The paced sender writes the 'S'
     * start marker before the first frame of each response.
     */
    fun enqueueResponseAudio(ulaw: ByteArray) {
        if (ulaw.isEmpty() || !isConnectedFlag) return
        dlQueue.offer(DlItem.Audio(ulaw))
        ensureDlThread()
    }

    /** The response is complete: flush the queue, then write 'E'. */
    fun finishResponse() {
        if (!isConnectedFlag) return
        dlQueue.offer(DlItem.End)
        ensureDlThread()
    }

    /**
     * Barge-in / voice-off: drop everything immediately. Deliberately no 'E' —
     * a late 'E' could land after the 'S' of the next response and kill it.
     */
    fun cancelResponse() {
        dlCancelled = true
        dlQueue.clear()
        dlQueue.offer(DlItem.End)   // closes the open response in the sender
    }

    /** Raw CONTROL write ('F'/'f' WiFi bootstrap, …), retried off-thread. */
    fun writeControl(bytes: ByteArray) {
        if (!isConnectedFlag) return
        Thread({ writeControlNoResponse(bytes) }, "BleCtrlWrite")
            .apply { isDaemon = true; start() }
    }

    /**
     * 2 s LinkManager tick (link handler thread): check ping liveness, send
     * the next ping, roll the rate counters, and return a metrics snapshot.
     */
    fun tick(): Snapshot {
        checkLivenessAndPing()
        val secs = 2.0
        val rtt: DoubleArray
        val sent: Int
        val lost: Int
        synchronized(pingLock) {
            rtt = doubleArrayOf(rttLast, if (rttCount > 0) rttSum / rttCount else 0.0, rttMin, rttMax)
            sent = pingsSentTotal
            lost = pingsLostTotal
        }
        return Snapshot(
            transport = TransportMetrics(
                rttMs = rtt[0],
                rttAvgMs = rtt[1],
                rttMinMs = rtt[2],
                rttMaxMs = rtt[3],
                pingsSent = sent,
                pingsLost = lost,
                rxBytesPerSec = statRxBytes.getAndSet(0) / secs,
                txBytesPerSec = statTxBytes.getAndSet(0) / secs,
                rxBytesTotal = rxBytesTotal.get(),
                txBytesTotal = txBytesTotal.get(),
            ),
            micRecvPerSec = statMicRecv.getAndSet(0) / secs,
            micAcceptedPerSec = statMicAccepted.getAndSet(0) / secs,
            micDupPerSec = statMicDup.getAndSet(0) / secs,
            micLostPerSec = statMicLost.getAndSet(0) / secs,
            imageSeqGapsTotal = imageSeqGapsTotal.get(),
            reconnects = reconnects.get(),
            mtu = negotiatedMtu,
            phyTx = phyTx,
            phyRx = phyRx,
        )
    }

    // ── Ping / liveness ──

    private fun checkLivenessAndPing() {
        if (!isConnectedFlag) {
            synchronized(pingLock) {
                pingSent.clear()
                missedPings = 0
                rttLast = 0.0
            }
            return
        }
        var wedged = false
        val pkt: ByteArray
        val id: Int
        synchronized(pingLock) {
            // A working link echoes in ms, so a ping still outstanding when
            // the next one goes out counts as missed. Three in a row means
            // the link is wedged (the stack still says connected, nothing
            // moves) — force a disconnect so the rescan path recovers it.
            if (pingSent.isNotEmpty()) {
                missedPings += pingSent.size
                pingsLostTotal += pingSent.size
                pingSent.clear()
            } else if (!pingQueueFailed) {
                // Only a genuinely quiet, queue-healthy link resets the count —
                // pings that never even queued (writeCharacteristic false) are
                // misses too, or a wedged stack could evade detection forever.
                missedPings = 0
            }
            if (missedPings >= PING_MISS_LIMIT) {
                missedPings = 0
                wedged = true
            }
            pingSeq++
            id = pingSeq
            pingsSentTotal++
            pkt = byteArrayOf(
                'P'.code.toByte(),
                (id and 0xFF).toByte(),
                ((id shr 8) and 0xFF).toByte(),
                ((id shr 16) and 0xFF).toByte(),
                ((id shr 24) and 0xFF).toByte(),
            )
        }
        if (wedged) {
            log("$PING_MISS_LIMIT pings unanswered — BLE link is wedged, forcing reconnect")
            try { gatt?.disconnect() } catch (_: Exception) {}
            return
        }
        // Clock starts at the actual (queued) write so RTT measures the link.
        if (writeControlNoResponse(pkt)) {
            synchronized(pingLock) {
                pingSent[id] = System.currentTimeMillis()
                pingQueueFailed = false
            }
        } else {
            synchronized(pingLock) {
                pingsLostTotal++
                missedPings++
                pingQueueFailed = true
            }
        }
    }

    private fun handlePingEcho(data: ByteArray) {
        if (data.size < 5) return
        val id = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8) or
            ((data[3].toInt() and 0xFF) shl 16) or ((data[4].toInt() and 0xFF) shl 24)
        synchronized(pingLock) {
            val sentAt = pingSent.remove(id) ?: return
            val ms = (System.currentTimeMillis() - sentAt).toDouble()
            rttLast = ms
            rttSum += ms
            rttCount++
            rttMin = if (rttMin == 0.0) ms else minOf(rttMin, ms)
            rttMax = maxOf(rttMax, ms)
            missedPings = 0
        }
    }

    private fun resetPingLiveness() {
        synchronized(pingLock) {
            pingSent.clear()
            missedPings = 0
            rttLast = 0.0
        }
    }

    // ── Scanning / connecting (main handler thread) ──

    private val scanWindowRunnable = Runnable {
        // Scan window elapsed with no result — pause, then restart.
        if (isScanning && shouldStayConnected) {
            stopScanQuietly()
            log("glasses not found yet — still searching")
            mainHandler.postDelayed(scanRestartRunnable, SCAN_PAUSE_MS)
        }
    }
    private val scanRestartRunnable = Runnable { startScanIfNeeded() }

    private fun startScanIfNeeded() {
        if (!shouldStayConnected || isConnectedFlag || isScanning || gatt != null) return
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth is off — will retry")
            setState(BleState.Disconnected)
            mainHandler.postDelayed(scanRestartRunnable, SCAN_PAUSE_MS)
            return
        }
        val s = adapter.bluetoothLeScanner
        if (s == null) {
            log("BLE scanner unavailable — will retry")
            mainHandler.postDelayed(scanRestartRunnable, SCAN_PAUSE_MS)
            return
        }
        scanner = s
        // Filter list = OR semantics. The service-UUID filter finds the real
        // glasses; the name filters find the macOS bench simulator, whose
        // CoreBluetooth peripheral stack tends to advertise 128-bit service
        // UUIDs in an Apple-proprietary "overflow area" invisible to Android
        // scanners (iOS sees it, we would not).
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build(),
            ScanFilter.Builder().setDeviceName("AIGlasses-ESP32S3").build(),
            ScanFilter.Builder().setDeviceName("AIGlasses-SIM").build(),
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        isScanning = true
        try {
            s.startScan(filters, settings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "startScan failed", e)
            isScanning = false
            log("scan failed: ${e.message} — will retry")
            mainHandler.postDelayed(scanRestartRunnable, SCAN_PAUSE_MS)
            return
        }
        setState(BleState.Scanning)
        log("scanning for glasses…")
        mainHandler.postDelayed(scanWindowRunnable, SCAN_WINDOW_MS)
    }

    private fun stopScanQuietly() {
        if (!isScanning) return
        isScanning = false
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            mainHandler.post {
                if (!isScanning || gatt != null) return@post
                stopScanQuietly()
                mainHandler.removeCallbacks(scanWindowRunnable)
                deviceName = try { device.name } catch (_: Exception) { null } ?: "AIGlasses"
                log("found $deviceName [${device.address}] — connecting")
                setState(BleState.Connecting)
                startConnectWatchdog()
                try {
                    gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } catch (e: Exception) {
                    Log.e(TAG, "connectGatt failed", e)
                    log("connect failed: ${e.message}")
                    scheduleReconnect()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            mainHandler.post {
                isScanning = false
                log("scan failed (error $errorCode) — will retry")
                mainHandler.removeCallbacks(scanWindowRunnable)
                mainHandler.postDelayed(scanRestartRunnable, SCAN_PAUSE_MS)
            }
        }
    }

    /** Abandon a stalled connect attempt after 15 s and go back to scanning. */
    private fun startConnectWatchdog() {
        connectAttempt++
        val attempt = connectAttempt
        mainHandler.postDelayed({
            if (attempt == connectAttempt && !isConnectedFlag && shouldStayConnected) {
                log("connect attempt timed out — rescanning")
                teardownGatt()
                scheduleReconnect()
            }
        }, CONNECT_TIMEOUT_MS)
    }

    private fun scheduleReconnect() {
        if (!shouldStayConnected) return
        setState(BleState.Disconnected)
        mainHandler.removeCallbacks(scanRestartRunnable)
        mainHandler.postDelayed(scanRestartRunnable, RECONNECT_DELAY_MS)
    }

    private fun teardownGatt() {
        isConnectedFlag = false
        connectAttempt++                    // invalidates the connect watchdog
        resetPingLiveness()
        resetDownlink()
        micSeqValid = false
        receivingImage = false
        discardingVideoFrame = false
        synchronized(imageBuffer) { imageBuffer.reset() }
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        audioTxChar = null
        audioRxChar = null
        controlChar = null
        imageTxChar = null
    }

    private fun resetDownlink() {
        dlCancelled = true
        dlQueue.clear()
    }

    private fun setState(state: BleState) {
        onStateChange?.invoke(state)
    }

    // ── GATT callback ──

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            try {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Connected, requesting MTU")
                    // HIGH priority pulls the connection interval to the low
                    // end of the firmware's 7.5–15 ms range (throughput).
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    // 2M PHY (BLE 5): the firmware requests the same from its
                    // side; controllers negotiate, falling back to 1M.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        g.setPreferredPhy(
                            BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_OPTION_NO_PREFERRED
                        )
                    }
                    g.requestMtu(TARGET_MTU)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected (status=$status)")
                    val wasConnected = isConnectedFlag
                    mainHandler.post {
                        teardownGatt()
                        if (wasConnected) {
                            if (everConnected) reconnects.incrementAndGet()
                            log("BLE dropped (status=$status) — reconnecting")
                        }
                        scheduleReconnect()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onConnectionStateChange error", e)
            }
        }

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            // 1 = 1M, 2 = 2M, 3 = Coded. Confirms whether the 2M request stuck.
            if (status == BluetoothGatt.GATT_SUCCESS) {
                phyTx = txPhy
                phyRx = rxPhy
            }
            Log.i(TAG, "PHY updated: tx=$txPhy rx=$rxPhy status=$status (1=1M, 2=2M, 3=Coded)")
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            try {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    negotiatedMtu = mtu
                    Log.i(TAG, "MTU: $mtu")
                } else {
                    Log.w(TAG, "MTU negotiation failed (status=$status)")
                }
                g.discoverServices()
            } catch (e: Exception) {
                Log.e(TAG, "onMtuChanged error", e)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            try {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("service discovery failed — rescanning")
                    mainHandler.post { teardownGatt(); scheduleReconnect() }
                    return
                }
                val service = g.getService(SERVICE_UUID)
                if (service == null) {
                    log("voice service not found on peripheral — rescanning")
                    mainHandler.post { teardownGatt(); scheduleReconnect() }
                    return
                }
                audioTxChar = service.getCharacteristic(AUDIO_TX_UUID)
                audioRxChar = service.getCharacteristic(AUDIO_RX_UUID)
                controlChar = service.getCharacteristic(CONTROL_UUID)
                imageTxChar = service.getCharacteristic(IMAGE_TX_UUID)
                if (audioTxChar == null || audioRxChar == null || controlChar == null) {
                    log("missing BLE characteristics — rescanning")
                    mainHandler.post { teardownGatt(); scheduleReconnect() }
                    return
                }
                if (imageTxChar == null) {
                    log("IMAGE_TX (aa04) not found — photos unavailable")
                }
                // Enable notifications in sequence: AUDIO_TX → IMAGE_TX → CONTROL.
                g.setCharacteristicNotification(audioTxChar!!, true)
                val txDesc = audioTxChar!!.getDescriptor(CCCD_UUID)
                if (txDesc != null) {
                    @Suppress("DEPRECATION")
                    txDesc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(txDesc)
                } else {
                    enableControlNotifications(g)
                }
            } catch (e: Exception) {
                Log.e(TAG, "onServicesDiscovered error", e)
                log("service setup failed: ${e.message}")
                mainHandler.post { teardownGatt(); scheduleReconnect() }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            try {
                when (descriptor.characteristic.uuid) {
                    AUDIO_TX_UUID -> enableImageTxNotifications(g)
                    IMAGE_TX_UUID -> enableControlNotifications(g)
                    CONTROL_UUID -> finishSubscription()
                }
            } catch (e: Exception) {
                Log.e(TAG, "onDescriptorWrite error", e)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            // Fires for both write types (for no-response: when the stack can
            // accept the next write). We only use it as a "try again now" nudge.
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "onCharacteristicWrite status=$status for ${characteristic.uuid}")
            }
            synchronized(writeMonitor) { writeMonitor.notifyAll() }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            try {
                val data = characteristic.value
                if (data == null || data.isEmpty()) return
                statRxBytes.addAndGet(data.size.toLong())
                rxBytesTotal.addAndGet(data.size.toLong())
                when (characteristic.uuid) {
                    AUDIO_TX_UUID -> handleMicFrame(data)
                    IMAGE_TX_UUID -> handleImageTx(data)
                    CONTROL_UUID -> handleControl(data)
                }
            } catch (e: Exception) {
                Log.e(TAG, "onCharacteristicChanged error", e)
            }
        }
    }

    private fun enableImageTxNotifications(g: BluetoothGatt) {
        try {
            val imgChar = imageTxChar
            if (imgChar == null) {
                enableControlNotifications(g)
                return
            }
            g.setCharacteristicNotification(imgChar, true)
            val imgDesc = imgChar.getDescriptor(CCCD_UUID)
            if (imgDesc != null) {
                @Suppress("DEPRECATION")
                imgDesc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(imgDesc)
            } else {
                enableControlNotifications(g)
            }
        } catch (e: Exception) {
            Log.e(TAG, "enableImageTxNotifications error", e)
            enableControlNotifications(g)
        }
    }

    private fun enableControlNotifications(g: BluetoothGatt) {
        try {
            val ctrl = controlChar ?: return
            g.setCharacteristicNotification(ctrl, true)
            val ctrlDesc = ctrl.getDescriptor(CCCD_UUID)
            if (ctrlDesc != null) {
                @Suppress("DEPRECATION")
                ctrlDesc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(ctrlDesc)
            } else {
                finishSubscription()
            }
        } catch (e: Exception) {
            Log.e(TAG, "enableControlNotifications error", e)
            log("control notification setup failed — rescanning")
            mainHandler.post { teardownGatt(); scheduleReconnect() }
        }
    }

    private fun finishSubscription() {
        isConnectedFlag = true
        dlCancelled = false
        micSeqValid = false
        resetPingLiveness()
        val reconnected: Boolean
        // everConnected only touched on the main handler thread elsewhere;
        // set it via the handler to keep it single-threaded.
        reconnected = everConnected
        mainHandler.post {
            everConnected = true
            connectAttempt++                // cancels the connect watchdog
        }
        log("${if (reconnected) "RECONNECTED" else "connected"} to $deviceName — ATT MTU $negotiatedMtu")
        // Announce the voice protocol for this session. Always sent: the
        // firmware keeps its µ-law flag across connections, so an explicit
        // 'm' clears a stale realtime mode too.
        val marker = if (voiceModeWanted) 'M' else 'm'
        Thread({
            writeControlNoResponse(byteArrayOf(marker.code.toByte(), 0))
        }, "BleModeMarker").apply { isDaemon = true; start() }
        setState(BleState.Connected(deviceName, negotiatedMtu))
        onConnected?.invoke()
    }

    // ── Uplink: mic frames ──

    /**
     * Mirrors iOS BleManager.handleMicFrame: seq-filter stale duplicates
     * (delta 0 or ≥200 mod 256 — stale GATT-cache double-subscriptions
     * duplicate deliveries), zero-fill small gaps (1–8) so server VAD timing
     * stays sane, decode per OUR commanded mode (µ-law only while 'M' voice
     * mode is on — unconditional decoding turns PCM16 into garbage), and
     * forward PCM16 @16 kHz. Runs on the BLE binder thread.
     */
    private fun handleMicFrame(data: ByteArray) {
        if (data.size <= HEADER_SIZE || data[0] != 'A'.code.toByte()) return
        statMicRecv.incrementAndGet()
        val seq = data[1].toInt() and 0xFF
        val payload = data.copyOfRange(HEADER_SIZE, data.size)
        val pcm = if (voiceModeWanted) AudioCodec.ulawDecode(payload) else payload
        if (micSeqValid) {
            val delta = (seq - micLastSeq) and 0xFF
            if (delta == 0 || delta >= 200) {
                statMicDup.incrementAndGet()
                return
            }
            val gap = delta - 1
            if (gap in 1..8) {
                // Small real loss: zero-fill (sized in forwarded PCM16 bytes).
                statMicLost.addAndGet(gap)
                onMicAudio?.invoke(ByteArray(gap * pcm.size))
            } else if (gap > 8) {
                statMicLost.incrementAndGet()   // resync after a big jump, don't fill
            }
        }
        micLastSeq = seq
        micSeqValid = true
        statMicAccepted.incrementAndGet()
        onMicAudio?.invoke(pcm)
    }

    // ── CONTROL notifications ──

    private fun handleControl(data: ByteArray) {
        if (data.isEmpty()) return
        when (data[0].toInt().toChar()) {
            'S' -> {
                // Recording starts — firmware resets its mic seq to 0 at the
                // same instant, so the next frame re-anchors the tracker.
                micSeqValid = false
                Log.i(TAG, "glasses: recording started")
            }
            'E' -> Log.i(TAG, "glasses: recording ended")
            'X' -> {
                // Barge-in: user pressed the button during playback. The
                // glasses already tore down their speaker — stop sending.
                log("glasses: barge-in ('X') — aborting response audio")
                cancelResponse()
                onBargeIn?.invoke()
            }
            'P' -> handlePingEcho(data)
            'T' -> onStatsPacket?.invoke(data)
            'N' -> handleWifiInfoPacket(data)
            'V', 'W' -> {
                // Live video was removed; a stray marker from older firmware
                // is harmless — one log line and move on.
                log("legacy video marker '${data[0].toInt().toChar()}' — ignored (feature removed)")
            }
            'I' -> {
                // LEGACY (pre-in-band firmware): image header on CONTROL.
                // ['I'][flags][len u32 LE]
                val isVideoFrame = data.size >= 2 && data[1] == 0x01.toByte()
                val isVision = data.size >= 2 && data[1] == 0x02.toByte()
                val expected = if (data.size >= 6) readU32Le(data, 2) else 0
                startImageReceive(isVideoFrame, isVision, expected, legacy = true)
            }
            'J' -> {
                // LEGACY still-image end marker on CONTROL. Guarded so a
                // stray/duplicate 'J' can't emit an empty image.
                if (discardingVideoFrame) {
                    discardingVideoFrame = false
                    synchronized(imageBuffer) { imageBuffer.reset() }
                } else if (receivingImage) {
                    finishStillImage()
                }
            }
            else -> Log.d(TAG, "unknown control tag 0x${(data[0].toInt() and 0xFF).toString(16)}")
        }
    }

    /** 'N' answer to our 'F': "ssid\npass\nip\nport" (newline-separated ASCII). */
    private fun handleWifiInfoPacket(data: ByteArray) {
        val text = try { String(data, 1, data.size - 1, Charsets.UTF_8) } catch (_: Exception) { return }
        val parts = text.split("\n")
        if (parts.size < 4) {
            log("malformed 'N' WiFi info: $text")
            return
        }
        val port = parts[3].trim().toIntOrNull()
        if (port == null) {
            log("malformed 'N' WiFi port: ${parts[3]}")
            return
        }
        log("glasses WiFi AP: ${parts[0]} @ ${parts[2]}:$port")
        onWifiInfo?.invoke(parts[0], parts[1], parts[2].trim(), port)
    }

    // ── Images (in-band on IMAGE_TX) ──

    /**
     * The whole image path is in-band on IMAGE_TX — header, fragments, end
     * marker — whose notifications BLE delivers strictly in order, making
     * header/data races structurally impossible:
     *   'H' [flags][u32 LE size]  header — 0x00 photo, 0x02 vision,
     *                             0x01 legacy video (discarded with one log)
     *   'I' [seq][jpeg bytes]     data fragment
     *   'J' [frameIdx]            end of image
     */
    private fun handleImageTx(data: ByteArray) {
        if (data.isEmpty()) return
        when (data[0].toInt().toChar()) {
            'H' -> {
                if (data.size < 6) return
                val flags = data[1].toInt() and 0xFF
                startImageReceive(
                    isVideoFrame = flags == 0x01,
                    isVision = flags == 0x02,
                    expectedSize = readU32Le(data, 2),
                    legacy = false,
                )
                return
            }
            'J' -> {
                if (discardingVideoFrame) {
                    discardingVideoFrame = false
                    synchronized(imageBuffer) { imageBuffer.reset() }
                } else if (receivingImage) {
                    finishStillImage()
                }
                return
            }
        }
        if (data[0] != 'I'.code.toByte() || data.size <= HEADER_SIZE) return
        if (discardingVideoFrame) return   // legacy video payload — drop
        val payload = data.copyOfRange(HEADER_SIZE, data.size)
        val seq = data[1].toInt() and 0xFF
        if (!receivingImage) {
            // Recover a dropped 'H' header: fragments always start at seq 0,
            // so a seq-0 fragment with no open transfer means the header was
            // lost — open an implicit transfer (JPEG SOI still guards
            // integrity). A non-zero seq is a genuine mid-stream orphan.
            if (seq == 0) {
                startImageReceive(isVideoFrame = false, isVision = false, expectedSize = 0, legacy = false)
                log("image header missed — recovering from seq-0 fragment")
            } else {
                Log.w(TAG, "Stray image fragment (${payload.size} B, seq $seq) — dropped")
                return
            }
        }
        if (seq != imageSeqExpected) {
            val gap = (seq - imageSeqExpected) and 0xFF
            imageSeqGaps += gap
            imageSeqGapsTotal.addAndGet(gap)
            log("image SEQ gap: expected $imageSeqExpected got $seq (~$gap lost)")
        }
        imageSeqExpected = (seq + 1) and 0xFF
        synchronized(imageBuffer) { imageBuffer.write(payload) }
    }

    private fun startImageReceive(isVideoFrame: Boolean, isVision: Boolean, expectedSize: Int, legacy: Boolean) {
        // Live video was removed; a video-flagged transfer from older
        // firmware is swallowed whole (header + fragments + end), one log.
        discardingVideoFrame = isVideoFrame
        receivingImage = !isVideoFrame
        pendingImageIsVision = isVision
        expectedImageSize = expectedSize
        imageSeqExpected = 0
        imageSeqGaps = 0
        imageStartMs = System.currentTimeMillis()
        synchronized(imageBuffer) { imageBuffer.reset() }
        if (isVideoFrame) {
            if (!loggedLegacyVideo) {
                loggedLegacyVideo = true
                log("legacy video frame from firmware — discarding (feature removed)")
            }
        } else {
            log("photo incoming${if (legacy) " (legacy header)" else ""}: $expectedSize B expected")
        }
    }

    private fun finishStillImage() {
        receivingImage = false
        val jpeg: ByteArray
        synchronized(imageBuffer) {
            jpeg = imageBuffer.toByteArray()
            imageBuffer.reset()
        }
        val isVision = pendingImageIsVision
        pendingImageIsVision = false
        // Integrity: JPEG SOI marker (FF D8). A seq gap already corrupted it.
        if (jpeg.size < 2 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) {
            log("photo REJECTED: ${jpeg.size} B, missing JPEG SOI (seqGaps=$imageSeqGaps)")
            return
        }
        val elapsed = System.currentTimeMillis() - imageStartMs
        val sizeNote = if (expectedImageSize > 0 && jpeg.size != expectedImageSize)
            " (SIZE MISMATCH, expected $expectedImageSize)" else ""
        val kbs = if (elapsed > 0) jpeg.size * 1000.0 / elapsed / 1024.0 else 0.0
        log("${if (isVision) "vision" else "photo"} complete: ${jpeg.size} B in $elapsed ms " +
            "(${String.format("%.1f", kbs)} KB/s, seqGaps=$imageSeqGaps)$sizeNote")
        onPhotoStats?.invoke(jpeg.size, elapsed)
        if (isVision) onVisionPhoto?.invoke(jpeg) else onPhoto?.invoke(jpeg)
    }

    // ── Downlink: paced response audio ──

    private fun ensureDlThread() {
        synchronized(dlQueue) {
            if (dlThread?.isAlive == true) return
            dlThread = Thread({ downlinkLoop() }, "BleDownlink").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * Drains the response-audio queue while the link is up. Per response
     * (mirrors iOS drainLoop / realtime_ble.py):
     *   first chunk   → 'S' marker (write-with-response, retried), seq +
     *                   token bucket reset
     *   each chunk    → ['A'][seq][µ-law] on AUDIO_RX, WRITE_NO_RESPONSE with
     *                   queue-busy retry, DL_BURST burst then DL_BPS sustained
     *   End sentinel  → 'E' marker (+30 ms), suppressed after a barge-in
     */
    private fun downlinkLoop() {
        var respOpen = false
        var seq = 0
        var dlT0 = 0L
        var dlSent = 0L
        try {
            while (isConnectedFlag) {
                val item = dlQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                when (item) {
                    is DlItem.Audio -> {
                        if (!respOpen) {
                            if (dlCancelled) continue   // stragglers of a cancelled response
                            // Without 'S' the firmware ignores every audio
                            // write — abort rather than stream into the void.
                            if (!writeControlWithResponse(byteArrayOf('S'.code.toByte(), 0))) {
                                log("playback start marker failed — response dropped")
                                continue
                            }
                            respOpen = true
                            seq = 0
                            dlT0 = System.currentTimeMillis()
                            dlSent = 0
                        }
                        if (dlCancelled) continue
                        val rx = audioRxChar ?: continue
                        val g = gatt ?: continue
                        // µ-law is 1 byte/sample — no even-byte rounding needed,
                        // but the firmware RX buffer is BLE_MAX_PAYLOAD = 506:
                        // at MTU 512 the naive MTU-3-2 = 507 would silently
                        // truncate the last sample of every full fragment.
                        val maxPayload = (negotiatedMtu - 3 - HEADER_SIZE).coerceIn(20, 506)
                        var off = 0
                        while (off < item.ulaw.size && isConnectedFlag && !dlCancelled) {
                            val frag = minOf(maxPayload, item.ulaw.size - off)
                            // Token bucket: DL_BURST up front, then DL_BPS.
                            while (dlSent + frag > DL_BURST +
                                (System.currentTimeMillis() - dlT0) * DL_BPS / 1000 &&
                                isConnectedFlag && !dlCancelled
                            ) {
                                Thread.sleep(10)
                            }
                            if (!isConnectedFlag || dlCancelled) break
                            val pkt = ByteArray(HEADER_SIZE + frag)
                            pkt[0] = 'A'.code.toByte()
                            pkt[1] = (seq and 0xFF).toByte()
                            System.arraycopy(item.ulaw, off, pkt, HEADER_SIZE, frag)
                            @Suppress("DEPRECATION")
                            rx.value = pkt
                            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            // Queue-busy retry: a false return is a SILENT
                            // drop → seq gap → crackle on the glasses.
                            var queued = false
                            var attempts = 0
                            while (!queued && attempts < 100 && isConnectedFlag && !dlCancelled) {
                                @Suppress("DEPRECATION")
                                queued = g.writeCharacteristic(rx)
                                if (!queued) {
                                    Thread.sleep(2)
                                    attempts++
                                }
                            }
                            if (queued) {
                                statTxBytes.addAndGet(pkt.size.toLong())
                                txBytesTotal.addAndGet(pkt.size.toLong())
                            } else {
                                Log.w(TAG, "Response BLE write dropped (seq=$seq)")
                            }
                            seq++
                            off += frag
                            dlSent += frag
                        }
                    }
                    is DlItem.End -> {
                        if (respOpen && !dlCancelled && isConnectedFlag) {
                            Thread.sleep(30)
                            writeControlWithResponse(byteArrayOf('E'.code.toByte(), 0))
                            log("→ 'E' response end ($dlSent B sent)")
                        }
                        // After a barge-in, deliberately no 'E' — a late 'E'
                        // could land after the 'S' of the next response.
                        respOpen = false
                        if (dlQueue.isEmpty()) dlCancelled = false
                    }
                }
            }
        } catch (_: InterruptedException) {
            // teardown
        } catch (e: Exception) {
            Log.e(TAG, "Downlink error", e)
        }
    }

    // ── Control writes ──

    /**
     * Control marker via write-WITH-response + queue-busy retry, for the
     * critical 'S'/'E' stream markers. writeCharacteristic() returns false
     * while another GATT op holds the single pending slot — ignoring that
     * silently drops the marker (a dropped 'S' means the glasses never arm
     * playback; a dropped 'E' used to soft-lock them in PLAYING).
     */
    private fun writeControlWithResponse(bytes: ByteArray): Boolean =
        writeControlInternal(bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)

    /**
     * Control marker via write-WITHOUT-response, same retry. Used for
     * 'M'/'m'/'P'/'F'/'f': a response PDU can fail with ATT "Insufficient
     * Resource" while the server's buffers are full of mic notifications.
     * ATT is sequential, so ordering versus audio writes still holds.
     */
    private fun writeControlNoResponse(bytes: ByteArray): Boolean =
        writeControlInternal(bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)

    private fun writeControlInternal(bytes: ByteArray, writeType: Int): Boolean {
        val ctrl = controlChar ?: return false
        val g = gatt ?: return false
        synchronized(controlLock) {
            @Suppress("DEPRECATION")
            ctrl.value = bytes
            ctrl.writeType = writeType
            var attempts = 0
            while (attempts < 15 && isConnectedFlag) {
                @Suppress("DEPRECATION")
                if (g.writeCharacteristic(ctrl)) {
                    statTxBytes.addAndGet(bytes.size.toLong())
                    txBytesTotal.addAndGet(bytes.size.toLong())
                    return true
                }
                // writeCharacteristic == false means a GATT op is in flight.
                // Wait for onCharacteristicWrite to signal completion instead
                // of blind-spinning — wakes in ~one connection interval, and
                // the 20 ms cap bounds a wedged stack.
                synchronized(writeMonitor) {
                    try { writeMonitor.wait(20) } catch (_: InterruptedException) { return false }
                }
                attempts++
            }
        }
        Log.e(TAG, "Control write '${bytes[0].toInt().toChar()}' FAILED after retries")
        return false
    }

    // ── Helpers ──

    private fun readU32Le(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8) or
            ((data[at + 2].toInt() and 0xFF) shl 16) or ((data[at + 3].toInt() and 0xFF) shl 24)

    private fun log(line: String) {
        Log.i(TAG, line)
        onLog?.invoke(line)
    }
}
