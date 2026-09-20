package com.lulucamera.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colors = darkColorScheme(
    primary = Color(0xFFF4C27A),
    onPrimary = Color(0xFF332113),
    surface = Color(0xFF18130F),
    onSurface = Color(0xFFFFF7ED),
)

@Composable
fun LuLuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
