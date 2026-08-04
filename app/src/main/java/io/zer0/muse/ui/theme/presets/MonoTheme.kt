package io.zer0.muse.ui.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.zer0.muse.R
import io.zer0.muse.ui.theme.*

// 6. 黑白主题 (Mono - 纯黑白极简风格,参考 GPT/MANUS,默认主题)
// v1.0.25: 替换原 AMOLED 主题。primary 为纯黑(浅色)/纯白(深色),
// 全灰阶无品牌色,OLED 省电 + 对比度最高 + 极简质感。
// ─────────────────────────────────────────────────────────────────────────────
private val MonoLightBg = Color(0xFFFFFFFF)
private val MonoLightInk = Color(0xFF000000)
private val MonoLightSurfaceVariant = Color(0xFFF7F7F8)
private val MonoLightOnSurfaceVariant = Color(0xFF6B6B6B)
private val MonoDarkBg = Color(0xFF000000)
private val MonoDarkInk = Color(0xFFFFFFFF)
private val MonoDarkSurfaceVariant = Color(0xFF1C1C1E)
private val MonoDarkOnSurfaceVariant = Color(0xFF98989F)

val MonoTheme = PresetTheme(
    id = "mono",
    nameResId = R.string.theme_mono,
    lightScheme = lightColorScheme(
        // primary 为纯黑:CTA 按钮、选中态、用户气泡均为黑底白字
        primary = MonoLightInk,
        onPrimary = MonoLightBg,
        primaryContainer = Color(0xFFF0F0F0),
        onPrimaryContainer = MonoLightInk,
        inversePrimary = MonoLightBg,
        secondary = MonoLightOnSurfaceVariant,
        onSecondary = MonoLightBg,
        secondaryContainer = Color(0xFFF0F0F0),
        onSecondaryContainer = MonoLightInk,
        tertiary = MonoLightOnSurfaceVariant,
        onTertiary = MonoLightBg,
        tertiaryContainer = Color(0xFFF0F0F0),
        onTertiaryContainer = MonoLightInk,
        // surface 容器梯度:纯白基底,逐级加深
        surfaceContainer = MonoLightSurfaceVariant,
        surfaceContainerLow = MonoLightBg,
        surfaceContainerHigh = Color(0xFFECECEE),
        surfaceDim = Color(0xFFE5E5E8),
        surfaceBright = MonoLightBg,
        background = MonoLightBg,
        onBackground = MonoLightInk,
        surface = MonoLightBg,
        onSurface = MonoLightInk,
        surfaceVariant = MonoLightSurfaceVariant,
        onSurfaceVariant = MonoLightOnSurfaceVariant,
        surfaceTint = MonoLightInk,
        inverseSurface = MonoLightInk,
        inverseOnSurface = MonoLightBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerLightContainer,
        onErrorContainer = Danger,
        outline = Color(0xFFD1D1D6),
        outlineVariant = Color(0xFFE5E5EA),
        scrim = Color.Black,
    ),
    darkScheme = darkColorScheme(
        // primary 为纯白:CTA 按钮、选中态、用户气泡均为白底黑字
        primary = MonoDarkInk,
        onPrimary = MonoDarkBg,
        primaryContainer = Color(0xFF2C2C2E),
        onPrimaryContainer = MonoDarkInk,
        inversePrimary = MonoDarkBg,
        secondary = MonoDarkOnSurfaceVariant,
        onSecondary = MonoDarkBg,
        secondaryContainer = Color(0xFF2C2C2E),
        onSecondaryContainer = MonoDarkInk,
        tertiary = MonoDarkOnSurfaceVariant,
        onTertiary = MonoDarkBg,
        tertiaryContainer = Color(0xFF2C2C2E),
        onTertiaryContainer = MonoDarkInk,
        // surface 容器梯度:纯黑基底,逐级加亮
        surfaceContainer = MonoDarkSurfaceVariant,
        surfaceContainerLow = MonoDarkBg,
        surfaceContainerHigh = Color(0xFF2C2C2E),
        surfaceDim = MonoDarkBg,
        surfaceBright = Color(0xFF3A3A3C),
        background = MonoDarkBg,
        onBackground = MonoDarkInk,
        surface = MonoDarkBg,
        onSurface = MonoDarkInk,
        surfaceVariant = MonoDarkSurfaceVariant,
        onSurfaceVariant = MonoDarkOnSurfaceVariant,
        surfaceTint = MonoDarkInk,
        inverseSurface = MonoDarkInk,
        inverseOnSurface = MonoDarkBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerDarkContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF3A3A3C),
        outlineVariant = Color(0xFF2C2C2E),
        scrim = Color.Black,
    ),
)

// ─────────────────────────────────────────────────────────────────────────────
