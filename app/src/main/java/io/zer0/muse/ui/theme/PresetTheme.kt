package io.zer0.muse.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import io.zer0.muse.R

/**
 * 预设主题系统 (v0.22 重写,参考 rikkahub PresetTheme.kt)。
 *
 * 6 套预设主题:
 *  - warm_paper (暖纸 - 默认,保留现有配色)
 *  - sakura (樱花 - 粉色系)
 *  - ocean (海洋 - 蓝色系)
 *  - spring (春 - 清新绿)
 *  - autumn (秋 - 橙黄)
 *  - amoled (AMOLED 纯黑 - OLED 省电)
 *
 * 设计原则:
 *  - 每套预设都包含 light + dark 两份完整 ColorScheme
 *  - 品牌色 (primary) 是稀缺资源,只用在 <5% 面积的交互元素上
 *  - 背景色遵循暖纸哲学:浅色用暖白而非纯白,深色用接近黑而非纯黑 (AMOLED 除外)
 *  - 用户气泡用 primary 区分,AI 气泡用 surfaceVariant
 *  - L-PT2:sakura / ocean / spring / autumn 的 secondary / tertiary 刻意与
 *    primary 同色。设计意图是"每套主题只在一个色相上做深浅",贯彻品牌色稀缺原则,
 *    避免每套主题额外引入 2 个色相(6 套 × 3 色 = 18 色相)造成视觉杂乱;
 *    warm_paper / amoled 为基准主题。如未来需要多色相强调,可在此单独差异化。
 */
@Immutable
data class PresetTheme(
    val id: String,
    val nameResId: Int,
    val lightScheme: ColorScheme,
    val darkScheme: ColorScheme,
)

/**
 * 按 id 查找预设主题,找不到则回退到默认 (warm_paper)。
 *
 * 注意: [PresetThemes] 列表在文件末尾声明(所有主题 val 之后),
 * 避免 Kotlin 顶层属性初始化顺序导致的 forward-reference 编译错误。
 */
fun findPresetTheme(id: String): PresetTheme =
    PresetThemes.firstOrNull { it.id == id } ?: WarmPaperTheme

// ─────────────────────────────────────────────────────────────────────────────
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
// 2. 樱花主题 (粉色系 - 参考 rikkahub SakuraTheme)
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
// 5. 秋主题 (橙黄 - 参考 rikkahub AutumnTheme)
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
// 6. AMOLED 主题 (OLED 纯黑 - 省电 + 对比度最高)
// ─────────────────────────────────────────────────────────────────────────────
private val AmoledDarkBg = Color(0xFF000000)
private val AmoledDarkInk = Color(0xFFE8E8E8)
private val AmoledDarkAiBubble = Color(0xFF1C1C1E)

