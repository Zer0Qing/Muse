package io.zer0.muse.ui.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.zer0.muse.R
import io.zer0.muse.ui.theme.*

// 5. 秋主题 (橙黄 - 既有实现 AutumnTheme)
// ─────────────────────────────────────────────────────────────────────────────
private val AutumnPrimary = Color(0xFFE07A3F)
private val AutumnPrimaryLightContainer = Color(0xFFFBE0CC)
private val AutumnPrimaryDarkContainer = Color(0xFF4A2410)
private val AutumnOnPrimaryDark = Color(0xFFFBE0CC)
private val AutumnLightBg = Color(0xFFFCF7F2)
private val AutumnDarkBg = Color(0xFF1A0E08)
private val AutumnLightInk = Color(0xFF2A1A0E)
private val AutumnDarkInk = Color(0xFFEFD9CC)
private val AutumnLightAiBubble = Color(0xFFF5EAE0)
private val AutumnDarkAiBubble = Color(0xFF2A1E14)

val AutumnTheme = PresetTheme(
    id = "autumn",
    nameResId = R.string.theme_autumn,
    lightScheme = lightColorScheme(
        primary = AutumnPrimary,
        onPrimary = Color.White,
        primaryContainer = AutumnPrimaryLightContainer,
        onPrimaryContainer = Color(0xFF3A1A08),
        secondary = AutumnPrimary,
        onSecondary = Color.White,
        secondaryContainer = AutumnPrimaryLightContainer,
        onSecondaryContainer = Color(0xFF3A1A08),
        tertiary = AutumnPrimary,
        onTertiary = Color.White,
        // M-1: tertiary 与 primary 同色,container/onContainer 同步引用 primary 容器色。
        tertiaryContainer = AutumnPrimaryLightContainer,
        onTertiaryContainer = Color(0xFF3A1A08),
        // M-2: surface 容器梯度,surfaceContainer 复用 AiBubble(橙调略深于 surface)。
        surfaceContainer = AutumnLightAiBubble,
        surfaceContainerLow = AutumnLightBg,
        surfaceContainerHigh = Color(0xFFEDE0D0),
        surfaceDim = Color(0xFFE5D8C5),
        surfaceBright = Color(0xFFFFFBF6),
        background = AutumnLightBg,
        onBackground = AutumnLightInk,
        surface = AutumnLightBg,
        onSurface = AutumnLightInk,
        surfaceVariant = AutumnLightAiBubble,
        onSurfaceVariant = AutumnLightInk,
        surfaceTint = AutumnPrimary,
        inverseSurface = AutumnLightInk,
        inverseOnSurface = AutumnLightBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerLightContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF8C7A65),
        outlineVariant = Color(0xFFE8D9CC),
        scrim = Color.Black,
    ),
    darkScheme = darkColorScheme(
        primary = AutumnPrimary,
        onPrimary = AutumnOnPrimaryDark,
        primaryContainer = AutumnPrimaryDarkContainer,
        onPrimaryContainer = AutumnOnPrimaryDark,
        secondary = AutumnPrimary,
        onSecondary = AutumnOnPrimaryDark,
        secondaryContainer = AutumnPrimaryDarkContainer,
        onSecondaryContainer = AutumnOnPrimaryDark,
        tertiary = AutumnPrimary,
        onTertiary = AutumnOnPrimaryDark,
        // M-1: tertiary 与 primary 同色,container/onContainer 同步引用 primary 容器色。
        tertiaryContainer = AutumnPrimaryDarkContainer,
        onTertiaryContainer = AutumnOnPrimaryDark,
        // M-2: surface 容器梯度(深色越 high 越亮),surfaceContainer 复用 AiBubble。
        surfaceContainer = AutumnDarkAiBubble,
        surfaceContainerLow = AutumnDarkBg,
        surfaceContainerHigh = Color(0xFF3A2C1C),
        surfaceDim = Color(0xFF120A05),
        surfaceBright = Color(0xFF3D2E1F),
        background = AutumnDarkBg,
        onBackground = AutumnDarkInk,
        surface = AutumnDarkBg,
        onSurface = AutumnDarkInk,
        surfaceVariant = AutumnDarkAiBubble,
        onSurfaceVariant = AutumnDarkInk,
        surfaceTint = AutumnPrimary,
        inverseSurface = AutumnDarkInk,
        inverseOnSurface = AutumnDarkBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerDarkContainer,
        onErrorContainer = Danger,
        outline = Color(0xFFA0907C),
        outlineVariant = Color(0xFF33271C),
        scrim = Color.Black,
    ),
)

// ─────────────────────────────────────────────────────────────────────────────
