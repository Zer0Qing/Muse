package io.zer0.muse.ui.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.zer0.muse.R
import io.zer0.muse.ui.theme.*

// 12. 暮霭玫 (Dusk Rose) theme — 柔和玫瑰色调,暮色中的温柔
// ─────────────────────────────────────────────────────────────────────────────
private val DuskRosePrimary = Color(0xFFB07080)
private val DuskRosePrimaryLightContainer = Color(0xFFF5E0E5)
private val DuskRosePrimaryDarkContainer = Color(0xFF3D2028)
private val DuskRoseOnPrimaryDark = Color(0xFFF0D8DD)
private val DuskRoseLightBg = Color(0xFFFAF5F6)
private val DuskRoseDarkBg = Color(0xFF140E10)
private val DuskRoseLightInk = Color(0xFF2A1A1E)
private val DuskRoseDarkInk = Color(0xFFE8D8DC)
private val DuskRoseLightAiBubble = Color(0xFFF0E5E8)
private val DuskRoseDarkAiBubble = Color(0xFF1E1418)

val DuskRoseTheme = PresetTheme(
    id = "dusk_rose",
    nameResId = R.string.theme_dusk_rose,
    lightScheme = lightColorScheme(
        primary = DuskRosePrimary,
        onPrimary = Color.White,
        primaryContainer = DuskRosePrimaryLightContainer,
        onPrimaryContainer = Color(0xFF3A1820),
        secondary = DuskRosePrimary,
        onSecondary = Color.White,
        secondaryContainer = DuskRosePrimaryLightContainer,
        onSecondaryContainer = Color(0xFF3A1820),
        tertiary = DuskRosePrimary,
        onTertiary = Color.White,
        tertiaryContainer = DuskRosePrimaryLightContainer,
        onTertiaryContainer = Color(0xFF3A1820),
        surfaceContainer = DuskRoseLightAiBubble,
        surfaceContainerLow = DuskRoseLightBg,
        surfaceContainerHigh = Color(0xFFE8DDE0),
        surfaceDim = Color(0xFFE0D5D8),
        surfaceBright = Color(0xFFFDF8F9),
        background = DuskRoseLightBg,
        onBackground = DuskRoseLightInk,
        surface = DuskRoseLightBg,
        onSurface = DuskRoseLightInk,
        surfaceVariant = DuskRoseLightAiBubble,
        onSurfaceVariant = DuskRoseLightInk,
        surfaceTint = DuskRosePrimary,
        inverseSurface = DuskRoseLightInk,
        inverseOnSurface = DuskRoseLightBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerLightContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF8A7880),
        outlineVariant = Color(0xFFE0D5D8),
        scrim = Color.Black,
    ),
    darkScheme = darkColorScheme(
        primary = Color(0xFFD0909E),
        onPrimary = Color(0xFF2A1018),
        primaryContainer = DuskRosePrimaryDarkContainer,
        onPrimaryContainer = DuskRoseOnPrimaryDark,
        secondary = Color(0xFFD0909E),
        onSecondary = Color(0xFF2A1018),
        secondaryContainer = DuskRosePrimaryDarkContainer,
        onSecondaryContainer = DuskRoseOnPrimaryDark,
        tertiary = Color(0xFFD0909E),
        onTertiary = Color(0xFF2A1018),
        tertiaryContainer = DuskRosePrimaryDarkContainer,
        onTertiaryContainer = DuskRoseOnPrimaryDark,
        surfaceContainer = DuskRoseDarkAiBubble,
        surfaceContainerLow = DuskRoseDarkBg,
        surfaceContainerHigh = Color(0xFF241C20),
        surfaceDim = Color(0xFF0C0809),
        surfaceBright = Color(0xFF2E2228),
        background = DuskRoseDarkBg,
        onBackground = DuskRoseDarkInk,
        surface = DuskRoseDarkBg,
        onSurface = DuskRoseDarkInk,
        surfaceVariant = DuskRoseDarkAiBubble,
        onSurfaceVariant = DuskRoseDarkInk,
        surfaceTint = Color(0xFFD0909E),
        inverseSurface = DuskRoseDarkInk,
        inverseOnSurface = DuskRoseDarkBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerDarkContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF908088),
        outlineVariant = Color(0xFF2E2228),
        scrim = Color.Black,
    ),
)

// ─────────────────────────────────────────────────────────────────────────────
