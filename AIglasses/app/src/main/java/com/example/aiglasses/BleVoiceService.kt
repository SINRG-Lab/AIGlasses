package com.example.aiglasses

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * BLE Central (GATT client) that connects to the ESP32-C6 BLE Peripheral.
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
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val TARGET_MTU = 512
        private const val MIN_AUDIO_BYTES = 16000 * 2
        private const val SCAN_TIMEOUT_MS = 20000L
        private const val HEADER_SIZE = 2
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

    private var negotiatedMtu = 23
    private val audioChunks = mutableListOf<ByteArray>()
    @Volatile private var isConnected = false
    @Volatile private var isScanning = false

    private val mainHandler = Handler(Looper.getMainLooper())

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

    fun disconnect() {
        stopScan()
        isConnected = false
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        audioTxChar = null
        audioRxChar = null
        controlChar = null
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
                    gatt.requestMtu(TARGET_MTU)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected (status=$status)")
                    isConnected = false
                    synchronized(audioChunks) { audioChunks.clear() }
                    audioTxChar = null
                    audioRxChar = null
                    controlChar = null
                    try { gatt.close() } catch (_: Exception) {}
                    this@BleVoiceService.gatt = null
                    onEvent(BleEvent.Disconnected)
                }
            } catch (e: Exception) {
                Log.e(TAG, "onConnectionStateChange error", e)
            }
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

                if (audioTxChar == null || audioRxChar == null || controlChar == null) {
                    onEvent(BleEvent.Error("Missing BLE characteristics"))
                    return
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
                    // Step 2: enable Control notifications
                    doEnableControlNotifications(gatt)
                } else if (charUuid == CONTROL_UUID) {
                    // Step 3: fully connected
                    isConnected = true
                    val name = try { gatt.device?.name ?: "ESP32" } catch (_: Exception) { "ESP32" }
                    Log.i(TAG, "Fully connected to $name")
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
            CONTROL_UUID -> handleControl(data)
        }
    }

    private fun handleAudioTx(data: ByteArray) {
        if (data.size < HEADER_SIZE) return
        val tag = data[0].toInt().toChar()
        if (tag != 'A') return
        val payload = data.copyOfRange(HEADER_SIZE, data.size)
        if (payload.isEmpty()) return

        synchronized(audioChunks) {
            audioChunks.add(payload)
            if (audioChunks.size % 50 == 0) {
                val total = audioChunks.sumOf { it.size }
                Log.d(TAG, "Audio: ${audioChunks.size} chunks ($total bytes)")
                onEvent(BleEvent.ReceivingAudio(audioChunks.size, total))
            }
        }
    }

    private fun handleControl(data: ByteArray) {
        if (data.isEmpty()) return
        when (data[0].toInt().toChar()) {
            'E' -> {
                Log.i(TAG, "End marker → processing")
                processReceivedAudio()
            }
            'S' -> {
                Log.i(TAG, "Start marker → clearing buffer")
                synchronized(audioChunks) { audioChunks.clear() }
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
        Log.i(TAG, "Utterance: ${raw.size} bytes (${String.format("%.2f", dur)}s)")

        if (raw.size < MIN_AUDIO_BYTES) {
            Log.w(TAG, "Too short, ignoring")
            return
        }

        onEvent(BleEvent.ProcessingStarted)

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

    private fun sendAudioToEsp32(pcm: ByteArray) {
        val rx = audioRxChar ?: return
        val g = gatt ?: return
        if (!isConnected) return

        val maxPayload = negotiatedMtu - 3 - HEADER_SIZE
        val dur = pcm.size / (22050.0 * 2)
        Log.i(TAG, "Sending ${pcm.size} bytes (${String.format("%.2f", dur)}s)")
        onEvent(BleEvent.SendingAudio(pcm.size))

        try {
            // Start marker
            controlChar?.let { ctrl ->
                @Suppress("DEPRECATION")
                ctrl.value = byteArrayOf('S'.code.toByte(), 0)
                ctrl.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ctrl)
                Thread.sleep(30)
            }

            // Audio fragments
            var seq: Byte = 0
            var offset = 0
            var chunks = 0
            while (offset < pcm.size && isConnected) {
                val frag = minOf(maxPayload, pcm.size - offset)
                val pkt = ByteArray(HEADER_SIZE + frag)
                pkt[0] = 'A'.code.toByte()
                pkt[1] = seq++
                System.arraycopy(pcm, offset, pkt, HEADER_SIZE, frag)

                @Suppress("DEPRECATION")
                rx.value = pkt
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                g.writeCharacteristic(rx)

                offset += frag
                chunks++
                Thread.sleep(8)
            }

            // End marker
            Thread.sleep(30)
            if (isConnected) {
                controlChar?.let { ctrl ->
                    @Suppress("DEPRECATION")
                    ctrl.value = byteArrayOf('E'.code.toByte(), 0)
                    ctrl.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(ctrl)
                }
            }

            Log.i(TAG, "Sent $chunks chunks (${pcm.size} bytes)")
            onEvent(BleEvent.AudioSent(pcm.size))
        } catch (e: Exception) {
            Log.e(TAG, "Send error", e)
            onEvent(BleEvent.Error("Send failed: ${e.message}"))
        }
    }
}
