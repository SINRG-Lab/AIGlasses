package com.example.aiglasses

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * BLE Central (GATT client) that connects to the ESP32-S3 BLE Peripheral.
 *
 * Protocol:
 *   Audio TX (NOTIFY):  ESP32 mic → Android   [TAG][SEQ][PCM]
 *   Audio RX (WRITE):   Android TTS → ESP32   [TAG][SEQ][PCM]
 *   Control  (WRITE+NOTIFY): 'E' end, 'S' start markers
 */
@SuppressLint("MissingPermission")
class BleVoiceService(
    private val context: Context,
    private val pipeline: VoiceAssistantPipeline,
    private val onEvent: (BleEvent) -> Unit
) {
    companion object {
        private const val TAG = "BleVoiceService"

        private val SERVICE_UUID = UUID.fromString("0000aa00-1234-5678-abcd-0e5032c6b1e0")
        private val AUDIO_TX_UUID = UUID.fromString("0000aa01-1234-5678-abcd-0e5032c6b1e0")
        private val AUDIO_RX_UUID = UUID.fromString("0000aa02-1234-5678-abcd-0e5032c6b1e0")
        private val CONTROL_UUID = UUID.fromString("0000aa03-1234-5678-abcd-0e5032c6b1e0")
        private val IMAGE_TX_UUID = UUID.fromString("0000aa04-1234-5678-abcd-0e5032c6b1e0")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val TARGET_MTU = 512
        private const val MIN_AUDIO_BYTES = 16000 * 2
        private const val SCAN_TIMEOUT_MS = 20000L
        private const val HEADER_SIZE = 2
        // A photo taken standalone is only attached to a question asked within
        // this window. Older photos are considered stale and the question runs
        // voice-only. (Bundled vision via double-tap+hold lands well inside this.)
        private const val VISION_WINDOW_MS = 5000L

        // ── Realtime (GPT speech-to-speech) mode — values validated on hardware
        //    by HardwareTest/realtime_ble.py ──
        // Downlink token bucket: µ-law @24 kHz consumes 24000 B/s on the glasses;
        // burst fills the firmware prebuffer fast, then ~1.35× realtime sustains it.
        private const val RT_DL_BURST = 24L * 1024L
        private const val RT_DL_BPS = 32400L          // 1.35 × 24000 B/s of µ-law
        // Button released → mic frames stop. After this quiet gap, feed the server
        // VAD 600 ms of silence once so it closes the turn (it must HEAR silence).
        private const val RT_SILENCE_AFTER_MS = 250L
        private const val RT_SILENCE_BYTES = 2 * 24000 * 6 / 10   // 600 ms PCM16 @24 kHz
    }

    sealed class BleEvent {
        data object ScanStarted : BleEvent()
        data object ScanStopped : BleEvent()
        data class DeviceFound(val name: String, val address: String) : BleEvent()
        data class Connected(val name: String) : BleEvent()
        data object Disconnected : BleEvent()
        data class MtuNegotiated(val mtu: Int) : BleEvent()
        data class ReceivingAudio(val chunks: Int, val bytes: Int) : BleEvent()
        data object ProcessingStarted : BleEvent()
        data class SendingAudio(val totalBytes: Int) : BleEvent()
        data class AudioSent(val totalBytes: Int) : BleEvent()
        data class ImageReceived(val jpegBytes: ByteArray) : BleEvent()
        data class VideoReceived(val frames: List<ByteArray>) : BleEvent()
        data object PlaybackCancelled : BleEvent()
        data class Error(val message: String) : BleEvent()
    }

    private val bluetoothManager: BluetoothManager? =
        try { context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager }
        catch (_: Exception) { null }
    private val bluetoothAdapter: BluetoothAdapter? =
        try { bluetoothManager?.adapter } catch (_: Exception) { null }
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var audioTxChar: BluetoothGattCharacteristic? = null
    private var audioRxChar: BluetoothGattCharacteristic? = null
    private var controlChar: BluetoothGattCharacteristic? = null
    private var imageTxChar: BluetoothGattCharacteristic? = null

    private var negotiatedMtu = 23
    private val audioChunks = mutableListOf<ByteArray>()

    @Volatile private var isConnected = false
    @Volatile private var isScanning = false
    // Set when the glasses send 'X' (user pressed the button during playback —
    // barge-in on V2 firmware). Aborts the TTS transmit loop so we don't keep
    // streaming audio into a ring buffer nobody is playing, and so the leftover
    // stream can't fight a new utterance for BLE airtime.
    @Volatile private var ttsCancelled = false

    // Image reassembly state
    private val imageBuffer = ByteArrayOutputStream()
    @Volatile private var receivingImage = false
    private var pendingJpeg: ByteArray? = null
    private var pendingJpegTime = 0L   // when the pending photo was received (for the 5s window)

    // Performance measurement state
    private var perfImageTxStartMs  = 0L   // M17: image transfer start time
    private var perfExpectedImageSize = 0   // M24: expected bytes from 'I' marker
    private var perfImagePacketCount = 0    // M24: fragment count
    private var perfImageExpectedSeq = 0    // M24: next expected fragment seq (0-255 wrap)
    private var perfImageSeqGaps     = 0    // M24: dropped image fragments detected
    private var perfAudioRxStartMs   = 0L   // M11: audio receive start
    private var perfAudioExpectedSeq = -1   // next expected mic-chunk seq (-1 = fresh utterance)
    private var perfAudioSeqGaps     = 0    // dropped mic fragments detected

    // Video reassembly state
    @Volatile private var receivingVideo = false
    @Volatile private var receivingVideoFrame = false
    private val videoFrames = mutableListOf<ByteArray>()
    private val currentVideoFrame = ByteArrayOutputStream()
    private var expectedVideoFrameSize = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Realtime (GPT speech-to-speech) mode state ──
    // When enabled, mic 'A' frames carry µ-law @16 kHz and stream live to the
    // RealtimeVoiceClient instead of accumulating for the legacy pipeline, and
    // response audio streams back as µ-law @24 kHz. Photo/video handling is
    // untouched. The client reference is set by MainViewModel.
    @Volatile private var realtimeMode = false
    @Volatile private var realtimeClient: RealtimeVoiceClient? = null
    @Volatile private var rtMicSeqValid = false
    @Volatile private var rtMicLastSeq = 0
    @Volatile private var rtTalking = false
    private val rtMicConditioner = MicConditioner()   // BLE notify thread only
    private val rtDownQueue = java.util.concurrent.LinkedBlockingQueue<RtDown>()
    private var rtDownThread: Thread? = null          // guarded by rtDownQueue

    private sealed class RtDown {
        class Audio(val ulaw: ByteArray, val first: Boolean) : RtDown()
        data object End : RtDown()
    }

    // Push-to-talk release: frames stop, but server VAD needs to HEAR silence
    // to close the turn — feed it 600 ms of zeros once, then go quiet.
    private val rtSilenceRunnable = Runnable {
        if (rtTalking) {
            rtTalking = false
            realtimeClient?.appendAudio(ByteArray(RT_SILENCE_BYTES))
        }
    }

    // ── Public API ──

    fun startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            onEvent(BleEvent.Error("Bluetooth is not enabled"))
            return
        }
        scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            onEvent(BleEvent.Error("BLE scanner not available"))
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        isScanning = true
        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "startScan failed", e)
            isScanning = false
            onEvent(BleEvent.Error("Scan failed: ${e.message}"))
            return
        }
        onEvent(BleEvent.ScanStarted)
        Log.i(TAG, "BLE scan started")

        mainHandler.postDelayed({
            if (isScanning) {
                stopScan()
                onEvent(BleEvent.Error("Scan timeout — ESP32 not found. Is it powered on?"))
            }
        }, SCAN_TIMEOUT_MS)
    }

    fun stopScan() {
        if (!isScanning) return
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        isScanning = false
        onEvent(BleEvent.ScanStopped)
    }

    /** Wire (or clear) the GPT Realtime client used when realtime mode is on. */
    fun setRealtimeClient(client: RealtimeVoiceClient?) {
        realtimeClient = client
    }

    /**
     * Toggle realtime (µ-law) voice mode. Tells the firmware with 'M'/'m' on
     * CONTROL when connected; also sent on every connect (the glasses keep
     * sVoiceUlaw across connections, so an explicit marker un-sticks a stale
     * mode from a previous session).
     */
    fun setRealtimeMode(enabled: Boolean) {
        if (realtimeMode == enabled) return
        realtimeMode = enabled
        if (!enabled) resetRealtimeStreams()
        if (isConnected) {
            Thread {
                writeControlMarkerNoResponse(if (enabled) 'M' else 'm')
            }.apply { isDaemon = true; start() }
        }
    }

    /**
     * Queue a 24 kHz PCM16 response chunk for the glasses. Called by the
     * realtime event handler directly on the WebSocket reader thread — this
     * only µ-law-encodes and enqueues; the paced BLE writes happen on the
     * dedicated downlink thread.
     */
    fun queueRealtimeAudio(pcm24k: ByteArray, first: Boolean) {
        if (!realtimeMode || !isConnected || pcm24k.isEmpty()) return
        rtDownQueue.offer(RtDown.Audio(AudioCodec.ulawEncode(pcm24k), first))
        ensureRtDownThread()
    }

    /** The realtime response finished — flush the 'E' end marker after the audio. */
    fun endRealtimeResponse() {
        if (!realtimeMode || !isConnected) return
        rtDownQueue.offer(RtDown.End)
        ensureRtDownThread()
    }

    private fun resetRealtimeStreams() {
        rtMicSeqValid = false
        rtTalking = false
        rtDownQueue.clear()
        mainHandler.removeCallbacks(rtSilenceRunnable)
    }

    fun disconnect() {
        stopScan()
        if (isConnected && realtimeMode) {
            // Best effort: leave the glasses in the legacy protocol so an old
            // app connecting next doesn't feed PCM16 into the µ-law decoder.
            try { writeControlMarkerNoResponse('m') } catch (_: Exception) {}
        }
        isConnected = false
        resetRealtimeStreams()
        pendingJpeg = null
        receivingImage = false
        receivingVideo = false
        receivingVideoFrame = false
        synchronized(imageBuffer) { imageBuffer.reset() }
        synchronized(currentVideoFrame) { currentVideoFrame.reset() }
        synchronized(videoFrames) { videoFrames.clear() }
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        audioTxChar = null
        audioRxChar = null
        controlChar = null
        imageTxChar = null
        synchronized(audioChunks) { audioChunks.clear() }
    }

    fun isConnected(): Boolean = isConnected

    // ── Scan ──

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = try { device.name } catch (_: Exception) { null } ?: "ESP32"
            Log.i(TAG, "Found: $name [${device.address}]")
            stopScan()
            onEvent(BleEvent.DeviceFound(name, device.address))
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            Log.e(TAG, "Scan failed: $errorCode")
            onEvent(BleEvent.Error("Scan failed (error $errorCode)"))
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        try {
            Log.i(TAG, "Connecting...")
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.e(TAG, "connectGatt failed", e)
            onEvent(BleEvent.Error("Connect failed: ${e.message}"))
        }
    }

    // ── GATT Callback ──

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            try {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Connected, requesting MTU")
                    // Ask Android to pick the LOW end of the firmware's 7.5–15ms
                    // connection-interval range. The peripheral already allows down
                    // to 7.5ms (updateConnParams 6..12), but the central defaulted to
                    // 15ms → OTA throughput capped at ~33.8KB/s, below the 46.9KB/s
                    // real-time playback need (caused the thin ring buffer/underrun).
                    // HIGH priority ≈ ~11.25ms or lower, lifting OTA above playback rate.
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    // Request 2M PHY (BLE 5): double the raw symbol rate of the
                    // default 1M — more notify/write throughput on the same link.
                    // The firmware requests the same from its side; controllers
                    // negotiate and fall back to 1M when unsupported. Result is
                    // logged in onPhyUpdate as [PERF-M35].
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        gatt.setPreferredPhy(
                            BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_OPTION_NO_PREFERRED
                        )
                    }
                    gatt.requestMtu(TARGET_MTU)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected (status=$status)")
                    isConnected = false
                    resetRealtimeStreams()
                    synchronized(audioChunks) { audioChunks.clear() }
                    audioTxChar = null
                    audioRxChar = null
                    controlChar = null
                    imageTxChar = null
                    try { gatt.close() } catch (_: Exception) {}
                    this@BleVoiceService.gatt = null
                    onEvent(BleEvent.Disconnected)
                }
            } catch (e: Exception) {
                Log.e(TAG, "onConnectionStateChange error", e)
            }
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            // 1 = 1M, 2 = 2M, 3 = Coded. Confirms whether the 2M request stuck.
            Log.i(TAG, "[PERF-M35] PHY updated: tx=$txPhy rx=$rxPhy status=$status (1=1M, 2=2M, 3=Coded)")
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            try {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    negotiatedMtu = mtu
                    Log.i(TAG, "MTU: $mtu")
                    onEvent(BleEvent.MtuNegotiated(mtu))
                } else {
                    Log.w(TAG, "MTU failed (status=$status)")
                }
                gatt.discoverServices()
            } catch (e: Exception) {
                Log.e(TAG, "onMtuChanged error", e)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            try {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    onEvent(BleEvent.Error("Service discovery failed"))
                    return
                }

                val service = gatt.getService(SERVICE_UUID)
                if (service == null) {
                    onEvent(BleEvent.Error("Voice service not found on ESP32"))
                    return
                }

                audioTxChar = service.getCharacteristic(AUDIO_TX_UUID)
                audioRxChar = service.getCharacteristic(AUDIO_RX_UUID)
                controlChar = service.getCharacteristic(CONTROL_UUID)
                imageTxChar = service.getCharacteristic(IMAGE_TX_UUID)

                if (audioTxChar == null || audioRxChar == null || controlChar == null) {
                    onEvent(BleEvent.Error("Missing BLE characteristics"))
                    return
                }

                if (imageTxChar == null) {
                    Log.w(TAG, "IMAGE_TX (aa04) not found — vision mode unavailable")
                }

                Log.i(TAG, "Services found, enabling Audio TX notifications...")

                // Step 1: enable Audio TX notifications
                gatt.setCharacteristicNotification(audioTxChar!!, true)
                val txDesc = audioTxChar!!.getDescriptor(CCCD_UUID)
                if (txDesc != null) {
                    @Suppress("DEPRECATION")
                    txDesc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(txDesc)
                } else {
                    // No CCCD — skip to control
                    doEnableControlNotifications(gatt)
                }
            } catch (e: Exception) {
                Log.e(TAG, "onServicesDiscovered error", e)
                onEvent(BleEvent.Error("Service setup failed: ${e.message}"))
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            try {
                val charUuid = descriptor.characteristic.uuid
                Log.i(TAG, "Descriptor written for $charUuid (status=$status)")

                if (charUuid == AUDIO_TX_UUID) {
                    // Step 2: enable Image TX notifications (or skip to Control)
                    doEnableImageTxNotifications(gatt)
                } else if (charUuid == IMAGE_TX_UUID) {
                    // Step 3: enable Control notifications
                    doEnableControlNotifications(gatt)
                } else if (charUuid == CONTROL_UUID) {
                    // Step 4: fully connected
                    isConnected = true
                    val name = try { gatt.device?.name ?: "ESP32" } catch (_: Exception) { "ESP32" }
                    Log.i(TAG, "Fully connected to $name")
                    // Announce the voice protocol for this session. Always sent:
                    // the firmware keeps its µ-law flag across connections, so an
                    // explicit 'm' clears a stale realtime mode too.
                    val modeMarker = if (realtimeMode) 'M' else 'm'
                    Thread {
                        writeControlMarkerNoResponse(modeMarker)
                    }.apply { isDaemon = true; start() }
                    onEvent(BleEvent.Connected(name))
                }
            } catch (e: Exception) {
                Log.e(TAG, "onDescriptorWrite error", e)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            try {
                val data = characteristic.value
                if (data == null || data.isEmpty()) return
                dispatchCharacteristicData(characteristic.uuid, data)
            } catch (e: Exception) {
                Log.e(TAG, "onCharacteristicChanged error", e)
            }
        }
    }

    // ── Helpers ──

    private fun doEnableImageTxNotifications(gatt: BluetoothGatt) {
        try {
            val imgChar = imageTxChar
            if (imgChar == null) {
                // No IMAGE_TX characteristic — skip to Control
                doEnableControlNotifications(gatt)
                return
            }
            Log.i(TAG, "Enabling Image TX notifications...")
            gatt.setCharacteristicNotification(imgChar, true)
            val imgDesc = imgChar.getDescriptor(CCCD_UUID)
            if (imgDesc != null) {
                @Suppress("DEPRECATION")
                imgDesc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(imgDesc)
            } else {
                doEnableControlNotifications(gatt)
            }
        } catch (e: Exception) {
            Log.e(TAG, "enableImageTxNotifications error", e)
            doEnableControlNotifications(gatt)
        }
    }

    private fun doEnableControlNotifications(gatt: BluetoothGatt) {
        try {
            val ctrl = controlChar ?: return
            gatt.setCharacteristicNotification(ctrl, true)
            val ctrlDesc = ctrl.getDescriptor(CCCD_UUID)
            if (ctrlDesc != null) {
                @Suppress("DEPRECATION")
                ctrlDesc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(ctrlDesc)
            } else {
                // No CCCD — mark connected anyway
                isConnected = true
                onEvent(BleEvent.Connected("ESP32"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "enableControlNotifications error", e)
            onEvent(BleEvent.Error("Control notification setup failed"))
        }
    }

    private fun dispatchCharacteristicData(uuid: UUID, data: ByteArray) {
        when (uuid) {
            AUDIO_TX_UUID -> handleAudioTx(data)
            IMAGE_TX_UUID -> handleImageTx(data)
            CONTROL_UUID -> handleControl(data)
        }
    }

    private fun handleAudioTx(data: ByteArray) {
        if (data.size < HEADER_SIZE) return
        val tag = data[0].toInt().toChar()
        if (tag != 'A') return
        val payload = data.copyOfRange(HEADER_SIZE, data.size)
        if (payload.isEmpty()) return
        val seq = data[1].toInt() and 0xFF

        if (realtimeMode) {
            handleRealtimeMicFrame(seq, payload)
            return
        }

        synchronized(audioChunks) {
            if (audioChunks.isEmpty()) {
                perfAudioRxStartMs = System.currentTimeMillis()  // M11 start
                perfAudioExpectedSeq = seq                       // anchor to first chunk
                perfAudioSeqGaps = 0
            }
            // Detect dropped notifications via the 1-byte seq (wraps 0..255). A
            // dropped mic packet both loses audio AND, if left unfilled, shifts
            // every later int16 sample by a byte → full-scale garbage → Whisper
            // hallucination. Insert silence (zero bytes) for each missing fragment
            // so the PCM stream stays byte-aligned and the loss is benign + logged.
            if (seq != perfAudioExpectedSeq) {
                val gap = (seq - perfAudioExpectedSeq) and 0xFF
                perfAudioSeqGaps += gap
                Log.w(TAG, "[PERF-M11] Audio SEQ gap: expected $perfAudioExpectedSeq got $seq (lost ~$gap fragment(s)) — inserting silence")
                repeat(gap) { audioChunks.add(ByteArray(payload.size)) }
            }
            perfAudioExpectedSeq = (seq + 1) and 0xFF
            audioChunks.add(payload)
            if (audioChunks.size % 50 == 0) {
                val total = audioChunks.sumOf { it.size }
                Log.d(TAG, "Audio: ${audioChunks.size} chunks ($total bytes)")
                onEvent(BleEvent.ReceivingAudio(audioChunks.size, total))
            }
        }
    }

    /**
     * Realtime mode uplink: mic 'A' frames carry µ-law @16 kHz and stream live
     * while the button is held. Per frame (mirrors realtime_ble.py's
     * on_mic_frame): seq-filter stale duplicates, zero-fill small gaps to keep
     * VAD timing sane, µ-law-decode, condition, upsample to 24 kHz, forward.
     * Runs on the BLE notify thread; RealtimeVoiceClient.appendAudio only
     * enqueues onto OkHttp's writer queue, so nothing here blocks.
     */
    private fun handleRealtimeMicFrame(seq: Int, ulawPayload: ByteArray) {
        val client = realtimeClient ?: return
        if (rtMicSeqValid) {
            // Anything at-or-behind the last accepted seq (delta 0 or "negative"
            // mod 256) is a stale duplicate from a double-subscription: drop it.
            val delta = (seq - rtMicLastSeq) and 0xFF
            if (delta == 0 || delta >= 200) return
            val gap = delta - 1
            if (gap in 1..8) {
                // Small real loss: zero-fill so the VAD's clock doesn't jump.
                // gap × payload µ-law samples @16 kHz = ×3 bytes of PCM16 @24 kHz.
                client.appendAudio(ByteArray(gap * ulawPayload.size * 3))
            }
            // gap > 8: resync after a big jump, don't fill
        }
        rtMicLastSeq = seq
        rtMicSeqValid = true
        val pcm16k = AudioCodec.ulawDecode(ulawPayload)
        client.appendAudio(AudioCodec.upsample16kTo24k(rtMicConditioner.process(pcm16k)))
        rtTalking = true
        mainHandler.removeCallbacks(rtSilenceRunnable)
        mainHandler.postDelayed(rtSilenceRunnable, RT_SILENCE_AFTER_MS)
    }

    private fun handleControl(data: ByteArray) {
        if (data.isEmpty()) return
        when (data[0].toInt().toChar()) {
            'E' -> {
                if (realtimeMode) {
                    // Push-to-talk release: the mic stream stops here. The
                    // RT_SILENCE_AFTER_MS timer feeds server VAD its closing
                    // silence — no batch processing in realtime mode.
                    Log.i(TAG, "End marker (realtime) — turn closes via VAD silence")
                } else {
                    Log.i(TAG, "End marker → processing")
                    processReceivedAudio()
                }
            }
            'S' -> {
                // Flush stale audio left over from prior quick taps. Firmware emits
                // exactly one 'S' per utterance and resets its mic seq to 0 at the
                // same instant, so the next chunk re-anchors the seq tracker cleanly.
                Log.i(TAG, "Start marker → clearing buffer")
                if (realtimeMode) rtMicSeqValid = false
                synchronized(audioChunks) {
                    audioChunks.clear()
                    perfAudioExpectedSeq = -1
                    perfAudioSeqGaps = 0
                }
            }
            'X' -> {
                // V2 firmware barge-in: user pressed the button during playback.
                // The glasses already tore down their speaker — stop streaming.
                Log.i(TAG, "Playback cancelled by glasses (barge-in) — aborting TTS send")
                ttsCancelled = true
                onEvent(BleEvent.PlaybackCancelled)
            }
            'V' -> {
                // Video recording start — clear any stale audio and prepare frame list
                Log.i(TAG, "Video start marker")
                receivingVideo = true
                receivingVideoFrame = false
                synchronized(audioChunks) { audioChunks.clear() }
                synchronized(videoFrames) { videoFrames.clear() }
                synchronized(currentVideoFrame) { currentVideoFrame.reset() }
            }
            'W' -> {
                // Video end — fire event with all collected frames.
                // 'W' rides CONTROL while the last frame's data + 'J' ride IMAGE_TX,
                // so it can overtake the final frame's end marker. If a frame is
                // still open, salvage it when its buffer is complete per the header
                // size; otherwise drop it as truncated.
                if (receivingVideoFrame) {
                    synchronized(currentVideoFrame) {
                        if (expectedVideoFrameSize in 1..currentVideoFrame.size()) {
                            synchronized(videoFrames) { videoFrames.add(currentVideoFrame.toByteArray()) }
                            Log.w(TAG, "Video end overtook last frame-end marker — salvaged complete frame (${currentVideoFrame.size()} bytes)")
                        } else {
                            Log.w(TAG, "Video end overtook last frame-end marker — dropped incomplete frame (${currentVideoFrame.size()}/$expectedVideoFrameSize bytes)")
                        }
                        currentVideoFrame.reset()
                    }
                }
                receivingVideo = false
                receivingVideoFrame = false
                val frames: List<ByteArray>
                synchronized(videoFrames) {
                    frames = videoFrames.toList()
                    videoFrames.clear()
                }
                Log.i(TAG, "Video end marker: ${frames.size} frames received")
                onEvent(BleEvent.VideoReceived(frames))
            }
            'I' -> {
                // LEGACY (pre-in-band firmware): image/video-frame header on CONTROL.
                // Current firmware sends the header in-band on IMAGE_TX as 'H' so it
                // can't race the fragments; this branch keeps old firmware working.
                val isVideoFrame = data.size >= 2 && data[1] == 0x01.toByte()
                val expectedSize = if (data.size >= 6) {
                    ByteBuffer.wrap(data, 2, 4).order(ByteOrder.LITTLE_ENDIAN).int
                } else 0
                startImageReceive(isVideoFrame, expectedSize)
            }
            'J' -> {
                // LEGACY (pre-in-band firmware): still-image end marker on CONTROL.
                // Current firmware sends 'J' in-band on IMAGE_TX (see handleImageTx).
                // Guarded so a stray/duplicate 'J' can't emit an empty image.
                if (receivingImage) finishStillImage()
            }
        }
    }

    /**
     * IMAGE_TX carries the whole image path in-band — header, fragments, end
     * marker — on one characteristic, whose notifications BLE delivers strictly
     * in order. That makes header-after-data and end-before-data races (which
     * the old CONTROL-channel markers only papered over with guard delays)
     * structurally impossible:
     *   'H' [flags][u32 LE size]  header — flags 0x00 photo, 0x01 video frame
     *   'I' [seq][jpeg bytes]     data fragment
     *   'J' [frameIdx]            end of image / video frame
     */
    private fun handleImageTx(data: ByteArray) {
        if (data.isEmpty()) return
        when (data[0].toInt().toChar()) {
            'H' -> {
                if (data.size < 6) return
                val isVideoFrame = data[1] == 0x01.toByte()
                val expectedSize = ByteBuffer.wrap(data, 2, 4).order(ByteOrder.LITTLE_ENDIAN).int
                startImageReceive(isVideoFrame, expectedSize)
                return
            }
            'J' -> {
                // Header-only marker: ['J'][frameIndex] — checked before the size
                // guard below.
                if (receivingVideoFrame) {
                    receivingVideoFrame = false
                    synchronized(currentVideoFrame) {
                        val frameBytes = currentVideoFrame.toByteArray()
                        synchronized(videoFrames) { videoFrames.add(frameBytes) }
                        currentVideoFrame.reset()
                    }
                    Log.d(TAG, "Video frame ${data.getOrNull(1)?.toInt() ?: 0} complete (${videoFrames.size} frames so far)")
                } else if (receivingImage) {
                    finishStillImage()
                }
                return
            }
        }
        if (data[0].toInt().toChar() != 'I') return
        if (data.size <= HEADER_SIZE) return
        val payload = data.copyOfRange(HEADER_SIZE, data.size)
        val fragSeq = data[1].toInt() and 0xFF
        // Recover a dropped 'H' header: fragments always start at seq 0, so a
        // seq-0 fragment with no open transfer means the header was lost in its
        // connection event — open an implicit transfer (final JPEG decode still
        // guards integrity). A non-zero seq is a genuine mid-stream orphan.
        if (!receivingImage && !receivingVideoFrame && fragSeq == 0) {
            Log.w(TAG, "Image header missed — recovering from seq-0 fragment")
            startImageReceive(false, 0)
        }
        if (receivingVideoFrame) {
            synchronized(currentVideoFrame) { currentVideoFrame.write(payload) }
        } else if (receivingImage) {
            // M24: detect dropped fragments via the 1-byte seq in the header.
            // The seq wraps 0..255; any jump > 1 means notification(s) were lost,
            // which corrupts the JPEG. This confirms whether the firmware-side
            // notify retry fix actually eliminated the drops.
            val seq = data[1].toInt() and 0xFF
            if (seq != perfImageExpectedSeq) {
                val gap = (seq - perfImageExpectedSeq) and 0xFF
                perfImageSeqGaps += gap
                Log.w(TAG, "[PERF-M24] Image SEQ gap: expected $perfImageExpectedSeq got $seq (lost ~$gap fragment(s))")
            }
            perfImageExpectedSeq = (seq + 1) and 0xFF
            synchronized(imageBuffer) { imageBuffer.write(payload) }
            perfImagePacketCount++  // M24: count fragments
        } else {
            // No open transfer — a fragment arrived before its header (possible
            // only with legacy CONTROL-header firmware) or after a reset. Dropping
            // it beats silently corrupting the next image's reassembly buffer.
            Log.w(TAG, "Stray image fragment (${payload.size} bytes) with no open transfer — dropped")
        }
    }

    /**
     * Begin image/video-frame reassembly — shared by the in-band 'H' header
     * (current firmware) and the legacy CONTROL 'I' header (old firmware).
     */
    private fun startImageReceive(isVideoFrame: Boolean, expectedSize: Int) {
        if (isVideoFrame) {
            Log.d(TAG, "Video frame start ($expectedSize bytes)")
            receivingVideoFrame = true
            expectedVideoFrameSize = expectedSize
            synchronized(currentVideoFrame) { currentVideoFrame.reset() }
        } else {
            Log.i(TAG, "Image start marker (expected $expectedSize bytes)")
            perfImageTxStartMs   = System.currentTimeMillis()  // M17
            perfExpectedImageSize = expectedSize               // M24
            perfImagePacketCount  = 0                          // M24
            perfImageExpectedSeq  = 0                          // M24
            perfImageSeqGaps      = 0                          // M24
            receivingImage = true
            receivingVideoFrame = false
            synchronized(imageBuffer) { imageBuffer.reset() }
            Log.i(TAG, "[PERF-M17] Image transfer start: expected $expectedSize bytes")
        }
    }

    /** Still image complete — stash until 'E' arrives (5 s attach window). */
    private fun finishStillImage() {
        receivingImage = false
        synchronized(imageBuffer) {
            pendingJpeg = imageBuffer.toByteArray()
            imageBuffer.reset()
        }
        pendingJpegTime = System.currentTimeMillis()  // start the 5s attach window
        val imgBytes = pendingJpeg ?: ByteArray(0)
        val imgMs = System.currentTimeMillis() - perfImageTxStartMs
        val imgThroughput = if (imgMs > 0) imgBytes.size * 1000.0 / imgMs else 0.0
        Log.i(TAG, "[PERF-M17] Image transfer complete: ${imgBytes.size} bytes in ${imgMs}ms, $perfImagePacketCount packets")
        Log.i(TAG, "[PERF-M18] Image BLE RX throughput: ${String.format("%.0f", imgThroughput)} B/s (${String.format("%.1f", imgThroughput/1024)} KB/s)")
        val sizeMatch = imgBytes.size == perfExpectedImageSize
        Log.i(TAG, "[PERF-M24] Reassembly: expected=$perfExpectedImageSize assembled=${imgBytes.size} seqGaps=$perfImageSeqGaps → ${if (sizeMatch) "OK" else "SIZE MISMATCH!"}")
        // Attempt JPEG decode to verify integrity
        if (imgBytes.isNotEmpty()) {
            val bm = android.graphics.BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
            Log.i(TAG, "[PERF-M24] JPEG decode: ${if (bm != null) "SUCCESS (${bm.width}x${bm.height})" else "FAILED — corrupted transfer"}")
            bm?.recycle()
        }
        pendingJpeg?.let { onEvent(BleEvent.ImageReceived(it)) }

        // Realtime mode: feed the photo straight into the realtime conversation
        // as an input_image — the spoken question (already streaming) references
        // it. Consumed here so it can never fall through to the legacy vision path.
        if (realtimeMode) {
            val client = realtimeClient
            val jpeg = pendingJpeg
            pendingJpeg = null
            if (client != null && jpeg != null && jpeg.isNotEmpty()) {
                Thread {
                    try {
                        if (!client.sendImage(jpeg)) {
                            Log.w(TAG, "Realtime image send failed (socket down?)")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Realtime image send error", e)
                    }
                }.apply { isDaemon = true; start() }
            }
        }
    }

    private fun processReceivedAudio() {
        val raw: ByteArray
        synchronized(audioChunks) {
            raw = ByteArray(audioChunks.sumOf { it.size })
            var off = 0
            for (c in audioChunks) {
                System.arraycopy(c, 0, raw, off, c.size)
                off += c.size
            }
            audioChunks.clear()
        }

        val dur = raw.size / (16000.0 * 2)
        val audioRxMs = System.currentTimeMillis() - perfAudioRxStartMs
        Log.i(TAG, "Utterance: ${raw.size} bytes (${String.format("%.2f", dur)}s)")
        Log.i(TAG, "[PERF-M11] Utterance received: ${raw.size} bytes (${String.format("%.2f", dur)}s) in ${audioRxMs}ms from ESP32 (seqGaps=$perfAudioSeqGaps)")
        Log.i(TAG, "[PERF-M9]  MTU: $negotiatedMtu (payload: ${negotiatedMtu - 3 - HEADER_SIZE} bytes/pkt)")

        if (raw.size < MIN_AUDIO_BYTES) {
            Log.w(TAG, "Too short, ignoring")
            return
        }

        // Attach a recently-taken photo if one is within the 5s window.
        // Always consume the pending photo so a stale one can't attach later.
        val ageMs = System.currentTimeMillis() - pendingJpegTime
        val jpeg = pendingJpeg?.takeIf { it.isNotEmpty() && ageMs <= VISION_WINDOW_MS }
        if (pendingJpeg != null && jpeg == null) {
            Log.i(TAG, "Photo too old (${ageMs}ms > ${VISION_WINDOW_MS}ms) — answering voice-only")
        }
        pendingJpeg = null

        onEvent(BleEvent.ProcessingStarted)

        if (jpeg != null && jpeg.isNotEmpty()) {
            Log.i(TAG, "Vision mode: ${jpeg.size} bytes image + ${raw.size} bytes audio")
            Thread {
                try {
                    val pcm = pipeline.processWithVision(raw, jpeg)
                    if (pcm != null) sendAudioToEsp32(pcm)
                    else Log.w(TAG, "Vision pipeline returned null")
                } catch (e: Exception) {
                    Log.e(TAG, "Vision processing error", e)
                    onEvent(BleEvent.Error(e.message ?: "Vision processing failed"))
                }
            }.start()
        } else {
            Thread {
                try {
                    val pcm = pipeline.process(raw)
                    if (pcm != null) sendAudioToEsp32(pcm)
                    else Log.w(TAG, "Pipeline returned null")
                } catch (e: Exception) {
                    Log.e(TAG, "Processing error", e)
                    onEvent(BleEvent.Error(e.message ?: "Processing failed"))
                }
            }.start()
        }
    }

    /**
     * Write a control marker ('S'/'E') with the same queue-busy retry the audio
     * fragments get. writeCharacteristic() returns false when another GATT op
     * holds the single pending slot — ignoring that silently drops the marker.
     * A dropped 'S' means the ESP32 never arms playback (TTS streams into the
     * void); a dropped 'E' used to soft-lock it in PLAYING. Returns success.
     */
    private fun writeControlMarker(tag: Char): Boolean {
        val ctrl = controlChar ?: return false
        val g = gatt ?: return false
        @Suppress("DEPRECATION")
        ctrl.value = byteArrayOf(tag.code.toByte(), 0)
        ctrl.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        var attempts = 0
        while (attempts < 100 && isConnected) {
            @Suppress("DEPRECATION")
            if (g.writeCharacteristic(ctrl)) return true
            Thread.sleep(2)
            attempts++
        }
        Log.e(TAG, "Control marker '$tag' write FAILED after $attempts attempts")
        return false
    }

    /**
     * Control marker via write-WITHOUT-response, with the same queue-busy retry.
     * Used for the realtime-mode markers ('M'/'m'): a response PDU can fail with
     * ATT "Insufficient Resource" while the server's buffers are full of mic
     * notifications (realtime_ble.py hit exactly this). ATT is sequential, so
     * ordering versus audio writes still holds.
     */
    private fun writeControlMarkerNoResponse(tag: Char): Boolean {
        val ctrl = controlChar ?: return false
        val g = gatt ?: return false
        @Suppress("DEPRECATION")
        ctrl.value = byteArrayOf(tag.code.toByte(), 0)
        ctrl.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        var attempts = 0
        while (attempts < 100 && isConnected) {
            @Suppress("DEPRECATION")
            if (g.writeCharacteristic(ctrl)) return true
            try { Thread.sleep(2) } catch (_: InterruptedException) { return false }
            attempts++
        }
        Log.e(TAG, "Control marker '$tag' (no-response) write FAILED after $attempts attempts")
        return false
    }

    // ── Realtime downlink: response audio → glasses speaker ──

    private fun ensureRtDownThread() {
        synchronized(rtDownQueue) {
            if (rtDownThread?.isAlive == true) return
            rtDownThread = Thread { rtDownlinkLoop() }.apply {
                name = "RtDownlink"
                isDaemon = true
                start()
            }
        }
    }

    /**
     * Drains the realtime downlink queue for as long as the link is up and
     * realtime mode is on. Per response (mirrors realtime_ble.py):
     *   first delta  → 'S' marker, reset seq + token bucket
     *   each chunk   → ['A'][seq][µ-law] packets on AUDIO_RX, WRITE_NO_RESPONSE,
     *                  paced RT_DL_BURST burst then RT_DL_BPS sustained
     *   response.done→ 'E' marker
     * The 'X' barge-in aborts exactly like the TTS path: stop streaming, skip
     * the rest of this response, and deliberately do NOT send the trailing 'E'.
     */
    private fun rtDownlinkLoop() {
        var respOpen = false
        var seq = 0
        var dlT0 = 0L
        var dlSent = 0L
        try {
            while (isConnected && realtimeMode) {
                val item = rtDownQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    ?: continue
                when (item) {
                    is RtDown.Audio -> {
                        if (item.first) {
                            ttsCancelled = false   // fresh response — clear any barge-in
                            // Without 'S' the firmware ignores every audio write —
                            // abort this response rather than stream into the void.
                            if (!writeControlMarker('S')) {
                                onEvent(BleEvent.Error("Playback start marker failed — realtime response dropped"))
                                respOpen = false
                                continue
                            }
                            respOpen = true
                            seq = 0
                            dlT0 = System.currentTimeMillis()
                            dlSent = 0
                        }
                        if (!respOpen || ttsCancelled) continue
                        val rx = audioRxChar ?: continue
                        val g = gatt ?: continue
                        // µ-law is 1 byte/sample — no even-byte rounding needed.
                        val maxPayload = (negotiatedMtu - 3 - HEADER_SIZE).coerceAtLeast(20)
                        var off = 0
                        while (off < item.ulaw.size && isConnected && !ttsCancelled) {
                            val frag = minOf(maxPayload, item.ulaw.size - off)
                            // Token bucket: RT_DL_BURST up front, then RT_DL_BPS.
                            while (dlSent + frag > RT_DL_BURST +
                                (System.currentTimeMillis() - dlT0) * RT_DL_BPS / 1000 &&
                                isConnected && !ttsCancelled
                            ) {
                                Thread.sleep(10)
                            }
                            if (!isConnected || ttsCancelled) break
                            val pkt = ByteArray(HEADER_SIZE + frag)
                            pkt[0] = 'A'.code.toByte()
                            pkt[1] = (seq and 0xFF).toByte()
                            System.arraycopy(item.ulaw, off, pkt, HEADER_SIZE, frag)
                            @Suppress("DEPRECATION")
                            rx.value = pkt
                            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            // Same queue-busy retry as the TTS path: a false return
                            // is a SILENT drop → seq gap → crackle on the glasses.
                            var queued = false
                            var attempts = 0
                            while (!queued && attempts < 100 && isConnected && !ttsCancelled) {
                                @Suppress("DEPRECATION")
                                queued = g.writeCharacteristic(rx)
                                if (!queued) {
                                    Thread.sleep(2)
                                    attempts++
                                }
                            }
                            if (!queued) Log.w(TAG, "Realtime BLE write dropped (seq=$seq)")
                            seq++
                            off += frag
                            dlSent += frag
                        }
                    }
                    is RtDown.End -> {
                        if (respOpen && !ttsCancelled && isConnected) {
                            Thread.sleep(30)
                            writeControlMarker('E')
                        }
                        // After a barge-in, deliberately no 'E' — a late 'E' could
                        // land after the 'S' of the next response and kill it.
                        respOpen = false
                    }
                }
            }
        } catch (_: InterruptedException) {
            // teardown
        } catch (e: Exception) {
            Log.e(TAG, "Realtime downlink error", e)
        }
    }

    private fun sendAudioToEsp32(pcm: ByteArray) {
        val rx = audioRxChar ?: return
        val g = gatt ?: return
        if (!isConnected) return

        // Round payload down to EVEN bytes so each packet carries whole 16-bit samples.
        // Without this, lost packets (WRITE_NR has no ACK) shift the audio buffer by an
        // odd byte count, misaligning every subsequent 16-bit sample → buzzing/noise.
        val maxPayload = (negotiatedMtu - 3 - HEADER_SIZE) and 0x7FFFFFFE.toInt()
        val dur = pcm.size / (24000.0 * 2)
        Log.i(TAG, "Sending ${pcm.size} bytes (${String.format("%.2f", dur)}s)")
        val bleTxStartMs = System.currentTimeMillis()  // M4/M10
        onEvent(BleEvent.SendingAudio(pcm.size))
        ttsCancelled = false   // fresh send — clear any cancel from a previous response

        try {
            // Start marker — abort if it can't be delivered: without 'S' the
            // firmware ignores everything that follows, so streaming the audio
            // anyway just wastes ~10s of BLE airtime for silent playback.
            if (!writeControlMarker('S')) {
                onEvent(BleEvent.Error("Playback start marker failed — response not sent"))
                return
            }
            Thread.sleep(30)

            // Audio fragments
            // WRITE_TYPE_NO_RESPONSE writes are queued by Android's BLE stack (typically
            // 4–7 slots). If we blast faster than the link can drain, writeCharacteristic()
            // returns false and the packet is SILENTLY DROPPED. We must check the return
            // value and back off until the slot frees, otherwise the ESP32 sees seq gaps
            // and the audio crackles.
            var seq: Byte = 0
            var offset = 0
            var chunks = 0
            var dropped = 0
            while (offset < pcm.size && isConnected && !ttsCancelled) {
                val frag = minOf(maxPayload, pcm.size - offset)
                val pkt = ByteArray(HEADER_SIZE + frag)
                pkt[0] = 'A'.code.toByte()
                pkt[1] = seq
                System.arraycopy(pcm, offset, pkt, HEADER_SIZE, frag)

                @Suppress("DEPRECATION")
                rx.value = pkt
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

                var queued = false
                var attempts = 0
                while (!queued && attempts < 100 && isConnected && !ttsCancelled) {
                    @Suppress("DEPRECATION")
                    queued = g.writeCharacteristic(rx)
                    if (!queued) {
                        Thread.sleep(2)
                        attempts++
                    }
                }
                if (!queued) {
                    dropped++
                    Log.w(TAG, "BLE write dropped (seq=$seq) after $attempts retries")
                }

                seq++
                offset += frag
                chunks++
                Thread.sleep(4)  // small inter-packet delay; queue check above does the real pacing
            }
            if (dropped > 0) Log.w(TAG, "Total dropped audio packets: $dropped/$chunks")
            val bleTxMs = System.currentTimeMillis() - bleTxStartMs
            val bleThroughput = if (bleTxMs > 0) pcm.size * 1000.0 / bleTxMs else 0.0
            Log.i(TAG, "[PERF-M4]  BLE TX (Android→ESP32): ${pcm.size} bytes, $chunks chunks, $dropped dropped")
            Log.i(TAG, "[PERF-M10] BLE TX throughput: ${String.format("%.0f", bleThroughput)} B/s (${String.format("%.1f", bleThroughput/1024)} KB/s) in ${bleTxMs}ms")

            if (ttsCancelled) {
                // Barge-in: the glasses already reset to idle. Deliberately do NOT
                // send 'E' — a late 'E' could land after the 'S' of the user's next
                // question and terminate that stream early.
                Log.i(TAG, "TTS send aborted at $offset/${pcm.size} bytes (barge-in)")
                onEvent(BleEvent.AudioSent(offset))
                return
            }

            // End marker (retried — a lost 'E' used to strand the firmware in
            // PLAYING; its stall watchdog now also covers this, belt-and-braces)
            Thread.sleep(30)
            if (isConnected) {
                writeControlMarker('E')
            }

            Log.i(TAG, "Sent $chunks chunks (${pcm.size} bytes)")
            onEvent(BleEvent.AudioSent(pcm.size))
        } catch (e: Exception) {
            Log.e(TAG, "Send error", e)
            onEvent(BleEvent.Error("Send failed: ${e.message}"))
        }
    }
}
