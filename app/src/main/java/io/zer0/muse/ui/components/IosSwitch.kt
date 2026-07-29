package io.zer0.muse.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseHaptics

/**
 * Muse UI Kit — iOS 风格开关 [IosSwitch]。
 *
 * 设计稿对齐:
 *  - 开启态: 品牌绿背景 + 白色滑块
 *  - 关闭态: 浅灰背景(#E8E8E4) + 白色滑块
 *  - 无涟漪,颜色渐变过渡(200ms easeOutCubic)
 *  - 尺寸: 51x31dp(对齐 iOS UISwitch)
 *
 * 用法:
 * ```
 * IosSwitch(
 *     checked = isEnabled,
 *     onCheckedChange = { isEnabled = it },
 * )
 * ```
 *
 * @param checked 是否开启
 * @param onCheckedChange 状态变更回调
 * @param modifier 修饰符
 * @param enabled 是否可交互(禁用态降低透明度)
 */
@Composable
fun IosSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    // 轨道颜色动画
    val trackColor by animateColorAsState(
        targetValue = if (checked) {
            Color(0xFF2A7A55) // 品牌绿
        } else {
            Color(0xFFE8E8E4) // 浅灰(浅色模式) / 深灰(深色模式由调用方覆盖)
        },
        animationSpec = tween(
            durationMillis = MuseAnimation.TACTILE_MS,
            easing = MuseAnimation.EaseOutCubic,
        ),
        label = "ios_switch_track",
    )

    // 滑块偏移: 关闭=2dp, 开启=22dp (51 - 27 - 2 = 22)
    val thumbOffset = if (checked) 22.dp else 2.dp

    Box(
        modifier = modifier
            .size(width = 51.dp, height = 31.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (enabled) trackColor else trackColor.copy(alpha = 0.5f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = {
                    MuseHaptics.light(haptic)
                    onCheckedChange(!checked)
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        // 白色滑块
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(27.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}
