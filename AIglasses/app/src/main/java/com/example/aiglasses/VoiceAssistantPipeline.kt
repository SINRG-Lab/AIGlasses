package com.example.aiglasses

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceAssistantPipeline(
    private val context: Context,
    private val openAIService: OpenAIService,
    private val onEvent: (PipelineEvent) -> Unit
) {
    companion object {
        private const val TAG = "VoicePipeline"
        private const val MIC_SAMPLE_RATE = 16000
        private const val TTS_SAMPLE_RATE = 24000  // OpenAI TTS native PCM rate — no resampling
        // Peak-limiter target: ~-1.3 dBFS leaves margin under the ESP32 SPK_VOL_SHIFT=1 ceiling
        private const val LIMITER_TARGET_PEAK = 28000
        private const val LIMITER_MAX_BOOST = 2.0  // +6 dB cap so quiet TTS isn't pumped to noise floor
    }

    sealed class PipelineEvent {
        data class Transcription(val text: String) : PipelineEvent()
        data class AiResponse(val text: String) : PipelineEvent()
        data class Error(val message: String) : PipelineEvent()
        data object Processing : PipelineEvent()
        data object TtsSynthesizing : PipelineEvent()
    }

    fun initTts() { /* OpenAI TTS needs no local init */ }
    fun destroy() { /* nothing to release */ }

    /**
     * Full pipeline: raw PCM from ESP32 → Whisper → GPT-4o-mini → OpenAI TTS → PCM.
     * Runs on a background thread (blocking).
     */
    fun process(rawPcm: ByteArray): ByteArray? {
        try {
            val pipelineStartMs = System.currentTimeMillis()
            val duration = rawPcm.size / (MIC_SAMPLE_RATE * 2.0)
            Log.i(TAG, "Processing utterance: ${rawPcm.size} bytes (${String.format("%.2f", duration)}s)")

            // 1. Save PCM as WAV for Whisper
            onEvent(PipelineEvent.Processing)
            val wavFile = pcmToWav(rawPcm, MIC_SAMPLE_RATE)
            Log.i(TAG, "Saved utterance WAV: ${wavFile.length()} bytes")

            // 2. Transcribe with Whisper
            Log.i(TAG, "Transcribing with Whisper...")
            val whisperStartMs = System.currentTimeMillis()
            val userText = openAIService.transcribe(wavFile)
            val whisperMs = System.currentTimeMillis() - whisperStartMs
            wavFile.delete()
            Log.i(TAG, "[PERF-M1] Whisper: ${whisperMs}ms → \"$userText\"")
            onEvent(PipelineEvent.Transcription(userText))

            if (userText.isBlank()) {
                Log.w(TAG, "Empty transcription, skipping")
                return null
            }

            // 3. Get AI response from GPT-4o-mini
            Log.i(TAG, "Getting AI response...")
            val chatStartMs = System.currentTimeMillis()
            val aiResponse = openAIService.chat(userText)
            val chatMs = System.currentTimeMillis() - chatStartMs
            Log.i(TAG, "[PERF-M2] GPT-4o-mini: ${chatMs}ms → \"$aiResponse\"")
            onEvent(PipelineEvent.AiResponse(aiResponse))

            // 4. Synthesize with OpenAI TTS (raw 24kHz mono PCM) → resample → limit
            onEvent(PipelineEvent.TtsSynthesizing)
            Log.i(TAG, "Synthesizing with OpenAI TTS...")
            val ttsStartMs = System.currentTimeMillis()
            val pcm24k = openAIService.speak(aiResponse)
            val ttsMs = System.currentTimeMillis() - ttsStartMs
            Log.i(TAG, "[PERF-M3] TTS: ${ttsMs}ms → ${pcm24k.size} bytes PCM @ 24kHz")

            val ttsPcm = peakLimit(pcm24k)
            val ttsDuration = ttsPcm.size / (TTS_SAMPLE_RATE * 2.0)
            Log.i(TAG, "TTS PCM ready: ${ttsPcm.size} bytes (${String.format("%.2f", ttsDuration)}s)")

            val totalMs = System.currentTimeMillis() - pipelineStartMs
            Log.i(TAG, "[PERF-M7] Voice pipeline total: ${totalMs}ms (whisper=${whisperMs} chat=${chatMs} tts=${ttsMs})")

            return ttsPcm
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline error", e)
            onEvent(PipelineEvent.Error(e.message ?: "Unknown error"))
            return null
        }
    }

    /**
     * Vision pipeline: optional audio PCM + JPEG → transcription → vision chat → TTS PCM.
     *
     * @param rawPcm 16kHz 16-bit mono PCM (nullable — if absent, uses a default prompt)
     * @param jpegBytes Raw JPEG image bytes from the ESP32 camera
     * @return PCM bytes at 22050Hz for the ESP32 speaker, or null on error
     */
    fun processWithVision(rawPcm: ByteArray?, jpegBytes: ByteArray): ByteArray? {
        try {
            val pipelineStartMs = System.currentTimeMillis()
            Log.i(TAG, "Vision request: image=${jpegBytes.size} bytes" +
                if (rawPcm != null) ", audio=${rawPcm.size} bytes" else "")

            onEvent(PipelineEvent.Processing)

            // 1. Transcribe audio if present
            var whisperMs = 0L
            val userText = if (rawPcm != null && rawPcm.size >= MIC_SAMPLE_RATE * 2) {
                val wavFile = pcmToWav(rawPcm, MIC_SAMPLE_RATE)
                Log.i(TAG, "Transcribing audio for vision...")
                val whisperStartMs = System.currentTimeMillis()
                val text = openAIService.transcribe(wavFile)
                whisperMs = System.currentTimeMillis() - whisperStartMs
                wavFile.delete()
                Log.i(TAG, "[PERF-M1] Whisper (vision): ${whisperMs}ms → \"$text\"")
                onEvent(PipelineEvent.Transcription(text))
                if (text.isBlank()) "What do you see in this image?" else text
            } else {
                "What do you see in this image?"
            }

            // 2. Vision chat with image + transcribed question
            Log.i(TAG, "Sending to vision API...")
            val visionStartMs = System.currentTimeMillis()
            val aiResponse = openAIService.visionChat(userText, jpegBytes)
            val visionMs = System.currentTimeMillis() - visionStartMs
            Log.i(TAG, "[PERF-M27] Vision API (GPT-4o): ${visionMs}ms → \"$aiResponse\"")
            onEvent(PipelineEvent.AiResponse(aiResponse))

            // 3. Synthesize with OpenAI TTS (raw 24kHz mono PCM) → resample → limit
            onEvent(PipelineEvent.TtsSynthesizing)
            Log.i(TAG, "Synthesizing with OpenAI TTS...")
            val ttsStartMs = System.currentTimeMillis()
            val pcm24k = openAIService.speak(aiResponse)
            val ttsMs = System.currentTimeMillis() - ttsStartMs
            Log.i(TAG, "[PERF-M3] TTS (vision): ${ttsMs}ms → ${pcm24k.size} bytes PCM @ 24kHz")

            val ttsPcm = peakLimit(pcm24k)
            val ttsDuration = ttsPcm.size / (TTS_SAMPLE_RATE * 2.0)
            Log.i(TAG, "TTS PCM ready: ${ttsPcm.size} bytes (${String.format("%.2f", ttsDuration)}s)")

            val totalMs = System.currentTimeMillis() - pipelineStartMs
            Log.i(TAG, "[PERF-M7] Vision pipeline total: ${totalMs}ms (whisper=${whisperMs} vision=${visionMs} tts=${ttsMs})")

            return ttsPcm
        } catch (e: Exception) {
            Log.e(TAG, "Vision pipeline error", e)
            onEvent(PipelineEvent.Error(e.message ?: "Vision processing failed"))
            return null
        }
    }

    /**
     * Decode MP3 bytes to 16-bit mono PCM at TTS_SAMPLE_RATE using Android MediaCodec.
     */
    private fun decodeMp3ToPcm(mp3Bytes: ByteArray): ByteArray? {
        val mp3File = File(context.cacheDir, "tts_openai.mp3")
        mp3File.writeBytes(mp3Bytes)

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(mp3File.absolutePath)

            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = fmt
                    break
                }
            }

            if (audioTrackIndex < 0 || inputFormat == null) {
                Log.e(TAG, "No audio track found in MP3")
                return null
            }

            extractor.selectTrack(audioTrackIndex)

            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            val srcSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels   = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            Log.i(TAG, "MP3 format: $mime ${srcSampleRate}Hz ${srcChannels}ch")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val outputPcm = mutableListOf<ByteArray>()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val timeoutUs = 10_000L

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIdx >= 0) {
                    if (bufferInfo.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        val chunk = ByteArray(bufferInfo.size)
                        outBuf.get(chunk)
                        outputPcm.add(chunk)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }

            val totalSize = outputPcm.sumOf { it.size }
            val rawPcm = ByteArray(totalSize)
            var off = 0
            for (chunk in outputPcm) {
                System.arraycopy(chunk, 0, rawPcm, off, chunk.size)
                off += chunk.size
            }

            Log.i(TAG, "Decoded ${rawPcm.size} bytes PCM (${srcSampleRate}Hz ${srcChannels}ch)")

            var mono = if (srcChannels == 2) stereoToMono(rawPcm) else rawPcm

            if (srcSampleRate != TTS_SAMPLE_RATE) {
                Log.i(TAG, "Resampling from ${srcSampleRate}Hz to ${TTS_SAMPLE_RATE}Hz")
                mono = resample(mono, srcSampleRate, TTS_SAMPLE_RATE)
            }

            Log.i(TAG, "Final PCM: ${mono.size} bytes at ${TTS_SAMPLE_RATE}Hz mono 16-bit")
            return mono

        } catch (e: Exception) {
            Log.e(TAG, "MP3 decode error", e)
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            mp3File.delete()
        }
    }

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int): File {
        val wavFile = File(context.cacheDir, "utterance.wav")
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val fileSize = 36 + dataSize

        RandomAccessFile(wavFile, "rw").use { raf ->
            raf.setLength(0)
            raf.writeBytes("RIFF")
            raf.write(intToLittleEndian(fileSize))
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.write(intToLittleEndian(16))
            raf.write(shortToLittleEndian(1))
            raf.write(shortToLittleEndian(channels))
            raf.write(intToLittleEndian(sampleRate))
            raf.write(intToLittleEndian(byteRate))
            raf.write(shortToLittleEndian(blockAlign))
            raf.write(shortToLittleEndian(bitsPerSample))
            raf.writeBytes("data")
            raf.write(intToLittleEndian(dataSize))
            raf.write(pcm)
        }

        return wavFile
    }

    private fun stereoToMono(stereo: ByteArray): ByteArray {
        val bb = ByteBuffer.wrap(stereo).order(ByteOrder.LITTLE_ENDIAN)
        val mono = ByteBuffer.allocate(stereo.size / 2).order(ByteOrder.LITTLE_ENDIAN)
        while (bb.remaining() >= 4) {
            val left = bb.short.toInt()   // widen to Int before arithmetic to prevent overflow
            val right = bb.short.toInt()
            mono.putShort(((left + right) shr 1).toShort())
        }
        return mono.array()
    }

    /**
     * Peak-normalise PCM16 LE in-place: scale so max |sample| ≈ LIMITER_TARGET_PEAK,
     * but cap the gain at LIMITER_MAX_BOOST so quiet TTS isn't pumped to the noise floor.
     * Recovers perceived loudness lost by the ESP32-side digital attenuation.
     */
    private fun peakLimit(pcm: ByteArray): ByteArray {
        val n = pcm.size / 2
        if (n == 0) return pcm
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        var peak = 1
        for (i in 0 until n) {
            val s = bb.getShort(i * 2).toInt()
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
        }
        val gain = minOf(LIMITER_TARGET_PEAK.toDouble() / peak, LIMITER_MAX_BOOST)
        if (gain <= 1.0) return pcm  // already at-or-above target peak — leave alone
        for (i in 0 until n) {
            val s = (bb.getShort(i * 2) * gain).toInt().coerceIn(-32768, 32767)
            bb.putShort(i * 2, s.toShort())
        }
        return pcm
    }

    /**
     * Resample with a simple windowed-sinc (low-pass) anti-aliasing filter.
     * The previous linear interpolation had no pre-filter, causing aliasing buzz
     * when the source rate (e.g. 24000 Hz) differs from the target (22050 Hz).
     */
    private fun resample(input: ByteArray, srcRate: Int, dstRate: Int): ByteArray {
        val srcBb = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
        val srcSamples = input.size / 2
        val dstSamples = (srcSamples.toLong() * dstRate / srcRate).toInt()
        val dst = ByteBuffer.allocate(dstSamples * 2).order(ByteOrder.LITTLE_ENDIAN)

        val srcArray = FloatArray(srcSamples)
        for (i in 0 until srcSamples) srcArray[i] = srcBb.short.toFloat()

        val ratio = srcRate.toDouble() / dstRate
        // Cutoff at the lower Nyquist; kernel half-width of 16 taps per side
        val cutoff = if (dstRate < srcRate) dstRate.toDouble() / srcRate else 1.0
        val halfWin = 16

        for (i in 0 until dstSamples) {
            val center = i * ratio
            var sum = 0.0
            var weight = 0.0
            val kStart = (center - halfWin).toInt().coerceAtLeast(0)
            val kEnd   = (center + halfWin).toInt().coerceAtMost(srcSamples - 1)
            for (k in kStart..kEnd) {
                val x = (k - center) * cutoff * Math.PI
                val sinc = if (x == 0.0) 1.0 else Math.sin(x) / x
                // Hann window
                val win = 0.5 * (1.0 + Math.cos(Math.PI * (k - center) / halfWin))
                val w = sinc * win
                sum += srcArray[k] * w
                weight += w
            }
            val sample = if (weight != 0.0) (sum / weight) else 0.0
            dst.putShort(sample.toInt().coerceIn(-32768, 32767).toShort())
        }

        return dst.array()
    }

    private fun intToLittleEndian(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun shortToLittleEndian(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
}
