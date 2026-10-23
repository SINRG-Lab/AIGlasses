package com.example.aiglasses.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.example.aiglasses.ui.theme.SignalOrange
import com.example.aiglasses.ui.theme.TextPrimary
import com.example.aiglasses.ui.theme.TextTertiary
import kotlin.math.PI
import kotlin.math.sin

/** Visual mode of the 5-bar mini waveform (spec §5.3). */
enum class WaveformMode { Idle, Listening, Thinking, Speaking, Error }

/**
 * 5-bar mini waveform, 27×20dp: bars 3dp wide, 3dp gap, r=1.5dp, centered.
 *
 * - Idle/Error: five static 4dp dots (tertiary / gray).
 * - Listening: bars breathe 4→16dp in orange, per-bar phase offset — organic.
 * - Thinking: dots with an alpha wave travelling left→right, orange.
 * - Speaking: like listening but 4→12dp in text-primary.
 */
@Composable
fun VoiceWaveform(
    mode: WaveformMode,
    modifier: Modifier = Modifier
) {
    val color by animateColorAsState(
        targetValue = when (mode) {
            WaveformMode.Listening, WaveformMode.Thinking -> SignalOrange
            WaveformMode.Speaking -> TextPrimary
            WaveformMode.Idle, WaveformMode.Error -> TextTertiary
        },
        animationSpec = tween(200, easing = LinearEasing),
        label = "wave_color"
    )

    // Loop only while genuinely alive — idle/error bars are fully static.
    val live = mode == WaveformMode.Listening ||
        mode == WaveformMode.Thinking || mode == WaveformMode.Speaking
    val heightPhase: Float
    val alphaPhase: Float
    if (live) {
        val transition = rememberInfiniteTransition(label = "wave")
        // Bar-height phase (600ms sine cycle, per-bar 100ms stagger).
        val h by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing)),
            label = "wave_height"
        )
        // Thinking alpha wave (900ms cycle, per-dot 120ms stagger).
        val a by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "wave_alpha"
        )
        heightPhase = h
        alphaPhase = a
    } else {
        heightPhase = 0f
        alphaPhase = 0f
    }

    Canvas(modifier = modifier.size(27.dp, 20.dp)) {
        val barW = 3.dp.toPx()
        val gap = 3.dp.toPx()
        val minH = 4.dp.toPx()
        val maxH = when (mode) {
            WaveformMode.Listening -> 16.dp.toPx()
            WaveformMode.Speaking -> 12.dp.toPx()
            else -> minH
        }
        val radius = CornerRadius(1.5.dp.toPx())
        val centerY = size.height / 2f

        for (i in 0 until 5) {
            val h: Float
            val a: Float
            when (mode) {
                WaveformMode.Listening, WaveformMode.Speaking -> {
                    // Sine per bar with 100ms stagger over a 600ms cycle.
                    val phase = (heightPhase - i * (100f / 600f)) * 2f * PI.toFloat()
                    val t = (sin(phase) + 1f) / 2f
                    h = minH + (maxH - minH) * t
                    a = 1f
                }
                WaveformMode.Thinking -> {
                    val phase = (alphaPhase - i * (120f / 900f)) * 2f * PI.toFloat()
                    val t = (sin(phase) + 1f) / 2f
                    h = minH
                    a = 0.3f + 0.7f * t
                }
                else -> {
                    h = minH
                    a = 1f
                }
            }
            drawRoundRect(
                color = color,
                alpha = a,
                topLeft = Offset(i * (barW + gap), centerY - h / 2f),
                size = Size(barW, h),
                cornerRadius = radius
            )
        }
    }
}