val AmoledTheme = PresetTheme(
    id = "amoled",
    nameResId = R.string.theme_amoled,
    // AMOLED 浅色模式沿用暖纸浅色 (OLED 用户多数时间用深色)
    lightScheme = WarmPaperTheme.lightScheme,
    darkScheme = darkColorScheme(
        // v1.0.21: 深色模式 primary 改用 LaurelGreenBright,与 warm_paper 保持一致
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
        // M-2: surface 容器梯度,复用 warm_paper 深色取值(surface=纯黑,dim 同纯黑)。
        surfaceContainer = Color(0xFF141416),
        surfaceContainerLow = AmoledDarkBg,
        surfaceContainerHigh = Color(0xFF1C1C1E),
        surfaceDim = AmoledDarkBg,
        surfaceBright = Color(0xFF2C2C2E),
        background = AmoledDarkBg,
        onBackground = AmoledDarkInk,
        surface = AmoledDarkBg,
        onSurface = AmoledDarkInk,
        surfaceVariant = AmoledDarkAiBubble,
        onSurfaceVariant = AmoledDarkInk,
        surfaceTint = LaurelGreenBright,
        inverseSurface = AmoledDarkInk,
        inverseOnSurface = AmoledDarkBg,
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
// 8. Washi (和紙) theme — HanaAgent KAMI warm washi paper inspired
// ─────────────────────────────────────────────────────────────────────────────
private val WashiPrimary = Color(0xFF8B6F47)
private val WashiPrimaryLightContainer = Color(0xFFF0E4D0)
private val WashiPrimaryDarkContainer = Color(0xFF3A2C18)
private val WashiOnPrimaryDark = Color(0xFFF0E4D0)
private val WashiLightBg = Color(0xFFFBF7F0)
private val WashiDarkBg = Color(0xFF14100A)
private val WashiLightInk = Color(0xFF2A2014)
private val WashiDarkInk = Color(0xFFE8DFD0)
private val WashiLightAiBubble = Color(0xFFF0E8DA)
private val WashiDarkAiBubble = Color(0xFF1E1810)

val WashiTheme = PresetTheme(
    id = "washi",
    nameResId = R.string.theme_washi,
    lightScheme = lightColorScheme(
        primary = WashiPrimary,
        onPrimary = Color.White,
        primaryContainer = WashiPrimaryLightContainer,
        onPrimaryContainer = Color(0xFF3A2C18),
        secondary = WashiPrimary,
        onSecondary = Color.White,
        secondaryContainer = WashiPrimaryLightContainer,
        onSecondaryContainer = Color(0xFF3A2C18),
        tertiary = WashiPrimary,
        onTertiary = Color.White,
        tertiaryContainer = WashiPrimaryLightContainer,
        onTertiaryContainer = Color(0xFF3A2C18),
        surfaceContainer = WashiLightAiBubble,
        surfaceContainerLow = WashiLightBg,
        surfaceContainerHigh = Color(0xFFE8E0D2),
        surfaceDim = Color(0xFFE0D8C8),
        surfaceBright = Color(0xFFFDFBF5),
        background = WashiLightBg,
        onBackground = WashiLightInk,
        surface = WashiLightBg,
        onSurface = WashiLightInk,
        surfaceVariant = WashiLightAiBubble,
        onSurfaceVariant = WashiLightInk,
        surfaceTint = WashiPrimary,
        inverseSurface = WashiLightInk,
        inverseOnSurface = WashiLightBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerLightContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF8A8070),
        outlineVariant = Color(0xFFE0D8C8),
        scrim = Color.Black,
    ),
    darkScheme = darkColorScheme(
        primary = Color(0xFFC4A878),
        onPrimary = Color(0xFF2A2014),
        primaryContainer = WashiPrimaryDarkContainer,
        onPrimaryContainer = WashiOnPrimaryDark,
        secondary = Color(0xFFC4A878),
        onSecondary = Color(0xFF2A2014),
        secondaryContainer = WashiPrimaryDarkContainer,
        onSecondaryContainer = WashiOnPrimaryDark,
        tertiary = Color(0xFFC4A878),
        onTertiary = Color(0xFF2A2014),
        tertiaryContainer = WashiPrimaryDarkContainer,
        onTertiaryContainer = WashiOnPrimaryDark,
        surfaceContainer = WashiDarkAiBubble,
        surfaceContainerLow = WashiDarkBg,
        surfaceContainerHigh = Color(0xFF241E14),
        surfaceDim = Color(0xFF0C0804),
        surfaceBright = Color(0xFF2E2818),
        background = WashiDarkBg,
        onBackground = WashiDarkInk,
        surface = WashiDarkBg,
        onSurface = WashiDarkInk,
        surfaceVariant = WashiDarkAiBubble,
        onSurfaceVariant = WashiDarkInk,
        surfaceTint = Color(0xFFC4A878),
        inverseSurface = WashiDarkInk,
        inverseOnSurface = WashiDarkBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerDarkContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF8A7E68),
        outlineVariant = Color(0xFF2E2818),
        scrim = Color.Black,
    ),
)

// ─────────────────────────────────────────────────────────────────────────────
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
// 11. 琥珀金 (Amber Gold) theme — 暖琥珀色调,日落金光
// ─────────────────────────────────────────────────────────────────────────────
private val AmberPrimary = Color(0xFFB8860B)
private val AmberPrimaryLightContainer = Color(0xFFF5E8C8)
private val AmberPrimaryDarkContainer = Color(0xFF3D2D08)
private val AmberOnPrimaryDark = Color(0xFFF0E0B8)
private val AmberLightBg = Color(0xFFFAF8F2)
private val AmberDarkBg = Color(0xFF141208)
private val AmberLightInk = Color(0xFF2A2510)
private val AmberDarkInk = Color(0xFFE8E0C8)
private val AmberLightAiBubble = Color(0xFFF0EBD8)
private val AmberDarkAiBubble = Color(0xFF1E1C10)

