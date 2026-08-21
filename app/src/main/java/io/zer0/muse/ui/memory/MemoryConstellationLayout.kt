package io.zer0.muse.ui.memory

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.sin

/** 确定性的记忆星座节点坐标，供 UI 和布局回归测试共同使用。 */
data class ConstellationPoint(val x: Dp, val y: Dp)

internal fun buildConstellationCoordinates(
    nodes: List<MemoryGraphNode>,
    contentWidth: Dp,
    contentHeight: Dp,
): Map<Long, ConstellationPoint> {
    val sorted = nodes.sortedWith(
        compareByDescending<MemoryGraphNode> { it.isPinned }
            .thenByDescending { it.importance }
            .thenByDescending { it.confidence }
            .thenBy { it.factId },
    )
    val centerX = contentWidth.value / 2f
    val centerY = contentHeight.value / 2f
    val goldenAngle = Math.PI * (3.0 - sqrt(5.0))
    return sorted.mapIndexed { index, node ->
        val radius = 170.0 + sqrt(index.toDouble() + 1.0) * 175.0
        val angle = index * goldenAngle - Math.PI / 2.0
        val x = (centerX + cos(angle) * radius - 85.0).coerceAtLeast(16.0)
        val y = (centerY + sin(angle) * radius - 34.0).coerceAtLeast(16.0)
        node.factId to ConstellationPoint(x.dp, y.dp)
    }.toMap()
}

/**
 * 分类标签独立于节点布局，并对节点/其他标签做碰撞检测。
 * 旧实现直接取分类节点平均点，节点一多时标签会压在卡片上，看起来像重叠节点。
 */
internal fun buildCategoryCoordinates(
    groups: Map<String, List<MemoryGraphNode>>,
    nodeCoordinates: Map<Long, ConstellationPoint>,
    labelWidth: Float = 120f,
    labelHeight: Float = 28f,
): Map<String, ConstellationPoint> {
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float)
    fun intersects(a: Rect, b: Rect, gap: Float = 8f): Boolean =
        a.left < b.right + gap && b.left < a.right + gap &&
            a.top < b.bottom + gap && b.top < a.bottom + gap

    val nodeRects = nodeCoordinates.values.map { point ->
        Rect(point.x.value, point.y.value, point.x.value + 170f, point.y.value + 68f)
    }
    val placed = mutableListOf<Rect>()
    return groups.keys.sorted().associateWith { category ->
        val points = groups[category].orEmpty().mapNotNull { nodeCoordinates[it.factId] }
        val centerX = points.map { it.x.value }.average().toFloat()
        val centerY = points.map { it.y.value }.average().toFloat()
        val goldenAngle = Math.PI * (3.0 - kotlin.math.sqrt(5.0))
        var chosen = Rect(centerX - labelWidth / 2f, centerY - labelHeight / 2f, centerX + labelWidth / 2f, centerY + labelHeight / 2f)
        for (attempt in 0..80) {
            val radius = if (attempt == 0) 0f else 46f + kotlin.math.sqrt(attempt.toDouble()).toFloat() * 34f
            val angle = attempt * goldenAngle - Math.PI / 2.0
            val candidate = Rect(
                centerX + cos(angle).toFloat() * radius - labelWidth / 2f,
                centerY + sin(angle).toFloat() * radius - labelHeight / 2f,
                centerX + cos(angle).toFloat() * radius + labelWidth / 2f,
                centerY + sin(angle).toFloat() * radius + labelHeight / 2f,
            )
            if (nodeRects.none { intersects(candidate, it) } && placed.none { intersects(candidate, it) }) {
                chosen = candidate
                break
            }
            chosen = candidate
        }
        placed += chosen
        ConstellationPoint(chosen.left.dp, chosen.top.dp)
    }
}
