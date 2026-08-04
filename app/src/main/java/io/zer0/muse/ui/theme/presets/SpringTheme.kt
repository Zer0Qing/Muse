package io.zer0.muse.ui.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.zer0.muse.R
import io.zer0.muse.ui.theme.*

// 4. 春主题 (清新绿 - 参考 rikkahub SpringTheme)
// ─────────────────────────────────────────────────────────────────────────────
private val SpringPrimary = Color(0xFF4CAF50)
private val SpringPrimaryLightContainer = Color(0xFFD8F0D9)
private val SpringPrimaryDarkContainer = Color(0xFF1F3D22)
private val SpringOnPrimaryDark = Color(0xFFD8F0D9)
private val SpringLightBg = Color(0xFFF7FAF5)
private val SpringDarkBg = Color(0xFF0D140C)
private val SpringLightInk = Color(0xFF1A2A14)
private val SpringDarkInk = Color(0xFFD9E8D5)
private val SpringLightAiBubble = Color(0xFFE9F0E4)
private val SpringDarkAiBubble = Color(0xFF162218)

val SpringTheme = PresetTheme(
    id = "spring",
    nameResId = R.string.theme_spring,
    lightScheme = lightColorScheme(
        primary = SpringPrimary,
        onPrimary = Color.White,
        primaryContainer = SpringPrimaryLightContainer,
        onPrimaryContainer = Color(0xFF0A2A0E),
        secondary = SpringPrimary,
        onSecondary = Color.White,
        secondaryContainer = SpringPrimaryLightContainer,
        onSecondaryContainer = Color(0xFF0A2A0E),
        tertiary = SpringPrimary,
        onTertiary = Color.White,
        // M-1: tertiary 与 primary 同色,container/onContainer 同步引用 primary 容器色。
        tertiaryContainer = SpringPrimaryLightContainer,
        onTertiaryContainer = Color(0xFF0A2A0E),
        // M-2: surface 容器梯度,surfaceContainer 复用 AiBubble(绿调略深于 surface)。
        surfaceContainer = SpringLightAiBubble,
        surfaceContainerLow = SpringLightBg,
        surfaceContainerHigh = Color(0xFFDDE8D5),
        surfaceDim = Color(0xFFD5E0CE),
        surfaceBright = Color(0xFFFBFDF8),
        background = SpringLightBg,
        onBackground = SpringLightInk,
        surface = SpringLightBg,
        onSurface = SpringLightInk,
        surfaceVariant = SpringLightAiBubble,
        onSurfaceVariant = SpringLightInk,
        surfaceTint = SpringPrimary,
        inverseSurface = SpringLightInk,
        inverseOnSurface = SpringLightBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerLightContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF6B7D62),
        outlineVariant = Color(0xFFD5E0CE),
        scrim = Color.Black,
    ),
    darkScheme = darkColorScheme(
        primary = SpringPrimary,
        onPrimary = SpringOnPrimaryDark,
        primaryContainer = SpringPrimaryDarkContainer,
        onPrimaryContainer = SpringOnPrimaryDark,
        secondary = SpringPrimary,
        onSecondary = SpringOnPrimaryDark,
        secondaryContainer = SpringPrimaryDarkContainer,
        onSecondaryContainer = SpringOnPrimaryDark,
        tertiary = SpringPrimary,
        onTertiary = SpringOnPrimaryDark,
        // M-1: tertiary 与 primary 同色,container/onContainer 同步引用 primary 容器色。
        tertiaryContainer = SpringPrimaryDarkContainer,
        onTertiaryContainer = SpringOnPrimaryDark,
        // M-2: surface 容器梯度(深色越 high 越亮),surfaceContainer 复用 AiBubble。
        surfaceContainer = SpringDarkAiBubble,
        surfaceContainerLow = SpringDarkBg,
        surfaceContainerHigh = Color(0xFF1F2D1A),
        surfaceDim = Color(0xFF080D07),
        surfaceBright = Color(0xFF243320),
        background = SpringDarkBg,
        onBackground = SpringDarkInk,
        surface = SpringDarkBg,
        onSurface = SpringDarkInk,
        surfaceVariant = SpringDarkAiBubble,
        onSurfaceVariant = SpringDarkInk,
        surfaceTint = SpringPrimary,
        inverseSurface = SpringDarkInk,
        inverseOnSurface = SpringDarkBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerDarkContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF889C7E),
        outlineVariant = Color(0xFF1F2D1A),
        scrim = Color.Black,
    ),
)

// ─────────────────────────────────────────────────────────────────────────────
