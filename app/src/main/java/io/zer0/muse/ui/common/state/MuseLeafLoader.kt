package io.zer0.muse.ui.common.state

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseMotion

/**
 * 叶脉光流加载动画。
 *
 * 图标是一片叶子加叶尖一颗星。这里不另找一个通用转圈，而是把图标本身拆成动作：
 * 光顺着叶脉从叶柄流向叶尖，汇成那颗星，停一会儿，再沿原路退回。
 * 一轮结束和下一轮开始是同一帧，所以看起来是连续的呼吸，而不是重播。
 *
 * 只用在「用户确实在等」的场合（整页加载、正文生成）。
 * 按钮里那些十几 dp 的小转圈继续用 [MuseSpinner] —— 叶子在这个尺寸下只剩一团绿点，
 * 不如一个干净圆弧。
 *
 * @param size 边长。低于 24dp 时叶脉细节会糊，建议使用方保留更大尺寸。
 * @param color 主色。叶身用它的低透明度版本，光流与星用它本身。
 */
@Composable
fun MuseLeafLoader(modifier: Modifier = Modifier, size: Dp = 40.dp, color: Color = LocalContentColor.current) {
    val reducedMotion = MuseMotion.isReducedMotion()
    val progress = if (reducedMotion) {
        0.55f
    } else {
        val transition = rememberInfiniteTransition(label = "muse_leaf")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = CYCLE_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "muse_leaf_progress",
        )
        animated
    }
    val shape = remember { LeafShape() }

    Canvas(modifier = modifier.size(size)) {
        // 路径坐标系是固定的 100×100，画布却是 size 对应的像素。
        // 不换算就等于「拿 100 宽的图嵌进 39 像素的框」—— 只有左上角那一小块露出来，
        // 而那一小块在画布里正好落在右下区域，看起来就像叶子歪到了右下角。
        // 注意：外层参数也叫 size（Dp），进到 DrawScope 里要显式取 this.size 才是画布尺寸。
        val canvas = this.size
        val unit = canvas.minDimension / LEAF_ARTBOARD
        // 把内容包围盒的中心挪到画布中心：缩放与旋转都绕同一个点转，
        // 这样角度怎么调都不会把图形推出框外。
        // 这里的平移发生在缩放之后（外层 translate），所以位移量是画布坐标系里的
        // 绝对差值，不能再乘 unit——乘了就是把「缩放过一次的偏移」又缩一次，
        // 结果只在 unit 恰好等于 1 时居中，其余尺寸都会被推向一侧并溢出裁掉。
        val center = Offset(CONTENT_CENTER_X, CONTENT_CENTER_Y)
        translate(
            left = canvas.width / 2f - CONTENT_CENTER_X,
            top = canvas.height / 2f - CONTENT_CENTER_Y,
        ) {
            scale(unit, unit, pivot = center) {
                rotate(degrees = LEAF_TILT_DEGREES, pivot = center) {
                    drawLeafBody(shape, color, progress)
                    drawVeinFlow(shape, color, progress)
                    drawStar(shape, color, progress)
                }
            }
        }
    }
}

/** 一轮循环时长。留够「呼吸」那段，短于这个数等待时会显得急。 */
private const val CYCLE_MS = 6_000

/** 路径使用的虚拟画布边长。所有控制点都基于这个坐标系。 */
private const val LEAF_ARTBOARD = 100f

/**
 * 对齐基准：叶身自身的包围盒中心。
 *
 * 不取「叶身 + 叶尖星」的合并包围盒：那颗星只在叶脉汇聚的一小段进度里出现，
 * 其余时间不在，而画布的位置是恒定的——把星算进去，星一缺席整片叶就显低。
 * 叶身中心 y=48.8，含星中心 y=44.3，相差 4.5，32dp 下就是 1.4dp 的下坠。
 */
private const val CONTENT_CENTER_X = 50.06f
private const val CONTENT_CENTER_Y = 48.8f

/**
 * 叶子相对竖直方向的倾斜。
 *
 * 图标本体约 44 度（叶柄到叶尖相对水平线）。下面的路径是按 56 度画的，
 * 所以在这里回转 12 度对齐，改角度只需动这个数，路径本身不用碰。
 */
private const val LEAF_TILT_DEGREES = -12f

