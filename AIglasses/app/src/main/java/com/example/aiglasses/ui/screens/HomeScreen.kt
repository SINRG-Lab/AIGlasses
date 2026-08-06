package com.example.aiglasses.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiglasses.MainViewModel
import com.example.aiglasses.link.WifiPhase
import com.example.aiglasses.model.ConnectionState
import com.example.aiglasses.model.GlassesStatus
import com.example.aiglasses.model.LogEntry
import com.example.aiglasses.model.PipelineStatus
import com.example.aiglasses.model.SavedImage
import com.example.aiglasses.model.VoiceState
import com.example.aiglasses.ui.components.AmbientBackground
import com.example.aiglasses.ui.components.ButtonVariant
import com.example.aiglasses.ui.components.GlassCard
import com.example.aiglasses.ui.components.GlassPillButton
import com.example.aiglasses.ui.components.StatusBadge
import com.example.aiglasses.ui.theme.Blue
import com.example.aiglasses.ui.theme.GlassBorder
import com.example.aiglasses.ui.theme.GlassSurface
import com.example.aiglasses.ui.theme.Green
import com.example.aiglasses.ui.theme.Orange
import com.example.aiglasses.ui.theme.Purple
import com.example.aiglasses.ui.theme.Red
import com.example.aiglasses.ui.theme.TextPrimary
import com.example.aiglasses.ui.theme.TextSecondary
import com.example.aiglasses.ui.theme.TextTertiary
import android.graphics.Bitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onDevModeReveal: () -> Unit
) {
    val glassesStatus by viewModel.glassesStatus.collectAsStateWithLifecycle()
    val pipelineStatus by viewModel.pipelineStatus.collectAsStateWithLifecycle()
    val logMessages by viewModel.logMessages.collectAsStateWithLifecycle()
    val wifiPhase by viewModel.wifiPhase.collectAsStateWithLifecycle()
    val metrics by viewModel.linkMetrics.collectAsStateWithLifecycle()
    val wifiAuto by viewModel.wifiAuto.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val pendingPhoto by viewModel.pendingPhoto.collectAsStateWithLifecycle()
    val photoAttached by viewModel.photoAttached.collectAsStateWithLifecycle()
    val voiceWanted by viewModel.voiceAutoEnabled.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val connectionState = glassesStatus.connectionState

    val recentActivity = remember(logMessages) {
        logMessages.filter { it.tag == "USER" || it.tag == "AI" }.takeLast(5)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AmbientBackground(connectionState = connectionState)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 56.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ConnectionCard(
                glassesStatus = glassesStatus,
                wifiPhase = wifiPhase,
                bleRttMs = metrics.ble.rttMs,
                wifiRttMs = metrics.wifi.rttMs,
                onConnect = { viewModel.startScan() },
                onDisconnect = { viewModel.stopScan() },
                onDevModeReveal = onDevModeReveal
            )

            VoiceCard(
                pipelineStatus = pipelineStatus,
                voiceWanted = voiceWanted,
                apiKeyMissing = apiKey.isBlank(),
                lastError = lastError,
                onRetry = { viewModel.retryVoiceNow() }
            )

            pendingPhoto?.let { photo ->
                PendingPhotoCard(
                    photo = photo,
                    bitmap = glassesStatus.lastImageBitmap,
                    attached = photoAttached,
                    onAsk = { viewModel.askAboutPendingPhoto() },
                    onDismiss = { viewModel.dismissPendingPhoto() }
                )
            }

            WifiAutoCard(
                enabled = wifiAuto,
                phase = wifiPhase,
                onToggle = { viewModel.setWifiAuto(it) }
            )

            if (recentActivity.isNotEmpty()) {
                RecentActivityCard(entries = recentActivity)
            }
        }
    }
}

// ── Connection card: BLE + WiFi state chips, RTT, connect control ──

