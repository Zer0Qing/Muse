package io.zer0.muse.ui.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.zer0.muse.R
import io.zer0.muse.ui.theme.*

// 11. 琥珀金 (Amber Gold) theme — 暖琥珀色调,日落金光
// ─────────────────────────────────────────────────────────────────────────────
private val AmberPrimary = Color(0xFFB8860B)
private val AmberPrimaryLightContainer = Color(0xFFF5E8C8)
private val AmberPrimaryDarkContainer = Color(0xFF3D2D08)
private val AmberOnPrimaryDark = Color(0xFFF0E0B8)
private val AmberLightBg = Color(0xFFFAF8F2)
private val AmberDarkBg = Color(0xFF141208)
private val AmberLightInk = Color(0xFF2A2510)
private val AmberDarkInk = Color(0xFFE8E0C8)
private val AmberLightAiBubble = Color(0xFFF0EBD8)
private val AmberDarkAiBubble = Color(0xFF1E1C10)

val AmberGoldTheme = PresetTheme(
    id = "amber_gold",
    nameResId = R.string.theme_amber_gold,
    lightScheme = lightColorScheme(
        primary = AmberPrimary,
        onPrimary = Color.White,
        primaryContainer = AmberPrimaryLightContainer,
        onPrimaryContainer = Color(0xFF3A2808),
        secondary = AmberPrimary,
        onSecondary = Color.White,
        secondaryContainer = AmberPrimaryLightContainer,
        onSecondaryContainer = Color(0xFF3A2808),
        tertiary = AmberPrimary,
        onTertiary = Color.White,
        tertiaryContainer = AmberPrimaryLightContainer,
        onTertiaryContainer = Color(0xFF3A2808),
        surfaceContainer = AmberLightAiBubble,
        surfaceContainerLow = AmberLightBg,
        surfaceContainerHigh = Color(0xFFE8E2D0),
        surfaceDim = Color(0xFFE0DAC8),
        surfaceBright = Color(0xFFFDFBF5),
        background = AmberLightBg,
        onBackground = AmberLightInk,
        surface = AmberLightBg,
        onSurface = AmberLightInk,
        surfaceVariant = AmberLightAiBubble,
        onSurfaceVariant = AmberLightInk,
        surfaceTint = AmberPrimary,
        inverseSurface = AmberLightInk,
        inverseOnSurface = AmberLightBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerLightContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF8A8060),
        outlineVariant = Color(0xFFE0DAC8),
        scrim = Color.Black,
    ),
    darkScheme = darkColorScheme(
        primary = Color(0xFFD4A830),
        onPrimary = Color(0xFF2A2008),
        primaryContainer = AmberPrimaryDarkContainer,
        onPrimaryContainer = AmberOnPrimaryDark,
        secondary = Color(0xFFD4A830),
        onSecondary = Color(0xFF2A2008),
        secondaryContainer = AmberPrimaryDarkContainer,
        onSecondaryContainer = AmberOnPrimaryDark,
        tertiary = Color(0xFFD4A830),
        onTertiary = Color(0xFF2A2008),
        tertiaryContainer = AmberPrimaryDarkContainer,
        onTertiaryContainer = AmberOnPrimaryDark,
        surfaceContainer = AmberDarkAiBubble,
        surfaceContainerLow = AmberDarkBg,
        surfaceContainerHigh = Color(0xFF242010),
        surfaceDim = Color(0xFF0C0A04),
        surfaceBright = Color(0xFF2E2818),
        background = AmberDarkBg,
        onBackground = AmberDarkInk,
        surface = AmberDarkBg,
        onSurface = AmberDarkInk,
        surfaceVariant = AmberDarkAiBubble,
        onSurfaceVariant = AmberDarkInk,
        surfaceTint = Color(0xFFD4A830),
        inverseSurface = AmberDarkInk,
        inverseOnSurface = AmberDarkBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerDarkContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF908868),
        outlineVariant = Color(0xFF2E2818),
        scrim = Color.Black,
    ),
)

// ─────────────────────────────────────────────────────────────────────────────
