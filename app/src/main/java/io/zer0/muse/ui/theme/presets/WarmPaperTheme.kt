package io.zer0.muse.ui.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.zer0.muse.R
import io.zer0.muse.ui.theme.*

// 1. 暖纸主题 (默认 - 保留现有 Color.kt 配色)
// L-10: 浅色 primary(LaurelGreen #2D8C5F)on 白色,对比度接近 WCAG AA 下限(4.5:1)。
// 这是品牌色权衡:月桂绿是缪斯视觉符号,刻意不加深以保留"墨绿生机"质感;AA 增强级
// (7:1)会迫使 primary 偏暗、失去品牌识别度。onPrimary 文字始终用白底深色容器兜底,
// 正文级文字不落在 primary 上,故实际无障碍风险可控。amoled 浅色沿用 warm_paper,同此注。
// ─────────────────────────────────────────────────────────────────────────────
val WarmPaperTheme = PresetTheme(
    id = "warm_paper",
    nameResId = R.string.theme_warm_paper,
    lightScheme = lightColorScheme(
        primary = LaurelGreen,
        onPrimary = Color.White,
        primaryContainer = LaurelGreenLightContainer,
        onPrimaryContainer = LaurelGreenDark,
        inversePrimary = StarGold,
        secondary = LaurelGreen,
        onSecondary = Color.White,
        secondaryContainer = LaurelGreenLightContainer,
        onSecondaryContainer = LaurelGreenDark,
        tertiary = LaurelGreen,
        onTertiary = Color.White,
        // M-1: tertiary 与 primary 同色,container/onContainer 同步引用 primary 容器色,
        // 避免"画图"徽章等 tertiaryContainer 使用点回落默认紫色。
        tertiaryContainer = LaurelGreenLightContainer,
        onTertiaryContainer = LaurelGreenDark,
        // M-2: surface 容器梯度(浅色:surfaceContainerLow=surface 基准,越 high 越暗,
        // surfaceDim 最暗、surfaceBright 最亮),消除默认紫灰回落。
        surfaceContainer = Color(0xFFF5F2EE),
        surfaceContainerLow = LightBg,
        surfaceContainerHigh = Color(0xFFEDE9E3),
        surfaceDim = Color(0xFFE8E4DE),
        surfaceBright = Color(0xFFFFFCF8),
        background = LightBg,
        onBackground = Ink,
        surface = LightBg,
        onSurface = Ink,
        surfaceVariant = LightAiBubble,
        onSurfaceVariant = Ink,
        surfaceTint = LaurelGreen,
        inverseSurface = Ink,
        inverseOnSurface = LightBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerLightContainer,
        onErrorContainer = Danger,
        outline = Secondary,
        outlineVariant = Divider,
        scrim = Color.Black,
    ),
    darkScheme = darkColorScheme(
        // v1.0.21: 深色模式 primary 改用 LaurelGreenBright(#4A9F70),OLED 屏幕可见性提升
        primary = LaurelGreenBright,
        onPrimary = Color.White,
        primaryContainer = LaurelGreenDarkContainer,
        onPrimaryContainer = LaurelGreenLight,
        inversePrimary = StarGold,
        secondary = LaurelGreenBright,
        onSecondary = Color.White,
        secondaryContainer = LaurelGreenDarkContainer,
        onSecondaryContainer = LaurelGreenLight,
        tertiary = LaurelGreenBright,
        onTertiary = Color.White,
        // M-1: tertiary 与 primary 同色,container/onContainer 同步引用 primary 容器色。
        tertiaryContainer = LaurelGreenDarkContainer,
        onTertiaryContainer = LaurelGreenLight,
        // M-2: surface 容器梯度(深色:surfaceContainerLow=surface 基准,越 high 越亮,
        // surfaceDim 最暗、surfaceBright 最亮)。surface=纯黑,surfaceDim 同为纯黑无法更暗。
        surfaceContainer = Color(0xFF141416),
        surfaceContainerLow = DarkBg,
        surfaceContainerHigh = Color(0xFF1C1C1E),
        surfaceDim = DarkBg,
        surfaceBright = Color(0xFF2C2C2E),
        background = DarkBg,
        onBackground = DarkInk,
        surface = DarkBg,
        onSurface = DarkInk,
        surfaceVariant = DarkAiBubble,
        onSurfaceVariant = DarkInk,
        surfaceTint = LaurelGreenBright,
        inverseSurface = DarkInk,
        inverseOnSurface = DarkBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerDarkContainer,
        onErrorContainer = Danger,
        outline = Secondary,
        outlineVariant = DarkDivider,
        scrim = Color.Black,
    ),
)

// ─────────────────────────────────────────────────────────────────────────────
