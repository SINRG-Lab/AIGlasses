package com.example.aiglasses.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiglasses.MainViewModel
import com.example.aiglasses.link.BleState
import com.example.aiglasses.link.WifiPhase
import com.example.aiglasses.model.LogEntry
import com.example.aiglasses.model.SavedImage
import com.example.aiglasses.model.VoiceState
import com.example.aiglasses.ui.components.DotMode
import com.example.aiglasses.ui.components.Eyebrow
import com.example.aiglasses.ui.components.GlassesGlyph
import com.example.aiglasses.ui.components.PanelCard
import com.example.aiglasses.ui.components.StatusDot
import com.example.aiglasses.ui.components.VoiceWaveform
import com.example.aiglasses.ui.components.WaveformMode
import com.example.aiglasses.ui.theme.BleBlue
import com.example.aiglasses.ui.theme.BleBlueDim
import com.example.aiglasses.ui.theme.ErrorRed
import com.example.aiglasses.ui.theme.Hairline
import com.example.aiglasses.ui.theme.MonoTelemetry
import com.example.aiglasses.ui.theme.PanelHigh
import com.example.aiglasses.ui.theme.PanelHighest
import com.example.aiglasses.ui.theme.SignalOrange
import com.example.aiglasses.ui.theme.SignalOrangeDim
import com.example.aiglasses.ui.theme.TelemetryGreen
import com.example.aiglasses.ui.theme.TelemetryGreenDim
import com.example.aiglasses.ui.theme.TextPrimary
import com.example.aiglasses.ui.theme.TextSecondary
import com.example.aiglasses.ui.theme.TextTertiary
import com.example.aiglasses.ui.theme.WarnAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onDevModeReveal: () -> Unit
) {
    val glassesStatus by viewModel.glassesStatus.collectAsStateWithLifecycle()
    val bleState by viewModel.bleState.collectAsStateWithLifecycle()
    val pipelineStatus by viewModel.pipelineStatus.collectAsStateWithLifecycle()
    val logMessages by viewModel.logMessages.collectAsStateWithLifecycle()
    val wifiPhase by viewModel.wifiPhase.collectAsStateWithLifecycle()
    val metrics by viewModel.linkMetrics.collectAsStateWithLifecycle()
    val fwStats by viewModel.fwStats.collectAsStateWithLifecycle()
    val wifiAuto by viewModel.wifiAuto.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val pendingPhoto by viewModel.pendingPhoto.collectAsStateWithLifecycle()
    val photoAttached by viewModel.photoAttached.collectAsStateWithLifecycle()
    val voiceWanted by viewModel.voiceAutoEnabled.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()

    // Error banner is dismissable per-message (UI-side; controller keeps state).
    var dismissedError by remember { mutableStateOf<String?>(null) }
    val visibleError = lastError?.takeIf { it != dismissedError }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        HomeTopBar(onDeveloper = onDevModeReveal)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                DeviceHeroCard(
                    bleState = bleState,
                    deviceName = glassesStatus.deviceName,
                    firmwareVersion = fwStats?.version ?: 0,
                    mtu = glassesStatus.mtu,
                    wifiPhase = wifiPhase,
                    bleRttMs = metrics.ble.rttMs,
                    wifiRttMs = metrics.wifi.rttMs,
                    onConnect = { viewModel.startScan() },
                    onDisconnect = { viewModel.stopScan() },
                    onDevModeReveal = onDevModeReveal
                )
            }

            if (visibleError != null) {
                item {
                    ErrorBanner(
                        message = visibleError,
                        onDismiss = { dismissedError = visibleError }
                    )
                }
            }

            item {
                Eyebrow("Voice", modifier = Modifier.padding(top = 8.dp))
            }

            item {
                VoiceChip(
                    voiceState = pipelineStatus.voiceState,
                    voiceWanted = voiceWanted,
                    apiKeyMissing = apiKey.isBlank(),
                    hasError = lastError != null,
                    bleConnected = bleState is BleState.Connected,
                    onRetry = { viewModel.retryVoiceNow() }
                )
            }

            item {
                TranscriptStream(
                    logMessages = logMessages,
                    liveUser = pipelineStatus.lastTranscription,
                    liveAssistant = pipelineStatus.lastAiResponse,
                    voiceState = pipelineStatus.voiceState
                )
            }

            item {
                PendingPhotoCard(
                    photo = pendingPhoto,
                    bitmap = glassesStatus.lastImageBitmap,
                    attached = photoAttached,
                    onAsk = { viewModel.askAboutPendingPhoto() },
                    onDismiss = { viewModel.dismissPendingPhoto() }
                )
            }

            item {
                Eyebrow("Network", modifier = Modifier.padding(top = 8.dp))
            }

            item {
                AutoWifiRow(
                    enabled = wifiAuto,
                    phase = wifiPhase,
                    wifiRttMs = metrics.wifi.rttMs,
                    onToggle = { viewModel.setWifiAuto(it) }
                )
            }
        }
    }
}

