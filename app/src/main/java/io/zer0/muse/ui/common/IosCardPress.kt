package io.zer0.muse.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseCornerRadius
import io.zer0.muse.ui.theme.MuseHaptics

/**
 * iOS 风格卡片按压效果 — Kelivo 触觉交互核心组件。
 *
 * 按下时:
 *  - 背景颜色渐变 (200ms easeOutCubic): 白方向偏移 55% / 黑方向偏移 55%
 *  - 可选缩放 (0.98): 轻微缩小营造"陷入"感
 *    v1.0.23: 缩放曲线从 tween 改为 spring(MediumBouncy + StiffnessMediumLow),
 *    按下与回弹自带轻微过冲,呈现 MANUS 风格弹性反馈
 *  - 可选触觉反馈: 轻击
 *
 * 无涟漪效果 (indication = null), 替代标准 `clickable {}` 的 Surface。
 *
 * 用法:
 * ```
 * IosCardPress(onClick = { ... }) {
 *     Text("卡片内容")
 * }
 * ```
 *
 * @param onClick 点击回调
 * @param modifier 修饰符
 * @param shape 卡片形状 (默认 12dp 圆角)
 * @param containerColor 背景色 (默认 `surface`)
 * @param elevation 阴影高度 (默认 0dp, 平坦风格)
 * @param enableScale 是否启用按压缩放 (默认 false)
 * @param enableHaptic 是否启用触觉反馈 (默认 true)
 * @param content 卡片内容
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IosCardPress(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(MuseCornerRadius.BUTTON.dp),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    elevation: Dp = 0.dp,
    enableScale: Boolean = false,
    enableHaptic: Boolean = true,
    /** v1.0.16: 可选长按回调,用于长按菜单等场景;为空时使用普通 clickable。 */
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current

    // 按压颜色渐变: 白方向 → 变浅 55%, 黑方向 → 变深 55%
    val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val pressedColor = if (isLight) {
        containerColor.copy(
            red = (containerColor.red + (1f - containerColor.red) * 0.55f).coerceAtMost(1f),
            green = (containerColor.green + (1f - containerColor.green) * 0.55f).coerceAtMost(1f),
            blue = (containerColor.blue + (1f - containerColor.blue) * 0.55f).coerceAtMost(1f),
        )
    } else {
        containerColor.copy(
            red = containerColor.red * 0.45f,
            green = containerColor.green * 0.45f,
            blue = containerColor.blue * 0.45f,
        )
    }

    val animatedColor by animateColorAsState(
        targetValue = if (isPressed) pressedColor else containerColor,
        animationSpec = tween(
            durationMillis = MuseAnimation.TACTILE_MS + 20, // 220ms, 比图标按钮稍慢
            easing = MuseAnimation.EaseOutCubic,
        ),
        label = "card_press_color",
    )

    // v1.0.23: 按压缩放改为 spring 弹簧曲线,增强 MANUS 风格弹性反馈。
    // 原 tween 线性曲线过渡生硬,spring 中等阻尼 + 中低刚度让卡片按下与回弹
    // 自带轻微过冲,模拟指尖按压软质的物理感,与 MANUS 暖调质感呼应。
    // 颜色渐变保留 tween(触觉色彩变化不需要弹性,线性更稳)。
    val animatedScale by animateFloatAsState(
        targetValue = if (isPressed && enableScale) 0.98f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "card_press_scale",
    )

    // v1.0.16: 根据是否有 onLongClick 选择 clickable/combinedClickable,两者均无 Material ripple 遮罩
    val pressModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null, // 无涟漪
            onClick = {
                if (enableHaptic) {
                    MuseHaptics.light(haptic)
                }
                onClick()
            },
            onLongClick = onLongClick,
        )
    } else {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null, // 无涟漪
            role = Role.Button,
            onClick = {
                if (enableHaptic) {
                    MuseHaptics.light(haptic)
                }
                onClick()
            },
        )
    }

    Surface(
        modifier = modifier
            .scale(animatedScale)
            .then(pressModifier),
        shape = shape,
        color = animatedColor,
        shadowElevation = elevation,
    ) {
        content()
    }
}
