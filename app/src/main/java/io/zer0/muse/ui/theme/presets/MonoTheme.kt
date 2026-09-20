package io.zer0.muse.ui.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.zer0.muse.R
import io.zer0.muse.ui.theme.*

// 6. 黑白主题 (Mono - 极简灰阶,默认主题)
// v1.0.25: 替换原 AMOLED 主题。primary 为纯黑(浅色)/纯白(深色)，全灰阶无品牌色。
// v1.0.91: 灰阶整体调柔 —— 纯黑纯白拉得太满，字与底、块与底的落差都硬。
//   浅色:白底 -> #FAFAFB 微灰白,纯黑墨 -> #1E1E22 柔黑,灰块过渡更轻;
//   深色:纯黑底 -> #101014 柔黑(仍近 OLED 黑),纯白墨 -> #E9E9ED 柔白,灰块抬亮成"雾面"。
//   整体仍是同一套灰阶结构(无品牌色),只是把两极往中间收了一点。
// ─────────────────────────────────────────────────────────────────────────────
private val MonoLightBg = Color(0xFFFAFAFB)
private val MonoLightInk = Color(0xFF1E1E22)
private val MonoLightSurfaceVariant = Color(0xFFF2F2F5)
private val MonoLightOnSurfaceVariant = Color(0xFF75757E)
private val MonoLightContainer = Color(0xFFEDEDF1)
private val MonoLightContainerHigh = Color(0xFFEAEAEF)
private val MonoLightContainerDim = Color(0xFFE4E4E9)
private val MonoDarkBg = Color(0xFF101014)
private val MonoDarkInk = Color(0xFFE9E9ED)
private val MonoDarkSurfaceVariant = Color(0xFF1B1B1F)
private val MonoDarkOnSurfaceVariant = Color(0xFF9C9CA4)
private val MonoDarkContainer = Color(0xFF242428)
private val MonoDarkContainerHigh = Color(0xFF26262B)
private val MonoDarkContainerBright = Color(0xFF303036)

val MonoTheme = PresetTheme(
    id = "mono",
    nameResId = R.string.theme_mono,
    lightScheme = lightColorScheme(
        // primary 为柔黑:CTA 按钮、选中态、用户气泡均为柔黑底浅字
        primary = MonoLightInk,
        onPrimary = MonoLightBg,
        primaryContainer = MonoLightContainer,
        onPrimaryContainer = MonoLightInk,
        inversePrimary = MonoLightBg,
        secondary = MonoLightOnSurfaceVariant,
        onSecondary = MonoLightBg,
        secondaryContainer = MonoLightContainer,
        onSecondaryContainer = MonoLightInk,
        tertiary = MonoLightOnSurfaceVariant,
        onTertiary = MonoLightBg,
        tertiaryContainer = MonoLightContainer,
        onTertiaryContainer = MonoLightInk,
        // surface 容器梯度:微灰白基底,逐级加深(落差调小,灰得朦胧)
        surfaceContainer = MonoLightSurfaceVariant,
        surfaceContainerLow = MonoLightBg,
        surfaceContainerHigh = MonoLightContainerHigh,
        surfaceDim = MonoLightContainerDim,
        surfaceBright = Color(0xFFFDFDFE),
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
        outline = Color(0xFFD5D5DC),
        outlineVariant = MonoLightContainerHigh,
        scrim = Color.Black,
    ),
    darkScheme = darkColorScheme(
        // primary 为柔白:CTA 按钮、选中态、用户气泡均为柔白底深字
        primary = MonoDarkInk,
        onPrimary = MonoDarkBg,
        primaryContainer = MonoDarkContainer,
        onPrimaryContainer = MonoDarkInk,
        inversePrimary = MonoDarkBg,
        secondary = MonoDarkOnSurfaceVariant,
        onSecondary = MonoDarkBg,
        secondaryContainer = MonoDarkContainer,
        onSecondaryContainer = MonoDarkInk,
        tertiary = MonoDarkOnSurfaceVariant,
        onTertiary = MonoDarkBg,
        tertiaryContainer = MonoDarkContainer,
        onTertiaryContainer = MonoDarkInk,
        // surface 容器梯度:柔黑基底,逐级加亮
        surfaceContainer = MonoDarkSurfaceVariant,
        surfaceContainerLow = MonoDarkBg,
        surfaceContainerHigh = MonoDarkContainerHigh,
        surfaceDim = MonoDarkBg,
        surfaceBright = MonoDarkContainerBright,
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
        outline = Color(0xFF35353B),
        outlineVariant = MonoDarkContainerHigh,
        scrim = Color.Black,
    ),
)

// ─────────────────────────────────────────────────────────────────────────────