@Composable
private fun ConnectionCard(
    glassesStatus: GlassesStatus,
    wifiPhase: WifiPhase,
    bleRttMs: Double,
    wifiRttMs: Double,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDevModeReveal: () -> Unit
) {
    val connectionState = glassesStatus.connectionState
    val isScanning = connectionState == ConnectionState.Scanning

    // Pulsing glow while searching
    val infiniteTransition = rememberInfiniteTransition(label = "scan_glow")
    val scanPulse by infiniteTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            tween(1200, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "scan_pulse"
    )
    val glowColor = if (isScanning) Orange.copy(alpha = scanPulse) else Color.Transparent

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (glowColor != Color.Transparent) {
                    drawCircle(
                        color = glowColor,
                        radius = size.maxDimension * 0.6f,
                        center = center
                    )
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onDevModeReveal() })
            },
        depth = 2,
        cornerRadius = 20.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (glassesStatus.deviceName.isNotBlank())
                        glassesStatus.deviceName
                    else "AI Glasses",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                StatusBadge(state = connectionState)
            }

            // Transport chips: Bluetooth is the primary lane, Wi-Fi the bulk lane
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TransportChip(
                    icon = Icons.Filled.Bluetooth,
                    label = "BLE",
                    active = connectionState == ConnectionState.Connected,
                    detail = when {
                        connectionState == ConnectionState.Connected && bleRttMs > 0 ->
                            "${bleRttMs.toInt()} ms"
                        connectionState == ConnectionState.Connected -> "up"
                        isScanning -> "searching"
                        else -> "down"
                    }
                )
                TransportChip(
                    icon = Icons.Filled.Wifi,
                    label = "WiFi",
                    active = wifiPhase is WifiPhase.Active,
                    detail = when (wifiPhase) {
                        is WifiPhase.Active ->
                            if (wifiRttMs > 0) "${wifiRttMs.toInt()} ms" else "up"
                        is WifiPhase.Requesting, is WifiPhase.Approving,
                        is WifiPhase.Connecting -> "joining"
                        is WifiPhase.Failed -> "retrying"
                        else -> "off"
                    }
                )
                if (glassesStatus.mtu > 0) {
                    TransportChip(
                        icon = null,
                        label = "MTU",
                        active = false,
                        detail = "${glassesStatus.mtu}"
                    )
                }
            }

            GlassPillButton(
                text = when (connectionState) {
                    ConnectionState.Disconnected -> "Connect Glasses"
                    ConnectionState.Scanning -> "Stop Searching"
                    ConnectionState.Connected -> "Disconnect"
                },
                onClick = {
                    if (connectionState == ConnectionState.Disconnected) onConnect()
                    else onDisconnect()
                },
                variant = if (connectionState == ConnectionState.Disconnected)
                    ButtonVariant.Accent else ButtonVariant.Danger,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TransportChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    label: String,
    active: Boolean,
    detail: String
) {
    val tint = if (active) Green else TextTertiary
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (active) Green.copy(alpha = 0.10f) else GlassSurface,
        border = BorderStroke(1.dp, if (active) Green.copy(alpha = 0.35f) else GlassBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = tint,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Text(
                    text = label,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    color = TextTertiary
                )
            }
            Text(
                text = detail,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (active) Green else TextSecondary
            )
        }
    }
}

// ── Voice status chip + live transcripts (no toggle; retry only) ──

