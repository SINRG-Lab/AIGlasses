package com.example.aiglasses.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

    val orb1Color: Color
    val orb2Color: Color
    val orb3Color: Color
    when (connectionState) {
        ConnectionState.Active -> { orb1Color = OrbBlue; orb2Color = OrbGreen; orb3Color = OrbPurple }
        ConnectionState.Connected -> { orb1Color = OrbBlue; orb2Color = OrbPurple; orb3Color = OrbBlue }
        ConnectionState.Scanning -> { orb1Color = OrbOrange; orb2Color = OrbPurple; orb3Color = OrbBlue }
        ConnectionState.Disconnected -> { orb1Color = OrbPurple; orb2Color = OrbBlue; orb3Color = OrbPurple }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "ambient")

    val orb1X by infiniteTransition.animateFloat(
        initialValue = 0.1f, targetValue = 0.5f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Reverse),
        label = "o1x"
    )
    val orb1Y by infiniteTransition.animateFloat(
        initialValue = 0.05f, targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(11000, easing = LinearEasing), RepeatMode.Reverse),
        label = "o1y"
    )
    val orb2X by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse),
        label = "o2x"
    )
    val orb2Y by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(7500, easing = LinearEasing), RepeatMode.Reverse),
        label = "o2y"
    )
    val orb3X by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(13000, easing = LinearEasing), RepeatMode.Reverse),
        label = "o3x"
    )
    val orb3Y by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(8500, easing = LinearEasing), RepeatMode.Reverse),
        label = "o3y"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        // Base gradient
        drawRect(brush = Brush.verticalGradient(bgColors))

        val orbRadius = size.minDimension * 0.45f

        // Orb 1
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(orb1Color, Color.Transparent),
                center = Offset(size.width * orb1X, size.height * orb1Y),
                radius = orbRadius
            ),
            radius = orbRadius,
            center = Offset(size.width * orb1X, size.height * orb1Y)
        )

        // Orb 2
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(orb2Color, Color.Transparent),
                center = Offset(size.width * orb2X, size.height * orb2Y),
                radius = orbRadius * 0.8f
            ),
            radius = orbRadius * 0.8f,
            center = Offset(size.width * orb2X, size.height * orb2Y)
        )

        // Orb 3
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(orb3Color, Color.Transparent),
                center = Offset(size.width * orb3X, size.height * orb3Y),
                radius = orbRadius * 0.6f
            ),
            radius = orbRadius * 0.6f,
            center = Offset(size.width * orb3X, size.height * orb3Y)
        )
    }
}
