package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val AuraDarkColorScheme = darkColorScheme(
    primary = AuraDarkTokens.Primary,
    onPrimary = AuraDarkTokens.OnPrimary,
    secondary = AuraDarkTokens.Secondary,
    tertiary = AuraDarkTokens.Primary,
    background = AuraDarkTokens.Background,
    onBackground = AuraDarkTokens.Primary,
    surface = AuraDarkTokens.Surface,
    onSurface = AuraDarkTokens.Primary,
    surfaceVariant = AuraDarkTokens.Interactive,
    onSurfaceVariant = AuraDarkTokens.Secondary,
    outline = AuraDarkTokens.FocusedBorder,
    outlineVariant = AuraDarkTokens.Border,
    primaryContainer = AuraDarkTokens.Interactive,
    onPrimaryContainer = AuraDarkTokens.Primary,
    onSecondary = AuraDarkTokens.OnPrimary,
    secondaryContainer = AuraDarkTokens.Interactive,
    onSecondaryContainer = AuraDarkTokens.Primary,
    onTertiary = AuraDarkTokens.OnPrimary,
    tertiaryContainer = AuraDarkTokens.Interactive,
    onTertiaryContainer = AuraDarkTokens.Primary,
    surfaceTint = androidx.compose.ui.graphics.Color.Transparent,
    inverseSurface = AuraDarkTokens.Interactive,
    inverseOnSurface = AuraDarkTokens.Primary,
    inversePrimary = AuraDarkTokens.Primary,
    surfaceDim = AuraDarkTokens.Background,
    surfaceBright = AuraDarkTokens.Interactive,
    surfaceContainerLowest = AuraDarkTokens.Background,
    surfaceContainerLow = AuraDarkTokens.Surface,
    surfaceContainer = AuraDarkTokens.Surface,
    surfaceContainerHigh = AuraDarkTokens.Interactive,
    surfaceContainerHighest = AuraDarkTokens.Interactive
)

private val AuraLightColorScheme = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF62662E),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = androidx.compose.ui.graphics.Color(0xFF555D54),
    background = androidx.compose.ui.graphics.Color(0xFFEAE6D2),
    onBackground = androidx.compose.ui.graphics.Color(0xFF121A1B),
    surface = androidx.compose.ui.graphics.Color(0xFFF4F1E4),
    onSurface = androidx.compose.ui.graphics.Color(0xFF121A1B),
    surfaceVariant = androidx.compose.ui.graphics.Color.White,
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF505852),
    outline = androidx.compose.ui.graphics.Color(0xFF121A1B).copy(alpha = 0.12f),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF121A1B).copy(alpha = 0.08f)
)

@Composable
fun MyApplicationTheme(
    // Dark is the product default; callers can still opt into the existing light theme.
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AuraDarkColorScheme else AuraLightColorScheme,
        typography = Typography,
        content = content
    )
}
