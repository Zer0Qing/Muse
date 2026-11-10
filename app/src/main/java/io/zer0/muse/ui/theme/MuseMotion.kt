package io.zer0.muse.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import androidx.compose.animation.core.tween as composeTween

/**
 * Muse 全局动效策略。
 *
 * 所有有限动画都应通过 [tween] 或 [tweenSpec] 创建：前者用于 Compose 组合上下文，
 * 后者用于导航转场等非 Composable 回调。两者共享同一套时长、缓动和系统动画缩放规则。
 * 当系统关闭动画时，循环动画由调用方切换为静态状态，避免继续产生帧回调。
 */
object MuseMotion {
    private val LocalAnimatorScale = compositionLocalOf { 1f }
    private val staticAnimatorScale = AtomicReference(1f)

    /**
     * 在主题根部提供动效缩放状态，并只注册一个系统设置观察器。
     * 非 Composable 的导航转场会读取同一份静态缩放值，避免出现“页面转场仍在动、内容动画已停”的不一致。
     */
    @Composable
    fun Provide(content: @Composable () -> Unit) {
        val context = LocalContext.current.applicationContext
        val initialScale = remember(context) { readAnimatorScale(context) }
        var animatorScale by remember(context) { mutableFloatStateOf(initialScale) }
        staticAnimatorScale.set(animatorScale)

        DisposableEffect(context) {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    val value = readAnimatorScale(context)
                    animatorScale = value
                    staticAnimatorScale.set(value)
                }
            }
            val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
            context.contentResolver.registerContentObserver(uri, false, observer)
            onDispose { context.contentResolver.unregisterContentObserver(observer) }
        }

        CompositionLocalProvider(LocalAnimatorScale provides animatorScale) {
            content()
        }
    }

    /** 是否处于 reduced-motion 状态（系统动画缩放为 0）。 */
    @Composable
    fun isReducedMotion(): Boolean = animatorScale() <= 0f

    /** 根据系统动效缩放返回实际时长。 */
    @Composable
    fun duration(durationMillis: Int): Int = scaleDuration(durationMillis, animatorScale())

    /**
     * 创建遵循当前 Compose 动效缩放的 tween。
     * 0ms 代表立即完成；循环动画不要仅依赖 0ms，而应由调用方改为静态内容。
     */
    @Composable
    fun <T> tween(durationMillis: Int, easing: Easing = MuseAnimation.Standard, delayMillis: Int = 0): DurationBasedAnimationSpec<T> =
        tweenForScale(durationMillis, easing, delayMillis, animatorScale())

    /**
     * 创建供非 Composable 回调使用的 tween，例如 Navigation Compose 的 transition helper。
     * 缩放值由 [Provide] 的单一观察器同步；未进入主题时安全地按 1x 处理。
     */
    fun <T> tweenSpec(durationMillis: Int, easing: Easing = MuseAnimation.Standard, delayMillis: Int = 0): DurationBasedAnimationSpec<T> =
        tweenForScale(
            durationMillis = durationMillis,
            easing = easing,
            delayMillis = delayMillis,
            scale = staticAnimatorScale.get(),
        )

    /**
     * 创建遵循当前动效缩放的弹簧规格。
     *
     * 弹簧没有时长语义：缩放 > 0 时三档参数原样生效（缩放只直接作用于 tween 时长）；
     * 缩放 <= 0（系统关闭动画）时退化为 snap 即时完成，与卡片按压的降级契约一致。
     * 位移/尺寸/缩放类动画优先从这里取规格；透明度仍走 tween，弹簧会让 alpha 越过 [0,1]。
     */
    @Composable
    fun <T> spring(tier: MuseSpringTier, visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        springForScale(tier, visibilityThreshold, animatorScale())

    /** 供非 Composable 回调（导航转场等）使用的弹簧规格，读同一份静态缩放。 */
    fun <T> springSpec(tier: MuseSpringTier, visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        springForScale(tier, visibilityThreshold, staticAnimatorScale.get())

    /** 纯淡入的统一进入过渡。 */
    fun fadeEnter(durationMillis: Int = MuseAnimation.NORMAL_MS, delayMillis: Int = 0): EnterTransition = fadeIn(
        tweenSpec(
            durationMillis = durationMillis,
            easing = MuseAnimation.EaseOutCubic,
            delayMillis = delayMillis,
        ),
    )

    /** 纯淡出的统一退出过渡。 */
    fun fadeExit(durationMillis: Int = MuseAnimation.NORMAL_MS, delayMillis: Int = 0): ExitTransition = fadeOut(
        tweenSpec(
            durationMillis = durationMillis,
            easing = MuseAnimation.EaseInCubic,
            delayMillis = delayMillis,
        ),
    )

    /**
     * 仅展开内容的统一进入过渡。
     *
     * 尺寸分量由 STEADY 弹簧驱动（临界阻尼、终点不回弹，承接原 GrowEase
     * "先长出来再稳住"的读感）。弹簧没有时长语义，故不再收时长参数——
     * 保留死参数会被 detekt UnusedParameter 点名，也不如签名诚实。
     */
    fun expandEnter(): EnterTransition = expandVertically(
        animationSpec = springSpec(MuseSpringTier.STEADY),
    )

    /** 仅收起内容的统一退出过渡（尺寸分量 STEADY 弹簧，终点停稳）。 */
    fun expandExit(): ExitTransition = shrinkVertically(
        animationSpec = springSpec(MuseSpringTier.STEADY),
    )

    /** 展开/折叠内容的统一进入过渡（时长只作用于淡入分量）。 */
    fun expandFadeEnter(fadeDurationMillis: Int = MuseAnimation.NORMAL_MS): EnterTransition = expandEnter() + fadeEnter(fadeDurationMillis)

    /** 展开/折叠内容的统一退出过渡（时长只作用于淡出分量）。 */
    fun expandFadeExit(fadeDurationMillis: Int = MuseAnimation.NORMAL_MS): ExitTransition = expandExit() + fadeExit(fadeDurationMillis)

    /** 仅水平展开的统一进入过渡（尺寸分量 STEADY 弹簧；弹簧无时长语义，不收时长参数）。 */
    fun horizontalExpandEnter(expandFrom: Alignment.Horizontal = Alignment.Start): EnterTransition = expandHorizontally(
        animationSpec = springSpec(MuseSpringTier.STEADY),
        expandFrom = expandFrom,
    )

    /** 仅水平收起的统一退出过渡（尺寸分量 STEADY 弹簧）。 */
    fun horizontalExpandExit(shrinkTowards: Alignment.Horizontal = Alignment.Start): ExitTransition = shrinkHorizontally(
        animationSpec = springSpec(MuseSpringTier.STEADY),
        shrinkTowards = shrinkTowards,
    )

    /** 水平展开并淡入，适用于从边缘出现的内容卡片（时长只作用于淡入分量）。 */
    fun horizontalExpandFadeEnter(
        fadeDurationMillis: Int = MuseAnimation.NORMAL_MS,
        expandFrom: Alignment.Horizontal = Alignment.End,
    ): EnterTransition = horizontalExpandEnter(expandFrom) + fadeEnter(fadeDurationMillis)

    /** 水平收起并淡出，适用于从边缘消失的内容卡片（时长只作用于淡出分量）。 */
    fun horizontalExpandFadeExit(
        fadeDurationMillis: Int = MuseAnimation.NORMAL_MS,
        shrinkTowards: Alignment.Horizontal = Alignment.End,
    ): ExitTransition = horizontalExpandExit(shrinkTowards) + fadeExit(fadeDurationMillis)

    /**
     * 纵向滑入并淡入。
     *
     * v2.6.7: 默认位移从 `it / 2`（半屏）收到 `it / 20`（约 5%）。
     * 半屏配 easeOutCubic 的读感是 "整块被抽上来"；5% 才是 "就地浮现"。
     * 流体化: 位移分量改 RESPONSIVE 弹簧（微过冲即时跟手），
     * 透明度分量继续用 fadeDurationMillis 的 tween。
     * 确实需要大位移的场合（全屏预览等）请显式传 initialOffsetY。
     */
    fun verticalSlideFadeEnter(
        durationMillis: Int = MuseAnimation.CONTENT_ENTER_MS,
        fadeDurationMillis: Int = durationMillis,
        initialOffsetY: (Int) -> Int = { (it * MuseAnimation.SLIDE_FRACTION_CONTENT).roundToInt() },
    ): EnterTransition = slideInVertically(
        animationSpec = springSpec(MuseSpringTier.RESPONSIVE),
        initialOffsetY = initialOffsetY,
    ) + fadeEnter(fadeDurationMillis)

    /**
     * 纵向滑出并淡出。
     *
     * v2.6.7: 退场默认比入场短（200ms vs 260ms），且位移同样收到 5%。
     * 流体化: 退场位移用 STEADY 弹簧（不回弹，离开不拖沓）。
     */
    fun verticalSlideFadeExit(
        durationMillis: Int = MuseAnimation.CONTENT_EXIT_MS,
        fadeDurationMillis: Int = durationMillis,
        targetOffsetY: (Int) -> Int = { (it * MuseAnimation.SLIDE_FRACTION_CONTENT).roundToInt() },
    ): ExitTransition = slideOutVertically(
        animationSpec = springSpec(MuseSpringTier.STEADY),
        targetOffsetY = targetOffsetY,
    ) + fadeExit(fadeDurationMillis)

    /**
     * 水平滑入并淡入。
     *
     * v2.6.7: 默认位移从整屏（`it`）收到 1/12（约 8%）。
     * 整屏横向滑动是 iOS 导航栈的语法；用在内容条/浮层上会显得很重。
     * 流体化: 位移分量改 RESPONSIVE 弹簧。
     */
    fun horizontalSlideFadeEnter(
        durationMillis: Int = MuseAnimation.CONTENT_ENTER_MS,
        fadeDurationMillis: Int = durationMillis,
        initialOffsetX: (Int) -> Int = { (it * MuseAnimation.SLIDE_FRACTION_PAGE).roundToInt() },
    ): EnterTransition = slideInHorizontally(
        animationSpec = springSpec(MuseSpringTier.RESPONSIVE),
        initialOffsetX = initialOffsetX,
    ) + fadeEnter(fadeDurationMillis)

    /** 水平滑出并淡出（退场短于入场；位移分量 STEADY 弹簧不回弹）。 */
    fun horizontalSlideFadeExit(
        durationMillis: Int = MuseAnimation.CONTENT_EXIT_MS,
        fadeDurationMillis: Int = durationMillis,
        targetOffsetX: (Int) -> Int = { (it * MuseAnimation.SLIDE_FRACTION_PAGE).roundToInt() },
    ): ExitTransition = slideOutHorizontally(
        animationSpec = springSpec(MuseSpringTier.STEADY),
        targetOffsetX = targetOffsetX,
    ) + fadeExit(fadeDurationMillis)

    /**
     * v2.6.7: "就地长出" 入场 —— 缩放 + 淡入，无位移。
     *
     * 缩放入场（与 Compose `AnimatedScale` 同形）：元素不跑动，
     * 而是从 96% 尺寸长到 100%。这是最不 "抽" 的入场方式，
     * 适合卡片、提示条、菜单项这些本来就该在原位出现的东西。
     *
     * 不用 [MuseAnimation.EaseOutBack]：回弹在频繁出现的列表项上会显得嗧。
     */
    fun scaleFadeEnter(
        durationMillis: Int = MuseAnimation.CONTENT_ENTER_MS,
        fadeDurationMillis: Int = durationMillis,
        initialScale: Float = 0.96f,
    ): EnterTransition = scaleIn(
        animationSpec = springSpec(MuseSpringTier.RESPONSIVE),
        initialScale = initialScale,
        transformOrigin = TransformOrigin(0.5f, 0.5f),
    ) + fadeEnter(fadeDurationMillis)

    /** v2.6.7: "就地收回" 退场 —— 缩放 + 淡出，移向 98%（比入场更浅）；缩放分量 STEADY 弹簧。 */
    fun scaleFadeExit(
        durationMillis: Int = MuseAnimation.CONTENT_EXIT_MS,
        fadeDurationMillis: Int = durationMillis,
        targetScale: Float = 0.98f,
    ): ExitTransition = scaleOut(
        animationSpec = springSpec(MuseSpringTier.STEADY),
        targetScale = targetScale,
        transformOrigin = TransformOrigin(0.5f, 0.5f),
    ) + fadeExit(fadeDurationMillis)

    @Composable
    private fun animatorScale(): Float = LocalAnimatorScale.current

    internal fun <T> springForScale(tier: MuseSpringTier, visibilityThreshold: T?, scale: Float): FiniteAnimationSpec<T> {
        val safeScale = sanitizeScale(scale)
        if (safeScale <= 0f) return snap()
        return spring(
            dampingRatio = MuseAnimation.dampingRatio(tier),
            stiffness = MuseAnimation.stiffness(tier),
            visibilityThreshold = visibilityThreshold,
        )
    }

    private fun <T> tweenForScale(durationMillis: Int, easing: Easing, delayMillis: Int, scale: Float): DurationBasedAnimationSpec<T> {
        val safeScale = sanitizeScale(scale)
        val scaledDuration = scaleDuration(durationMillis, safeScale)
        val scaledDelay = if (scaledDuration == 0 || delayMillis <= 0 || safeScale <= 0f) {
            0
        } else {
            (delayMillis * safeScale).roundToInt().coerceAtLeast(0)
        }
        return composeTween(
            durationMillis = scaledDuration,
            easing = easing,
            delayMillis = scaledDelay,
        )
    }

    private fun scaleDuration(durationMillis: Int, scale: Float): Int {
        val safeScale = sanitizeScale(scale)
        if (durationMillis <= 0) return 0
        // v2.6.x 崩溃修复: 系统动画缩放为 0(开发者选项/省电模式关闭动画)时,
        // 不能把时长压到 0 —— navigation-compose 的 NavHost 内部用
        // SeekableTransitionState 计算进度时是 playTime / totalDurationNanos 的除法,
        // 总时长为 0 会算出 NaN(0/0),再传给 seekTo 就抛
        // "IllegalArgumentException: Expecting fraction between 0 and 1. Got NaN"。
        // 1ms 在视觉上仍是「立即完成」,但能让 totalDurationNanos 非 0,避免除零。
        val scaled = if (safeScale <= 0f) 1 else (durationMillis * safeScale).roundToInt()
        return scaled.coerceAtLeast(1)
    }

    private fun sanitizeScale(value: Float): Float = if (value.isFinite()) value.coerceAtLeast(0f) else 1f

    private fun readAnimatorScale(context: Context): Float {
        return try {
            sanitizeScale(
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                ),
            )
        } catch (e: SecurityException) {
            // 个别定制 ROM 拒绝读取系统设置；按非降级处理并记录，便于排查。
            android.util.Log.w("MuseMotion", "read animator scale denied", e)
            1f
        }
    }
}
