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
