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
 * 导航过渡动画 helper — 消除 NavGraph 中重复的 enterTransition/popExitTransition 块。
 *
 * 提供两种语义化过渡:
 *  - 水平导航:整屏覆盖滑动(二级页 push/pop),用于绝大多数页面;
 *  - 垂直 push:首页 HOME 专属的垂直过渡。
 *
 * 分层约定(关键,别改回半透明叠加):
 * Navigation Compose 的 AnimatedContent 把进入页(targetState)画在上层、退场页画在下层。
 * push:进入页不透明、走满屏([navPageOffset])从右侧盖上来,下层页走 1/4 视差([navUnderOffset])压暗。
 * pop(返回,主流读感):离开页整屏向右滑出([navPageOffset]),返回页只做 1/4 左视差归位
 * ([navUnderOffset])、不压暗——即「当前页整体右滑、上一页就地揭示」,而非旧的"从左侧整屏盖入"。
 * 进入页一律不透明:淡入会在上层透出下层形成"遮罩叠影"。深度由差速表达,不靠半透明叠加。
 *
 * 位移分量统一由 [MuseSpringTier.STEADY] 弹簧驱动(临界阻尼,终点停稳不回弹),
 * 导航中途反向时从当前位移继续而不是重播整段;压暗分量保留 tween(EXIT_MS 令牌)。
 * 时长收敛到 [MuseAnimation] 令牌,不用 private const 绕过令牌体系。
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
 * 注:slideInHorizontally / fadeOut 等动画构造函数均为顶级函数,不依赖
 * AnimatedContentTransitionScope receiver,故本对象内方法均为普通函数,可直接调用。
 */
// 纵向、横向两类过渡必须集中在同一词汇表，超过默认函数数阈值是有意的。
@Suppress("TooManyFunctions")
object MuseTransitions {

    /**
     * 首页纵向过渡的页面级位移（宽/高的 1/12 ≈ 8%）。
     *
     * 内容级/纵向位移压在 6%~8% 时表达的是"这页从哪来"，而不是"这页要滑多远"。
     * 水平导航不复用此值——它走 [navPageOffset] 满屏覆盖（见类注）。
     */
    private fun pageOffset(total: Int): Int = (total * MuseAnimation.SLIDE_FRACTION_PAGE).toInt()

    /** 首页纵向过渡的次要层位移（1/16 ≈ 6%）—— 被覆盖页退场、pop 回场。 */
    private fun secondaryOffset(total: Int): Int = (total * MuseAnimation.SLIDE_FRACTION_SECONDARY).toInt()

    /**
     * 导航进入页位移（满屏）：栈顶不透明页从导航方向整屏盖上来。
     * AnimatedContent 把进入页画在上层，故进入页必须不透明、走满屏才能"盖住"下层。
     */
    private fun navPageOffset(total: Int): Int = (total * MuseAnimation.SLIDE_FRACTION_NAV_PAGE).toInt()

    /** 导航被覆盖页视差位移（1/4 屏）：下层页随进入页反向差速退让，深度由差速表达。 */
    private fun navUnderOffset(total: Int): Int = (total * MuseAnimation.SLIDE_FRACTION_NAV_UNDER).toInt()

    /** 被覆盖页退场末端透明度：轻微压暗表达"退到下层"；进入页不透明盖在其上，故不会透出叠影。 */
    private const val UNDER_PAGE_DIM_ALPHA = 0.7f

    /**
     * 水平 push 入场 —— 进入页(栈顶)从右侧整屏不透明滑入盖住下层。
     * 不加淡入:进入页在上层,半透明会透出下层形成"遮罩叠影",这正是要消除的读感。
     */
    fun horizontalPushEnter(): EnterTransition = slideInHorizontally(
        MuseMotion.springSpec(MuseSpringTier.STEADY),
        initialOffsetX = { navPageOffset(it) },
    )

    /** 水平 push 退场 —— 被覆盖的下层页向左视差退让 1/4 屏并轻微压暗。 */
    fun horizontalPushExit(): ExitTransition = slideOutHorizontally(
        MuseMotion.springSpec(MuseSpringTier.STEADY),
        targetOffsetX = { -navUnderOffset(it) },
    ) + fadeOut(
        MuseMotion.tweenSpec(MuseAnimation.EXIT_MS, easing = MuseAnimation.EaseInCubic),
        targetAlpha = UNDER_PAGE_DIM_ALPHA,
    )

    /**
     * 水平 pop 入场 —— 返回页(下层)只做小幅左视差归位,不再整屏从左侧盖入。
     * 主流返回的读感是「当前页整体右滑退出、上一页就地揭示」,故返回页位移收到 1/4 屏视差,
     * 把主导 motion 让给 [horizontalPushPopExit] 的整屏右滑。保持不透明,避免上层淡入透出叠影。
     */
    fun horizontalPopEnter(): EnterTransition = slideInHorizontally(
        MuseMotion.springSpec(MuseSpringTier.STEADY),
        initialOffsetX = { -navUnderOffset(it) },
    )

    /**
     * 水平 pop 退场 —— 离开的当前页整屏向右滑出(满屏),即用户直觉的「返回=整体往右滑一下」。
     * 不再只走 1/4 视差+压暗(那是旧的"被覆盖"读感);干净滑出,不压暗。
     */
    fun horizontalPushPopExit(): ExitTransition = slideOutHorizontally(
        MuseMotion.springSpec(MuseSpringTier.STEADY),
        targetOffsetX = { navPageOffset(it) },
    )

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
