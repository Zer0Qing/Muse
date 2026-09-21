package io.zer0.muse.ui.common.state

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion

/**
 * 局部进度组件 — 按钮里、卡片角上、行内的小转圈和进度条。
 *
 * 页面级加载仍用 [MuseLoadingState]；本文件只负责「页面里某个小块正在忙」。
 *
 * 与 Material3 `CircularProgressIndicator` 的差别：
 *  - 颜色默认跟随 [LocalContentColor]，放在实心/危险色按钮里不会变成同色不可见；
 *  - 尺寸与线宽走令牌，全应用统一；
 *  - 自绘，不引入新的原生控件依赖。
 *
 * @param size 直径
 * @param strokeWidth 线宽
 * @param color 弧线颜色（默认跟随当前文字色）
 */
@Composable
fun MuseSpinner(
    modifier: Modifier = Modifier,
    size: Dp = MuseIconSizes.iconSmall,
    strokeWidth: Dp = MuseIconSizes.progressStroke,
    color: Color = LocalContentColor.current,
) {
    val density = LocalDensity.current
    val diameterPx = with(density) { size.toPx() }
    val strokePx = with(density) { strokeWidth.toPx() }
    val reducedMotion = MuseMotion.isReducedMotion()

    val angle = if (reducedMotion) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "muse_spinner")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = MuseMotion.tween(MuseAnimation.LOOP_SLOW_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "muse_spinner_angle",
        )
        animated
    }

    Canvas(modifier = modifier.size(size)) {
        val inset = strokePx / 2f
        val arcSize = Size(diameterPx - strokePx, diameterPx - strokePx)
        drawArc(
            color = color.copy(alpha = 0.2f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
    }
}

/**
 * 确定性进度条（0..1）。
 *
 * @param progress 进度，自动收敛到 0..1
 * @param height 轨道高度
 */
@Composable
fun MuseProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MuseActionColors.container,
    trackColor: Color = MuseActionColors.neutralContainer,
    height: Dp = MuseProgressDefaults.trackHeight,
) {
    val fraction = progress.coerceIn(0f, 1f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = trackColor, cornerRadius = radius)
        if (fraction > 0f) {
            drawRoundRect(
                color = color,
                size = Size(size.width * fraction, size.height),
                cornerRadius = radius,
            )
        }
    }
}

/**
 * 不确定进度条 — 用于时长未知的加载（取代原生 LinearProgressIndicator 的无限模式）。
 */
@Composable
fun MuseIndeterminateProgressBar(
    modifier: Modifier = Modifier,
    color: Color = MuseActionColors.container,
    trackColor: Color = MuseActionColors.neutralContainer,
    height: Dp = MuseProgressDefaults.trackHeight,
) {
    val reducedMotion = MuseMotion.isReducedMotion()
    val head = if (reducedMotion) {
        0.35f
    } else {
        val transition = rememberInfiniteTransition(label = "muse_progress")
        val animated by transition.animateFloat(
            initialValue = -MuseProgressDefaults.segmentFraction,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = MuseMotion.tween(MuseAnimation.LOOP_EXTRA_SLOW_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "muse_progress_head",
        )
        animated
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = trackColor, cornerRadius = radius)
        val segment = size.width * MuseProgressDefaults.segmentFraction
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * head, 0f),
            size = Size(segment, size.height),
            cornerRadius = radius,
        )
    }
}

/** 进度组件的尺寸常量。 */
object MuseProgressDefaults {
    /** 轨道高度。 */
    val trackHeight: Dp = 4.dp

    /** 不确定进度条滑动段的宽度占比。 */
    const val segmentFraction: Float = 0.3f
}
