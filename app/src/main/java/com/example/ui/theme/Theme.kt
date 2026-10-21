package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AuraDarkColorScheme = darkColorScheme(
    primary = Color(0xFFA7AA63),
    onPrimary = TitaniumOnPrimary,
    secondary = SilverSecondary,
    tertiary = MetallicHighlight,
    background = MatteBackground,
    onBackground = PlatinumPrimary,
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = SilverSecondary,
    outline = Color.White.copy(alpha = 0.12f),
    outlineVariant = Color.White.copy(alpha = 0.08f)
)

private val AuraLightColorScheme = lightColorScheme(
    primary = Color(0xFF62662E),
    onPrimary = Color.White,
    secondary = Color(0xFF555D54),
    background = Color(0xFFEAE6D2),
    onBackground = Color(0xFF121A1B),
    surface = Color(0xFFF4F1E4),
    onSurface = Color(0xFF121A1B),
    surfaceVariant = Color.White,
    onSurfaceVariant = Color(0xFF505852),
    outline = Color(0xFF121A1B).copy(alpha = 0.12f),
    outlineVariant = Color(0xFF121A1B).copy(alpha = 0.08f)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AuraDarkColorScheme else AuraLightColorScheme,
        typography = Typography,
        content = content
    )
}
