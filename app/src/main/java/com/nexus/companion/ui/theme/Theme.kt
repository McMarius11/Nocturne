package com.nexus.companion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NexusDarkColorScheme = darkColorScheme(
    primary = NexusPrimary,
    onPrimary = NexusBlack,
    primaryContainer = NexusPrimaryVariant,
    secondary = NexusSecondary,
    onSecondary = NexusBlack,
    background = NexusBlack,
    onBackground = NexusTextPrimary,
    surface = NexusSurface,
    onSurface = NexusTextPrimary,
    surfaceVariant = NexusSurfaceVariant,
    onSurfaceVariant = NexusTextSecondary,
    error = NexusAccent,
    onError = NexusBlack
)

@Composable
fun NexusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NexusDarkColorScheme,
        typography = NexusTypography,
        content = content
    )
}
