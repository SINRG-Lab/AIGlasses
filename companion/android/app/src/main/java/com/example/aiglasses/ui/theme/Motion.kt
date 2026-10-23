package com.example.aiglasses.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp

// Spec §8 — springs move geometry, tweens move color; nothing loops at rest
// except the §7 status dot and an active voice waveform.
object Motion {
    val SpatialDefault = spring<Dp>(dampingRatio = 0.8f, stiffness = 380f)
    val SpatialFast = spring<Float>(dampingRatio = 0.6f, stiffness = 800f)   // chips, dots
    val SpatialSlow = spring<Float>(dampingRatio = 0.8f, stiffness = 200f)   // sheets, large cards
    val EffectDefault = tween<Float>(200, easing = LinearEasing)             // color/alpha
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecel = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccel = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}
