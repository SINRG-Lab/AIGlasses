package com.example.aiglasses.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.aiglasses.MainViewModel
import com.example.aiglasses.ui.components.GlassDock
import com.example.aiglasses.ui.screens.*

private val mainRoutes = setOf(Screen.Home.route, Screen.Features.route, Screen.Settings.route)

@Composable
fun AppNavigation(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val currentBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStack?.destination?.route
    val showDock = currentRoute in mainRoutes

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    viewModel = viewModel,
                    onLiveViewOpen = { navController.navigate(Screen.LiveView.route) },
                    onDevModeReveal = { navController.navigate(Screen.Developer.route) }
                )
            }
            composable(Screen.Features.route) {
                FeaturesScreen(viewModel = viewModel)
            }
            composable(Screen.Settings.route) {
                SettingsScreen(viewModel = viewModel)
            }
            composable(
                route = Screen.LiveView.route,
                enterTransition = {
                    slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = tween(380)
                    ) + fadeIn(tween(380))
                },
                exitTransition = {
                    slideOutVertically(
                        targetOffsetY = { it },
                        animationSpec = tween(300)
                    ) + fadeOut(tween(300))
                }
            ) {
                LiveViewScreen(
                    viewModel = viewModel,
                    onDismiss = { navController.popBackStack() }
                )
            }
            composable(
                route = Screen.Developer.route,
                enterTransition = {
                    fadeIn(tween(280)) + scaleIn(initialScale = 0.95f, animationSpec = tween(280))
                },
                exitTransition = {
                    fadeOut(tween(220)) + scaleOut(targetScale = 0.95f, animationSpec = tween(220))
                }
            ) {
                DeveloperScreen(
                    viewModel = viewModel,
                    onDismiss = { navController.popBackStack() }
                )
            }
        }

        // Glass dock — shown only on main 3 routes
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
                onNavigate = { route ->
                    navController.navigate(route) {
                        popUpTo(Screen.Home.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}
