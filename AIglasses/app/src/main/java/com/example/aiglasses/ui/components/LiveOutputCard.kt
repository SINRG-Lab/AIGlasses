package com.example.aiglasses.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aiglasses.ui.theme.*

@Composable
fun LiveOutputCard(
    text: String,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerX by infiniteTransition.animateFloat(
        initialValue = -1.5f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer_x"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse_alpha"
    )

    GlassCard(
        modifier = modifier,
        depth = 3,
        cornerRadius = 20.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 120.dp)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Shimmer bar
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
                            val shimmerBrush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Purple.copy(alpha = 0.4f),
                                    Blue.copy(alpha = 0.6f),
                                    Purple.copy(alpha = 0.4f),
                                    Color.Transparent
                                ),
                                start = Offset(shimmerX * size.width, 0f),
                                end = Offset((shimmerX + 1f) * size.width, 0f)
                            )
                            drawRoundRect(
                                brush = shimmerBrush,
                                size = Size(size.width, size.height),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                            )
                        }
                    }
                    Text(
                        text = "Thinking...",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Purple.copy(alpha = pulseAlpha),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                AnimatedVisibility(
                    visible = text.isNotBlank(),
                    enter = fadeIn(tween(300)) + scaleIn(initialScale = 0.96f, animationSpec = tween(300)),
                    exit = fadeOut(tween(200))
                ) {
                    Text(
                        text = text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Normal,
                        color = TextPrimary,
                        textAlign = TextAlign.Center,
                        lineHeight = 26.sp
                    )
                }
                if (text.isBlank()) {
                    Text(
                        text = "Waiting for input...",
                        fontSize = 15.sp,
                        color = TextTertiary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
