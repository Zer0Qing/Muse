package io.zer0.muse.ui.common.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import io.zer0.muse.ui.theme.MuseBreakpoints

/**
 * P1-4 平板适配:窗口宽度分级。
 *
 * 阈值(对齐 Material 3 WindowSizeClass 规范,数值收敛于 [MuseBreakpoints] 令牌):
 *  - [Compact]:  < 600dp(手机竖屏)
 *  - [Medium]:   600dp - 839dp(平板竖屏 / 手机横屏)
 *  - [Expanded]: ≥ 840dp(平板横屏 / 桌面)
 *
 * 实现说明:项目当前未引入 androidx.compose.material3:material3-window-size-class
 * 单独依赖,故用 [LocalConfiguration] 的 screenWidthDp 判断(语义等价于 WindowSizeClass
 * 基于 windowSizeDp 的判断,且可在任意 Composable 作用域直接调用,无需 BoxWithConstraints 包装)。
 */
enum class WindowWidthClass {
    /** 手机竖屏(< 600dp)。 */
    Compact,

    /** 平板竖屏 / 手机横屏(600dp - 839dp)。 */
    Medium,

    /** 平板横屏 / 桌面(≥ 840dp)。 */
    Expanded,
}

/**
 * 根据当前窗口宽度推断 [WindowWidthClass]。
 *
 * 在配置变更(旋转、分屏)时 LocalConfiguration 会重新组合,自动返回新分级。
 *
 * 使用示例:
 * ```
 * val widthClass = rememberWindowWidthClass()
 * if (widthClass == WindowWidthClass.Expanded) {
 *     // 双列布局
 * } else {
 *     // 单列布局
 * }
 * ```
 */
@Composable
fun rememberWindowWidthClass(): WindowWidthClass {
    val configuration = LocalConfiguration.current
    val view = androidx.compose.ui.platform.LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current

    // 自由窗口 / 分屏被系统 resize 时，Configuration.screenWidthDp 在部分 OEM 上
    // 不会同步更新（仍报整屏宽度），窄窗口会被误判成 Expanded：首页任务页走双栏、
    // 右栏聊天区被挤出可视范围，点会话列表只换来一次按下高亮；同时快捷胶囊因
    // isWideTasks=true 被隐藏。改用承载 Compose 的窗口 View 实测宽度，它跟随真实布局。
    val measuredWidthPx = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(view, density) {
        fun sync() {
            if (view.width > 0) measuredWidthPx.intValue = view.width
        }
        sync()
        val listener = android.view.View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width > 0) measuredWidthPx.intValue = v.width
        }
        view.addOnLayoutChangeListener(listener)
        onDispose { view.removeOnLayoutChangeListener(listener) }
    }

    val widthDp = measuredWidthPx.intValue
        .takeIf { it > 0 }
        ?.let { it / density.density }
        ?: configuration.screenWidthDp.toFloat()

    return when {
        widthDp >= MuseBreakpoints.EXPANDED_DP -> WindowWidthClass.Expanded
        widthDp >= MuseBreakpoints.MEDIUM_DP -> WindowWidthClass.Medium
        else -> WindowWidthClass.Compact
    }
}
