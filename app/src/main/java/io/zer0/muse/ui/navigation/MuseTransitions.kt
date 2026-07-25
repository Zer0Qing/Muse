package io.zer0.muse.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import io.zer0.muse.ui.theme.MuseAnimation

/**
 * 导航过渡动画 helper — 消除 NavGraph 中 65 处重复的 enterTransition/popExitTransition 块。
 *
 * 提供两种语义化过渡:
 *  - 水平 push:标准右滑入/左滑出(二级页 push),用于绝大多数二级页面
 *  - 垂直 push:首页 HOME 专属的垂直过渡
 *
 * v1.0.16:时长统一收敛到 [MuseAnimation] 令牌(NAV_HORIZONTAL_MS / NAV_VERTICAL_ENTER_MS /
 * NAV_VERTICAL_EXIT_MS),不再使用 private const 绕过令牌体系。
 *
 * 用法:
 * ```
 * composable(
 *     route = MuseRoutes.XXX,
 *     enterTransition = { MuseTransitions.horizontalPushEnter() },
 *     popExitTransition = { MuseTransitions.horizontalPushPopExit() },
 * )
 * ```
 *
 * 注:slideInHorizontally / fadeIn / slideOutVertically 等动画构造函数均为顶级函数,
 * 不依赖 AnimatedContentTransitionScope receiver,故本对象内方法均为普通函数,可直接通过
 * `MuseTransitions.xxx()` 调用。
 */
object MuseTransitions {
    /** 标准水平 push 入场(从右侧滑入 + 淡入)。 */
    fun horizontalPushEnter(): EnterTransition =
        slideInHorizontally(tween(MuseAnimation.NAV_HORIZONTAL_MS), initialOffsetX = { it }) +
            fadeIn(tween(MuseAnimation.NAV_HORIZONTAL_MS))

    /** 标准水平 push 退场(向右滑出 + 淡出)。 */
    fun horizontalPushPopExit(): ExitTransition =
        slideOutHorizontally(tween(MuseAnimation.NAV_HORIZONTAL_MS), targetOffsetX = { it }) +
            fadeOut(tween(MuseAnimation.NAV_HORIZONTAL_MS))

    /** 垂直 push 入场(从下方滑入 + 淡入)— HOME 专用 enterTransition。 */
    fun verticalPushEnter(): EnterTransition =
        slideInVertically(tween(MuseAnimation.NAV_VERTICAL_ENTER_MS), initialOffsetY = { it }) +
            fadeIn(tween(MuseAnimation.NAV_VERTICAL_ENTER_MS))

    /** 垂直 push 退场(向上滑出 1/3 + 淡出)— HOME 专用 exitTransition。 */
    fun verticalPushExit(): ExitTransition =
        slideOutVertically(tween(MuseAnimation.NAV_VERTICAL_ENTER_MS), targetOffsetY = { -it / 3 }) +
            fadeOut(tween(MuseAnimation.NAV_VERTICAL_EXIT_MS))

    /** 垂直 push 重新入场(从上方滑入 1/3 + 淡入)— HOME 专用 popEnterTransition。 */
    fun verticalPushPopEnter(): EnterTransition =
        slideInVertically(tween(MuseAnimation.NAV_VERTICAL_ENTER_MS), initialOffsetY = { -it / 3 }) +
            fadeIn(tween(MuseAnimation.NAV_VERTICAL_EXIT_MS))

    /** 垂直 push 弹出退场(向下滑出 + 淡出)— HOME 专用 popExitTransition。 */
    fun verticalPushPopExit(): ExitTransition =
        slideOutVertically(tween(MuseAnimation.NAV_VERTICAL_ENTER_MS), targetOffsetY = { it }) +
            fadeOut(tween(MuseAnimation.NAV_VERTICAL_EXIT_MS))
}
