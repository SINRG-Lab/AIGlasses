package com.example.aiglasses.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiglasses.MainViewModel
import com.example.aiglasses.model.ConnectionState
import com.example.aiglasses.model.InputSource
import com.example.aiglasses.ui.components.*
import com.example.aiglasses.ui.theme.*

@Composable
fun LiveViewScreen(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val glassesStatus by viewModel.glassesStatus.collectAsStateWithLifecycle()
    val pipelineStatus by viewModel.pipelineStatus.collectAsStateWithLifecycle()
    var isMuted by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        AmbientBackground(connectionState = ConnectionState.Active)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(top = 56.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusBadge(state = glassesStatus.connectionState)
                    if (glassesStatus.activeSource == InputSource.Vision) {
                        SourceChip(icon = { Icon(Icons.Filled.CameraAlt, null, tint = Blue, modifier = Modifier.size(14.dp)) }, label = "Camera")
                    }
                    SourceChip(icon = { Icon(Icons.Filled.Mic, null, tint = Green, modifier = Modifier.size(14.dp)) }, label = "Mic")
                }
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

            // Center content
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Image preview
                glassesStatus.lastImageBitmap?.let { bitmap ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = GlassSurface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .aspectRatio(4f / 3f)
                    ) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Captured frame",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // AI output card
                LiveOutputCard(
                    text = pipelineStatus.lastAiResponse,
                    isLoading = pipelineStatus.isProcessing,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Bottom controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GlassPillButton(
                    text = "Stop",
                    onClick = {
                        viewModel.stopScan()
                        onDismiss()
                    },
                    variant = ButtonVariant.Danger,
                    modifier = Modifier.weight(1f)
                )
                GlassPillButton(
                    text = if (isMuted) "Unmute" else "Mute",
                    onClick = { isMuted = !isMuted },
                    variant = if (isMuted) ButtonVariant.Accent else ButtonVariant.Default,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SourceChip(
    icon: @Composable () -> Unit,
    label: String
) {
    Surface(
        shape = RoundedCornerShape(980.dp),
        color = GlassSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Text(
                text = label,
                fontSize = 11.sp,
                color = TextSecondary
            )
        }
    }
}
