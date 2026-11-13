package com.stark.jarvis.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val JarvisDarkColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = ObsidianBackground,
    secondary = CyanGlow,
    onSecondary = ObsidianBackground,
    background = ObsidianBackground,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    error = RoseSecurity
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JarvisDarkColorScheme,
        content = content
    )
}
