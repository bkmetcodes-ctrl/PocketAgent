package com.bekacodes.pocketagent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PocketDarkScheme = darkColorScheme(
    primary = PocketColors.Indigo,
    onPrimary = Color.White,
    secondary = PocketColors.IndigoSoft,
    tertiary = PocketColors.Emerald,
    onTertiary = Color(0xFF04231A),
    error = PocketColors.Coral,
    onError = Color.White,
    background = PocketColors.Background,
    onBackground = Color(0xFFE2E8F0),
    surface = PocketColors.Background,
    onSurface = Color(0xFFE2E8F0),
    surfaceContainer = PocketColors.Card,
    surfaceContainerHigh = PocketColors.Sheet,
    onSurfaceVariant = PocketColors.TextSecondary,
    outline = Color(0xFF64748B),
    outlineVariant = PocketColors.Hairline,
)

@Composable
fun PocketAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PocketDarkScheme, content = content)
}
