package com.example.ui.theme

import androidx.compose.ui.graphics.Color

/** Canonical AuraShare dark-mode tokens. */
object AuraDarkTokens {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF1C1C1E)
    val Interactive = Color(0xFF2C2C2E)
    val Primary = Color(0xFFF5F5F5)
    val OnPrimary = Color(0xFF000000)
    val Secondary = Color(0xFF8E8E93)

    val Border = Color.White.copy(alpha = 0.08f)
    val FocusedBorder = Color.White.copy(alpha = 0.12f)
    val Selected = Primary.copy(alpha = 0.12f)
    val FocusRing = Color.White.copy(alpha = 0.08f)
    val FocusBorder = Color.White.copy(alpha = 0.28f)
    val Divider = Color.White.copy(alpha = 0.10f)
    val Hover = Color.White.copy(alpha = 0.05f)

    const val BorderAlpha = 0.08f
    const val FocusedBorderAlpha = 0.12f
    const val FocusBorderAlpha = 0.28f
}

// Backward-compatible names used by existing screens and components.
val MatteObsidian = AuraDarkTokens.Background
val MatteBackground = AuraDarkTokens.Background
val MatteSurface = AuraDarkTokens.Surface
val MatteSurfaceCard = AuraDarkTokens.Surface
val MatteSurfaceElevated = AuraDarkTokens.Interactive
val MatteBorder = AuraDarkTokens.FocusedBorder
val MatteBorderSubtle = AuraDarkTokens.Border

val PlatinumPrimary = AuraDarkTokens.Primary
val TitaniumOnPrimary = AuraDarkTokens.OnPrimary
val SilverSecondary = AuraDarkTokens.Secondary
val GraphiteMuted = AuraDarkTokens.Secondary.copy(alpha = 0.6f)
val GraphiteTrack = AuraDarkTokens.Interactive
val MetallicHighlight = AuraDarkTokens.Primary
