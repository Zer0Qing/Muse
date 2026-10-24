package io.zer0.muse.ui.common.animation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 墨形的几何：三个形态共用一条极坐标半径函数族。
 *
 * 这是整套变形的关键约束。三个形态（叶 / 星 / 环）都在同一个角度上各自给出半径，
 * 变形就是插值半径。因为拓扑永远一致，中间不会出现撕裂、错位、控制点反接这类问题，
 * 也不需要在形态之间做交叉淡变。
 *
 * 换成描边路径后，同一个函数还被复用来做「一笔画出」的起笔与沿轮廓游走的光。
 */

/** 叶形态：尖端在 t=0，圆端在 t=π。 */
internal const val FORM_LEAF = 0f

/** 星形态：四个臂。 */
internal const val FORM_SPARK = 1f

/** 环形态：落定。 */
internal const val FORM_RING = 2f

/** 形态总数，用于 环→叶 回绕时判定相邻。 */
internal const val FORM_COUNT = 3

/** 轮廓采样段数。18dp 尺寸下 96 段肉眼已看不出折线。 */
private const val CONTOUR_SEGMENTS = 96

private const val TWO_PI = (Math.PI * 2).toFloat()

/** 极坐标半径：三种形态在同一个角度上各自给出半径。 */
internal fun formRadius(form: Int, angle: Float): Float = when (form) {
    // 叶：sin(angle/2) 的半个叶形，尖在 angle=0，圆在 angle=π。指数小于 1 让叶身饱满。
    0 -> 0.02f + 0.98f * (abs(sin(angle / 2f))).pow(0.8f)
    // 星：四臂。|cos 2θ| 在 [0,1] 有四个峰，压底不让腰部塌到中心。
    1 -> 0.34f + 0.66f * (abs(cos(2f * angle))).pow(0.7f)
    // 环：闭合且带轻微起伏，避免变成几何圆那样死板。
    else -> 0.66f + 0.09f * cos(3f * angle)
}

/** 两形态之间的平滑过渡权重（走 smoothstep，避免起止处速度突变）。 */
internal fun smooth01(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

/**
 * 周期内某一时刻的形态值：0 叶 → 1 星 → 2 环 → 回到 0。
 * 每段之间留出停顿，让每个形态都被看见，而不是一路滑过去。
 */
internal fun formAt(progress: Float): Float = when {
    progress < 0.34f -> FORM_LEAF
    progress < 0.46f -> FORM_LEAF + smooth01((progress - 0.34f) / 0.12f) * (FORM_SPARK - FORM_LEAF)
    progress < 0.60f -> FORM_SPARK
    progress < 0.72f -> FORM_SPARK + smooth01((progress - 0.60f) / 0.12f) * (FORM_RING - FORM_SPARK)
    progress < 0.86f -> FORM_RING
    else -> FORM_RING + smooth01((progress - 0.86f) / 0.14f) * (FORM_COUNT - FORM_RING)
}

/** 某形态在当前形态值下的占比，用于淡入淡出附属元素（叶柄、游走光）。 */
internal fun formWeight(form: Float, target: Float): Float {
    val direct = abs(form - target)
    // 环(2) 与叶(0) 在回绕时相邻，距离按 3-|差| 计。
    val wrapped = FORM_COUNT - direct
    return (1f - minOf(direct, wrapped)).coerceIn(0f, 1f)
}

/** 前 16% 把轮廓本身画出来，之后维持完整。 */
internal fun revealAt(progress: Float): Float = (progress / 0.16f).coerceIn(0f, 1f)

/**
 * 按形态值生成轮廓路径。
 *
 * @param form 形态值，取整段之间的小数表示两形态之间的插值进度
 * @param center 画布中心
 * @param radius 画布内可用半径
 */
internal fun contourPath(form: Float, center: Offset, radius: Float): Path {
    val base = form.toInt().coerceIn(0, FORM_COUNT - 1)
    val next = (base + 1) % FORM_COUNT
    val mix = smooth01(form - base)
    val path = Path()
    for (i in 0..CONTOUR_SEGMENTS) {
        val angle = i.toFloat() / CONTOUR_SEGMENTS * TWO_PI
        val r = formRadius(base, angle) * (1f - mix) + formRadius(next, angle) * mix
        val x = center.x + r * radius * cos(angle)
        val y = center.y + r * radius * sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}
