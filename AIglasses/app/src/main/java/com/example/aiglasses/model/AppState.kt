package com.example.aiglasses.model

import android.graphics.Bitmap

enum class ConnectionState { Disconnected, Scanning, Connected, Active }

enum class InputSource { Voice, Vision }

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
    val lastInferenceMs: Long = 0L
)
