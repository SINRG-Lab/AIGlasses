package com.example.aiglasses.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.aiglasses.ui.theme.ErrorRed
import com.example.aiglasses.ui.theme.OutlineGray
import com.example.aiglasses.ui.theme.SignalOrange
import com.example.aiglasses.ui.theme.TelemetryGreen

/** Link status rendered as one small dot — the only loop permitted at rest. */
enum class DotMode { Idle, Searching, Connecting, Connected, Error }

private val BreathEasing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

/**
 * Spec §7 — "Searching done elegantly".
 *
 * - Searching: 8dp orange dot, luminance breathing 0.35↔1.0 (1100ms), no scale.
 * - Connecting: dot @60% alpha + Ø16dp sweep ring (270° arc, 1200ms/rev).
 * - On success: ring fades out 150ms, dot goes green over 400ms with one
 *   scale blip 1.0→1.25→1.0 — then permanently static.
 * - Idle: static gray (absence, never red). Error: static red.
 */
@Composable
fun StatusDot(
    mode: DotMode,
    modifier: Modifier = Modifier,
    dotSize: Dp = 8.dp
) {
    val ringSize = dotSize * 2

    val dotColor by animateColorAsState(
        targetValue = when (mode) {
            DotMode.Idle -> OutlineGray
            DotMode.Searching, DotMode.Connecting -> SignalOrange
            DotMode.Connected -> TelemetryGreen
            DotMode.Error -> ErrorRed
        },
        animationSpec = tween(400, easing = LinearEasing),
        label = "dot_color"
    )

    // The loop exists only while searching/connecting — static otherwise.
    val breathAlpha: Float
    val sweepAngle: Float
    if (mode == DotMode.Searching || mode == DotMode.Connecting) {
        val transition = rememberInfiniteTransition(label = "dot_loop")
        val breath by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(1100, easing = BreathEasing),
                RepeatMode.Reverse
            ),
            label = "breath"
        )
        val sweep by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                tween(1200, easing = LinearEasing)
            ),
            label = "sweep"
        )
        breathAlpha = breath
        sweepAngle = sweep
    } else {
        breathAlpha = 1f
        sweepAngle = 0f
    }

    val ringAlpha by animateFloatAsState(
        targetValue = if (mode == DotMode.Connecting) 0.9f else 0f,
        animationSpec = tween(150, easing = LinearEasing),
        label = "ring_alpha"
    )

    // One confirmation blip when the link comes up, then static forever.
    val blipScale = remember { Animatable(1f) }
    LaunchedEffect(mode) {
        if (mode == DotMode.Connected) {
            blipScale.snapTo(1f)
            blipScale.animateTo(1.25f, spring(dampingRatio = 0.55f, stiffness = 800f))
            blipScale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 800f))
        } else {
            blipScale.snapTo(1f)
        }
    }

    val dotAlpha = when (mode) {
        DotMode.Searching -> breathAlpha
        DotMode.Connecting -> 0.6f
        else -> 1f
    }

    Canvas(modifier = modifier.size(ringSize)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val dotRadius = (dotSize.toPx() / 2f) * blipScale.value
        drawCircle(
            color = dotColor,
            radius = dotRadius,
            center = center,
            alpha = dotAlpha
        )
        if (ringAlpha > 0f) {
            val stroke = 1.5.dp.toPx()
            drawArc(
                color = SignalOrange,
                startAngle = sweepAngle,
                sweepAngle = 270f,
                useCenter = false,
                alpha = ringAlpha,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke)
            )
        }
    }
}
