package com.example.aiglasses

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    sealed class PipelineEvent {
        data class Transcription(val text: String) : PipelineEvent()
        data class AiResponse(val text: String) : PipelineEvent()
        data class Error(val message: String) : PipelineEvent()
        data object Processing : PipelineEvent()
        data object TtsSynthesizing : PipelineEvent()
    }

    fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS language not supported, trying default")
                    tts?.setLanguage(Locale.ENGLISH)
                }
                ttsReady = true
                Log.i(TAG, "TTS initialized successfully")
            } else {
                Log.e(TAG, "TTS init failed with status: $status")
            }
        }
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    /**
     * Full pipeline: raw PCM bytes from ESP32 → transcription → AI response → TTS PCM bytes.
     * Runs on a background thread (blocking).
     *
     * @param rawPcm 16kHz, 16-bit, mono PCM from the ESP32 microphone
     * @return PCM bytes at 22050Hz, 16-bit, mono for the ESP32 speaker, or null on error
     */
    fun process(rawPcm: ByteArray): ByteArray? {
        try {
            val duration = rawPcm.size / (MIC_SAMPLE_RATE * 2.0)
            Log.i(TAG, "Processing utterance: ${rawPcm.size} bytes (${String.format("%.2f", duration)}s)")

            // 1. Save PCM as WAV for Whisper API
            onEvent(PipelineEvent.Processing)
            val wavFile = pcmToWav(rawPcm, MIC_SAMPLE_RATE)
            Log.i(TAG, "Saved utterance WAV: ${wavFile.length()} bytes")

            // 2. Transcribe with OpenAI Whisper API
            Log.i(TAG, "Transcribing with Whisper...")
            val userText = openAIService.transcribe(wavFile)
            wavFile.delete()
            Log.i(TAG, "User said: \"$userText\"")
            onEvent(PipelineEvent.Transcription(userText))

            if (userText.isBlank()) {
                Log.w(TAG, "Empty transcription, skipping")
                return null
            }

            // 3. Get AI response from OpenAI
            Log.i(TAG, "Getting AI response...")
            val aiResponse = openAIService.chat(userText)
            Log.i(TAG, "AI response: \"$aiResponse\"")
            onEvent(PipelineEvent.AiResponse(aiResponse))

            // 4. Synthesize TTS -> PCM at 22050Hz mono 16-bit
            onEvent(PipelineEvent.TtsSynthesizing)
            Log.i(TAG, "Synthesizing TTS...")
            val ttsPcm = synthesizeToPcm(aiResponse)
            if (ttsPcm == null) {
                onEvent(PipelineEvent.Error("TTS synthesis failed"))
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
     * Wrap raw PCM into a WAV file for the Whisper API.
     */
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
            // RIFF header
            raf.writeBytes("RIFF")
            raf.write(intToLittleEndian(fileSize))
            raf.writeBytes("WAVE")
            // fmt chunk
            raf.writeBytes("fmt ")
            raf.write(intToLittleEndian(16)) // chunk size
            raf.write(shortToLittleEndian(1)) // PCM format
            raf.write(shortToLittleEndian(channels))
            raf.write(intToLittleEndian(sampleRate))
            raf.write(intToLittleEndian(byteRate))
            raf.write(shortToLittleEndian(blockAlign))
            raf.write(shortToLittleEndian(bitsPerSample))
            // data chunk
            raf.writeBytes("data")
            raf.write(intToLittleEndian(dataSize))
            raf.write(pcm)
        }

        return wavFile
    }

    /**
     * Use Android TTS to synthesize text, then extract raw PCM from the generated WAV.
     */
    private fun synthesizeToPcm(text: String): ByteArray? {
        if (!ttsReady || tts == null) {
            Log.e(TAG, "TTS not ready")
            return null
        }

        val ttsFile = File(context.cacheDir, "tts_output.wav")
        if (ttsFile.exists()) ttsFile.delete()

        val latch = CountDownLatch(1)
        var success = false

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS synthesis started")
            }
            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS synthesis done")
                success = true
                latch.countDown()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS synthesis error (deprecated callback)")
                latch.countDown()
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS synthesis error code: $errorCode")
                latch.countDown()
            }
        })

        val result = tts?.synthesizeToFile(text, null, ttsFile, "tts_utterance")
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "synthesizeToFile returned error: $result")
            return null
        }

        // Wait up to 30 seconds for TTS
        if (!latch.await(30, TimeUnit.SECONDS)) {
            Log.e(TAG, "TTS timed out after 30 seconds")
            return null
        }

        if (!success || !ttsFile.exists() || ttsFile.length() < 44) {
            Log.e(TAG, "TTS file not created or too small: exists=${ttsFile.exists()}, size=${ttsFile.length()}")
            return null
        }

        Log.i(TAG, "TTS WAV file: ${ttsFile.length()} bytes")

        // Extract PCM from WAV, resample to 22050Hz mono 16-bit if needed
        val pcm = extractPcmFromWav(ttsFile)
        ttsFile.delete()

        if (pcm == null) {
            Log.e(TAG, "Failed to extract PCM from TTS WAV")
        }

        return pcm
    }

    /**
     * Read a WAV file and return the raw PCM data section.
     * Android TTS typically outputs at the device's default rate, so we resample to 22050Hz.
     */
    private fun extractPcmFromWav(wavFile: File): ByteArray? {
        try {
            RandomAccessFile(wavFile, "r").use { raf ->
                // Read RIFF header
                val riff = ByteArray(4)
                raf.read(riff)
                if (String(riff) != "RIFF") return null

                raf.skipBytes(4) // file size

                val wave = ByteArray(4)
                raf.read(wave)
                if (String(wave) != "WAVE") return null

                // Find fmt and data chunks
                var srcSampleRate = 0
                var srcChannels = 0
                var srcBitsPerSample = 0
                var pcmData: ByteArray? = null

                while (raf.filePointer < raf.length()) {
                    val chunkId = ByteArray(4)
                    if (raf.read(chunkId) != 4) break
                    val chunkSizeBuf = ByteArray(4)
                    raf.read(chunkSizeBuf)
                    val chunkSize = ByteBuffer.wrap(chunkSizeBuf).order(ByteOrder.LITTLE_ENDIAN).int

                    when (String(chunkId)) {
                        "fmt " -> {
                            val fmtData = ByteArray(chunkSize)
                            raf.read(fmtData)
                            val bb = ByteBuffer.wrap(fmtData).order(ByteOrder.LITTLE_ENDIAN)
                            bb.short // audio format
                            srcChannels = bb.short.toInt()
                            srcSampleRate = bb.int
                            bb.int // byte rate
                            bb.short // block align
                            srcBitsPerSample = bb.short.toInt()
                        }
                        "data" -> {
                            pcmData = ByteArray(chunkSize)
                            raf.read(pcmData)
                        }
                        else -> {
                            raf.skipBytes(chunkSize)
                        }
                    }
                }

                if (pcmData == null || srcSampleRate == 0) {
                    Log.e(TAG, "Invalid WAV: no data or fmt chunk")
                    return null
                }

                Log.i(TAG, "TTS WAV format: ${srcSampleRate}Hz, ${srcChannels}ch, ${srcBitsPerSample}bit, ${pcmData.size} bytes PCM")

                // Convert to mono if stereo
                var mono16 = if (srcChannels == 2 && srcBitsPerSample == 16) {
                    Log.i(TAG, "Converting stereo to mono")
                    stereoToMono(pcmData)
                } else {
                    pcmData
                }

                // Resample to 22050Hz if needed
                if (srcSampleRate != TTS_SAMPLE_RATE) {
                    Log.i(TAG, "Resampling from ${srcSampleRate}Hz to ${TTS_SAMPLE_RATE}Hz")
                    mono16 = resample(mono16, srcSampleRate, TTS_SAMPLE_RATE)
                }

                Log.i(TAG, "Final PCM: ${mono16.size} bytes at ${TTS_SAMPLE_RATE}Hz mono 16-bit")
                return mono16
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting PCM from WAV", e)
            return null
        }
    }

    private fun stereoToMono(stereo: ByteArray): ByteArray {
        val bb = ByteBuffer.wrap(stereo).order(ByteOrder.LITTLE_ENDIAN)
        val mono = ByteBuffer.allocate(stereo.size / 2).order(ByteOrder.LITTLE_ENDIAN)
        while (bb.remaining() >= 4) {
            val left = bb.short
            val right = bb.short
            mono.putShort(((left + right) / 2).toShort())
        }
        return mono.array()
    }

    /**
     * Simple linear interpolation resampling for 16-bit mono PCM.
     */
    private fun resample(input: ByteArray, srcRate: Int, dstRate: Int): ByteArray {
        val srcBb = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
        val srcSamples = input.size / 2
        val dstSamples = (srcSamples.toLong() * dstRate / srcRate).toInt()
        val dst = ByteBuffer.allocate(dstSamples * 2).order(ByteOrder.LITTLE_ENDIAN)

        val srcArray = ShortArray(srcSamples)
        for (i in 0 until srcSamples) {
            srcArray[i] = srcBb.short
        }

        val ratio = srcRate.toDouble() / dstRate
        for (i in 0 until dstSamples) {
            val srcPos = i * ratio
            val idx = srcPos.toInt()
            val frac = srcPos - idx

            val s0 = srcArray[idx.coerceIn(0, srcSamples - 1)]
            val s1 = srcArray[(idx + 1).coerceIn(0, srcSamples - 1)]
            val sample = (s0 + frac * (s1 - s0)).toInt().coerceIn(-32768, 32767)
            dst.putShort(sample.toShort())
        }

        return dst.array()
    }

    private fun intToLittleEndian(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun shortToLittleEndian(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
}
