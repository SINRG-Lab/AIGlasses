package com.example.aiglasses.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiglasses.MainViewModel
import com.example.aiglasses.model.ConnectionState
import com.example.aiglasses.model.GlassesStatus
import com.example.aiglasses.model.InputSource
import com.example.aiglasses.model.LogEntry
import com.example.aiglasses.ui.components.*
import com.example.aiglasses.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onLiveViewOpen: () -> Unit,
    onDevModeReveal: () -> Unit
) {
    val glassesStatus by viewModel.glassesStatus.collectAsStateWithLifecycle()
    val pipelineStatus by viewModel.pipelineStatus.collectAsStateWithLifecycle()
    val logMessages by viewModel.logMessages.collectAsStateWithLifecycle()
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
            // Hero glasses status card
            HeroCard(
                viewModel = viewModel,
                onDevModeReveal = onDevModeReveal
            )

            // Input source strip
            InputSourceStrip(
                glassesStatus = glassesStatus,
                onVisionTap = { if (glassesStatus.lastImageBitmap != null) onLiveViewOpen() }
            )

            // Recent activity
            if (recentActivity.isNotEmpty()) {
                RecentActivityCard(entries = recentActivity)
            }

            // Prompt card when disconnected
            if (connectionState == ConnectionState.Disconnected) {
                GlassCard(depth = 1, cornerRadius = 14.dp) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Tap 'Connect Glasses' to start",
                            fontSize = 14.sp,
                            color = TextTertiary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(
    viewModel: MainViewModel,
    onDevModeReveal: () -> Unit
) {
    val glassesStatus by viewModel.glassesStatus.collectAsStateWithLifecycle()
    val connectionState = glassesStatus.connectionState
    val isActive = connectionState == ConnectionState.Active

    val glowColor by animateColorAsState(
        targetValue = when (connectionState) {
            ConnectionState.Active -> Blue.copy(alpha = 0.5f)
            ConnectionState.Connected -> Blue.copy(alpha = 0.2f)
            ConnectionState.Scanning -> Orange.copy(alpha = 0.2f)
            ConnectionState.Disconnected -> Color.Transparent
        },
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow),
        label = "hero_glow"
    )

    val cardScale by animateFloatAsState(
        targetValue = if (isActive) 1.01f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow),
        label = "hero_scale"
    )

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .scale(cardScale)
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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "AIGLASSES",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp,
                        color = TextTertiary
                    )
                    Text(
                        text = if (glassesStatus.deviceName.isNotBlank())
                            glassesStatus.deviceName
                        else "NemoClaw / OpenClaw",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                }
                StatusBadge(state = connectionState)
            }

            // MTU / signal row
            if (glassesStatus.mtu > 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatChip(label = "MTU", value = "${glassesStatus.mtu}")
                    StatChip(label = "LINK", value = if (glassesStatus.mtu >= 512) "High" else "Std")
                }
            }

            // Connect / disconnect button
            GlassPillButton(
                text = when (connectionState) {
                    ConnectionState.Disconnected -> "Connect Glasses"
                    ConnectionState.Scanning -> "Scanning..."
                    ConnectionState.Connected, ConnectionState.Active -> "Disconnect"
                },
                onClick = {
                    if (connectionState == ConnectionState.Disconnected ||
                        connectionState == ConnectionState.Scanning) {
                        viewModel.startScan()
                    } else {
                        viewModel.stopScan()
                    }
                },
                variant = when (connectionState) {
                    ConnectionState.Disconnected -> ButtonVariant.Accent
                    ConnectionState.Scanning -> ButtonVariant.Default
                    else -> ButtonVariant.Danger
                },
                enabled = connectionState != ConnectionState.Scanning,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = GlassSurface,
        border = BorderStroke(1.dp, GlassBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                color = TextTertiary
            )
            Text(
                text = value,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
        }
    }
}

@Composable
private fun InputSourceStrip(
    glassesStatus: GlassesStatus,
    onVisionTap: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val now = remember { timeFormat.format(Date()) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "INPUT SOURCES",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            color = TextTertiary
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                InputSourceCard(
                    icon = Icons.Filled.Mic,
                    label = "Voice",
                    status = "Tap to talk",
                    isActive = glassesStatus.connectionState == ConnectionState.Active &&
                            glassesStatus.activeSource == InputSource.Voice,
                    timestamp = if (glassesStatus.connectionState != ConnectionState.Disconnected) now else "—",
                    onClick = {}
                )
            }
            item {
                InputSourceCard(
                    icon = Icons.Filled.CameraAlt,
                    label = "Vision",
                    status = if (glassesStatus.lastImageBitmap != null) "Image ready" else "No image",
                    isActive = glassesStatus.activeSource == InputSource.Vision &&
                            glassesStatus.lastImageBitmap != null,
                    timestamp = if (glassesStatus.imageByteCount > 0)
                        "${glassesStatus.imageByteCount / 1024}KB" else "—",
                    onClick = onVisionTap
                )
            }
        }
    }
}

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
