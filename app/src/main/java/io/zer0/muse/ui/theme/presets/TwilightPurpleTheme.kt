package io.zer0.muse.ui.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.zer0.muse.R
import io.zer0.muse.ui.theme.*

// 10. 暮紫韵 (Twilight Purple) theme — 优雅紫调,暮色天空的渐变意象
// ─────────────────────────────────────────────────────────────────────────────
private val TwilightPrimary = Color(0xFF6B5CA5)
private val TwilightPrimaryLightContainer = Color(0xFFE8E0F5)
private val TwilightPrimaryDarkContainer = Color(0xFF2D2650)
private val TwilightOnPrimaryDark = Color(0xFFE0D8F0)
private val TwilightLightBg = Color(0xFFF8F6FA)
private val TwilightDarkBg = Color(0xFF110E18)
private val TwilightLightInk = Color(0xFF1A1528)
private val TwilightDarkInk = Color(0xFFE0D8E8)
private val TwilightLightAiBubble = Color(0xFFEEEAF5)
private val TwilightDarkAiBubble = Color(0xFF1C1825)

val TwilightPurpleTheme = PresetTheme(
    id = "twilight_purple",
    nameResId = R.string.theme_twilight_purple,
    lightScheme = lightColorScheme(
        primary = TwilightPrimary,
        onPrimary = Color.White,
        primaryContainer = TwilightPrimaryLightContainer,
        onPrimaryContainer = Color(0xFF1A1040),
        secondary = TwilightPrimary,
        onSecondary = Color.White,
        secondaryContainer = TwilightPrimaryLightContainer,
        onSecondaryContainer = Color(0xFF1A1040),
        tertiary = TwilightPrimary,
        onTertiary = Color.White,
        tertiaryContainer = TwilightPrimaryLightContainer,
        onTertiaryContainer = Color(0xFF1A1040),
        surfaceContainer = TwilightLightAiBubble,
        surfaceContainerLow = TwilightLightBg,
        surfaceContainerHigh = Color(0xFFE5E0EE),
        surfaceDim = Color(0xFFDDD8E8),
        surfaceBright = Color(0xFFFBFAFC),
        background = TwilightLightBg,
        onBackground = TwilightLightInk,
        surface = TwilightLightBg,
        onSurface = TwilightLightInk,
        surfaceVariant = TwilightLightAiBubble,
        onSurfaceVariant = TwilightLightInk,
        surfaceTint = TwilightPrimary,
        inverseSurface = TwilightLightInk,
        inverseOnSurface = TwilightLightBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerLightContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF7A7088),
        outlineVariant = Color(0xFFDDD8E5),
        scrim = Color.Black,
    ),
    darkScheme = darkColorScheme(
        primary = Color(0xFF9B8ED0),
        onPrimary = Color(0xFF1A1040),
        primaryContainer = TwilightPrimaryDarkContainer,
        onPrimaryContainer = TwilightOnPrimaryDark,
        secondary = Color(0xFF9B8ED0),
        onSecondary = Color(0xFF1A1040),
        secondaryContainer = TwilightPrimaryDarkContainer,
        onSecondaryContainer = TwilightOnPrimaryDark,
        tertiary = Color(0xFF9B8ED0),
        onTertiary = Color(0xFF1A1040),
        tertiaryContainer = TwilightPrimaryDarkContainer,
        onTertiaryContainer = TwilightOnPrimaryDark,
        surfaceContainer = TwilightDarkAiBubble,
        surfaceContainerLow = TwilightDarkBg,
        surfaceContainerHigh = Color(0xFF221E2C),
        surfaceDim = Color(0xFF0A080E),
        surfaceBright = Color(0xFF2A2435),
        background = TwilightDarkBg,
        onBackground = TwilightDarkInk,
        surface = TwilightDarkBg,
        onSurface = TwilightDarkInk,
        surfaceVariant = TwilightDarkAiBubble,
        onSurfaceVariant = TwilightDarkInk,
        surfaceTint = Color(0xFF9B8ED0),
        inverseSurface = TwilightDarkInk,
        inverseOnSurface = TwilightDarkBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerDarkContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF8880A0),
        outlineVariant = Color(0xFF252030),
        scrim = Color.Black,
    ),
)

// ─────────────────────────────────────────────────────────────────────────────
