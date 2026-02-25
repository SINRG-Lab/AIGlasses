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
        private const val TTS_SAMPLE_RATE = 22050
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
            val duration = rawPcm.size / (MIC_SAMPLE_RATE * 2.0)
            Log.i(TAG, "Processing utterance: ${rawPcm.size} bytes (${String.format("%.2f", duration)}s)")

            // 1. Save PCM as WAV for Whisper
            onEvent(PipelineEvent.Processing)
            val wavFile = pcmToWav(rawPcm, MIC_SAMPLE_RATE)
            Log.i(TAG, "Saved utterance WAV: ${wavFile.length()} bytes")

            // 2. Transcribe with Whisper
            Log.i(TAG, "Transcribing with Whisper...")
            val userText = openAIService.transcribe(wavFile)
            wavFile.delete()
            Log.i(TAG, "User said: \"$userText\"")
            onEvent(PipelineEvent.Transcription(userText))

            if (userText.isBlank()) {
                Log.w(TAG, "Empty transcription, skipping")
                return null
            }

            // 3. Get AI response from GPT-4o-mini
            Log.i(TAG, "Getting AI response...")
            val aiResponse = openAIService.chat(userText)
            Log.i(TAG, "AI response: \"$aiResponse\"")
            onEvent(PipelineEvent.AiResponse(aiResponse))

            // 4. Synthesize with OpenAI TTS → decode MP3 → PCM at 22050Hz
            onEvent(PipelineEvent.TtsSynthesizing)
            Log.i(TAG, "Synthesizing with OpenAI TTS...")
            val mp3Bytes = openAIService.speak(aiResponse)
            Log.i(TAG, "OpenAI TTS returned ${mp3Bytes.size} bytes of MP3")

            val ttsPcm = decodeMp3ToPcm(mp3Bytes)
            if (ttsPcm == null) {
                onEvent(PipelineEvent.Error("MP3 decode failed"))
                return null
            }
            val ttsDuration = ttsPcm.size / (TTS_SAMPLE_RATE * 2.0)
            Log.i(TAG, "TTS PCM ready: ${ttsPcm.size} bytes (${String.format("%.2f", ttsDuration)}s)")

            return ttsPcm
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline error", e)
            onEvent(PipelineEvent.Error(e.message ?: "Unknown error"))
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
