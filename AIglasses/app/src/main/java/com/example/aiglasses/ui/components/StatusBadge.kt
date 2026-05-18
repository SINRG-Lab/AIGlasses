package com.example.aiglasses.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aiglasses.model.ConnectionState
import com.example.aiglasses.ui.theme.*

@Composable
fun StatusBadge(
    state: ConnectionState,
    modifier: Modifier = Modifier
) {
    val (label, color) = when (state) {
        ConnectionState.Disconnected -> "Offline" to Red
        ConnectionState.Scanning -> "Scanning" to Orange
        ConnectionState.Connected -> "Idle" to Blue
        ConnectionState.Active -> "Live" to Green
    }

    val isActive = state == ConnectionState.Active
    val infiniteTransition = rememberInfiniteTransition(label = "badge_pulse")
    val dotScale by if (isActive) {
        infiniteTransition.animateFloat(
            initialValue = 1f, targetValue = 1.4f,
            animationSpec = infiniteRepeatable(
                tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse
            ),
            label = "dot_scale"
        )
    } else {
        infiniteTransition.animateFloat(
            initialValue = 1f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Restart),
            label = "dot_static"
        )
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(980.dp),
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                modifier = Modifier
                    .size(6.dp)
                    .scale(dotScale),
                shape = RoundedCornerShape(50),
                color = color
            ) {}
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = color,
                letterSpacing = 0.3.sp
            )
        }
    }
}
