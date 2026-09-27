package com.akshat.edithglasses.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val EdithAccent = Color(0xFF00E5FF)      // cyan HUD accent
val EdithAccentDim = Color(0xFF0A2A30)
val EdithBackground = Color(0xFF05080A)  // near-black
val EdithSurface = Color(0xFF0D1416)
val EdithWarning = Color(0xFFFFC857)     // amber, echoes the OLED's yellow zone
val EdithTextPrimary = Color(0xFFE6FBFF)

private val EdithColorScheme = darkColorScheme(
    primary = EdithAccent,
    onPrimary = Color.Black,
    secondary = EdithWarning,
    background = EdithBackground,
    surface = EdithSurface,
    onBackground = EdithTextPrimary,
    onSurface = EdithTextPrimary,
)

@Composable
fun EdithGlassesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EdithColorScheme,
        content = content
    )
}
