package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val AuraDarkColorScheme = darkColorScheme(
  primary = PlatinumPrimary,
  onPrimary = TitaniumOnPrimary,
  secondary = SilverSecondary,
  onSecondary = TitaniumOnPrimary,
  tertiary = MetallicHighlight,
  background = MatteBackground,
  onBackground = PlatinumPrimary,
  surface = MatteSurface,
  onSurface = PlatinumPrimary,
  surfaceVariant = MatteSurfaceCard,
  onSurfaceVariant = SilverSecondary,
  outline = MatteBorder,
  outlineVariant = MatteBorderSubtle
)

@Composable
fun MyApplicationTheme(
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = AuraDarkColorScheme,
    typography = Typography,
    content = content
  )
}
