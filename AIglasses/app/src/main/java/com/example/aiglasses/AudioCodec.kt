package com.example.aiglasses

/**
 * Audio codecs for the realtime BLE voice path.
 *
 * G.711 µ-law: BLE audio is 1 byte/sample in both directions — halves the
 * packet rate (centrals demonstrably drop notifications above ~40/s) at
 * telephony speech quality. Bit-exact port of ulawEncode()/ulawDecode() in
 * HardwareTest.ino / S3_App_V2/ble_link.cpp, validated against the glasses
 * by HardwareTest/realtime_ble.py.
 *
 * All PCM byte arrays are 16-bit little-endian mono.
 */
object AudioCodec {

    /** 256-entry µ-law → PCM16 lookup table (same construction as realtime_ble.py). */
    private val ULAW_DECODE_LUT = ShortArray(256) { u ->
        val v = u.inv() and 0xFF
        var t = ((v and 0x0F) shl 3) + 0x84
        t = t shl ((v and 0x70) shr 4)
        (if (v and 0x80 != 0) 0x84 - t else t - 0x84).toShort()
    }

    /** µ-law bytes → PCM16 LE bytes (2× the input length). */
    fun ulawDecode(ulaw: ByteArray): ByteArray {
        val out = ByteArray(ulaw.size * 2)
        for (i in ulaw.indices) {
            val s = ULAW_DECODE_LUT[ulaw[i].toInt() and 0xFF].toInt()
            out[2 * i] = (s and 0xFF).toByte()
            out[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** PCM16 LE bytes → µ-law bytes (half the input length; odd trailing byte dropped). */
    fun ulawEncode(pcm: ByteArray): ByteArray {
        val n = pcm.size / 2
        val out = ByteArray(n)
        for (i in 0 until n) {
            val sample = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
            out[i] = ulawEncodeSample(sample)
        }
        return out
    }

    /** One PCM16 sample → one µ-law byte. Port of the firmware's ulawEncode(). */
    private fun ulawEncodeSample(sample: Int): Byte {
        var pcm = sample
        var sign = 0
        if (pcm < 0) {
            sign = 0x80
            pcm = -pcm
        }
        if (pcm > 32635) pcm = 32635   // CLIP (also handles -32768 → 32768)
        pcm += 0x84                    // bias
        var exp = 7
        var mask = 0x4000
        while ((pcm and mask) == 0 && exp > 0) {
            exp--
            mask = mask shr 1
        }
        val mant = (pcm shr (exp + 3)) and 0x0F
        return (sign or (exp shl 4) or mant).inv().toByte()
    }

    /**
     * 16 kHz → 24 kHz linear-interpolation upsample (2 samples in → 3 out).
     * Port of resample_16k_to_24k() in HardwareTest/realtime_bridge.py.
     * Upsampling never aliases, so no anti-aliasing filter is needed here.
     */
    fun upsample16kTo24k(pcm16k: ByteArray): ByteArray {
        val n = pcm16k.size / 2
        if (n < 2) return ByteArray(0)
        val src = ShortArray(n)
        for (i in 0 until n) {
            src[i] = ((pcm16k[2 * i].toInt() and 0xFF) or
                    (pcm16k[2 * i + 1].toInt() shl 8)).toShort()
        }
        val m = (n * 3) / 2
        val out = ByteArray(m * 2)
        for (j in 0 until m) {
            val x = j * 2.0 / 3.0
            val i = x.toInt()
            val s: Int = if (i + 1 >= n) {
                src[n - 1].toInt()
            } else {
                val f = x - i
                (src[i] + (src[i + 1] - src[i]) * f).toInt()
            }
            out[2 * j] = (s and 0xFF).toByte()
            out[2 * j + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }
}

/**
 * High-pass + slow AGC for the quiet onboard mic, applied before upload.
 * Port of MicConditioner in HardwareTest/realtime_bridge.py (validated on
 * hardware): one-pole HPF (~80 Hz) kills DC offset and handling rumble so
 * the AGC doesn't amplify it; gain adapts slowly toward a −6 dBFS peak
 * target, fast-attacks down on loud input, and is capped at 12× so silence
 * never becomes white noise.
 *
 * Stateful across chunks — call from ONE thread only (the BLE notify thread).
 */
class MicConditioner {
    private var prevX = 0.0
    private var prevY = 0.0
    private var gain = 4.0                 // start at Seeed's ×4

    fun reset() {
        prevX = 0.0
        prevY = 0.0
        gain = 4.0
    }

    /** PCM16 LE @16 kHz in → conditioned PCM16 LE @16 kHz out. */
    fun process(pcm: ByteArray): ByteArray {
        val n = pcm.size / 2
        if (n == 0) return ByteArray(0)
        val buf = IntArray(n)
        val r = 0.97                       // ~80 Hz corner @ 16 kHz
        var peak = 1
        for (i in 0 until n) {
            val x = ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toDouble()
            val y = x - prevX + r * prevY
            prevX = x
            prevY = y
            val v = y.toInt().coerceIn(-32768, 32767)
            buf[i] = v
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
        }
        if (peak * gain > 30000) {         // fast attack: never clip
            gain = maxOf(1.0, 30000.0 / peak)
        } else if (peak > 300) {           // speech present: slow release toward target
            val desired = minOf(12.0, 16000.0 / peak)
            gain += (desired - gain) * 0.05
        }
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (buf[i] * gain).toInt().coerceIn(-32768, 32767)
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
