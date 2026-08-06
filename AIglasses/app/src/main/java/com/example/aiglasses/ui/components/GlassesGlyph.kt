package com.example.aiglasses.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The entire "device rendering" — a 2dp line drawing of the glasses in a
 * 96×36 viewport (spec §5.2): two rounded-rect lenses, a bridge, temple
 * stubs. No fill, no photo, no 3D.
 */
@Composable
fun GlassesGlyph(
    color: Color,
    modifier: Modifier = Modifier,
    width: Dp = 96.dp,
    height: Dp = 36.dp,
    strokeWidth: Dp = 2.dp
) {
    Canvas(modifier = modifier.size(width, height)) {
        val s = size.width / 96f
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        val path = Path()

        // Lenses: 34×22, corner 8, vertically centered in the 36-unit viewport.
        val lensTop = 7f * s
        val lensH = 22f * s
        val lensW = 34f * s
        val corner = CornerRadius(8f * s, 8f * s)
        path.addRoundRect(
            RoundRect(rect = androidx.compose.ui.geometry.Rect(Offset(12f * s, lensTop), Size(lensW, lensH)), cornerRadius = corner)
        )
        path.addRoundRect(
            RoundRect(rect = androidx.compose.ui.geometry.Rect(Offset(50f * s, lensTop), Size(lensW, lensH)), cornerRadius = corner)
        )
        // Bridge: short arc between the lenses.
        path.moveTo(46f * s, 13f * s)
        path.quadraticTo(48f * s, 11f * s, 50f * s, 13f * s)
        // Temple stubs.
        path.moveTo(12f * s, 11f * s)
        path.lineTo(3f * s, 9f * s)
        path.moveTo(84f * s, 11f * s)
        path.lineTo(93f * s, 9f * s)

        drawPath(path = path, color = color, style = stroke)
    }
}
