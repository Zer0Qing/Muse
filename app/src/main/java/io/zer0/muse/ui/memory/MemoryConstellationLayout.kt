package io.zer0.muse.ui.memory

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.sin

/** 确定性的记忆星座节点坐标，供 UI 和布局回归测试共同使用。 */
data class ConstellationPoint(val x: Dp, val y: Dp)

/** v2.0 聚簇星座:节点中心(dp 数值)与簇锚点。 */
data class ConstellationCenter(val x: Float, val y: Float)

data class ClusterAnchor(
    val category: String,
    val x: Float,
    val y: Float,
    /** 簇内节点环半径(dp),用于画柔光背景。 */
    val spread: Float,
)

data class ClusterConstellation(
    val nodeCenters: Map<Long, ConstellationCenter>,
    val clusters: List<ClusterAnchor>,
)

private const val GOLDEN_ANGLE = 2.399963f // 黄金角(弧度)

/** 类别展示顺序(与记忆页面分类标签一致),未知类别排最后。 */
private val CATEGORY_ORDER = listOf(
    "identity", "preference", "relationship", "event", "goal", "medical",
)

/**
 * v2.0 记忆星座布局:先按类别聚簇,再在簇内环形分布。
 *
 * 相比旧的单螺旋铺开,同主题记忆聚在一起形成"星座群",簇间用黄金角散布,
 * 整体更可读;布局完全由节点集合决定,同样输入永远得到同一张图。
 * 返回坐标为节点中心(dp 数值),直接供 Canvas 绘制使用。
 */
internal fun buildClusterConstellation(
    nodes: List<MemoryGraphNode>,
    sizeDp: Float,
): ClusterConstellation {
    if (nodes.isEmpty()) return ClusterConstellation(emptyMap(), emptyList())
    val groups = nodes.groupBy { it.category.lowercase() }.entries
        .sortedWith(
            compareBy(
                { entry -> CATEGORY_ORDER.indexOf(entry.key).let { if (it < 0) CATEGORY_ORDER.size else it } },
                { entry -> entry.key },
            ),
        )
    val center = sizeDp / 2f
    val clusterCount = groups.size
    val spread = sizeDp * 0.26f
    val anchors = mutableListOf<ClusterAnchor>()
    val nodeCenters = mutableMapOf<Long, ConstellationCenter>()

    groups.forEachIndexed { ci, entry ->
        val angle = ci * GOLDEN_ANGLE
        val ringRadius = if (clusterCount <= 1) {
            0f
        } else {
            spread * (0.32f + 0.68f * ci.toFloat() / (clusterCount - 1).toFloat())
        }
        val cx = center + cos(angle) * ringRadius
        val cy = center + sin(angle) * ringRadius
        val members = entry.value.sortedBy { it.factId }
        val count = members.size
        val nodeRing = clusterNodeRing(count)
        members.forEachIndexed { ni, node ->
            val a = ni * GOLDEN_ANGLE
            // 固定抖动:让簇内节点不完全落在几何圆上,又保持布局确定
            val jitter = 1f - (node.factId % 5) * 0.05f
            val nx = cx + cos(a) * nodeRing * jitter
            val ny = cy + sin(a) * nodeRing * jitter
            nodeCenters[node.factId] = ConstellationCenter(nx, ny)
        }
        anchors += ClusterAnchor(entry.key, cx, cy, maxOf(nodeRing, 90f))
    }
    return ClusterConstellation(nodeCenters, anchors)
}

/** 簇内节点环半径(dp),供画布尺寸估算与绘制共用。 */
internal fun clusterNodeRing(count: Int): Float =
    if (count <= 1) 0f else 26f + sqrt(count.toDouble()).toFloat() * 22f

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
