package com.example.aiglasses.model

import android.graphics.Bitmap

/** Primary-link (BLE) connection state as shown in the UI. */
enum class ConnectionState { Disconnected, Scanning, Connected }

/** Live GPT Realtime conversation state (mirrors iOS AppModel.VoiceStatus). */
enum class VoiceState { Idle, Connecting, Listening, Hearing, Thinking, Speaking }

data class GlassesStatus(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val deviceName: String = "",
    val mtu: Int = 0,
    val lastImageBitmap: Bitmap? = null,
    val imageByteCount: Int = 0
)

/** Voice conversation surface: state + latest transcripts. */
data class PipelineStatus(
    val lastTranscription: String = "",
    val lastAiResponse: String = "",
    val voiceState: VoiceState = VoiceState.Idle
)

data class SavedImage(
    val filename: String,
    val timestamp: Long,
    val sizeBytes: Int,
    val isVideo: Boolean = false
)
