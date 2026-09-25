package io.zer0.muse.ui.common.surface

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Muse 系统栏适配唯一入口。
 *
 * 规则：
 * - 顶栏容器负责 status bar，页面内容不再重复加 statusBarsPadding。
 * - 底栏/底部面板整体负责 navigation bar、系统手势区和 IME，内部内容只处理业务间距。
 * - 背景层可以沉浸式延伸，但可点击内容层必须使用对应安全区 modifier。
 */
fun Modifier.museTopBarInsets(): Modifier = statusBarsPadding()

fun Modifier.museBottomBarInsets(): Modifier =
    navigationBarsPadding()
        .imePadding()

fun Modifier.museDialogInsets(): Modifier =
    imePadding().navigationBarsPadding()

/**
 * v2.0.1: 可靠的状态栏顶部内边距（吸顶 / 滚动新结构专用）。
 *
 * 部分环境（如 MuMu 虚拟屏）对 app 回报的 statusBars insets 为 0 / 极小值，
 * 会让吸顶元素与滚动视口侵入状态栏；这里在 insets 异常小时回退到保守值，
 * insets 正常时使用系统真值。
 *
 * 与 [museTopBarInsets] 的区别：后者是页面顶栏常规入口（历史行为，
 * 依赖布局自身高度避开状态栏）；本函数面向“滚动内容负责顶部安全区”的新结构
 * （设置页吸顶搜索栏、可滚动大标题等）。
 */
@Composable
fun Modifier.museSafeTopInsetPadding(): Modifier {
    val density = LocalDensity.current
    val insetsTop = WindowInsets.statusBars.getTop(density)
    val minValid = with(density) { MIN_VALID_TOP_DP.dp.roundToPx() }
    val fallback = with(density) { FALLBACK_TOP_DP.dp.roundToPx() }
    val topPx = if (insetsTop < minValid) fallback else insetsTop
    return this.then(Modifier.padding(top = with(density) { topPx.toDp() }))
}

/** 兜底顶部内边距：状态栏 insets 异常（0 / 极小）时使用。 */
private const val FALLBACK_TOP_DP = 30

/** 视为“异常小”的 insets 阈值。 */
private const val MIN_VALID_TOP_DP = 8
