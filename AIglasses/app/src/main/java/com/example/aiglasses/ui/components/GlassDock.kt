package com.example.aiglasses.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.aiglasses.ui.navigation.Screen
import com.example.aiglasses.ui.theme.*

data class DockItem(
    val screen: Screen,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String
)

val dockItems = listOf(
    DockItem(Screen.Home, Icons.Filled.Home, "Home"),
    DockItem(Screen.Features, Icons.Filled.GridView, "Features"),
    DockItem(Screen.Gallery, Icons.Filled.PhotoLibrary, "Gallery"),
    DockItem(Screen.Settings, Icons.Filled.Settings, "Settings")
)

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
            shape = RoundedCornerShape(980.dp),
            color = GlassSurfaceMed,
            border = BorderStroke(1.dp, GlassBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                dockItems.forEach { item ->
                    val isSelected = currentRoute == item.screen.route
                    DockIconButton(
                        item = item,
                        isSelected = isSelected,
                        onClick = { if (!isSelected) onNavigate(item.screen.route) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DockIconButton(
    item: DockItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) Blue.copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.clip(RoundedCornerShape(14.dp))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = if (isSelected) Blue else TextTertiary,
                modifier = Modifier.size(22.dp)
            )
            AnimatedVisibility(
                visible = isSelected,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    modifier = Modifier.size(4.dp),
                    shape = RoundedCornerShape(50),
                    color = Blue
                ) {}
            }
        }
    }
}
