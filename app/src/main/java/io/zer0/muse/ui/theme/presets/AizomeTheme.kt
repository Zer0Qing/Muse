package io.zer0.muse.ui.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.zer0.muse.R
import io.zer0.muse.ui.theme.*

// 9. Aizome (藍染) theme — HanaAgent KAMI indigo dye inspired
// ─────────────────────────────────────────────────────────────────────────────
private val AizomePrimary = Color(0xFF3D5A80)
private val AizomePrimaryLightContainer = Color(0xFFD4E4F4)
private val AizomePrimaryDarkContainer = Color(0xFF1A2A3C)
private val AizomeOnPrimaryDark = Color(0xFFD4E4F4)
private val AizomeLightBg = Color(0xFFF4F7FA)
private val AizomeDarkBg = Color(0xFF080C12)
private val AizomeLightInk = Color(0xFF10182A)
private val AizomeDarkInk = Color(0xFFD0D8E8)
private val AizomeLightAiBubble = Color(0xFFE4EAF2)
private val AizomeDarkAiBubble = Color(0xFF121A24)

val AizomeTheme = PresetTheme(
    id = "aizome",
    nameResId = R.string.theme_aizome,
    lightScheme = lightColorScheme(
        primary = AizomePrimary,
        onPrimary = Color.White,
        primaryContainer = AizomePrimaryLightContainer,
        onPrimaryContainer = Color(0xFF0A1A2C),
        secondary = AizomePrimary,
        onSecondary = Color.White,
        secondaryContainer = AizomePrimaryLightContainer,
        onSecondaryContainer = Color(0xFF0A1A2C),
        tertiary = AizomePrimary,
        onTertiary = Color.White,
        tertiaryContainer = AizomePrimaryLightContainer,
        onTertiaryContainer = Color(0xFF0A1A2C),
        surfaceContainer = AizomeLightAiBubble,
        surfaceContainerLow = AizomeLightBg,
        surfaceContainerHigh = Color(0xFFD8E0EA),
        surfaceDim = Color(0xFFD0D8E2),
        surfaceBright = Color(0xFFFAFBFC),
        background = AizomeLightBg,
        onBackground = AizomeLightInk,
        surface = AizomeLightBg,
        onSurface = AizomeLightInk,
        surfaceVariant = AizomeLightAiBubble,
        onSurfaceVariant = AizomeLightInk,
        surfaceTint = AizomePrimary,
        inverseSurface = AizomeLightInk,
        inverseOnSurface = AizomeLightBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerLightContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF6A7888),
        outlineVariant = Color(0xFFD0D8E2),
        scrim = Color.Black,
    ),
    darkScheme = darkColorScheme(
        primary = Color(0xFF7EA8D0),
        onPrimary = Color(0xFF0A1A2C),
        primaryContainer = AizomePrimaryDarkContainer,
        onPrimaryContainer = AizomeOnPrimaryDark,
        secondary = Color(0xFF7EA8D0),
        onSecondary = Color(0xFF0A1A2C),
        secondaryContainer = AizomePrimaryDarkContainer,
        onSecondaryContainer = AizomeOnPrimaryDark,
        tertiary = Color(0xFF7EA8D0),
        onTertiary = Color(0xFF0A1A2C),
        tertiaryContainer = AizomePrimaryDarkContainer,
        onTertiaryContainer = AizomeOnPrimaryDark,
        surfaceContainer = AizomeDarkAiBubble,
        surfaceContainerLow = AizomeDarkBg,
        surfaceContainerHigh = Color(0xFF1A2430),
        surfaceDim = Color(0xFF04080C),
        surfaceBright = Color(0xFF202C38),
        background = AizomeDarkBg,
        onBackground = AizomeDarkInk,
        surface = AizomeDarkBg,
        onSurface = AizomeDarkInk,
        surfaceVariant = AizomeDarkAiBubble,
        onSurfaceVariant = AizomeDarkInk,
        surfaceTint = Color(0xFF7EA8D0),
        inverseSurface = AizomeDarkInk,
        inverseOnSurface = AizomeDarkBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerDarkContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF7888A0),
        outlineVariant = Color(0xFF1A2430),
        scrim = Color.Black,
    ),
)

// ─────────────────────────────────────────────────────────────────────────────
