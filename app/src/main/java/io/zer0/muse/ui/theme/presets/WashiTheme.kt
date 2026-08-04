package io.zer0.muse.ui.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.zer0.muse.R
import io.zer0.muse.ui.theme.*

// 8. Washi (和紙) theme — HanaAgent KAMI warm washi paper inspired
// ─────────────────────────────────────────────────────────────────────────────
private val WashiPrimary = Color(0xFF8B6F47)
private val WashiPrimaryLightContainer = Color(0xFFF0E4D0)
private val WashiPrimaryDarkContainer = Color(0xFF3A2C18)
private val WashiOnPrimaryDark = Color(0xFFF0E4D0)
private val WashiLightBg = Color(0xFFFBF7F0)
private val WashiDarkBg = Color(0xFF14100A)
private val WashiLightInk = Color(0xFF2A2014)
private val WashiDarkInk = Color(0xFFE8DFD0)
private val WashiLightAiBubble = Color(0xFFF0E8DA)
private val WashiDarkAiBubble = Color(0xFF1E1810)

val WashiTheme = PresetTheme(
    id = "washi",
    nameResId = R.string.theme_washi,
    lightScheme = lightColorScheme(
        primary = WashiPrimary,
        onPrimary = Color.White,
        primaryContainer = WashiPrimaryLightContainer,
        onPrimaryContainer = Color(0xFF3A2C18),
        secondary = WashiPrimary,
        onSecondary = Color.White,
        secondaryContainer = WashiPrimaryLightContainer,
        onSecondaryContainer = Color(0xFF3A2C18),
        tertiary = WashiPrimary,
        onTertiary = Color.White,
        tertiaryContainer = WashiPrimaryLightContainer,
        onTertiaryContainer = Color(0xFF3A2C18),
        surfaceContainer = WashiLightAiBubble,
        surfaceContainerLow = WashiLightBg,
        surfaceContainerHigh = Color(0xFFE8E0D2),
        surfaceDim = Color(0xFFE0D8C8),
        surfaceBright = Color(0xFFFDFBF5),
        background = WashiLightBg,
        onBackground = WashiLightInk,
        surface = WashiLightBg,
        onSurface = WashiLightInk,
        surfaceVariant = WashiLightAiBubble,
        onSurfaceVariant = WashiLightInk,
        surfaceTint = WashiPrimary,
        inverseSurface = WashiLightInk,
        inverseOnSurface = WashiLightBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerLightContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF8A8070),
        outlineVariant = Color(0xFFE0D8C8),
        scrim = Color.Black,
    ),
    darkScheme = darkColorScheme(
        primary = Color(0xFFC4A878),
        onPrimary = Color(0xFF2A2014),
        primaryContainer = WashiPrimaryDarkContainer,
        onPrimaryContainer = WashiOnPrimaryDark,
        secondary = Color(0xFFC4A878),
        onSecondary = Color(0xFF2A2014),
        secondaryContainer = WashiPrimaryDarkContainer,
        onSecondaryContainer = WashiOnPrimaryDark,
        tertiary = Color(0xFFC4A878),
        onTertiary = Color(0xFF2A2014),
        tertiaryContainer = WashiPrimaryDarkContainer,
        onTertiaryContainer = WashiOnPrimaryDark,
        surfaceContainer = WashiDarkAiBubble,
        surfaceContainerLow = WashiDarkBg,
        surfaceContainerHigh = Color(0xFF241E14),
        surfaceDim = Color(0xFF0C0804),
        surfaceBright = Color(0xFF2E2818),
        background = WashiDarkBg,
        onBackground = WashiDarkInk,
        surface = WashiDarkBg,
        onSurface = WashiDarkInk,
        surfaceVariant = WashiDarkAiBubble,
        onSurfaceVariant = WashiDarkInk,
        surfaceTint = Color(0xFFC4A878),
        inverseSurface = WashiDarkInk,
        inverseOnSurface = WashiDarkBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerDarkContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF8A7E68),
        outlineVariant = Color(0xFF2E2818),
        scrim = Color.Black,
    ),
)

// ─────────────────────────────────────────────────────────────────────────────
