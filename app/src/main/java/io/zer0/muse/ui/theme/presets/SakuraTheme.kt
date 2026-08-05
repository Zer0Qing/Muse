package io.zer0.muse.ui.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.zer0.muse.R
import io.zer0.muse.ui.theme.*

// 2. 樱花主题 (粉色系 - 既有实现 SakuraTheme)
// ─────────────────────────────────────────────────────────────────────────────
private val SakuraPrimary = Color(0xFFD85F8C)
private val SakuraPrimaryLightContainer = Color(0xFFFFD9E4)
private val SakuraPrimaryDarkContainer = Color(0xFF5C2A40)
private val SakuraOnPrimaryLight = Color(0xFFFFFFFF)
private val SakuraOnPrimaryDark = Color(0xFFFFD9E4)
private val SakuraLightBg = Color(0xFFFFF8FA)
private val SakuraDarkBg = Color(0xFF1A0F12)
private val SakuraLightInk = Color(0xFF2A1A1F)
private val SakuraDarkInk = Color(0xFFEFD9E0)
private val SakuraLightAiBubble = Color(0xFFF5E7EC)
private val SakuraDarkAiBubble = Color(0xFF2A1F23)

val SakuraTheme = PresetTheme(
    id = "sakura",
    nameResId = R.string.theme_sakura,
    lightScheme = lightColorScheme(
        primary = SakuraPrimary,
        onPrimary = SakuraOnPrimaryLight,
        primaryContainer = SakuraPrimaryLightContainer,
        onPrimaryContainer = Color(0xFF3B0F22),
        secondary = SakuraPrimary,
        onSecondary = SakuraOnPrimaryLight,
        secondaryContainer = SakuraPrimaryLightContainer,
        onSecondaryContainer = Color(0xFF3B0F22),
        tertiary = SakuraPrimary,
        onTertiary = SakuraOnPrimaryLight,
        // M-1: tertiary 与 primary 同色,container/onContainer 同步引用 primary 容器色。
        tertiaryContainer = SakuraPrimaryLightContainer,
        onTertiaryContainer = Color(0xFF3B0F22),
        // M-2: surface 容器梯度,surfaceContainer 复用 AiBubble(已为"略深于 surface"的粉调)。
        surfaceContainer = SakuraLightAiBubble,
        surfaceContainerLow = SakuraLightBg,
        surfaceContainerHigh = Color(0xFFEFD9E0),
        surfaceDim = Color(0xFFE8D0D8),
        surfaceBright = Color(0xFFFFFCFD),
        background = SakuraLightBg,
        onBackground = SakuraLightInk,
        surface = SakuraLightBg,
        onSurface = SakuraLightInk,
        surfaceVariant = SakuraLightAiBubble,
        onSurfaceVariant = SakuraLightInk,
        surfaceTint = SakuraPrimary,
        inverseSurface = SakuraLightInk,
        inverseOnSurface = SakuraLightBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerLightContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF8E7A82),
        outlineVariant = Color(0xFFE8D9DF),
        scrim = Color.Black,
    ),
    darkScheme = darkColorScheme(
        primary = SakuraPrimary,
        onPrimary = SakuraOnPrimaryDark,
        primaryContainer = SakuraPrimaryDarkContainer,
        onPrimaryContainer = SakuraOnPrimaryDark,
        secondary = SakuraPrimary,
        onSecondary = SakuraOnPrimaryDark,
        secondaryContainer = SakuraPrimaryDarkContainer,
        onSecondaryContainer = SakuraOnPrimaryDark,
        tertiary = SakuraPrimary,
        onTertiary = SakuraOnPrimaryDark,
        // M-1: tertiary 与 primary 同色,container/onContainer 同步引用 primary 容器色。
        tertiaryContainer = SakuraPrimaryDarkContainer,
        onTertiaryContainer = SakuraOnPrimaryDark,
        // M-2: surface 容器梯度(深色越 high 越亮),surfaceContainer 复用 AiBubble。
        surfaceContainer = SakuraDarkAiBubble,
        surfaceContainerLow = SakuraDarkBg,
        surfaceContainerHigh = Color(0xFF3A2D32),
        surfaceDim = Color(0xFF120A0C),
        surfaceBright = Color(0xFF3D2E33),
        background = SakuraDarkBg,
        onBackground = SakuraDarkInk,
        surface = SakuraDarkBg,
        onSurface = SakuraDarkInk,
        surfaceVariant = SakuraDarkAiBubble,
        onSurfaceVariant = SakuraDarkInk,
        surfaceTint = SakuraPrimary,
        inverseSurface = SakuraDarkInk,
        inverseOnSurface = SakuraDarkBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerDarkContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF9D8A92),
        outlineVariant = Color(0xFF3A2F33),
        scrim = Color.Black,
    ),
)

// ─────────────────────────────────────────────────────────────────────────────
