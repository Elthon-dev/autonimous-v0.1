package com.elthon.broexe.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF00315C),
    primaryContainer = Color(0xFF17456F),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFBBC7DB),
    background = Color(0xFF101418),
    surface = Color(0xFF161B20),
    surfaceVariant = Color(0xFF222932),
    onBackground = Color(0xFFE2E2E6),
    onSurface = Color(0xFFE2E2E6),
    error = Color(0xFFFFB4AB)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF175FA4),
    background = Color(0xFFFAFAFC),
    surface = Color(0xFFFFFFFF)
)

@Composable
fun BroExeTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    // Bro.exe defaults to a clean dark theme regardless of system setting,
    // as requested for the first build.
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}

@Suppress("unused")
@Composable
private fun SystemAware(): Boolean = isSystemInDarkTheme()