val AmberGoldTheme = PresetTheme(
    id = "amber_gold",
    nameResId = R.string.theme_amber_gold,
    lightScheme = lightColorScheme(
        primary = AmberPrimary,
        onPrimary = Color.White,
        primaryContainer = AmberPrimaryLightContainer,
        onPrimaryContainer = Color(0xFF3A2808),
        secondary = AmberPrimary,
        onSecondary = Color.White,
        secondaryContainer = AmberPrimaryLightContainer,
        onSecondaryContainer = Color(0xFF3A2808),
        tertiary = AmberPrimary,
        onTertiary = Color.White,
        tertiaryContainer = AmberPrimaryLightContainer,
        onTertiaryContainer = Color(0xFF3A2808),
        surfaceContainer = AmberLightAiBubble,
        surfaceContainerLow = AmberLightBg,
        surfaceContainerHigh = Color(0xFFE8E2D0),
        surfaceDim = Color(0xFFE0DAC8),
        surfaceBright = Color(0xFFFDFBF5),
        background = AmberLightBg,
        onBackground = AmberLightInk,
        surface = AmberLightBg,
        onSurface = AmberLightInk,
        surfaceVariant = AmberLightAiBubble,
        onSurfaceVariant = AmberLightInk,
        surfaceTint = AmberPrimary,
        inverseSurface = AmberLightInk,
        inverseOnSurface = AmberLightBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerLightContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF8A8060),
        outlineVariant = Color(0xFFE0DAC8),
        scrim = Color.Black,
    ),
    darkScheme = darkColorScheme(
        primary = Color(0xFFD4A830),
        onPrimary = Color(0xFF2A2008),
        primaryContainer = AmberPrimaryDarkContainer,
        onPrimaryContainer = AmberOnPrimaryDark,
        secondary = Color(0xFFD4A830),
        onSecondary = Color(0xFF2A2008),
        secondaryContainer = AmberPrimaryDarkContainer,
        onSecondaryContainer = AmberOnPrimaryDark,
        tertiary = Color(0xFFD4A830),
        onTertiary = Color(0xFF2A2008),
        tertiaryContainer = AmberPrimaryDarkContainer,
        onTertiaryContainer = AmberOnPrimaryDark,
        surfaceContainer = AmberDarkAiBubble,
        surfaceContainerLow = AmberDarkBg,
        surfaceContainerHigh = Color(0xFF242010),
        surfaceDim = Color(0xFF0C0A04),
        surfaceBright = Color(0xFF2E2818),
        background = AmberDarkBg,
        onBackground = AmberDarkInk,
        surface = AmberDarkBg,
        onSurface = AmberDarkInk,
        surfaceVariant = AmberDarkAiBubble,
        onSurfaceVariant = AmberDarkInk,
        surfaceTint = Color(0xFFD4A830),
        inverseSurface = AmberDarkInk,
        inverseOnSurface = AmberDarkBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerDarkContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF908868),
        outlineVariant = Color(0xFF2E2818),
        scrim = Color.Black,
    ),
)

// ─────────────────────────────────────────────────────────────────────────────
// 12. 暮霭玫 (Dusk Rose) theme — 柔和玫瑰色调,暮色中的温柔
// ─────────────────────────────────────────────────────────────────────────────
private val DuskRosePrimary = Color(0xFFB07080)
private val DuskRosePrimaryLightContainer = Color(0xFFF5E0E5)
private val DuskRosePrimaryDarkContainer = Color(0xFF3D2028)
private val DuskRoseOnPrimaryDark = Color(0xFFF0D8DD)
private val DuskRoseLightBg = Color(0xFFFAF5F6)
private val DuskRoseDarkBg = Color(0xFF140E10)
private val DuskRoseLightInk = Color(0xFF2A1A1E)
private val DuskRoseDarkInk = Color(0xFFE8D8DC)
private val DuskRoseLightAiBubble = Color(0xFFF0E5E8)
private val DuskRoseDarkAiBubble = Color(0xFF1E1418)

