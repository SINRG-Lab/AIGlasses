package com.example.aiglasses.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The WiFi bulk lane (docs/WIFI_LINK.md): the glasses run a SoftAP + TCP
 * server; Android joins via a LOCAL-ONLY network request — the correct
 * "Wi-Fi Direct experience" for hardware without P2P support:
 *
 *   WifiNetworkSpecifier(ssid, wpa2 pass) inside a NetworkRequest with
 *   TRANSPORT_WIFI + removeCapability(NET_CAPABILITY_INTERNET)
 *   → ConnectivityManager.requestNetwork → one-time system device-approval
 *   dialog → onAvailable(network) → TCP socket created VIA
 *   network.socketFactory (mandatory: binds the socket to the local-only
 *   network; all other traffic incl. the OpenAI websocket keeps its normal
 *   cellular/WiFi routing). Never bindProcessToNetwork.
 *
 * Wire format, both directions: [channel u8][len u16 LE][inner packet],
 * inner packets byte-identical to the matching BLE characteristic payload:
 *   ch 1 AUDIO   — legacy; voice rides BLE, tolerated + dropped
 *   ch 2 CONTROL — 'R' hello, 'P' ping echo, 'T' fw stats, 'K' keepalive out
 *   ch 3 IMAGE   — 'H' header, 'I' fragments, 'J' end (ordered stream: no
 *                  seq gaps, no header races — parser is simpler than BLE's)
 *
 * The socket is up only after the firmware's 'R' hello. Liveness: 'P' every
 * LinkManager tick; 3 outstanding misses (or the firmware's own 10 s ping
 * watchdog) drops the socket. The network callback stays registered while
 * connected — unregistering releases the network.
 */
class WifiLinkClient(private val context: Context) {

    companion object {
        private const val TAG = "WifiLinkClient"

        // Frame channels (must match wifi_link.h)
        private const val CH_AUDIO = 1
        private const val CH_CONTROL = 2
        private const val CH_IMAGE = 3

        /** Max inner packet per frame (WIFI_FRAME_MAX); larger = stream desync. */
        private const val FRAME_MAX = 4096

        private const val DIAL_TIMEOUT_MS = 8_000
        private const val HELLO_TIMEOUT_MS = 6_000L
        // Covers the system approval dialog + AP association; expiry fires
        // onUnavailable, which feeds the LinkManager redial loop.
        private const val APPROVAL_TIMEOUT_MS = 45_000
        // Anti-doze keepalive: phones aggressively power-save the WiFi radio
        // on internet-less APs; the radio stays awake while TRANSMITTING, so
        // trickle a tiny 'K' frame (app → glasses, no reply).
        private const val KEEPALIVE_MS = 150L
        private const val PING_MISS_LIMIT = 3
    }

    /** Per-tick metrics snapshot handed to LinkManager (2 s window). */
    data class Snapshot(
        val transport: TransportMetrics,
        val connects: Int,
        val socketUptimeMs: Long,
    )

    // ── Callbacks (wired once by LinkManager) ──
    var onNetworkAvailable: (() -> Unit)? = null           // approval done, dialing next
    var onHello: (() -> Unit)? = null                      // 'R' seen — lane ACTIVE
    /** Lane went down. wasActive = it had said hello; reason for the log/UI. */
    var onDown: ((Boolean, String) -> Unit)? = null
    var onPhoto: ((ByteArray) -> Unit)? = null
    var onVisionPhoto: ((ByteArray) -> Unit)? = null
    var onPhotoStats: ((Int, Long) -> Unit)? = null        // (bytes, millis)
    var onStatsPacket: ((ByteArray) -> Unit)? = null       // raw 'T' packet incl. tag
    var onLog: ((String) -> Unit)? = null

    // ── State ──
    private val connectivityManager: ConnectivityManager? =
        try { context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager }
        catch (_: Exception) { null }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private var generation = 0                              // guarded by stateLock
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var helloSeen = false
    @Volatile private var connectedAtMs = 0L
    private val writeLock = Any()

    // Image reassembly (reader thread only)
    private var receivingImage = false
    private var discardingVideoFrame = false
    private var pendingImageIsVision = false
    private var expectedImageSize = 0
    private var imageBuffer = java.io.ByteArrayOutputStream()
    private var imageStartMs = 0L
    private var loggedLegacyAudio = false

    // Ping / RTT (guarded by pingLock)
    private val pingLock = Any()
    private var pingSeq = 0
    private val pingSent = HashMap<Int, Long>()
    private var missedPings = 0
    private var rttLast = 0.0
    private var rttSum = 0.0
    private var rttCount = 0
    private var rttMin = 0.0
    private var rttMax = 0.0
    private var pingsSentTotal = 0
    private var pingsLostTotal = 0

    // Rolling stat counters
    private val statRxBytes = AtomicLong(0)
    private val statTxBytes = AtomicLong(0)
    private val rxBytesTotal = AtomicLong(0)
    private val txBytesTotal = AtomicLong(0)
    private val connects = AtomicInteger(0)

    val isConnected: Boolean get() = helloSeen && socket != null

    // ── Public API ──

    /**
     * One full bring-up attempt: request the local-only network, then dial
     * the socket via its socketFactory. Any failure (declined dialog, AP out
     * of range, dial timeout, no hello) reports through [onDown] exactly once.
     */
    fun start(ssid: String, pass: String, host: String, port: Int) {
        stopInternal()
        // Early failures (nothing registered yet) must notify directly:
        // teardown() has nothing to clean, so down() would stay silent.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onDown?.invoke(false, "Wi-Fi auto-join needs Android 10+ — photos stay on Bluetooth")
            return
        }
        val cm = connectivityManager
        if (cm == null) {
            onDown?.invoke(false, "ConnectivityManager unavailable")
            return
        }
        val gen: Int
        synchronized(stateLock) { gen = ++generation }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!isCurrent(gen)) return
                log("local-only network up — dialing $host:$port")
                onNetworkAvailable?.invoke()
                Thread({ dialAndRead(network, host, port, gen) }, "WifiLinkSocket")
                    .apply { isDaemon = true; start() }
            }

            override fun onUnavailable() {
                if (!isCurrent(gen)) return
                down(false, "network unavailable (approval declined, timed out, or AP out of range)")
            }

            override fun onLost(network: Network) {
                if (!isCurrent(gen)) return
                down(helloSeen, "Wi-Fi network lost")
            }
        }
        try {
            cm.requestNetwork(buildRequest(ssid, pass), cb, APPROVAL_TIMEOUT_MS)
            synchronized(stateLock) { networkCallback = cb }
            log("requesting local-only Wi-Fi \"$ssid\" — approve the system dialog if it appears")
        } catch (e: SecurityException) {
            onDown?.invoke(false, "Wi-Fi request not permitted (missing CHANGE_NETWORK_STATE / NEARBY_WIFI_DEVICES?): ${e.message}")
        } catch (e: Exception) {
            onDown?.invoke(false, "Wi-Fi request failed: ${e.message}")
        }
    }

    /** Tear down without an [onDown] notification (user/toggle initiated). */
    fun stop() {
        stopInternal()
    }

    /** Raw control packet on the socket ('f', …) — fire and forget. */
    fun sendControl(bytes: ByteArray) {
        sendFrame(CH_CONTROL, bytes)
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
            connects = connects.get(),
            socketUptimeMs = if (isConnected) System.currentTimeMillis() - connectedAtMs else 0L,
        )
    }

    // ── Network request (API 29+) ──

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildRequest(ssid: String, pass: String): NetworkRequest {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(pass)
            .build()
        return NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
    }

    // ── Socket dial + read loop (dedicated thread) ──

    private fun dialAndRead(network: Network, host: String, port: Int, gen: Int) {
        val s: Socket
        try {
            // MANDATORY: create the socket via the network's factory so it is
            // bound to the local-only network — the process default network
            // (cellular/infra WiFi) keeps carrying everything else.
            s = network.socketFactory.createSocket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), DIAL_TIMEOUT_MS)
        } catch (e: Exception) {
            if (isCurrent(gen)) down(false, "dial failed: ${e.message}")
            return
        }
        synchronized(stateLock) {
            if (generation != gen) {
                try { s.close() } catch (_: Exception) {}
                return
            }
            socket = s
            output = try { BufferedOutputStream(s.getOutputStream()) } catch (_: Exception) { null }
        }
        if (output == null) {
            down(false, "socket streams unavailable")
            return
        }
        log("socket up — waiting for glasses hello")
        // Hello watchdog: a socket that opens but never says 'R' is not our
        // firmware (or the AP handed us a stale connection) — give up loudly.
        mainHandler.postDelayed({
            if (isCurrent(gen) && !helloSeen) {
                down(false, "no hello from glasses within ${HELLO_TIMEOUT_MS / 1000} s")
            }
        }, HELLO_TIMEOUT_MS)

        // Reset per-connection reassembly state (this thread owns it).
        receivingImage = false
        discardingVideoFrame = false
        imageBuffer.reset()

        try {
            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            val header = ByteArray(3)
            while (isCurrent(gen)) {
                input.readFully(header)
                val channel = header[0].toInt() and 0xFF
                val len = (header[1].toInt() and 0xFF) or ((header[2].toInt() and 0xFF) shl 8)
                if (len > FRAME_MAX) {
                    // No way to resync a byte stream mid-flight; reconnecting
                    // takes milliseconds.
                    down(helloSeen, "oversized frame ($len B) — protocol desync")
                    return
                }
                val packet = ByteArray(len)
                if (len > 0) input.readFully(packet)
                statRxBytes.addAndGet((len + 3).toLong())
                rxBytesTotal.addAndGet((len + 3).toLong())
                handlePacket(channel, packet, gen)
            }
        } catch (e: EOFException) {
            if (isCurrent(gen)) down(helloSeen, "glasses closed the socket")
        } catch (e: Exception) {
            if (isCurrent(gen)) down(helloSeen, "socket error: ${e.message}")
        }
    }

    // ── Frame handling (reader thread) ──

    private fun handlePacket(channel: Int, packet: ByteArray, gen: Int) {
        if (packet.isEmpty()) return
        when (channel) {
            CH_AUDIO -> {
                // Voice rides BLE in the V2 policy. Tolerate a stray frame
                // from older firmware — one log line, drop it.
                if (!loggedLegacyAudio) {
                    loggedLegacyAudio = true
                    log("audio frame on Wi-Fi (legacy firmware?) — ignoring; voice rides BLE")
                }
            }
            CH_CONTROL -> handleControl(packet, gen)
            CH_IMAGE -> handleImage(packet, gen)
        }
    }

    private fun handleControl(packet: ByteArray, gen: Int) {
        when (packet[0].toInt().toChar()) {
            'R' -> {
                // Hello: the link is real. Second byte = protocol version.
                val version = if (packet.size > 1) packet[1].toInt() and 0xFF else 0
                helloSeen = true
                connectedAtMs = System.currentTimeMillis()
                connects.incrementAndGet()
                synchronized(pingLock) { missedPings = 0 }
                log("glasses hello (protocol v$version) — Wi-Fi bulk lane ACTIVE")
                startKeepalive(gen)
                onHello?.invoke()
            }
            'P' -> handlePingEcho(packet)
            'T' -> onStatsPacket?.invoke(packet)
            'V', 'W' -> log("legacy video marker '${packet[0].toInt().toChar()}' on Wi-Fi — ignored")
            else -> Log.d(TAG, "control 0x${(packet[0].toInt() and 0xFF).toString(16)} on Wi-Fi")
        }
    }

    /**
     * Same inner packets as BLE IMAGE_TX, but one ordered TCP stream: no
     * duplicate delivery, no seq gaps, no header/fragment races — seq bytes
     * are kept for format compatibility only and not checked.
     */
    private fun handleImage(packet: ByteArray, gen: Int) {
        // The reassembly fields are shared class state but logically owned by
        // the CURRENT reader generation: a superseded reader that is still
        // draining a packet must not corrupt the new connection's photo.
        if (!isCurrent(gen)) return
        when (packet[0].toInt().toChar()) {
            'H' -> {
                if (packet.size < 6) return
                val flags = packet[1].toInt() and 0xFF
                discardingVideoFrame = flags == 0x01     // legacy live video — drop
                receivingImage = !discardingVideoFrame
                pendingImageIsVision = flags == 0x02
                expectedImageSize = (packet[2].toInt() and 0xFF) or
                    ((packet[3].toInt() and 0xFF) shl 8) or
                    ((packet[4].toInt() and 0xFF) shl 16) or
                    ((packet[5].toInt() and 0xFF) shl 24)
                imageBuffer.reset()
                imageStartMs = System.currentTimeMillis()
                if (receivingImage) log("photo incoming on Wi-Fi: $expectedImageSize B expected")
            }
            'I' -> {
                if (packet.size <= 2 || !receivingImage) return
                imageBuffer.write(packet, 2, packet.size - 2)
            }
            'J' -> {
                if (discardingVideoFrame) {
                    discardingVideoFrame = false
                    imageBuffer.reset()
                } else if (receivingImage) {
                    receivingImage = false
                    val jpeg = imageBuffer.toByteArray()
                    imageBuffer.reset()
                    val isVision = pendingImageIsVision
                    pendingImageIsVision = false
                    if (jpeg.size < 2 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) {
                        log("photo REJECTED: ${jpeg.size} B, missing JPEG SOI")
                        return
                    }
                    val elapsed = System.currentTimeMillis() - imageStartMs
                    val kbs = if (elapsed > 0) jpeg.size * 1000.0 / elapsed / 1024.0 else 0.0
                    log("${if (isVision) "vision" else "photo"} complete on Wi-Fi: ${jpeg.size} B " +
                        "in $elapsed ms (${String.format("%.1f", kbs)} KB/s)")
                    onPhotoStats?.invoke(jpeg.size, elapsed)
                    if (isVision) onVisionPhoto?.invoke(jpeg) else onPhoto?.invoke(jpeg)
                }
            }
        }
    }

    // ── Ping / liveness ──

    private fun checkLivenessAndPing() {
        if (!isConnected) {
            synchronized(pingLock) {
                pingSent.clear()
                missedPings = 0
                rttLast = 0.0
            }
            return
        }
        var dead = false
        val pkt: ByteArray
        val id: Int
        synchronized(pingLock) {
            // The previous ping is due back well before the next one goes out
            // (RTT on a working link is ms). Still outstanding = missed.
            if (pingSent.isNotEmpty()) {
                missedPings += pingSent.size
                pingsLostTotal += pingSent.size
                pingSent.clear()
                if (missedPings >= PING_MISS_LIMIT) {
                    missedPings = 0
                    dead = true
                }
            } else {
                missedPings = 0
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
        if (dead) {
            log("$PING_MISS_LIMIT pings unanswered — Wi-Fi link is dead, dropping the socket")
            down(true, "3 pings unanswered")
            return
        }
        if (sendFrame(CH_CONTROL, pkt)) {
            synchronized(pingLock) { pingSent[id] = System.currentTimeMillis() }
        }
    }

    private fun handlePingEcho(packet: ByteArray) {
        if (packet.size < 5) return
        val id = (packet[1].toInt() and 0xFF) or ((packet[2].toInt() and 0xFF) shl 8) or
            ((packet[3].toInt() and 0xFF) shl 16) or ((packet[4].toInt() and 0xFF) shl 24)
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

    // ── Keepalive ──

    private fun startKeepalive(gen: Int) {
        Thread({
            try {
                while (isCurrent(gen) && isConnected) {
                    sendFrame(CH_CONTROL, byteArrayOf('K'.code.toByte()))
                    Thread.sleep(KEEPALIVE_MS)
                }
            } catch (_: InterruptedException) {
                // teardown
            }
        }, "WifiKeepalive").apply { isDaemon = true; start() }
    }

    // ── Send path ──

    private fun sendFrame(channel: Int, packet: ByteArray): Boolean {
        if (packet.size > FRAME_MAX) return false
        val out = output ?: return false
        try {
            synchronized(writeLock) {
                out.write(channel)
                out.write(packet.size and 0xFF)
                out.write((packet.size shr 8) and 0xFF)
                out.write(packet)
                out.flush()
            }
        } catch (e: Exception) {
            down(helloSeen, "write failed: ${e.message}")
            return false
        }
        statTxBytes.addAndGet((packet.size + 3).toLong())
        txBytesTotal.addAndGet((packet.size + 3).toLong())
        return true
    }

    // ── Teardown ──

    private fun isCurrent(gen: Int): Boolean = synchronized(stateLock) { generation == gen }

    /** Lane failed/died: clean up and notify LinkManager exactly once. */
    private fun down(wasActive: Boolean, reason: String) {
        if (!teardown()) return   // an earlier down/stop already handled it
        onDown?.invoke(wasActive, reason)
    }

    private fun stopInternal() {
        teardown()
    }

    /** Returns true if there was anything to tear down (dedupes callbacks). */
    private fun teardown(): Boolean {
        val s: Socket?
        val cb: ConnectivityManager.NetworkCallback?
        synchronized(stateLock) {
            if (socket == null && networkCallback == null) return false
            generation++             // invalidates callbacks, threads, watchdogs
            s = socket
            cb = networkCallback
            socket = null
            output = null
            networkCallback = null
        }
        helloSeen = false
        connectedAtMs = 0L
        synchronized(pingLock) {
            pingSent.clear()
            missedPings = 0
            rttLast = 0.0
        }
        try { s?.close() } catch (_: Exception) {}
        if (cb != null) {
            // Spec: unregister on socket death (a held request would pin a
            // possibly-stale network); the redial loop re-requests. Android
            // remembers the user's approval, so no second dialog normally.
            try { connectivityManager?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        return true
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        onLog?.invoke(line)
    }
}
