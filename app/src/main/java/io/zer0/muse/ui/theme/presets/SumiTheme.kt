package io.zer0.muse.ui.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.zer0.muse.R
import io.zer0.muse.ui.theme.*

// 7. Sumi (墨) theme — HanaAgent KAMI ink-dyeing inspired
// ─────────────────────────────────────────────────────────────────────────────
private val SumiPrimary = Color(0xFF4A4A4A)
private val SumiPrimaryLightContainer = Color(0xFFE0DED8)
private val SumiPrimaryDarkContainer = Color(0xFF2A2A28)
private val SumiOnPrimaryDark = Color(0xFFE0DED8)
private val SumiLightBg = Color(0xFFF8F6F2)
private val SumiDarkBg = Color(0xFF101010)
private val SumiLightInk = Color(0xFF1A1A18)
private val SumiDarkInk = Color(0xFFE0DED8)
private val SumiLightAiBubble = Color(0xFFEDEBE5)
private val SumiDarkAiBubble = Color(0xFF1A1A18)

val SumiTheme = PresetTheme(
    id = "sumi",
    nameResId = R.string.theme_sumi,
    lightScheme = lightColorScheme(
        primary = SumiPrimary,
        onPrimary = Color.White,
        primaryContainer = SumiPrimaryLightContainer,
        onPrimaryContainer = Color(0xFF1A1A18),
        secondary = SumiPrimary,
        onSecondary = Color.White,
        secondaryContainer = SumiPrimaryLightContainer,
        onSecondaryContainer = Color(0xFF1A1A18),
        tertiary = SumiPrimary,
        onTertiary = Color.White,
        tertiaryContainer = SumiPrimaryLightContainer,
        onTertiaryContainer = Color(0xFF1A1A18),
        surfaceContainer = SumiLightAiBubble,
        surfaceContainerLow = SumiLightBg,
        surfaceContainerHigh = Color(0xFFE5E3DD),
        surfaceDim = Color(0xFFDDD8D0),
        surfaceBright = Color(0xFFFBFAF7),
        background = SumiLightBg,
        onBackground = SumiLightInk,
        surface = SumiLightBg,
        onSurface = SumiLightInk,
        surfaceVariant = SumiLightAiBubble,
        onSurfaceVariant = SumiLightInk,
        surfaceTint = SumiPrimary,
        inverseSurface = SumiLightInk,
        inverseOnSurface = SumiLightBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerLightContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF7A7870),
        outlineVariant = Color(0xFFDDD8D0),
        scrim = Color.Black,
    ),
    darkScheme = darkColorScheme(
        primary = Color(0xFF9A9890),
        onPrimary = SumiOnPrimaryDark,
        primaryContainer = SumiPrimaryDarkContainer,
        onPrimaryContainer = SumiOnPrimaryDark,
        secondary = Color(0xFF9A9890),
        onSecondary = SumiOnPrimaryDark,
        secondaryContainer = SumiPrimaryDarkContainer,
        onSecondaryContainer = SumiOnPrimaryDark,
        tertiary = Color(0xFF9A9890),
        onTertiary = SumiOnPrimaryDark,
        tertiaryContainer = SumiPrimaryDarkContainer,
        onTertiaryContainer = SumiOnPrimaryDark,
        surfaceContainer = SumiDarkAiBubble,
        surfaceContainerLow = SumiDarkBg,
        surfaceContainerHigh = Color(0xFF1E1E1C),
        surfaceDim = Color(0xFF0A0A0A),
        surfaceBright = Color(0xFF282826),
        background = SumiDarkBg,
        onBackground = SumiDarkInk,
        surface = SumiDarkBg,
        onSurface = SumiDarkInk,
        surfaceVariant = SumiDarkAiBubble,
        onSurfaceVariant = SumiDarkInk,
        surfaceTint = Color(0xFF9A9890),
        inverseSurface = SumiDarkInk,
        inverseOnSurface = SumiDarkBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerDarkContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF787870),
        outlineVariant = Color(0xFF2A2A28),
        scrim = Color.Black,
    ),
)

// ─────────────────────────────────────────────────────────────────────────────