/** 叶身显隐：整轮的大部分时间都在，头尾各留一段淡入淡出。 */
private const val BODY_FADE_IN_END = 0.14f
private const val BODY_FADE_OUT_START = 0.90f

/** 叶脉光流：从叶柄流到叶尖，再回到叶柄。 */
private const val FLOW_START = 0.06f
private const val FLOW_REACH_TIP = 0.30f
private const val FLOW_RETURN_START = 0.66f
private const val FLOW_RETURN_END = 0.84f

/** 星：叶尖汇光时长出来，退场时缩回去。 */
private const val STAR_GROW_START = 0.32f
private const val STAR_GROW_END = 0.46f
private const val STAR_FADE_START = 0.86f

/**
 * 叶子的几何。
 *
 * 坐标基于 100×100 的画布，与图标同构：叶柄在左下、叶尖在右上，
 * 三条叶脉从叶柄散开、往叶尖收拢，星落在叶尖外上方。
 */
private class LeafShape {
    /** 叶身轮廓。 */
    val body: Path = Path().apply {
        moveTo(33.2f, 73.4f)
        cubicTo(30.6f, 55.4f, 38.4f, 33.6f, 66.6f, 24.2f)
        cubicTo(71.2f, 41.6f, 55.4f, 66.2f, 33.2f, 73.4f)
        close()
    }

    /** 三条叶脉，顺序是从最长（贴外缘）到最短（贴叶柄侧）。 */
    val veins: List<Path> = listOf(
        Path().apply {
            moveTo(34.4f, 70.6f)
            cubicTo(41.2f, 57.4f, 50.2f, 42.6f, 65.4f, 25.8f)
        },
        Path().apply {
            moveTo(33.6f, 71.6f)
            cubicTo(41.6f, 62.2f, 50.6f, 50.4f, 61.2f, 36.4f)
        },
        Path().apply {
            moveTo(33.4f, 72.2f)
            cubicTo(41.8f, 66.4f, 49.8f, 58.2f, 55.4f, 48.6f)
        },
    )

    /** 叶尖那颗四角星。 */
    val star: Path = Path().apply {
        moveTo(66.6f, 15.2f)
        cubicTo(67.5f, 19.1f, 68.4f, 20f, 72.3f, 20.9f)
        cubicTo(68.4f, 21.8f, 67.5f, 22.7f, 66.6f, 26.6f)
        cubicTo(65.7f, 22.7f, 64.8f, 21.8f, 60.9f, 20.9f)
        cubicTo(64.8f, 20f, 65.7f, 19.1f, 66.6f, 15.2f)
        close()
    }

    /** 三条叶脉各自的长度，用于把「流光」按比例切开。 */
    val veinLengths: List<Float> by lazy {
        veins.map { path ->
            PathMeasure().apply { setPath(path, false) }.length
        }
    }
}

/** 叶身：整轮在，头尾淡。 */
private fun DrawScope.drawLeafBody(shape: LeafShape, color: Color, progress: Float) {
    val alpha = when {
        progress < BODY_FADE_IN_END -> progress / BODY_FADE_IN_END
        progress < BODY_FADE_OUT_START -> 1f
        else -> ((1f - progress) / (1f - BODY_FADE_OUT_START)).coerceIn(0f, 1f)
    } * BODY_BODY_MAX_ALPHA
    if (alpha <= 0.01f) return
    drawPath(path = shape.body, color = color.copy(alpha = alpha))
}

/** 光沿叶脉流动：去程由叶柄向叶尖，回程原路退回。 */
private fun DrawScope.drawVeinFlow(shape: LeafShape, color: Color, progress: Float) {
    val travel = veinTravel(progress) ?: return
    val color = color.copy(alpha = travel.alpha * VEIN_MAX_ALPHA)
    shape.veins.forEachIndexed { index, path ->
        // 三条脉错开一点起步，看起来是依次亮起而不是同时闪。
        val local = (travel.fraction - index * VEIN_STAGGER).coerceIn(0f, 1f)
        if (local <= 0f) return@forEachIndexed
        val segment = subPath(path, local)
        drawPath(
            path = segment,
            color = color,
            style = Stroke(width = veinWidth, cap = StrokeCap.Round),
        )
    }
}

