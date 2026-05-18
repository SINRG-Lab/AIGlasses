package com.example.aiglasses.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiglasses.MainViewModel
import com.example.aiglasses.model.ConnectionState
import com.example.aiglasses.ui.components.AmbientBackground
import com.example.aiglasses.ui.components.FeatureToggleCard
import com.example.aiglasses.ui.theme.TextPrimary
import com.example.aiglasses.ui.theme.TextTertiary

private data class FeatureItem(
    val id: String,
    val iconKey: String,
    val name: String,
    val description: String,
    val locked: Boolean = false
)

private val features = listOf(
    FeatureItem("voice", "mic", "Voice Assistant", "Real-time AI responses to voice queries"),
    FeatureItem("vision", "camera", "Vision Mode", "AI scene description from camera feed"),
    FeatureItem("transcription", "record", "Transcription", "Live speech-to-text via Whisper"),
    FeatureItem("tts", "volume", "TTS Response", "Text-to-speech AI replies via Nova voice"),
    FeatureItem("focus", "visibility", "Focus Mode", "Suppress non-critical output during tasks"),
    FeatureItem("trackpad", "touch_app", "Trackpad Gestures", "Navigate with glasses trackpad", locked = true),
    FeatureItem("display", "display_settings", "Glasses Display", "Show output on glasses display", locked = true)
)

@Composable
fun FeaturesScreen(viewModel: MainViewModel) {
    val glassesStatus by viewModel.glassesStatus.collectAsStateWithLifecycle()
    val isConnected = glassesStatus.connectionState != ConnectionState.Disconnected

    val enabledState = remember { mutableStateMapOf<String, Boolean>().apply {
        put("voice", true)
        put("transcription", true)
        put("tts", true)
        put("vision", false)
        put("focus", false)
        put("trackpad", false)
        put("display", false)
    } }

    Box(modifier = Modifier.fillMaxSize()) {
        AmbientBackground(connectionState = glassesStatus.connectionState)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(top = 56.dp, bottom = 100.dp)
        ) {
            Text(
                text = "Capabilities",
                style = androidx.compose.material3.MaterialTheme.typography.displayMedium,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "Toggle features for your glasses",
                fontSize = 15.sp,
                color = TextTertiary,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(features) { feature ->
                    val icon = when (feature.iconKey) {
                        "mic" -> Icons.Filled.Mic
                        "camera" -> Icons.Filled.CameraAlt
                        "record" -> Icons.Filled.GraphicEq
                        "volume" -> Icons.Filled.VolumeUp
                        "visibility" -> Icons.Filled.Visibility
                        "touch_app" -> Icons.Filled.TouchApp
                        else -> Icons.Filled.Widgets
                    }
                    val isLocked = feature.locked ||
                            (feature.id == "vision" && !isConnected)

                    FeatureToggleCard(
                        icon = icon,
                        name = feature.name,
                        description = feature.description,
                        isEnabled = enabledState[feature.id] ?: false,
                        isLocked = isLocked,
                        onToggle = { enabled ->
                            if (!isLocked) enabledState[feature.id] = enabled
                        }
                    )
                }
            }
        }
    }
}
