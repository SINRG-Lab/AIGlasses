package com.example.aiglasses

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * User-adjustable voice-session settings. Authentication and model selection
 * are intentionally server-managed and never stored in this preferences file.
 */
class RealtimeSettings(context: Context) {

    companion object {
        const val PREFS_NAME = "aiglasses_prefs"
        /** Removed from all reads; retained only to erase keys saved by older app versions. */
        private const val LEGACY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_VOICE = "realtime_voice"
        private const val KEY_EFFORT = "realtime_effort"
        private const val KEY_GLASSES_VOICE_ENABLED = "glasses_voice_enabled"

        val VOICES = listOf("marin", "cedar", "alloy")
        val EFFORTS = listOf("minimal", "low", "medium", "high")

        val DEFAULT_VOICE = VOICES[0]
        const val DEFAULT_EFFORT = "low"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Upgrading must remove any permanent key a previous build accepted.
        if (prefs.contains(LEGACY_OPENAI_API_KEY)) {
            prefs.edit().remove(LEGACY_OPENAI_API_KEY).apply()
        }
    }

    private val _voice = MutableStateFlow(prefs.getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE)
    val voice: StateFlow<String> = _voice.asStateFlow()

    private val _effort = MutableStateFlow(prefs.getString(KEY_EFFORT, DEFAULT_EFFORT) ?: DEFAULT_EFFORT)
    val effort: StateFlow<String> = _effort.asStateFlow()

    /** Durable privacy preference honored by the foreground service after process restarts. */
    val glassesVoiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_GLASSES_VOICE_ENABLED, true)

    fun setVoice(voice: String) {
        _voice.update { voice }
        prefs.edit().putString(KEY_VOICE, voice).apply()
    }

    fun setEffort(effort: String) {
        _effort.update { effort }
        prefs.edit().putString(KEY_EFFORT, effort).apply()
    }

    fun setGlassesVoiceEnabled(enabled: Boolean) {
        // This controls whether locked-phone physical-button audio is accepted, so commit before
        // returning rather than risking a process death before an asynchronous apply reaches disk.
        prefs.edit().putBoolean(KEY_GLASSES_VOICE_ENABLED, enabled).commit()
    }
}
