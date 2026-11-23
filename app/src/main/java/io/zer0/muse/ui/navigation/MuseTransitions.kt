package io.zer0.muse.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavHostController
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion

/**
 * 防重复压栈导航扩展：默认开启 `launchSingleTop = true`，
 * 避免快速双击列表项或入口按钮时把同一目的地压入返回栈两次。
 */
fun <T : Any> NavHostController.navigateSingleTop(route: T) {
    navigate(route) {
        launchSingleTop = true
    }
}

/**
 * 防空栈安全回退扩展：仅在存在上一级回退项时执行 `popBackStack()`，
 * 防止快速连点顶部返回按钮把根页面（HomeRoute）误弹出栈。
 */
fun NavHostController.popBackStackSafe(): Boolean =
    if (previousBackStackEntry != null) {
        popBackStack()
    } else {
        false
    }

/**
 * 导航过渡动画 helper — 全站统一收敛到干脆、单轴的水平 push / pop 推滑。
 *
 * 设计原则：
 *  - 单一轴向：首页与全部子页共享同一水平轴，不混用垂直退场与水平入场；
 *  - 确定时长：水平推滑与下层视差共用 [MuseAnimation.NAV_HORIZONTAL_MS]（240ms）与
 *    [MuseAnimation.EaseOutCubic] 减速曲线，避免慢弹簧 ~500ms 拖尾导致触摸门控滞留、
 *    双层节奏错拍；
 *  - 克制层次：栈顶进入/退出页保持 100% 不透明，被覆盖下层页仅做 1/4 视差与极轻压暗
 *    （[UNDER_PAGE_DIM_ALPHA]），无回弹、无重度暗遮罩、无叠影。
 */
@Suppress("TooManyFunctions")
object MuseTransitions {

    private fun pageOffset(total: Int): Int = (total * MuseAnimation.SLIDE_FRACTION_PAGE).toInt()

    private fun secondaryOffset(total: Int): Int = (total * MuseAnimation.SLIDE_FRACTION_SECONDARY).toInt()

    private fun navPageOffset(total: Int): Int = (total * MuseAnimation.SLIDE_FRACTION_NAV_PAGE).toInt()

    private fun navUnderOffset(total: Int): Int = (total * MuseAnimation.SLIDE_FRACTION_NAV_UNDER).toInt()

    /** 被覆盖页退场末端透明度：极轻压暗建立层次感，避免 0.7 级别的发灰重遮罩。 */
    private const val UNDER_PAGE_DIM_ALPHA = 0.92f

    private fun <T> horizontalSpec() = MuseMotion.tweenSpec<T>(
        durationMillis = MuseAnimation.NAV_HORIZONTAL_MS,
        easing = MuseAnimation.EaseOutCubic,
    )

    /** 水平 push 入场：新页从右侧满屏不透明干脆滑入。 */
    fun horizontalPushEnter(): EnterTransition = slideInHorizontally(
        animationSpec = horizontalSpec(),
        initialOffsetX = { navPageOffset(it) },
    )

    /** 水平 push 退场：下层页与新页同频向左退让 1/4 屏并极轻压暗。 */
    fun horizontalPushExit(): ExitTransition = slideOutHorizontally(
        animationSpec = horizontalSpec(),
        targetOffsetX = { -navUnderOffset(it) },
    ) + fadeOut(
        animationSpec = horizontalSpec(),
        targetAlpha = UNDER_PAGE_DIM_ALPHA,
    )

    /** 水平 pop 入场：下层返回页不透明从左 1/4 视差位干脆归位。 */
    fun horizontalPopEnter(): EnterTransition = slideInHorizontally(
        animationSpec = horizontalSpec(),
        initialOffsetX = { -navUnderOffset(it) },
    )

    /** 水平 pop 退场：当前页整屏向右干净滑出。 */
    fun horizontalPushPopExit(): ExitTransition = slideOutHorizontally(
        animationSpec = horizontalSpec(),
        targetOffsetX = { navPageOffset(it) },
    )

    /** 纵向 push 入场（保留给显式垂直模态场景使用，同步改用确定时长减速曲线）。 */
    fun verticalPushEnter(): EnterTransition = slideInVertically(
        animationSpec = MuseMotion.tweenSpec(MuseAnimation.NAV_VERTICAL_ENTER_MS, easing = MuseAnimation.EaseOutCubic),
        initialOffsetY = { pageOffset(it) },
    ) + fadeIn(MuseMotion.tweenSpec(MuseAnimation.NAV_VERTICAL_ENTER_MS, easing = MuseAnimation.EaseOutCubic))

    /** 纵向 push 退场。 */
    fun verticalPushExit(): ExitTransition = slideOutVertically(
        animationSpec = MuseMotion.tweenSpec(MuseAnimation.NAV_VERTICAL_EXIT_MS, easing = MuseAnimation.EaseOutCubic),
        targetOffsetY = { -secondaryOffset(it) },
    ) + fadeOut(MuseMotion.tweenSpec(MuseAnimation.NAV_VERTICAL_EXIT_MS, easing = MuseAnimation.EaseOutCubic))

    /** 纵向 pop 入场。 */
    fun verticalPopEnter(): EnterTransition = slideInVertically(
        animationSpec = MuseMotion.tweenSpec(MuseAnimation.NAV_VERTICAL_ENTER_MS, easing = MuseAnimation.EaseOutCubic),
        initialOffsetY = { -secondaryOffset(it) },
    ) + fadeIn(MuseMotion.tweenSpec(MuseAnimation.NAV_VERTICAL_ENTER_MS, easing = MuseAnimation.EaseOutCubic))

    /** 纵向 pop 退场。 */
    fun verticalPushPopExit(): ExitTransition = slideOutVertically(
        animationSpec = MuseMotion.tweenSpec(MuseAnimation.NAV_VERTICAL_EXIT_MS, easing = MuseAnimation.EaseOutCubic),
        targetOffsetY = { pageOffset(it) },
    ) + fadeOut(MuseMotion.tweenSpec(MuseAnimation.NAV_VERTICAL_EXIT_MS, easing = MuseAnimation.EaseOutCubic))
}
