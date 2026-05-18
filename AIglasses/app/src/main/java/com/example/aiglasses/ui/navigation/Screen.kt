package com.example.aiglasses.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Features : Screen("features")
    object Gallery : Screen("gallery")
    object Settings : Screen("settings")
    object LiveView : Screen("live_view")
    object Developer : Screen("developer")
}
