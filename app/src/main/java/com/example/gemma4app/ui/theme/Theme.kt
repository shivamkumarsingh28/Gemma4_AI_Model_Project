package com.example.gemma4app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dr. AI brand colors
val DrAITeal      = Color(0xFF1D9E75)
val DrAITealLight = Color(0xFF4DC8A8)
val DrAINavy      = Color(0xFF0D1B2A)
val DrAIDark      = Color(0xFF081420)
val DrAICard      = Color(0xFF111E2E)
val DrAIBorder    = Color(0xFF1E3A5F)

private val DarkColors = darkColorScheme(
    primary          = DrAITeal,
    onPrimary        = Color.White,
    primaryContainer = Color(0xFF1A3A5C),
    secondary        = Color(0xFF4DC8A8),
    background       = DrAIDark,
    surface          = DrAINavy,
    surfaceVariant   = DrAICard,
    outline          = DrAIBorder,
    onBackground     = Color(0xFFE8F4F8),
    onSurface        = Color(0xFFC8DFF0),
    onSurfaceVariant = Color(0xFF7A9CC4),
    error            = Color(0xFFCF6679)
)

private val LightColors = lightColorScheme(
    primary          = Color(0xFF0F6E56),
    onPrimary        = Color.White,
    primaryContainer = Color(0xFFE1F5EE),
    secondary        = Color(0xFF1D9E75),
    background       = Color(0xFFF4F9F7),
    surface          = Color.White,
    surfaceVariant   = Color(0xFFEAF5F0),
    outline          = Color(0xFFB0D4C8),
    onBackground     = Color(0xFF0D1B2A),
    onSurface        = Color(0xFF1A3A5C),
    onSurfaceVariant = Color(0xFF4A7A6A),
    error            = Color(0xFFB3263A)
)

@Composable
fun DrAITheme(
    darkTheme: Boolean = true,   // Default dark — medical apps look better dark
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}
