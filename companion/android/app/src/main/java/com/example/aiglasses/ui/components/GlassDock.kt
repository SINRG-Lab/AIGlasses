package com.example.aiglasses.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.aiglasses.ui.navigation.Screen
import com.example.aiglasses.ui.theme.Hairline
import com.example.aiglasses.ui.theme.Panel
import com.example.aiglasses.ui.theme.SignalOrange
import com.example.aiglasses.ui.theme.SignalOrangeDim
import com.example.aiglasses.ui.theme.TextTertiary

private data class DockItem(
    val screen: Screen,
    val icon: ImageVector,
    val label: String
)

private val dockItems = listOf(
    DockItem(Screen.Home, Icons.Outlined.Home, "Home"),
    DockItem(Screen.Gallery, Icons.Outlined.PhotoLibrary, "Gallery"),
    DockItem(Screen.Settings, Icons.Outlined.Settings, "Settings"),
    DockItem(Screen.Developer, Icons.Outlined.Code, "Developer")
)

/** Bottom navigation pill — Panel fill, hairline border, orange selection. */
@Composable
fun GlassDock(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = CircleShape,
            color = Panel,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, Hairline)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                dockItems.forEach { item ->
                    val selected = currentRoute == item.screen.route
                    Surface(
                        onClick = { if (!selected) onNavigate(item.screen.route) },
                        shape = CircleShape,
                        color = if (selected) SignalOrangeDim else Color.Transparent,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (selected) SignalOrange else TextTertiary,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .size(22.dp)
                        )
                    }
                }
            }
        }
    }
}
