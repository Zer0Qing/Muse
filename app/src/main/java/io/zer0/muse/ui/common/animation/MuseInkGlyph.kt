package io.zer0.muse.ui.common.animation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseAnimation

/** 整枚墨形相对画布的倾斜角，与主识别形的姿态一致。 */
private const val TILT_DEGREES = -38f

/** 沿轮廓游走的那段光的长度占比。 */
private const val TRAVEL_FRACTION = 0.28f

/** 思考指示器里墨形的边长。 */
val MuseThinkingGlyphSize = 18.dp

/**
 * 等待阶段的墨形指示器：一片叶，一枚星，一道环，三者互相变。
 *
 * 为什么用「形态」而不是「转圈」：转圈传达的是「有东西在忙」，形态变化传达的是
 * 「有东西在生长」。前者是机器的语言，后者才是我们想给人的感觉。
 *
 * 代价：纯 Canvas 描边，不起 WebView、不叠位图。几何定义在 MuseInkForm.kt。
 */

/** 墨形本体。 */
@Composable
internal fun MuseInkGlyph(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    animate: Boolean = true,
    cycleMs: Int = MuseAnimation.GLYPH_CYCLE_MS,
) {
    if (!animate) {
        Canvas(modifier) { drawGlyphFrame(progress = 0f, color = color, travel = 0f) }
        return
    }
    val transition = rememberInfiniteTransition(label = "muse_ink_glyph")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = cycleMs, easing = LinearEasing),
        ),
        label = "glyph_progress",
    )
    val travel by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = MuseAnimation.GLYPH_TRAVEL_MS, easing = LinearEasing),
        ),
        label = "glyph_travel",
    )
    Canvas(modifier) { drawGlyphFrame(progress = progress, color = color, travel = travel) }
}

private fun DrawScope.drawGlyphFrame(progress: Float, color: Color, travel: Float) {
    val radius = size.minDimension / 2f * 0.92f
    val center = Offset(size.width / 2f, size.height / 2f)
    val form = formAt(progress)
    val path = contourPath(form, center, radius)
    val hairline = Stroke(
        width = 1.6.dp.toPx(),
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
    )
    rotate(degrees = TILT_DEGREES, pivot = center) {
        // 不在这里做「一笔画出」的揭示：揭示要求进度归零时轮廓不可见，
        // 而循环末尾是 环→叶 的形变，两者在接缝处会互相打架——
        // 用户看到的是叶突然消失又重画一遍。现在轮廓始终可见，
        // 「生长感」完全由形态插值本身承担，首尾就能接上。
        drawPath(path, color, style = hairline)
        drawSparkTravel(path, color, travel, form)
        drawStem(center, radius, color, formWeight(form, FORM_LEAF))
    }
}

/**
 * 星形态下的一段游走光：取轮廓 28% 周长的一段，沿路径推进，线头追线尾。
 * 只有星形态占比足够高时才画，避免在叶/环上多出一截突兀的亮线。
 */
private fun DrawScope.drawSparkTravel(path: Path, color: Color, travel: Float, form: Float) {
    val weight = formWeight(form, FORM_SPARK)
    if (weight < 0.02f) return
    val measure = PathMeasure()
    measure.setPath(path, false)
    val length = measure.length
    if (length <= 0f) return
    val segmentLength = length * TRAVEL_FRACTION
    val start = travel * length
    val window = Path()
    val end = start + segmentLength
    if (end <= length) {
        measure.getSegment(start, end, window, true)
    } else {
        measure.getSegment(start, length, window, true)
        val head = Path()
        measure.getSegment(0f, end - length, head, true)
        window.addPath(head)
    }
    drawPath(
        path = window,
        color = color.copy(alpha = weight),
        style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round),
    )
}

/** 叶柄：只在叶形态显形，形变时淡出。 */
private fun DrawScope.drawStem(center: Offset, radius: Float, color: Color, weight: Float) {
    if (weight < 0.02f) return
    val startX = center.x + radius * 0.06f
    val startY = center.y + radius * 0.02f
    drawLine(
        color = color.copy(alpha = weight * 0.85f),
        start = Offset(startX, startY),
        end = Offset(center.x + radius * 0.30f, center.y + radius * 0.10f),
        strokeWidth = 1.4.dp.toPx(),
        cap = StrokeCap.Round,
    )
}
