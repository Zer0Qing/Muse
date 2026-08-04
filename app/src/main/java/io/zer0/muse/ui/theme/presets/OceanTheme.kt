package io.zer0.muse.ui.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.zer0.muse.R
import io.zer0.muse.ui.theme.*

// 3. 海洋主题 (蓝色系 - 参考 rikkahub OceanTheme)
// ─────────────────────────────────────────────────────────────────────────────
private val OceanPrimary = Color(0xFF2E7BD6)
private val OceanPrimaryLightContainer = Color(0xFFD4E8FA)
private val OceanPrimaryDarkContainer = Color(0xFF1A3A5C)
private val OceanOnPrimaryDark = Color(0xFFD4E8FA)
private val OceanLightBg = Color(0xFFF6F9FC)
private val OceanDarkBg = Color(0xFF0A1320)
private val OceanLightInk = Color(0xFF0E1A2A)
private val OceanDarkInk = Color(0xFFD9E3F0)
private val OceanLightAiBubble = Color(0xFFE8EEF5)
private val OceanDarkAiBubble = Color(0xFF16222F)

val OceanTheme = PresetTheme(
    id = "ocean",
    nameResId = R.string.theme_ocean,
    lightScheme = lightColorScheme(
        primary = OceanPrimary,
        onPrimary = Color.White,
        primaryContainer = OceanPrimaryLightContainer,
        onPrimaryContainer = Color(0xFF0E2A4A),
        secondary = OceanPrimary,
        onSecondary = Color.White,
        secondaryContainer = OceanPrimaryLightContainer,
        onSecondaryContainer = Color(0xFF0E2A4A),
        tertiary = OceanPrimary,
        onTertiary = Color.White,
        // M-1: tertiary 与 primary 同色,container/onContainer 同步引用 primary 容器色。
        tertiaryContainer = OceanPrimaryLightContainer,
        onTertiaryContainer = Color(0xFF0E2A4A),
        // M-2: surface 容器梯度,surfaceContainer 复用 AiBubble(蓝调略深于 surface)。
        surfaceContainer = OceanLightAiBubble,
        surfaceContainerLow = OceanLightBg,
        surfaceContainerHigh = Color(0xFFDDE5EF),
        surfaceDim = Color(0xFFD5DDE8),
        surfaceBright = Color(0xFFFBFDFE),
        background = OceanLightBg,
        onBackground = OceanLightInk,
        surface = OceanLightBg,
        onSurface = OceanLightInk,
        surfaceVariant = OceanLightAiBubble,
        onSurfaceVariant = OceanLightInk,
        surfaceTint = OceanPrimary,
        inverseSurface = OceanLightInk,
        inverseOnSurface = OceanLightBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerLightContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF6B7A8C),
        outlineVariant = Color(0xFFD5DDE6),
        scrim = Color.Black,
    ),
    darkScheme = darkColorScheme(
        primary = OceanPrimary,
        onPrimary = OceanOnPrimaryDark,
        primaryContainer = OceanPrimaryDarkContainer,
        onPrimaryContainer = OceanOnPrimaryDark,
        secondary = OceanPrimary,
        onSecondary = OceanOnPrimaryDark,
        secondaryContainer = OceanPrimaryDarkContainer,
        onSecondaryContainer = OceanOnPrimaryDark,
        tertiary = OceanPrimary,
        onTertiary = OceanOnPrimaryDark,
        // M-1: tertiary 与 primary 同色,container/onContainer 同步引用 primary 容器色。
        tertiaryContainer = OceanPrimaryDarkContainer,
        onTertiaryContainer = OceanOnPrimaryDark,
        // M-2: surface 容器梯度(深色越 high 越亮),surfaceContainer 复用 AiBubble。
        surfaceContainer = OceanDarkAiBubble,
        surfaceContainerLow = OceanDarkBg,
        surfaceContainerHigh = Color(0xFF223040),
        surfaceDim = Color(0xFF060C14),
        surfaceBright = Color(0xFF283848),
        background = OceanDarkBg,
        onBackground = OceanDarkInk,
        surface = OceanDarkBg,
        onSurface = OceanDarkInk,
        surfaceVariant = OceanDarkAiBubble,
        onSurfaceVariant = OceanDarkInk,
        surfaceTint = OceanPrimary,
        inverseSurface = OceanDarkInk,
        inverseOnSurface = OceanDarkBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerDarkContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF8896A8),
        outlineVariant = Color(0xFF223040),
        scrim = Color.Black,
    ),
)

// ─────────────────────────────────────────────────────────────────────────────
