package com.halbertb.clipfinder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GeminiSurface = Color(0xFFF6F7FB)
private val GeminiSurfaceVariant = Color(0xFFE8ECF5)
private val GeminiPrimary = Color(0xFF5B8DEF)
private val GeminiSecondary = Color(0xFF9B72F0)
private val GeminiOnSurface = Color(0xFF1B1C1F)
private val GeminiOutline = Color(0xFFC6CCDA)

private val LightColors =
    lightColorScheme(
        primary = GeminiPrimary,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD9E6FF),
        onPrimaryContainer = Color(0xFF0B2F6B),
        secondary = GeminiSecondary,
        onSecondary = Color.White,
        tertiary = Color(0xFF2FB6C2),
        background = GeminiSurface,
        onBackground = GeminiOnSurface,
        surface = GeminiSurface,
        onSurface = GeminiOnSurface,
        surfaceVariant = GeminiSurfaceVariant,
        onSurfaceVariant = Color(0xFF44474E),
        outline = GeminiOutline,
    )

@Composable
fun ClipFinderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography,
        shapes = Shapes,
        content = content,
    )
}
