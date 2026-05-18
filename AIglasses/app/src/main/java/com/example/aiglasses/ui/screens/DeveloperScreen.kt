package com.example.aiglasses.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiglasses.MainViewModel
import com.example.aiglasses.model.ConnectionState
import com.example.aiglasses.ui.components.*
import com.example.aiglasses.ui.theme.*

@Composable
fun DeveloperScreen(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val glassesStatus by viewModel.glassesStatus.collectAsStateWithLifecycle()
    val pipelineStatus by viewModel.pipelineStatus.collectAsStateWithLifecycle()
    val logMessages by viewModel.logMessages.collectAsStateWithLifecycle()
    val devModeEnabled by viewModel.devModeEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        AmbientBackground(connectionState = glassesStatus.connectionState)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(top = 56.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Developer",
                        style = MaterialTheme.typography.displayMedium,
                        color = TextPrimary
                    )
                    Surface(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(980.dp),
                        color = GlassSurface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = TextSecondary,
                            modifier = Modifier.padding(10.dp).size(18.dp)
                        )
                    }
                }
            }

            // Warning banner
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Orange.copy(alpha = 0.12f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Orange.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Warning, null, tint = Orange, modifier = Modifier.size(18.dp))
                        Text(
                            text = "Advanced mode — may affect performance",
                            fontSize = 13.sp,
                            color = Orange
                        )
                    }
                }
            }

            // Dev mode master toggle
            item {
                GlassCard(depth = 2, cornerRadius = 14.dp) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Developer Mode", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text(
                                if (devModeEnabled) "Active — extra logging enabled" else "Inactive",
                                fontSize = 12.sp, color = TextTertiary
                            )
                        }
                        Switch(
                            checked = devModeEnabled,
                            onCheckedChange = { viewModel.setDevMode(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Orange,
                                checkedTrackColor = Orange.copy(alpha = 0.3f),
                                uncheckedThumbColor = TextTertiary,
                                uncheckedTrackColor = GlassSurface,
                                uncheckedBorderColor = GlassBorder
                            )
                        )
                    }
                }
            }

            // Connection section
            item {
                DevSection(title = "Connection") {
                    DevStatRow("State", glassesStatus.connectionState.name)
                    DevStatRow("Device", if (glassesStatus.deviceName.isNotBlank()) glassesStatus.deviceName else "—")
                    DevStatRow("MTU", if (glassesStatus.mtu > 0) "${glassesStatus.mtu} bytes" else "—")
                    DevStatRow("Image size", if (glassesStatus.imageByteCount > 0) "${glassesStatus.imageByteCount} bytes" else "—")
                }
            }

            // Pipeline section
            item {
                DevSection(title = "Pipeline") {
                    DevStatRow("Processing", "${pipelineStatus.isProcessing}")
                    DevStatRow("Synthesizing", "${pipelineStatus.isSynthesizing}")
                    DevStatRow(
                        "Last transcription",
                        if (pipelineStatus.lastTranscription.isNotBlank())
                            pipelineStatus.lastTranscription.take(60)
                        else "—"
                    )
                    DevStatRow(
                        "Last response",
                        if (pipelineStatus.lastAiResponse.isNotBlank())
                            pipelineStatus.lastAiResponse.take(60)
                        else "—"
                    )
                    if (pipelineStatus.lastInferenceMs > 0) {
                        DevStatRow("Inference time", "${pipelineStatus.lastInferenceMs}ms")
                    }
                }
            }

            // AI Engine section
            item {
                DevSection(title = "AI Engine") {
                    DevStatRow("ASR model", "whisper-1")
                    DevStatRow("Chat model", "gpt-4o-mini")
                    DevStatRow("Vision model", "gpt-4o")
                    DevStatRow("TTS model", "tts-1")
                    DevStatRow("TTS voice", "nova")
                    DevStatRow("Mic sample rate", "16000 Hz")
                    DevStatRow("TTS sample rate", "22050 Hz")
                }
            }

            // Export section
            item {
                DevSection(title = "Export") {
                    Spacer(Modifier.height(4.dp))
                    GlassPillButton(
                        text = "Copy Logs to Clipboard",
                        onClick = {
                            val logsText = viewModel.copyLogsToClipboard()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("AI Glasses Logs", logsText))
                            Toast.makeText(context, "Logs copied (${logMessages.size} entries)", Toast.LENGTH_SHORT).show()
                        },
                        variant = ButtonVariant.Default,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Full log stream
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "LOG STREAM (${logMessages.size})",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp,
                        color = TextTertiary
                    )
                    GlassCard(depth = 1, cornerRadius = 14.dp) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                        ) {
                            LogStream(
                                entries = logMessages,
                                modifier = Modifier.fillMaxSize(),
                                emptyMessage = "No log entries"
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun DevSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            color = TextTertiary
        )
        GlassCard(depth = 1, cornerRadius = 14.dp) {
            Column(
                modifier = Modifier.padding(16.dp),
                content = content
            )
        }
    }
}
