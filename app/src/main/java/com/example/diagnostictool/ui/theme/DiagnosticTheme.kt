package com.example.diagnostictool.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DiagBlack = Color(0xFF101010)
val DiagGray = Color(0xFF3A3A3A)
val DiagLightGray = Color(0xFFB8B8B8)
val DiagWhite = Color(0xFFF5F5F5)
val DiagRed = Color(0xFFE31818)

private val DiagColorScheme = darkColorScheme(
    primary = DiagRed,
    onPrimary = DiagWhite,
    secondary = DiagRed,
    onSecondary = DiagWhite,
    background = DiagBlack,
    onBackground = DiagWhite,
    surface = DiagBlack,
    onSurface = DiagWhite,
    surfaceVariant = DiagGray,
    onSurfaceVariant = DiagLightGray,
    error = DiagRed,
)

@Composable
fun DiagnosticTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DiagColorScheme, content = content)
}
