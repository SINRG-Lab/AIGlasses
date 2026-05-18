package com.example.aiglasses.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aiglasses.ui.theme.*

@Composable
fun FeatureToggleCard(
    icon: ImageVector,
    name: String,
    description: String,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    isLocked: Boolean = false
) {
    val cardAlpha = if (isLocked) 0.4f else 1f

    GlassCard(
        modifier = modifier.alpha(cardAlpha),
        depth = 1,
        cornerRadius = 14.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Blue.copy(alpha = 0.15f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = name,
                            tint = if (isLocked) TextTertiary else Blue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (isLocked) {
                    Surface(
                        shape = RoundedCornerShape(980.dp),
                        color = TextTertiary.copy(alpha = 0.1f),
                        border = BorderStroke(1.dp, TextTertiary.copy(alpha = 0.2f))
                    ) {
                        Text(
                            text = "SOON",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp,
                            color = TextTertiary
                        )
                    }
                } else {
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = onToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Blue,
                            checkedTrackColor = Blue.copy(alpha = 0.3f),
                            uncheckedThumbColor = TextTertiary,
                            uncheckedTrackColor = GlassSurface,
                            uncheckedBorderColor = GlassBorder
                        )
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isLocked) TextTertiary else TextPrimary
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = TextTertiary,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
