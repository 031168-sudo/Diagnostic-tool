package com.example.diagnostictool.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DiagBlack = Color(0xFF101010)
val DiagGray = Color(0xFF3A3A3A)
val DiagLightGray = Color(0xFFB8B8B8)
val DiagWhite = Color(0xFFF5F5F5)
val DiagRed = Color(0xFFB71800)
val DiagGreen = Color(0xFF2E7D32)
val DiagButtonGray = Color(0xFF333333)

private val DarkColors = darkColorScheme(
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
    onError = DiagWhite,
)

private val LightColors = lightColorScheme(
    primary = DiagRed,
    onPrimary = Color.White,
    secondary = DiagRed,
    onSecondary = Color.White,
    background = Color(0xFFF6F6F6),
    onBackground = Color(0xFF141414),
    surface = Color.White,
    onSurface = Color(0xFF141414),
    surfaceVariant = Color(0xFFE6E6E6),
    onSurfaceVariant = Color(0xFF4A4A4A),
    error = DiagRed,
    onError = Color.White,
)

@Composable
fun DiagnosticTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
