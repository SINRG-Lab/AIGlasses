package com.example.aiglasses.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.aiglasses.MainViewModel
import com.example.aiglasses.ui.components.GlassDock
import com.example.aiglasses.ui.screens.DeveloperScreen
import com.example.aiglasses.ui.screens.GalleryScreen
import com.example.aiglasses.ui.screens.HomeScreen
import com.example.aiglasses.ui.screens.SettingsScreen
import com.example.aiglasses.ui.theme.Motion

private val mainRoutes = setOf(
    Screen.Home.route, Screen.Gallery.route, Screen.Settings.route, Screen.Developer.route
)

@Composable
fun AppNavigation(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val currentBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStack?.destination?.route
    val showDock = currentRoute in mainRoutes

    // Dock tabs and the hero long-press share one navigation pattern.
    val navigateTo: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(Screen.Home.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.fillMaxSize(),
            // §8: fade + short slide, EmphasizedDecel in / EmphasizedAccel out.
            enterTransition = {
                fadeIn(tween(350, easing = Motion.EmphasizedDecel)) +
                    slideInVertically(tween(350, easing = Motion.EmphasizedDecel)) { it / 24 }
            },
            exitTransition = {
                fadeOut(tween(200, easing = Motion.EmphasizedAccel))
            },
            popEnterTransition = {
                fadeIn(tween(350, easing = Motion.EmphasizedDecel))
            },
            popExitTransition = {
                fadeOut(tween(200, easing = Motion.EmphasizedAccel))
            }
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    viewModel = viewModel,
                    onDevModeReveal = { navigateTo(Screen.Developer.route) }
                )
            }
            composable(Screen.Gallery.route) {
                GalleryScreen(viewModel = viewModel)
            }
            composable(Screen.Settings.route) {
                SettingsScreen(viewModel = viewModel)
            }
            composable(Screen.Developer.route) {
                DeveloperScreen(viewModel = viewModel)
            }
        }

        AnimatedVisibility(
            visible = showDock,
            enter = fadeIn(tween(200)) + slideInVertically(initialOffsetY = { it / 2 }, animationSpec = tween(200)),
            exit = fadeOut(tween(150)) + slideOutVertically(targetOffsetY = { it / 2 }, animationSpec = tween(150)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        ) {
            GlassDock(
                currentRoute = currentRoute,
                onNavigate = navigateTo
            )
        }
    }
}
