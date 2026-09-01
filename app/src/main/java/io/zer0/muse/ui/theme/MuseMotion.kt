package io.zer0.muse.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween as composeTween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

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
    fun <T> tween(
        durationMillis: Int,
        easing: Easing = MuseAnimation.Standard,
        delayMillis: Int = 0,
    ): DurationBasedAnimationSpec<T> = tweenForScale(durationMillis, easing, delayMillis, animatorScale())

    /**
     * 创建供非 Composable 回调使用的 tween，例如 Navigation Compose 的 transition helper。
     * 缩放值由 [Provide] 的单一观察器同步；未进入主题时安全地按 1x 处理。
     */
    fun <T> tweenSpec(
        durationMillis: Int,
        easing: Easing = MuseAnimation.Standard,
        delayMillis: Int = 0,
    ): DurationBasedAnimationSpec<T> = tweenForScale(
        durationMillis = durationMillis,
        easing = easing,
        delayMillis = delayMillis,
        scale = staticAnimatorScale.get(),
    )

    /** 纯淡入的统一进入过渡。 */
    fun fadeEnter(
        durationMillis: Int = MuseAnimation.NORMAL_MS,
        delayMillis: Int = 0,
    ): EnterTransition =
        fadeIn(
            tweenSpec(
                durationMillis = durationMillis,
                easing = MuseAnimation.EaseOutCubic,
                delayMillis = delayMillis,
            ),
        )

    /** 纯淡出的统一退出过渡。 */
    fun fadeExit(
        durationMillis: Int = MuseAnimation.NORMAL_MS,
        delayMillis: Int = 0,
    ): ExitTransition =
        fadeOut(
            tweenSpec(
                durationMillis = durationMillis,
                easing = MuseAnimation.EaseInCubic,
                delayMillis = delayMillis,
            ),
        )

    /** 仅展开内容的统一进入过渡。 */
    fun expandEnter(durationMillis: Int = MuseAnimation.NORMAL_MS): EnterTransition =
        expandVertically(
            animationSpec = tweenSpec(durationMillis, easing = MuseAnimation.EaseOutCubic),
        )

    /** 仅收起内容的统一退出过渡。 */
    fun expandExit(durationMillis: Int = MuseAnimation.NORMAL_MS): ExitTransition =
        shrinkVertically(
            animationSpec = tweenSpec(durationMillis, easing = MuseAnimation.EaseInCubic),
        )

    /** 展开/折叠内容的统一进入过渡。 */
    fun expandFadeEnter(
        durationMillis: Int = MuseAnimation.NORMAL_MS,
        fadeDurationMillis: Int = durationMillis,
    ): EnterTransition =
        expandEnter(durationMillis) + fadeEnter(fadeDurationMillis)

    /** 展开/折叠内容的统一退出过渡。 */
    fun expandFadeExit(
        durationMillis: Int = MuseAnimation.NORMAL_MS,
        fadeDurationMillis: Int = durationMillis,
    ): ExitTransition =
        expandExit(durationMillis) + fadeExit(fadeDurationMillis)

    /** 仅水平展开的统一进入过渡。 */
    fun horizontalExpandEnter(
        durationMillis: Int = MuseAnimation.NORMAL_MS,
        expandFrom: Alignment.Horizontal = Alignment.Start,
    ): EnterTransition =
        expandHorizontally(
            animationSpec = tweenSpec(durationMillis, easing = MuseAnimation.EaseOutCubic),
            expandFrom = expandFrom,
        )

    /** 仅水平收起的统一退出过渡。 */
    fun horizontalExpandExit(
        durationMillis: Int = MuseAnimation.NORMAL_MS,
        shrinkTowards: Alignment.Horizontal = Alignment.Start,
    ): ExitTransition =
        shrinkHorizontally(
            animationSpec = tweenSpec(durationMillis, easing = MuseAnimation.EaseInCubic),
            shrinkTowards = shrinkTowards,
        )

    /** 水平展开并淡入，适用于从边缘出现的内容卡片。 */
    fun horizontalExpandFadeEnter(
        durationMillis: Int = MuseAnimation.NORMAL_MS,
        fadeDurationMillis: Int = durationMillis,
        expandFrom: Alignment.Horizontal = Alignment.End,
    ): EnterTransition =
        horizontalExpandEnter(durationMillis, expandFrom) + fadeEnter(fadeDurationMillis)

    /** 水平收起并淡出，适用于从边缘消失的内容卡片。 */
    fun horizontalExpandFadeExit(
        durationMillis: Int = MuseAnimation.NORMAL_MS,
        fadeDurationMillis: Int = durationMillis,
        shrinkTowards: Alignment.Horizontal = Alignment.End,
    ): ExitTransition =
        horizontalExpandExit(durationMillis, shrinkTowards) + fadeExit(fadeDurationMillis)

    /** 纵向滑入并淡入。 */
    fun verticalSlideFadeEnter(
        durationMillis: Int = MuseAnimation.NORMAL_MS,
        fadeDurationMillis: Int = durationMillis,
        initialOffsetY: (Int) -> Int = { it / 2 },
    ): EnterTransition =
        slideInVertically(
            animationSpec = tweenSpec(durationMillis, easing = MuseAnimation.EaseOutCubic),
            initialOffsetY = initialOffsetY,
        ) + fadeEnter(fadeDurationMillis)

    /** 纵向滑出并淡出。 */
    fun verticalSlideFadeExit(
        durationMillis: Int = MuseAnimation.NORMAL_MS,
        fadeDurationMillis: Int = durationMillis,
        targetOffsetY: (Int) -> Int = { it / 2 },
    ): ExitTransition =
        slideOutVertically(
            animationSpec = tweenSpec(durationMillis, easing = MuseAnimation.EaseInCubic),
            targetOffsetY = targetOffsetY,
        ) + fadeExit(fadeDurationMillis)

    /** 水平滑入并淡入。 */
    fun horizontalSlideFadeEnter(
        durationMillis: Int = MuseAnimation.NORMAL_MS,
        fadeDurationMillis: Int = durationMillis,
        initialOffsetX: (Int) -> Int = { it },
    ): EnterTransition =
        slideInHorizontally(
            animationSpec = tweenSpec(durationMillis, easing = MuseAnimation.EaseOutCubic),
            initialOffsetX = initialOffsetX,
        ) + fadeEnter(fadeDurationMillis)

    /** 水平滑出并淡出。 */
    fun horizontalSlideFadeExit(
        durationMillis: Int = MuseAnimation.NORMAL_MS,
        fadeDurationMillis: Int = durationMillis,
        targetOffsetX: (Int) -> Int = { it },
    ): ExitTransition =
        slideOutHorizontally(
            animationSpec = tweenSpec(durationMillis, easing = MuseAnimation.EaseInCubic),
            targetOffsetX = targetOffsetX,
        ) + fadeExit(fadeDurationMillis)

    @Composable
    private fun animatorScale(): Float = LocalAnimatorScale.current

    private fun <T> tweenForScale(
        durationMillis: Int,
        easing: Easing,
        delayMillis: Int,
        scale: Float,
    ): DurationBasedAnimationSpec<T> {
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
        if (durationMillis <= 0 || safeScale <= 0f) return 0
        return (durationMillis * safeScale).roundToInt().coerceAtLeast(1)
    }

    private fun sanitizeScale(value: Float): Float =
        if (value.isFinite()) value.coerceAtLeast(0f) else 1f

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
