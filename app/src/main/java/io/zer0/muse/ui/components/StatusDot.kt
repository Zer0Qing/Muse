package io.zer0.muse.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseColors

/**
 * Muse UI Kit — 状态圆点 [StatusDot]。
 *
 * 设计稿对齐:
 *  - 实心绿点 = 活跃/在线(dotActive)
 *  - 半透明绿点 = 历史(dotHistory)
 *  - 灰色 = 离线(dotOffline)
 *  - 活跃状态可选脉冲动画(alpha 呼吸)
 *
 * 用法:
 * ```
 * StatusDot(status = StatusDotStatus.ACTIVE, pulse = true)
 * StatusDot(status = StatusDotStatus.HISTORY)
 * StatusDot(status = StatusDotStatus.OFFLINE)
 * ```
 *
 * @param status 圆点状态
 * @param modifier 修饰符
 * @param size 圆点直径(默认 8dp)
 * @param pulse 是否启用脉冲动画(仅 ACTIVE 状态生效,默认 false)
 */
@Composable
fun StatusDot(
    status: StatusDotStatus,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
    pulse: Boolean = false,
) {
    val color = when (status) {
        StatusDotStatus.ACTIVE -> MuseColors.dotActive
        StatusDotStatus.HISTORY -> MuseColors.dotHistory
        StatusDotStatus.OFFLINE -> MuseColors.dotOffline
    }

    val alpha = if (pulse && status == StatusDotStatus.ACTIVE) {
        val transition = rememberInfiniteTransition(label = "status_dot_pulse")
        val animatedAlpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = MuseAnimation.LOOP_SLOW_MS,
                    easing = MuseAnimation.EaseOutCubic,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "status_dot_alpha",
        )
        animatedAlpha
    } else {
        1f
    }

    Box(
        modifier = modifier
            .size(size)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color),
    )
}

/** 状态圆点的三种状态。 */
enum class StatusDotStatus {
    /** 活跃/在线: 品牌绿实心。 */
    ACTIVE,
    /** 历史: 半透明绿。 */
    HISTORY,
    /** 离线: 灰色。 */
    OFFLINE,
}
