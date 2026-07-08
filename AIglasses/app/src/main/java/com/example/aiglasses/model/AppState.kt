package com.example.aiglasses.model

import android.graphics.Bitmap

enum class ConnectionState { Disconnected, Scanning, Connected, Active }

enum class InputSource { Voice, Vision }

/** Live GPT Realtime conversation state (mirrors realtime_ble.py's console states). */
enum class VoiceState { Idle, Listening, Hearing, Thinking, Speaking }

data class GlassesStatus(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val deviceName: String = "",
    val deviceAddress: String = "",
    val mtu: Int = 0,
    val activeSource: InputSource = InputSource.Voice,
    val lastImageBitmap: Bitmap? = null,
    val imageByteCount: Int = 0
)

data class PipelineStatus(
    val isProcessing: Boolean = false,
    val lastTranscription: String = "",
    val lastAiResponse: String = "",
    val isSynthesizing: Boolean = false,
    val lastInferenceMs: Long = 0L,
    val voiceState: VoiceState = VoiceState.Idle
)

data class SavedImage(
    val filename: String,
    val timestamp: Long,
    val sizeBytes: Int,
    val isVideo: Boolean = false
)
