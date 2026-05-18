package com.example.aiglasses.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = Blue,
    onPrimary = TextPrimary,
    secondary = Green,
    onSecondary = TextPrimary,
    tertiary = Purple,
    onTertiary = TextPrimary,
    error = Red,
    onError = TextPrimary,
    background = BgStart,
    onBackground = TextPrimary,
    surface = GlassSurface,
    onSurface = TextPrimary,
    surfaceVariant = GlassSurfaceMed,
    onSurfaceVariant = TextSecondary,
    outline = GlassBorder,
    outlineVariant = GlassBorder
)

@Composable
fun AIglassesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