// ── §5.1 Top bar — wordmark + overflow, nothing else ──

@Composable
private fun HomeTopBar(onDeveloper: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "SINRG // GLASSES",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = TextSecondary
        )
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "More",
                    tint = TextSecondary
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = PanelHigh
            ) {
                DropdownMenuItem(
                    text = { Text("Developer", color = TextPrimary) },
                    onClick = {
                        menuOpen = false
                        onDeveloper()
                    }
                )
            }
        }
    }
}

// ── §5.2 Device hero card ──

@Composable
private fun DeviceHeroCard(
    bleState: BleState,
    deviceName: String,
    firmwareVersion: Int,
    mtu: Int,
    wifiPhase: WifiPhase,
    bleRttMs: Double,
    wifiRttMs: Double,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDevModeReveal: () -> Unit
) {
    val connected = bleState is BleState.Connected

    val dotMode = when (bleState) {
        is BleState.Connected -> DotMode.Connected
        is BleState.Connecting -> DotMode.Connecting
        is BleState.Scanning -> DotMode.Searching
        is BleState.Disconnected -> DotMode.Idle
    }
    val statusWord = when (bleState) {
        is BleState.Connected -> "Connected"
        is BleState.Connecting -> "Connecting…"
        is BleState.Scanning -> "Searching…"
        is BleState.Disconnected -> "Not connected"
    }
    val statusColor = if (connected) TextPrimary else TextSecondary

    // Glyph stroke lerps to Text primary when connected (300ms).
    val glyphColor by animateColorAsState(
        targetValue = if (connected) TextPrimary else TextSecondary,
        animationSpec = tween(300, easing = LinearEasing),
        label = "glyph_color"
    )
    // Border cross-fades on state change; nothing moves.
    val borderColor by animateColorAsState(
        targetValue = if (connected) TelemetryGreen.copy(alpha = 0.25f) else Hairline,
        animationSpec = tween(300, easing = LinearEasing),
        label = "hero_border"
    )

    PanelCard(
        shape = MaterialTheme.shapes.large,
        borderColor = borderColor,
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .pointerInput(connected, bleState is BleState.Disconnected) {
                detectTapGestures(
                    onTap = {
                        if (bleState is BleState.Disconnected) onConnect() else onDisconnect()
                    },
                    onLongPress = { onDevModeReveal() }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 184.dp)
                .padding(20.dp)
        ) {
            GlassesGlyph(color = glyphColor)

            Spacer(Modifier.height(14.dp))

            Text(
                text = deviceName.ifBlank { "AI Glasses" },
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
            val telemetryLine = buildString {
                if (firmwareVersion > 0) append("fw v$firmwareVersion")
                if (mtu > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("mtu $mtu")
                }
                if (isEmpty()) append("esp32-s3")
            }
            Text(
                text = telemetryLine,
                style = MonoTelemetry,
                color = TextTertiary
            )

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(mode = dotMode)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusWord,
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor
                )
            }

            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TransportChip(
                    label = "BLE",
                    active = connected,
                    activeColor = BleBlue,
                    activeFill = BleBlueDim
                )
                val wifiArming = wifiPhase is WifiPhase.Requesting ||
                    wifiPhase is WifiPhase.Approving || wifiPhase is WifiPhase.Connecting
                TransportChip(
                    label = "WIFI",
                    active = wifiPhase is WifiPhase.Active,
                    activeColor = TelemetryGreen,
                    activeFill = TelemetryGreenDim,
                    arming = wifiArming
                )
                val rtt = if (wifiPhase is WifiPhase.Active && wifiRttMs > 0) wifiRttMs else bleRttMs
                if (rtt > 0) {
                    RttChip(rttMs = rtt)
                }
            }
        }
    }
}

