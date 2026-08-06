package com.example.aiglasses.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Gallery : Screen("gallery")
    object Settings : Screen("settings")
    object Developer : Screen("developer")
}
