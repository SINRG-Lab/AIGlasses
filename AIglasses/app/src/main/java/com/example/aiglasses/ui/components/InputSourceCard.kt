package com.example.aiglasses.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aiglasses.ui.theme.*

@Composable
fun InputSourceCard(
    icon: ImageVector,
    label: String,
    status: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    timestamp: String = ""
) {
    val borderColor by animateColorAsState(
        targetValue = if (isActive) Blue.copy(alpha = 0.5f) else GlassBorder,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow),
        label = "source_border"
    )
    val iconColor by animateColorAsState(
        targetValue = if (isActive) Blue else TextTertiary,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow),
        label = "source_icon"
    )

    Surface(
        onClick = onClick,
        modifier = modifier.width(130.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (isActive) GlassSurfaceMed else GlassSurface,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp)
                )
                Surface(
                    shape = RoundedCornerShape(980.dp),
                    color = if (isActive) Green.copy(alpha = 0.15f) else Color.Transparent,
                    border = if (isActive) BorderStroke(1.dp, Green.copy(alpha = 0.35f)) else null
                ) {
                    Text(
                        text = if (isActive) "Live" else "Idle",
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isActive) Green else TextTertiary
                    )
                }
            }
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                text = if (timestamp.isNotBlank()) timestamp else status,
                fontSize = 11.sp,
                color = TextTertiary,
                maxLines = 1
            )
        }
    }
}