@Composable
private fun TransportChip(
    label: String,
    active: Boolean,
    activeColor: Color,
    activeFill: Color,
    arming: Boolean = false
) {
    val text = when {
        active -> activeColor
        arming -> WarnAmber
        else -> TextTertiary
    }
    val fill by animateColorAsState(
        targetValue = if (active) activeFill else PanelHigh,
        animationSpec = tween(200, easing = LinearEasing),
        label = "chip_fill"
    )
    val border by animateColorAsState(
        targetValue = when {
            active -> activeColor.copy(alpha = 0.5f)
            arming -> WarnAmber.copy(alpha = 0.35f)
            else -> Color.Transparent
        },
        animationSpec = tween(200, easing = LinearEasing),
        label = "chip_border"
    )
    Surface(
        shape = CircleShape,
        color = fill,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, border)
    ) {
        Box(
            modifier = Modifier
                .height(32.dp)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = text
            )
        }
    }
}

@Composable
private fun RttChip(rttMs: Double) {
    // Value color thresholds; number does NOT tick-animate.
    val valueColor by animateColorAsState(
        targetValue = when {
            rttMs < 100 -> TelemetryGreen
            rttMs < 300 -> WarnAmber
            else -> ErrorRed
        },
        animationSpec = tween(200, easing = LinearEasing),
        label = "rtt_color"
    )
    Surface(
        shape = CircleShape,
        color = PanelHigh,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .height(32.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "RTT",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
            Text(
                text = "${rttMs.toInt()} ms",
                style = MonoTelemetry,
                color = valueColor
            )
        }
    }
}

// ── Error banner (iOS parity) ──

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit
) {
    PanelCard(borderColor = ErrorRed.copy(alpha = 0.35f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = ErrorRed,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = TextTertiary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

// ── §5.3 Voice chip — 5-bar waveform + label + retry ──

@Composable
private fun VoiceChip(
    voiceState: VoiceState,
    voiceWanted: Boolean,
    apiKeyMissing: Boolean,
    hasError: Boolean,
    bleConnected: Boolean,
    onRetry: () -> Unit
) {
    val (mode, label) = when {
        apiKeyMissing -> WaveformMode.Error to "Add your OpenAI API key in Settings"
        !voiceWanted -> WaveformMode.Error to "Voice off"
        hasError -> WaveformMode.Error to "Assistant unavailable"
        else -> when (voiceState) {
            VoiceState.Idle ->
                WaveformMode.Idle to if (bleConnected) "Starting voice…" else "Waiting for glasses…"
            VoiceState.Connecting -> WaveformMode.Thinking to "Connecting…"
            VoiceState.Listening -> WaveformMode.Idle to "Ready — hold the button to talk"
            VoiceState.Hearing -> WaveformMode.Listening to "Listening…"
            VoiceState.Thinking -> WaveformMode.Thinking to "Thinking…"
            VoiceState.Speaking -> WaveformMode.Speaking to "Responding…"
        }
    }
    val showRetry = !apiKeyMissing && (!voiceWanted || hasError)

    PanelCard(shape = CircleShape) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VoiceWaveform(mode = mode)
            Spacer(Modifier.width(12.dp))
            AnimatedContent(
                targetState = label,
                transitionSpec = {
                    (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 3 })
                        .togetherWith(fadeOut(tween(150)))
                },
                label = "voice_label",
                modifier = Modifier.weight(1f)
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 1
                )
            }
            if (showRetry) {
                TextButton(onClick = onRetry) {
                    Text(
                        text = if (voiceWanted) "Retry" else "Start",
                        style = MaterialTheme.typography.labelLarge,
                        color = SignalOrange
                    )
                }
            }
        }
    }
}

// ── §5.3 Transcript stream — no bubbles, no avatars ──

private data class Turn(
    val isUser: Boolean,
    val text: String,
    val timestamp: Long?,
    val partial: Boolean
)

