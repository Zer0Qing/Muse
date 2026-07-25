package io.zer0.muse.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/**
 * P1-4 平板适配:窗口宽度分级。
 *
 * 阈值(对齐 Material 3 WindowSizeClass 规范):
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
    return remember(configuration.screenWidthDp) {
        when {
            configuration.screenWidthDp >= 840 -> WindowWidthClass.Expanded
            configuration.screenWidthDp >= 600 -> WindowWidthClass.Medium
            else -> WindowWidthClass.Compact
        }
    }
}
