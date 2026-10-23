package com.example.aiglasses.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Spec §4 — M3E 10-step scale, subset. Pills use CircleShape directly.
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),   // text fields, small menus
    small = RoundedCornerShape(10.dp),       // thumbnails, inner chips
    medium = RoundedCornerShape(14.dp),      // standard cards, list groups
    large = RoundedCornerShape(20.dp),       // hero card, sheets, dialogs
    extraLarge = RoundedCornerShape(28.dp)   // bottom sheets, photo scrims
)
