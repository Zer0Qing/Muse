package io.zer0.muse.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.WindowCompat
import androidx.compose.runtime.RememberObserver

/**
 * v0.52: 从 Context 链中查找 Activity(用于操作 Window)。
 * Compose 里 LocalContext 可能是 ContextWrapper 包裹的,需向上回溯找到 Activity。
 */
private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/** 完全无绘制的 indication，避免 clickable/combinedClickable 出现黑色/深色遮罩。 */
private class NoIndicationNode : Modifier.Node(), DrawModifierNode {
    override fun ContentDrawScope.draw() { drawContent() }
}

private object NoIndicationNodeFactory : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = NoIndicationNode()
    override fun hashCode(): Int = System.identityHashCode(this)
    override fun equals(other: Any?): Boolean = other === this
}
/**
 * Muse 主题入口 (v0.22 重写,既有实现 Theme.kt)。
 *
 * 设计决策:
 *  - 用 [MaterialExpressiveTheme] 替代 MaterialTheme,启用 [MotionScheme.expressive]
 *    (CardGroup 按压动画、页面切换弹性动效均依赖此)
 *  - 6 套预设主题 (warm_paper / sakura / ocean / spring / autumn / amoled) 由 [themeId] 切换
 *  - v1.97 gap7: 支持自定义主题 ([customThemes]) — 用户基于种子色生成的 ColorScheme,
 *    优先级介于动态色与预设主题之间(动态色 > 自定义 > 预设)
 *  - 主题模式 (system / light / dark) 由 [darkTheme] 控制
 *  - 不启用 Material You 动态取色 (dynamicColor):品牌色不被系统壁纸污染
 *    (如未来需要,可在 settings 加开关,这里保留参数)
 *  - 字号缩放 [fontSizeScale] 通过 [MuseTypography.scaled] 应用到所有文字
 *
 * Safe Mode 说明 (L-13):各参数均带默认值(darkTheme 跟随系统、themeId=mono、
 * fontSizeScale=medium),故 `MuseTheme { content }` 即可独立运行。Safe Mode 设计目标
 * 是"最小可用":在 SettingsRepository 尚未就绪或读取失败的降级场景下,不依赖任何运行时
 * 状态即可渲染基本界面,因此刻意不接入动态色 / 自定义主题 / 字号缩放。
 *
 * @param darkTheme 是否深色模式 (由调用方据 SettingsRepository.themeModeFlow 决定)
 * @param themeId 预设主题 id (由调用方据 SettingsRepository.themeIdFlow 传入)
 * @param darkThemeId 深色模式独立主题 id (空字符串表示跟随亮色主题的暗色版)
 * @param fontSizeScale 字号档位 "small" / "medium" / "large" / "xlarge"
 * @param dynamicColor 是否启用 Material You 动态取色 (默认 false)
 * @param customThemes v1.97 gap7: 用户自定义主题列表(基于种子色生成 ColorScheme)
 * @param bodyFontFamily E2: 自定义正文字体族(默认系统字体;由调用方按 customFontPath 加载)
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@Suppress(
    "LongParameterList",
    "CyclomaticComplexMethod",
    // 主题根组件:主题模式/动态色/自定义主题/字体等参数集合与取色回退分支为屏幕级固有结构
)
fun MuseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeId: String = "mono",
    darkThemeId: String = "",
    fontSizeScale: String = "medium",
    dynamicColor: Boolean = false,
    customThemes: List<CustomTheme> = emptyList(),
    bodyFontFamily: FontFamily = FontFamily.Default,
    /** H5: 高对比主题 — 前景/背景对比拉满(深色=纯黑底纯白字,浅色=纯白底纯黑字),面向弱视用户。 */
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    // L-1: LocalContext.current 必须在 remember 外读取(remember 的 key 不含 context,
    // 否则 context 变化不会触发重算);colorScheme 用 remember 缓存,避免每次重组都重建
    // ColorScheme(dynamicColorScheme 会读系统资源,预设主题会查表,均应缓存)。
    // v1.97 gap7: remember key 加入 customThemes,使新增/编辑/删除自定义主题后立即重算。
    val context = LocalContext.current
    val resolvedThemeId = if (darkTheme && darkThemeId.isNotBlank()) darkThemeId else themeId
    val colorScheme = remember(dynamicColor, darkTheme, resolvedThemeId, customThemes) {
        when {
            // 动态色 (Android 12+): 系统壁纸取色 (默认关闭,保留接口)
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            // v1.97 gap7: 自定义主题 — 基于种子色 HCT 算法生成 ColorScheme
            // 优先级在动态色之后、预设主题之前,让用户既可用系统壁纸色,也可用自定义种子色
            else -> {
                val custom = customThemes.firstOrNull { it.id == resolvedThemeId }
                if (custom != null) {
                    custom.generateColorScheme(darkTheme)
                } else {
                    // 预设主题 (默认回退路径)
                    val preset = findPresetTheme(resolvedThemeId)
                    if (darkTheme) preset.darkScheme else preset.lightScheme
                }
            }
        }
    }
    // H5: 高对比模式在主题色之上覆盖背景/前景对比,保留 primary 等主题色语义
    val effectiveColorScheme = if (highContrast) colorScheme.toHighContrast(darkTheme) else colorScheme

    // v0.52: 预染色状态栏 + 导航栏,避免主题切换闪烁(先白后黑 / 先黑后白)。
    // 用 DisposableEffect(darkTheme) 在 darkTheme 变化时同步重设系统栏外观,
    // 比 SideEffect 更早且在 key 变化时立即触发,减少一帧色差。
    // L-3: key 只取 darkTheme —— 系统栏外观(图标明暗)仅依赖明暗模式,与 themeId 无关,
    // 纳入 themeId 会导致切换主题时多余地重设系统栏。themeId 变化引发的 colorScheme
    // 切换由上方 remember 自动处理,不影响系统栏外观。
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(darkTheme, effectiveColorScheme) {
            val window = context.findActivity()?.window
            if (window != null) {
                // edge-to-edge 由 MainActivity.enableEdgeToEdge 统一负责,L-11 删除此处
                // setDecorFitsSystemWindows 调用以免重复设置。
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
                // v1.131: 显式设置系统栏背景色,解决 enableEdgeToEdge 导致的透明状态栏/导航栏问题
                // (SystemBarStyle.auto 在部分设备上不生效,直接设 window 背景色更稳定)
                // 用 toArgb() 把 Compose Color 转 Int 色值(API 要求 Int)
                window.statusBarColor = effectiveColorScheme.background.toArgb()
                window.navigationBarColor = effectiveColorScheme.background.toArgb()
            }
            onDispose {
                // L-2: 保持当前外观,不做还原。MuseTheme 包裹整个 App,onDispose 仅在
                // Activity 销毁时触发,不存在"下一个非 Muse 主题界面"需要还原的场景;
                // 原代码设置 !darkTheme 实为 no-op(与 effect 体设置相同值),注释却声称
                // "交还给系统默认",属注释与行为不符,此处修正注释以匹配实际语义。
                val window = context.findActivity()?.window
                if (window != null) {
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !darkTheme
                    controller.isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    // M-TP1: 按 fontSizeScale 缓存缩放后的 Typography,避免每次重组都 copy 整个 Typography。
    // E2: bodyFontFamily 变化(导入/清除字体)时一并重建,将自定义字体应用到全部样式。
    val scaledTypography = remember(fontSizeScale, bodyFontFamily) {
        val base = MuseTypography.scaled(fontSizeScale)
        if (bodyFontFamily == FontFamily.Default) base else base.withFontFamily(bodyFontFamily)
    }
    // L-TH2: MotionScheme.expressive() 缓存为单例,避免每次重组新建。
    val motionScheme = remember { MotionScheme.expressive() }

    MaterialExpressiveTheme(
        colorScheme = effectiveColorScheme,
        typography = scaledTypography,
        shapes = MuseShapes,
        motionScheme = motionScheme,
    ) {
        // v1.68: 全局禁用默认 ripple 遮罩，避免浅色/深色主题下出现黑色按压阴影。
        // 需要按压反馈的组件使用 MuseCardPress / MuseTactileButton 等自带颜色渐变组件。
        // v1.0.52: 同步注入语义状态色与代码高亮色,业务代码不再硬编码裸色。
        val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors
        val codeColors = if (darkTheme) DarkCodeColors else LightCodeColors
        CompositionLocalProvider(
            LocalIndication provides NoIndicationNodeFactory,
            LocalStatusColors provides statusColors,
            LocalCodeColors provides codeColors,
        ) {
            content()
        }
    }
}

/**
 * H5: 高对比模式 — 把背景/前景/表面相关颜色拉满对比度(纯黑/纯白),保留主题 primary 语义色。
 * 面向弱视用户:文本与背景对比度达到最大(21:1),secondaryContainer/errorContainer 等
 * 语义容器色保持不变,避免破坏状态色可辨识度。
 */
private fun ColorScheme.toHighContrast(darkTheme: Boolean): ColorScheme =
    if (darkTheme) {
        copy(
            background = Color(0xFF000000),
            onBackground = Color(0xFFFFFFFF),
            surface = Color(0xFF000000),
            onSurface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFF1E1E1E),
            onSurfaceVariant = Color(0xFFE6E6E6),
        )
    } else {
        copy(
            background = Color(0xFFFFFFFF),
            onBackground = Color(0xFF000000),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF000000),
            surfaceVariant = Color(0xFFE6E6E6),
            onSurfaceVariant = Color(0xFF1E1E1E),
        )
    }
