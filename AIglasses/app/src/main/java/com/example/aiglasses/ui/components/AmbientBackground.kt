package com.example.aiglasses.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.aiglasses.model.ConnectionState
import com.example.aiglasses.ui.theme.*

@Composable
fun AmbientBackground(
    connectionState: ConnectionState = ConnectionState.Disconnected,
    modifier: Modifier = Modifier
) {
    val bgColors = when (connectionState) {
        ConnectionState.Active -> listOf(BgActiveStart, BgActiveMid, BgActiveEnd)
        else -> listOf(BgStart, BgMid, BgEnd)
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        // Clean diagonal gradient — Apple-style deep dark
        drawRect(
            brush = Brush.linearGradient(
                colors = bgColors,
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height)
            )
        )

        // Subtle fixed glow in top-right — like a soft ambient light source
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Purple.copy(alpha = 0.08f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.85f, size.height * 0.08f),
                radius = size.minDimension * 0.6f
            ),
            radius = size.minDimension * 0.6f,
            center = Offset(size.width * 0.85f, size.height * 0.08f)
        )

    }
}
