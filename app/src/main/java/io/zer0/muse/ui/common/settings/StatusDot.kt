package io.zer0.muse.ui.common.settings

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion

/**
 * 状态指示小圆点 — 用于 MCP server / Provider 连接状态。
 *
 * @param color 状态颜色(CONNECTED 绿 / CONNECTING 黄 / ERROR 红 / DISCONNECTED 灰)
 * @param size 圆点尺寸(默认 8dp)
 * @param pulse 是否启用脉冲动画(CONNECTING 状态用)
 * @param contentDescription 无障碍描述(如"已连接"),为 null 时不注册语义
 */
@Composable
fun StatusDot(
    color: Color,
    size: Dp = 8.dp,
    pulse: Boolean = false,
    contentDescription: String? = null,
) {
    val reducedMotion = MuseMotion.isReducedMotion()
    val alpha = if (!pulse) {
        1f
    } else if (reducedMotion) {
        0.65f
    } else {
        val transition = rememberInfiniteTransition(label = "status_dot_pulse")
        val animatedAlpha by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(MuseAnimation.LOOP_SLOW_MS),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "status_dot_alpha",
        )
        animatedAlpha
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
            // L-SC6: 注册 contentDescription 语义, TalkBack 可朗读连接状态(如"已连接")。
            .semantics(mergeDescendants = false) {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
    )
}
