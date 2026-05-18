package com.example.aiglasses.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.aiglasses.ui.theme.GlassBorder
import com.example.aiglasses.ui.theme.GlassRimLight
import com.example.aiglasses.ui.theme.GlassSurface
import com.example.aiglasses.ui.theme.GlassSurfaceMed
import com.example.aiglasses.ui.theme.GlassSurfaceStrong

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    depth: Int = 1,
    cornerRadius: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val surfaceColor = when (depth) {
        1 -> GlassSurface
        2 -> GlassSurfaceMed
        else -> GlassSurfaceStrong
    }

    val shape = RoundedCornerShape(cornerRadius)
    val clickableModifier = if (onClick != null) {
        modifier.clickable(onClick = onClick)
    } else modifier

    Surface(
        modifier = clickableModifier.fillMaxWidth(),
        shape = shape,
        color = surfaceColor,
        border = BorderStroke(1.dp, GlassBorder)
    ) {
        Box {
            content()
            // Glass overlay: rim light + corner glow
            Canvas(modifier = Modifier.matchParentSize()) {
                // Specular rim light — 1px across the top
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, GlassRimLight, Color.Transparent),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f)
                    ),
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, 1.dp.toPx())
                )
                // Inner corner glow — top-left radial
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x1AFFFFFF), Color.Transparent),
                        center = Offset(0f, 0f),
                        radius = size.width * 0.6f
                    ),
                    size = Size(size.width * 0.6f, size.height * 0.5f)
                )
            }
        }
    }
}