@Composable
private fun TranscriptStream(
    logMessages: List<LogEntry>,
    liveUser: String,
    liveAssistant: String,
    voiceState: VoiceState
) {
    val turns = remember(logMessages, liveUser, liveAssistant, voiceState) {
        val finals = logMessages
            .filter { it.tag == "USER" || it.tag == "AI" }
            .takeLast(4)
            .map { Turn(it.tag == "USER", it.message, it.timestamp, partial = false) }
            .toMutableList()
        // Live assistant partial: streaming text that hasn't been logged yet.
        val lastAi = finals.lastOrNull { !it.isUser }?.text
        if (liveAssistant.isNotBlank() && liveAssistant != lastAi &&
            (voiceState == VoiceState.Speaking || voiceState == VoiceState.Thinking)
        ) {
            finals += Turn(isUser = false, text = liveAssistant, timestamp = null, partial = true)
        }
        finals.takeLast(4).toList()
    }

    if (turns.isEmpty()) return

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        turns.forEach { turn ->
            // Partial settles to full alpha on final (250ms).
            val alpha by animateFloatAsState(
                targetValue = if (turn.partial) 0.7f else 1f,
                animationSpec = tween(250, easing = LinearEasing),
                label = "turn_alpha"
            )
            Column {
                turn.timestamp?.let {
                    Text(
                        text = timeFormat.format(Date(it)),
                        style = com.example.aiglasses.ui.theme.MonoData,
                        color = TextTertiary,
                        modifier = Modifier.padding(start = 12.dp, bottom = 2.dp)
                    )
                }
                if (turn.isUser) {
                    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                        Box(
                            modifier = Modifier
                                .padding(vertical = 3.dp)
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(SignalOrange, RoundedCornerShape(1.dp))
                        )
                        Text(
                            text = turn.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary.copy(alpha = alpha),
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                } else {
                    Text(
                        text = turn.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary.copy(alpha = alpha),
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }
    }
}

// ── §5.5 Pending-photo card ──

@Composable
private fun PendingPhotoCard(
    photo: SavedImage?,
    bitmap: Bitmap?,
    attached: Boolean,
    onAsk: () -> Unit,
    onDismiss: () -> Unit
) {
    // Hold the last photo so the exit animation has content to show.
    var lastPhoto by remember { mutableStateOf(photo) }
    if (photo != null) lastPhoto = photo
    val shown = lastPhoto ?: return

    AnimatedVisibility(
        visible = photo != null,
        enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) +
            fadeIn(tween(200)),
        exit = fadeOut(tween(150))
    ) {
        val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
        PanelCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Photo from glasses",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(MaterialTheme.shapes.small)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(PanelHighest)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (attached) "Photo attached" else "Photo from glasses",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = if (attached) "Ask your question out loud"
                        else "${shown.sizeBytes / 1024} KB · ${timeFormat.format(Date(shown.timestamp))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (attached) SignalOrange else TextSecondary
                    )
                }
                if (!attached) {
                    TextButton(onClick = onAsk) {
                        Text(
                            text = "Ask about it",
                            style = MaterialTheme.typography.labelLarge,
                            color = SignalOrange
                        )
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Dismiss",
                        tint = TextTertiary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

// ── §5.4 Auto-WiFi row ──

@Composable
private fun AutoWifiRow(
    enabled: Boolean,
    phase: WifiPhase,
    wifiRttMs: Double,
    onToggle: (Boolean) -> Unit
) {
    val active = phase is WifiPhase.Active
    val subtitle = when (phase) {
        is WifiPhase.Off ->
            if (enabled) "Waiting for Bluetooth…"
            else "Joins the glasses' network for faster photo transfer"
        is WifiPhase.Requesting -> "Asking the glasses to start Wi-Fi…"
        is WifiPhase.Approving -> "Joining — approve the system dialog if it appears"
        is WifiPhase.Connecting -> "Opening the data link…"
        is WifiPhase.Active ->
            if (wifiRttMs > 0) "link up · ${wifiRttMs.toInt()} ms" else "link up"
        is WifiPhase.Failed -> phase.message
    }
    val subtitleColor = when {
        active -> TelemetryGreen
        phase is WifiPhase.Failed -> WarnAmber
        else -> TextTertiary
    }

    PanelCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Outlined.Wifi,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Auto Wi-Fi transfer",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (active) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(TelemetryGreen, CircleShape)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = subtitle,
                        style = if (active) MonoTelemetry.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize)
                        else MaterialTheme.typography.bodySmall,
                        color = subtitleColor
                    )
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TextPrimary,
                    checkedTrackColor = SignalOrange,
                    uncheckedThumbColor = TextTertiary,
                    uncheckedTrackColor = PanelHighest,
                    uncheckedBorderColor = Hairline
                )
            )
        }
    }
}
