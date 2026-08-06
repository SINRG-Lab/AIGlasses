package com.example.aiglasses.ui.theme

import androidx.compose.ui.graphics.Color

// ── QUIET INSTRUMENT core tokens (spec §2) ──

/** Screen canvas — near-black, slightly cool. */
val Ink = Color(0xFF0B0D0F)

/** Cards, sheets. */
val Panel = Color(0xFF14171B)

/** Chips-on-card, pressed, dialogs. */
val PanelHigh = Color(0xFF1C2127)

/** Slider tracks, text fields. */
val PanelHighest = Color(0xFF242A31)

/** 1dp dividers/borders — the primary structure device. White @ 8%. */
val Hairline = Color(0x14FFFFFF)

/** Enabled component strokes. */
val OutlineGray = Color(0xFF3A424B)

val TextPrimary = Color(0xFFE9ECEF)
val TextSecondary = Color(0xFF98A2AD)
val TextTertiary = Color(0xFF5C6670)

// ── Signal colors ──

/** The one brand color: active voice, primary button, focus, live waveform. */
val SignalOrange = Color(0xFFFF6B2C)

/** Accent chip fills, selected row tint — orange @ 12%. */
val SignalOrangeDim = Color(0x1FFF6B2C)

/** Text on orange. */
val OnAccent = Color(0xFF160A04)

/** Green only ever means "link up". */
val TelemetryGreen = Color(0xFF4ADE80)

/** Connected chip fill — green @ 12%. */
val TelemetryGreenDim = Color(0x1F4ADE80)

/** Degraded link, low battery, WiFi fallback. */
val WarnAmber = Color(0xFFFFC53D)

/** Disconnected-unexpected, failures. */
val ErrorRed = Color(0xFFFF5D52)

/** BLE transport chip accent, dev-metrics series 2. */
val BleBlue = Color(0xFF6CA8FF)

/** BLE chip fill — blue @ 12%. */
val BleBlueDim = Color(0x1F6CA8FF)
