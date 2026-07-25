package io.zer0.muse.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseShapes

/**
 * Muse UI Kit — 容器基元 [MuseSurface]。
 *
 * v1.0.26: 统一所有卡片 / 分组容器 / 可点击矩形的实现,
 * 消除手动 `Modifier.clip().background().shadow()` 组合在浅色背景下产生的粗灰边、
 * 阴影穿透、嵌套色块等不稳定行为。
 *
 * 特性:
 *  - 由 Material3 [Surface] 统一处理 clip / color / shadow,获得柔和自然的阴影
 *  - 可选的 iOS 风格按压反馈(颜色渐变 + 触觉,无涟漪)
 *  - 可选的按压缩放(默认关闭)
 *  - 浅色模式向白方向偏移、深色模式向黑方向偏移的按压色,适配暖纸主题
 *
 * 与原 [io.zer0.muse.ui.common.IosCardPress] 的差异:
 *  - 基于 Surface 而非手动 Surface + scale + clickable,更稳更轻
 *  - 命名去 `Ios` 前缀,统一 `Muse` 命名空间
 *  - 不强制 `indication = null`,由调用方决定(pressed 时颜色渐变已足够反馈)
 *
 * 用法:
 * ```
 * // 静态卡片
 * MuseSurface(shape = MuseShapes.extraLarge, elevation = MuseElevation.card) {
 *     Text("内容")
 * }
 * // 可点击卡片(自动按压反馈 + 触觉)
 * MuseSurface(
 *     onClick = { ... },
 *     shape = MuseShapes.extraLarge,
 *     elevation = MuseElevation.card,
 * ) {
 *     Text("可点击卡片")
 * }
 * ```
 *
 * @param onClick 点击回调(null 表示不可点击,纯展示容器)
 * @param modifier 修饰符
 * @param shape 形状(默认 extraLarge,即 20dp 圆角,符合卡片主力圆角)
 * @param color 背景色(默认 surface,Material3 会自动处理 tonalElevation 色调叠加)
 * @param elevation 阴影高度(默认 0dp,平贴;卡片场景建议 1-2dp)
 * @param tonalElevation 色调海拔(默认 0dp,避免在浅色模式下产生嵌套色块)
 * @param enablePressedFeedback 是否启用按压颜色渐变反馈(仅 [onClick] 非空时生效,默认 true)
 * @param enableHaptic 是否启用触觉反馈(默认 true)
 * @param enableScale 是否启用按压缩放(默认 false,仅特殊场景如大卡片建议开启)
 * @param content 内容
 */
@Composable
fun MuseSurface(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    shape: Shape = MuseShapes.extraLarge,
    color: Color = MaterialTheme.colorScheme.surface,
    elevation: Dp = 0.dp,
    tonalElevation: Dp = 0.dp,
    enablePressedFeedback: Boolean = true,
    enableHaptic: Boolean = true,
    enableScale: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (onClick == null) {
        // 纯展示容器:无按压反馈,直接用 Surface
        Surface(
            modifier = modifier,
            shape = shape,
            color = color,
            tonalElevation = tonalElevation,
            shadowElevation = elevation,
            content = { content() },
        )
        return
    }

    // 可点击容器:按压颜色渐变 + 触觉反馈(无涟漪)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current

    // 按压颜色渐变: 浅色模式向白偏移 55%,深色模式向黑偏移 55%(对齐原 IosCardPress)
    val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val pressedColor = if (isLight) {
        color.copy(
            red = (color.red + (1f - color.red) * 0.55f).coerceAtMost(1f),
            green = (color.green + (1f - color.green) * 0.55f).coerceAtMost(1f),
            blue = (color.blue + (1f - color.blue) * 0.55f).coerceAtMost(1f),
        )
    } else {
        color.copy(
            red = color.red * 0.45f,
            green = color.green * 0.45f,
            blue = color.blue * 0.45f,
        )
    }

    val animatedColor by animateColorAsState(
        targetValue = if (isPressed && enablePressedFeedback) pressedColor else color,
        animationSpec = tween(
            durationMillis = MuseAnimation.TACTILE_MS,
            easing = MuseAnimation.EaseOutCubic,
        ),
        label = "muse_surface_color",
    )

    Surface(
        modifier = modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            role = Role.Button,
            onClick = {
                if (enableHaptic) {
                    MuseHaptics.light(haptic)
                }
                onClick()
            },
        ),
        shape = shape,
        color = animatedColor,
        tonalElevation = tonalElevation,
        shadowElevation = elevation,
        content = { content() },
    )
}