/** 叶尖那颗星：叶脉汇聚时描出来，退场时缩回。 */
private fun DrawScope.drawStar(shape: LeafShape, color: Color, progress: Float) {
    val grow = starGrow(progress)
    if (grow <= 0f) return
    val alpha = starAlpha(progress)
    if (alpha <= 0.01f) return
    val radius = LEAF_ARTBOARD * STAR_PIVOT_SCALE
    val pivot = Offset(shape.star.getBounds().center.x, shape.star.getBounds().center.y)
    val scale = 0.55f + 0.45f * grow
    rotate(degrees = 0f, pivot = pivot) {
        scale(scale, scale, pivot) {
            drawPath(
                path = shape.star,
                color = color.copy(alpha = alpha * STAR_MAX_ALPHA),
                style = Stroke(width = starWidth, cap = StrokeCap.Round),
            )
        }
    }
    // 星心的一点光晕，避免整颗星只有细线、在深色背景上发虚。
    if (grow > 0.6f) {
        drawCircle(
            color = color.copy(alpha = alpha * STAR_HALO_ALPHA * (grow - 0.6f) / 0.4f),
            radius = radius,
            center = pivot,
        )
    }
}

/** 叶脉「走到哪儿了」。返回 null 表示这一帧不该有光。 */
private data class VeinTravel(val fraction: Float, val alpha: Float)

private fun veinTravel(progress: Float): VeinTravel? = when {
    progress < FLOW_START -> null
    progress < FLOW_REACH_TIP ->
        VeinTravel(
            fraction = ((progress - FLOW_START) / (FLOW_REACH_TIP - FLOW_START)).coerceIn(0f, 1f),
            alpha = 1f,
        )
    progress < FLOW_RETURN_START -> VeinTravel(fraction = 1f, alpha = 1f)
    progress < FLOW_RETURN_END ->
        VeinTravel(
            fraction = 1f - ((progress - FLOW_RETURN_START) / (FLOW_RETURN_END - FLOW_RETURN_START))
                .coerceIn(0f, 1f),
            alpha = 1f,
        )
    else -> null
}

/** 星的描出进度 0..1。 */
private fun starGrow(progress: Float): Float = when {
    progress < STAR_GROW_START -> 0f
    progress < STAR_GROW_END ->
        ((progress - STAR_GROW_START) / (STAR_GROW_END - STAR_GROW_START)).coerceIn(0f, 1f)
    else -> 1f
}

/** 星的透明度：长出后有一次轻闪，退场时淡出。 */
private fun starAlpha(progress: Float): Float = when {
    progress < STAR_GROW_START -> 0f
    progress < STAR_GROW_END -> (progress - STAR_GROW_START) / (STAR_GROW_END - STAR_GROW_START)
    progress < STAR_FADE_START -> {
        // 中段轻闪两下，幅度小，不抢注意力。
        val breathe = 0.78f + 0.22f * kotlin.math.abs(
            kotlin.math.cos((progress - STAR_GROW_END) * STAR_FLICKER_TURNS * Math.PI.toFloat()),
        )
        breathe
    }
    else -> ((1f - progress) / (1f - STAR_FADE_START)).coerceIn(0f, 1f)
}

/** 取路径前 [fraction] 长度的一段，用于画「正在流过的光」。 */
private fun subPath(path: Path, fraction: Float): Path {
    val measure = PathMeasure().apply { setPath(path, false) }
    val destination = Path()
    measure.getSegment(0f, measure.length * fraction.coerceIn(0f, 1f), destination, true)
    return destination
}

// ── 视觉参数 ────────────────────────────────────────────
private const val BODY_BODY_MAX_ALPHA = 0.30f
private const val VEIN_MAX_ALPHA = 0.95f
private const val VEIN_STAGGER = 0.10f
private const val STAR_MAX_ALPHA = 0.95f
private const val STAR_HALO_ALPHA = 0.22f
private const val STAR_PIVOT_SCALE = 0.045f
private const val STAR_FLICKER_TURNS = 5f

/** 线宽按虚拟画布计算，不跟实际像素走 —— 否则同一份代码在不同尺寸下线粗细差一倍。 */
private val DrawScope.veinWidth: Float get() = LEAF_ARTBOARD * 0.028f
private val DrawScope.starWidth: Float get() = LEAF_ARTBOARD * 0.028f
