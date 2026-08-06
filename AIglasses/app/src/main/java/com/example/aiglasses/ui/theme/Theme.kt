package com.example.aiglasses.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// QUIET INSTRUMENT — dark-only for v1, dynamic color OFF (fixed hardware
// identity; wallpaper tints would repaint the instrument every day).
private val QuietInstrumentColors = darkColorScheme(
    primary = SignalOrange,
    onPrimary = OnAccent,
    primaryContainer = SignalOrangeDim,
    onPrimaryContainer = SignalOrange,
    secondary = TextSecondary,
    onSecondary = Ink,
    tertiary = BleBlue,
    onTertiary = Ink,
    error = ErrorRed,
    onError = Ink,
    background = Ink,
    onBackground = TextPrimary,
    surface = Panel,
    onSurface = TextPrimary,
    surfaceVariant = PanelHigh,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = Ink,
    surfaceContainerLow = Panel,
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelHigh,
    surfaceContainerHighest = PanelHighest,
    outline = OutlineGray,
    outlineVariant = Hairline,
    surfaceTint = androidx.compose.ui.graphics.Color.Transparent
)

@Composable
fun AIglassesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = QuietInstrumentColors,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
