package io.zer0.muse.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import io.zer0.muse.ui.theme.MuseAnimation
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import io.zer0.muse.ui.theme.MuseIconSizes

/**
 * v1.51: iOS 风格开关 — 替代 Material3 默认 [androidx.compose.material3.Switch]。
 *
 * 视觉差异(对比 Material Switch):
 *  - 胶囊轨道(51×31dp vs Material 52×32dp 但更柔和)
 *  - 拇指圆形(27dp),开启时向右滑动,带平滑动画
 *  - 开启色 primary,关闭色 surfaceVariant(opacity 调整)
 *  - 拇指 onPrimary 描边确保对比度
 *  - 完整 a11y 语义:H-SW1 改用 Modifier.toggleable(Role.Switch),
 *    自动注册 toggleableState + onClick 无障碍动作;H-SW2 外层包 48dp 最小触摸目标。
 *
 * 用法:
 * ```
 * IosSwitch(checked = enabled, onCheckedChange = { v -> update { it.copy(enabled = v) } })
 * ```
 */
@Composable
fun IosSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val trackWidth = 51.dp
    val trackHeight = 31.dp
    val thumbSize = 27.dp
    val trackPadding = 2.dp // 拇指与轨道内边距

    val density = LocalDensity.current
    val trackWidthPx = with(density) { trackWidth.toPx() }
    val trackHeightPx = with(density) { trackHeight.toPx() }
    val thumbSizePx = with(density) { thumbSize.toPx() }
    val trackPaddingPx = with(density) { trackPadding.toPx() }

    // 拇指位置动画(左/右)
    val targetThumbX = if (checked) {
        trackWidthPx - thumbSizePx - trackPaddingPx
    } else {
        trackPaddingPx
    }
    val thumbX by animateFloatAsState(
        targetValue = targetThumbX,
        animationSpec = tween(MuseAnimation.TACTILE_MS),
        label = "iosSwitchThumb",
    )

    // 开启时轨道颜色动画
    val trackColor = if (checked) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
    }
    val thumbColor = if (checked) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val thumbInnerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)

    // H-SW2: 外层 Box 提供最小 48dp 触摸目标(视觉仍为 51×31dp,Canvas 居中)。
    // H-SW1: 用 Modifier.toggleable 替代 pointerInput detectTapGestures,
    //        自动注册 Role.Switch + toggleableState + onClick 无障碍动作,
    //        TalkBack 可正确朗读开关状态并执行点击切换。
    // v1.120: 显式传 indication = null 禁用默认点击反馈(ripple/高亮矩形),
    //         iOS 开关本身有拇指滑动动画作为视觉反馈,叠加 ripple 会产生黑色方框遮罩。
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .defaultMinSize(
                minWidth = MuseIconSizes.touchTarget,
                minHeight = MuseIconSizes.touchTarget,
            )
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                onValueChange = { onCheckedChange?.invoke(it) },
                enabled = onCheckedChange != null,
                role = Role.Switch,
            )
            .semantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier.size(width = trackWidth, height = trackHeight),
        ) {
            // 轨道(胶囊形)
            drawRoundRect(
                color = trackColor,
                topLeft = Offset.Zero,
                size = Size(trackWidthPx, trackHeightPx),
                cornerRadius = CornerRadius(trackHeightPx / 2, trackHeightPx / 2),
            )
            // 拇指(圆形)
            drawCircle(
                color = thumbColor,
                radius = thumbSizePx / 2,
                center = Offset(
                    thumbX + thumbSizePx / 2,
                    trackHeightPx / 2,
                ),
            )
            // 开启时拇指内圈淡色(装饰)
            if (checked) {
                drawCircle(
                    color = thumbInnerColor,
                    radius = thumbSizePx / 2 * 0.6f,
                    center = Offset(
                        thumbX + thumbSizePx / 2,
                        trackHeightPx / 2,
                    ),
                )
            }
        }
    }
}