@Composable
private fun VoiceCard(
    pipelineStatus: PipelineStatus,
    voiceWanted: Boolean,
    apiKeyMissing: Boolean,
    lastError: String?,
    onRetry: () -> Unit
) {
    val state = pipelineStatus.voiceState
    val (label, color) = when {
        !voiceWanted -> "Voice off" to TextTertiary
        apiKeyMissing -> "No API key" to Orange
        else -> when (state) {
            VoiceState.Idle -> "Waiting for glasses…" to TextTertiary
            VoiceState.Connecting -> "Connecting…" to Orange
            VoiceState.Listening -> "Listening" to Blue
            VoiceState.Hearing -> "Hearing you…" to Orange
            VoiceState.Thinking -> "Thinking…" to Purple
            VoiceState.Speaking -> "Speaking" to Green
        }
    }

    val pulse = voiceWanted && (state == VoiceState.Hearing || state == VoiceState.Speaking)
    val infiniteTransition = rememberInfiniteTransition(label = "voice_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(600, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "voice_pulse_alpha"
    )

    GlassCard(depth = 1, cornerRadius = 14.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = color.copy(alpha = if (pulse) pulseAlpha else 1f),
                            shape = RoundedCornerShape(5.dp)
                        )
                )
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "GPT REALTIME",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    color = TextTertiary
                )
            }

            if (apiKeyMissing) {
                Text(
                    text = "Add your OpenAI API key in Settings to enable voice.",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )
            }

            if (lastError != null) {
                Text(
                    text = lastError,
                    fontSize = 13.sp,
                    color = Red,
                    lineHeight = 18.sp
                )
            }

            if (!voiceWanted || lastError != null) {
                GlassPillButton(
                    text = if (voiceWanted) "Retry Now" else "Start Voice",
                    onClick = onRetry,
                    variant = ButtonVariant.Accent,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (pipelineStatus.lastTranscription.isNotBlank()) {
                Text(
                    text = "You: ${pipelineStatus.lastTranscription}",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )
            }
            if (pipelineStatus.lastAiResponse.isNotBlank()) {
                Text(
                    text = pipelineStatus.lastAiResponse,
                    fontSize = 13.sp,
                    color = TextPrimary,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

// ── Latest photo from the glasses + "ask about it" ──

@Composable
private fun PendingPhotoCard(
    photo: SavedImage,
    bitmap: Bitmap?,
    attached: Boolean,
    onAsk: () -> Unit,
    onDismiss: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    GlassCard(depth = 1, cornerRadius = 14.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Latest photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Photo from your glasses",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "${photo.sizeBytes / 1024} KB · ${timeFormat.format(Date(photo.timestamp))}",
                        fontSize = 12.sp,
                        color = TextTertiary
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Dismiss",
                        tint = TextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (attached) {
                Text(
                    text = "Photo attached — ask your question out loud.",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Green
                )
            } else {
                GlassPillButton(
                    text = "Ask about it",
                    onClick = onAsk,
                    variant = ButtonVariant.Accent,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ── WiFi auto-join standing preference ──

@Composable
private fun WifiAutoCard(
    enabled: Boolean,
    phase: WifiPhase,
    onToggle: (Boolean) -> Unit
) {
    GlassCard(depth = 1, cornerRadius = 14.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Wi-Fi photo boost",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Auto-join the glasses' Wi-Fi for faster photos — reconnects by itself",
                        fontSize = 12.sp,
                        color = TextTertiary,
                        lineHeight = 16.sp
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Blue,
                        uncheckedThumbColor = TextTertiary,
                        uncheckedTrackColor = GlassSurface
                    )
                )
            }
            if (enabled) {
                val statusText = when (phase) {
                    is WifiPhase.Off -> "Waiting for Bluetooth…"
                    is WifiPhase.Requesting -> "Asking the glasses to start Wi-Fi…"
                    is WifiPhase.Approving -> "Joining — approve the system dialog if it appears"
                    is WifiPhase.Connecting -> "Connecting to the glasses…"
                    is WifiPhase.Active -> "Connected — photos ride Wi-Fi when it's faster"
                    is WifiPhase.Failed -> phase.message
                }
                Text(
                    text = statusText,
                    fontSize = 12.sp,
                    color = if (phase is WifiPhase.Active) Green else TextSecondary,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

// ── Recent conversation ──

@Composable
private fun RecentActivityCard(
    entries: List<LogEntry>
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "RECENT ACTIVITY",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            color = TextTertiary
        )
        GlassCard(depth = 1, cornerRadius = 14.dp) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                entries.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (entry.tag == "AI") Purple.copy(0.15f) else Blue.copy(0.15f),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                text = entry.tag,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (entry.tag == "AI") Purple else Blue
                            )
                        }
                        Text(
                            text = entry.message,
                            fontSize = 13.sp,
                            color = TextSecondary,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}