val DuskRoseTheme = PresetTheme(
    id = "dusk_rose",
    nameResId = R.string.theme_dusk_rose,
    lightScheme = lightColorScheme(
        primary = DuskRosePrimary,
        onPrimary = Color.White,
        primaryContainer = DuskRosePrimaryLightContainer,
        onPrimaryContainer = Color(0xFF3A1820),
        secondary = DuskRosePrimary,
        onSecondary = Color.White,
        secondaryContainer = DuskRosePrimaryLightContainer,
        onSecondaryContainer = Color(0xFF3A1820),
        tertiary = DuskRosePrimary,
        onTertiary = Color.White,
        tertiaryContainer = DuskRosePrimaryLightContainer,
        onTertiaryContainer = Color(0xFF3A1820),
        surfaceContainer = DuskRoseLightAiBubble,
        surfaceContainerLow = DuskRoseLightBg,
        surfaceContainerHigh = Color(0xFFE8DDE0),
        surfaceDim = Color(0xFFE0D5D8),
        surfaceBright = Color(0xFFFDF8F9),
        background = DuskRoseLightBg,
        onBackground = DuskRoseLightInk,
        surface = DuskRoseLightBg,
        onSurface = DuskRoseLightInk,
        surfaceVariant = DuskRoseLightAiBubble,
        onSurfaceVariant = DuskRoseLightInk,
        surfaceTint = DuskRosePrimary,
        inverseSurface = DuskRoseLightInk,
        inverseOnSurface = DuskRoseLightBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerLightContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF8A7880),
        outlineVariant = Color(0xFFE0D5D8),
        scrim = Color.Black,
    ),
    darkScheme = darkColorScheme(
        primary = Color(0xFFD0909E),
        onPrimary = Color(0xFF2A1018),
        primaryContainer = DuskRosePrimaryDarkContainer,
        onPrimaryContainer = DuskRoseOnPrimaryDark,
        secondary = Color(0xFFD0909E),
        onSecondary = Color(0xFF2A1018),
        secondaryContainer = DuskRosePrimaryDarkContainer,
        onSecondaryContainer = DuskRoseOnPrimaryDark,
        tertiary = Color(0xFFD0909E),
        onTertiary = Color(0xFF2A1018),
        tertiaryContainer = DuskRosePrimaryDarkContainer,
        onTertiaryContainer = DuskRoseOnPrimaryDark,
        surfaceContainer = DuskRoseDarkAiBubble,
        surfaceContainerLow = DuskRoseDarkBg,
        surfaceContainerHigh = Color(0xFF241C20),
        surfaceDim = Color(0xFF0C0809),
        surfaceBright = Color(0xFF2E2228),
        background = DuskRoseDarkBg,
        onBackground = DuskRoseDarkInk,
        surface = DuskRoseDarkBg,
        onSurface = DuskRoseDarkInk,
        surfaceVariant = DuskRoseDarkAiBubble,
        onSurfaceVariant = DuskRoseDarkInk,
        surfaceTint = Color(0xFFD0909E),
        inverseSurface = DuskRoseDarkInk,
        inverseOnSurface = DuskRoseDarkBg,
        error = Danger,
        onError = Color.White,
        errorContainer = DangerDarkContainer,
        onErrorContainer = Danger,
        outline = Color(0xFF908088),
        outlineVariant = Color(0xFF2E2228),
        scrim = Color.Black,
    ),
)

// ─────────────────────────────────────────────────────────────────────────────
// 预设主题注册表(必须在所有 theme val 声明之后)
// ─────────────────────────────────────────────────────────────────────────────
val PresetThemes: List<PresetTheme> = listOf(
    WarmPaperTheme,
    SakuraTheme,
    OceanTheme,
    SpringTheme,
    AutumnTheme,
    AmoledTheme,
    SumiTheme,
    WashiTheme,
    AizomeTheme,
    TwilightPurpleTheme,
    AmberGoldTheme,
    DuskRoseTheme,
)
