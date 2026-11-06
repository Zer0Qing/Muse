package io.zer0.muse.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MuseSpringTier

/**
 * 导航过渡动画 helper — 消除 NavGraph 中 198 处重复的 enterTransition/popExitTransition 块。
 *
 * 提供两种语义化过渡:
 *  - 水平 push:标准右滑入/左滑出(二级页 push),用于绝大多数二级页面
 *  - 垂直 push:首页 HOME 专属的垂直过渡
 *
 * v1.0.16:时长统一收敛到 [MuseAnimation] 令牌(NAV_HORIZONTAL_MS / NAV_VERTICAL_ENTER_MS /
 * NAV_VERTICAL_EXIT_MS),不再使用 private const 绕过令牌体系。
 *
 * 流体化改造:位移分量统一由 [MuseSpringTier.STEADY] 弹簧驱动(临界阻尼,终点停稳不回弹),
 * 导航中途反向时从当前位移继续而不是重播整段;透明度分量保留 tween——弹簧会让 alpha
 * 越过 [0,1],ENTER_MS/EXIT_MS 令牌值继续被淡入淡出消费,数值未动。手势级"可阻断跟手
 * 转场"依赖预测式返回改造,不在本层承诺。
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
// 纵向、横向两类过渡必须集中在同一词汇表，超过默认函数数阈值是有意的。
@Suppress("TooManyFunctions")
object MuseTransitions {

    /**
     * v2.6.7: 页面级位移像素（宽/高的 1/12 ≈ 8%）。
     *
     * 旧实现 `initialOffsetX = { it }` 让页面走满整屏，配 easeOutCubic
     * 的读感是"先猛冲一步再拖回原位"。页面与内容的位移压在 6%~8% 时，
     * 位移表达的是"这页从哪来"，而不是"这页要滑多远"。
     */
    private fun pageOffset(total: Int): Int = (total * MuseAnimation.SLIDE_FRACTION_PAGE).toInt()

    /** v2.6.7: 次要层位移（1/16 ≈ 6%）—— 被覆盖页退场、pop 回场。 */
    private fun secondaryOffset(total: Int): Int = (total * MuseAnimation.SLIDE_FRACTION_SECONDARY).toInt()

    /** 标准水平 push 入场(从右侧滑入 + 淡入)。 */
    fun horizontalPushEnter(): EnterTransition = slideInHorizontally(
        MuseMotion.springSpec(MuseSpringTier.STEADY),
        initialOffsetX = { pageOffset(it) },
    ) + fadeIn(MuseMotion.tweenSpec(MuseAnimation.ENTER_MS, easing = MuseAnimation.EaseOutCubic))

    /** v1.0.80: 纯 slide 入场(无淡入),避免半透明透出深色背景形成“遮罩”。 */
    fun horizontalSlideEnter(): EnterTransition = slideInHorizontally(
        MuseMotion.springSpec(MuseSpringTier.STEADY),
        initialOffsetX = { pageOffset(it) },
    )

    /** v1.0.80: 纯 slide 退场(无淡出),与 [horizontalSlideEnter] 配对。 */
    fun horizontalSlidePopExit(): ExitTransition = slideOutHorizontally(
        MuseMotion.springSpec(MuseSpringTier.STEADY),
        targetOffsetX = { pageOffset(it) },
    )

    /** 标准水平 push 退场(向右滑出 + 淡出)。 */
    fun horizontalPushPopExit(): ExitTransition = slideOutHorizontally(
        MuseMotion.springSpec(MuseSpringTier.STEADY),
        targetOffsetX = { pageOffset(it) },
    ) + fadeOut(MuseMotion.tweenSpec(MuseAnimation.EXIT_MS, easing = MuseAnimation.EaseInCubic))

    /** 水平 push 退场(向左滑出 1/3 + 淡出)— 根页面被覆盖时的退场。 */
    fun horizontalPushExit(): ExitTransition = slideOutHorizontally(
        MuseMotion.springSpec(MuseSpringTier.STEADY),
        targetOffsetX = { -secondaryOffset(it) },
    ) + fadeOut(MuseMotion.tweenSpec(MuseAnimation.EXIT_MS, easing = MuseAnimation.EaseInCubic))

    /** 水平 pop 入场(从左侧滑入 1/3 + 淡入)— 回到根页面时的入场。 */
    fun horizontalPopEnter(): EnterTransition = slideInHorizontally(
        MuseMotion.springSpec(MuseSpringTier.STEADY),
        initialOffsetX = { -secondaryOffset(it) },
    ) + fadeIn(MuseMotion.tweenSpec(MuseAnimation.ENTER_MS, easing = MuseAnimation.EaseOutCubic))

    /** 首页专用纵向 push 入场(从底部滑入 + 淡入)。 */
    fun verticalPushEnter(): EnterTransition = slideInVertically(
        animationSpec = MuseMotion.springSpec(MuseSpringTier.STEADY),
        initialOffsetY = { pageOffset(it) },
    ) + fadeIn(MuseMotion.tweenSpec(MuseAnimation.ENTER_MS, easing = MuseAnimation.EaseOutCubic))

    /** 首页专用纵向 push 退场(向上移出 1/3 + 淡出)。 */
    fun verticalPushExit(): ExitTransition = slideOutVertically(
        animationSpec = MuseMotion.springSpec(MuseSpringTier.STEADY),
        targetOffsetY = { -secondaryOffset(it) },
    ) + fadeOut(MuseMotion.tweenSpec(MuseAnimation.EXIT_MS, easing = MuseAnimation.EaseInCubic))

    /** 首页专用纵向 pop 入场(从上方回到首页 + 淡入)。 */
    fun verticalPopEnter(): EnterTransition = slideInVertically(
        animationSpec = MuseMotion.springSpec(MuseSpringTier.STEADY),
        initialOffsetY = { -secondaryOffset(it) },
    ) + fadeIn(MuseMotion.tweenSpec(MuseAnimation.ENTER_MS, easing = MuseAnimation.EaseOutCubic))

    /** 首页专用纵向 pop 退场(向底部移出 + 淡出)。 */
    fun verticalPushPopExit(): ExitTransition = slideOutVertically(
        animationSpec = MuseMotion.springSpec(MuseSpringTier.STEADY),
        targetOffsetY = { pageOffset(it) },
    ) + fadeOut(MuseMotion.tweenSpec(MuseAnimation.EXIT_MS, easing = MuseAnimation.EaseInCubic))
}
