package io.zer0.muse.ui.memory

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 确定性的记忆星座节点坐标，供 UI 和布局回归测试共同使用。 */
data class ConstellationPoint(val x: Dp, val y: Dp)

/** 聚簇星座：节点中心(dp 数值)与簇锚点。 */
data class ConstellationCenter(val x: Float, val y: Float)

data class ClusterAnchor(
    val category: String,
    val x: Float,
    val y: Float,
    /** 簇内节点环半径(dp),用于画柔光背景与标签锚点。 */
    val spread: Float,
    val memberCount: Int = 0,
)

data class ClusterConstellation(
    val nodeCenters: Map<Long, ConstellationCenter>,
    val clusters: List<ClusterAnchor>,
    /** 簇内最小生成树星座骨架连线，保证未建显式关系边时也具备星座形态。 */
    val skeletonEdges: List<Pair<Long, Long>> = emptyList(),
    val nodeDegrees: Map<Long, Int> = emptyMap(),
)

/** 星图或指定星群在画布坐标中的外接矩形(dp)。 */
data class ConstellationBounds(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
)

/** 屏幕空间下的标签矩形框(px)，用于碰撞避让。 */
data class ScreenLabelBox(
    val id: Long,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** 待放置的星体标签候选项(屏幕 px)。 */
data class LabelCandidate(
    val node: MemoryGraphNode,
    val starScreenX: Float,
    val starScreenY: Float,
    val starRadiusPx: Float,
    val chipWidthPx: Float,
    val chipHeightPx: Float,
    val priority: Int,
)

/** 已通过碰撞检测的标签及其摆放方向。 */
data class PlacedNodeLabel(
    val node: MemoryGraphNode,
    val box: ScreenLabelBox,
    val anchorRight: Boolean,
)

private const val GOLDEN_ANGLE = 2.399963f // 黄金角(弧度)
private const val STAR_MIN_SEPARATION_DP = 52f
private const val CLUSTER_MARGIN_DP = 96f
private const val REPULSION_ITERATIONS = 10
private const val CLUSTER_SEPARATION_ITERATIONS = 14

/** 类别展示顺序(与记忆页面分类标签一致),未知类别排最后。 */
private val CATEGORY_ORDER = listOf(
    "identity",
    "preference",
    "relationship",
    "event",
    "goal",
    "medical",
    "general",
)

/**
 * 记忆星座布局：按类别聚簇 + 簇内黄金螺旋排序 + 确定性斥力松弛 + 簇间碰撞分离 + 簇内 MST 骨架。
 *
 * 布局完全由节点与边集合决定，同一份输入永远得到同一张星图。
 */
internal fun buildClusterConstellation(
    nodes: List<MemoryGraphNode>,
    sizeDp: Float,
    edges: List<MemoryGraphEdge> = emptyList(),
): ClusterConstellation {
    if (nodes.isEmpty()) return ClusterConstellation(emptyMap(), emptyList())

    val degreeMap = computeNodeDegrees(edges)
    val groups = nodes.groupBy { it.category.lowercase() }.entries
        .sortedWith(
            compareBy(
                { entry -> CATEGORY_ORDER.indexOf(entry.key).let { if (it < 0) CATEGORY_ORDER.size else it } },
                { entry -> entry.key },
            ),
        )

    val clusterPlans = groups.map { entry ->
        val sortedMembers = entry.value.sortedWith(
            compareByDescending<MemoryGraphNode> { it.isPinned }
                .thenByDescending { it.importance }
                .thenByDescending { degreeMap[it.factId] ?: 0 }
                .thenBy { it.factId },
        )
        val localOffsets = layoutClusterMembers(sortedMembers)
        val localSpread = maxOf(
            localOffsets.maxOfOrNull { hypot(it.x, it.y) } ?: 0f,
            78f,
        )
        Triple(entry.key, sortedMembers, localOffsets to localSpread)
    }

    val canvasCenter = sizeDp / 2f
    val anchorCount = clusterPlans.size
    val baseSpread = sizeDp * 0.27f
    val mutableAnchors = clusterPlans.mapIndexed { ci, (category, _, offsetsAndSpread) ->
        val angle = ci * GOLDEN_ANGLE - 0.6f
        val ringRadius = if (anchorCount <= 1) {
            0f
        } else {
            baseSpread * (0.34f + 0.66f * ci.toFloat() / (anchorCount - 1).toFloat())
        }
        MutableCluster(
            category = category,
            cx = canvasCenter + cos(angle) * ringRadius,
            cy = canvasCenter + sin(angle) * ringRadius,
            spread = offsetsAndSpread.second,
        )
    }
    separateClusters(mutableAnchors, sizeDp)

    val nodeCenters = mutableMapOf<Long, ConstellationCenter>()
    val skeletonEdges = mutableListOf<Pair<Long, Long>>()
    val anchors = mutableListOf<ClusterAnchor>()

    clusterPlans.forEachIndexed { index, (_, members, offsetsAndSpread) ->
        val anchor = mutableAnchors[index]
        val localOffsets = offsetsAndSpread.first
        val minBound = CLUSTER_MARGIN_DP
        val maxBound = (sizeDp - CLUSTER_MARGIN_DP).coerceAtLeast(minBound)
        members.forEachIndexed { ni, node ->
            val offset = localOffsets[ni]
            val nx = (anchor.cx + offset.x).coerceIn(minBound, maxBound)
            val ny = (anchor.cy + offset.y).coerceIn(minBound, maxBound)
            nodeCenters[node.factId] = ConstellationCenter(nx, ny)
        }
        skeletonEdges += buildClusterMst(members, nodeCenters)
        anchors += ClusterAnchor(
            category = anchor.category,
            x = anchor.cx,
            y = anchor.cy,
            spread = maxOf(anchor.spread, 88f),
            memberCount = members.size,
        )
    }

    return ClusterConstellation(
        nodeCenters = nodeCenters,
        clusters = anchors,
        skeletonEdges = skeletonEdges,
        nodeDegrees = degreeMap,
    )
}

private data class MutableCluster(
    val category: String,
    var cx: Float,
    var cy: Float,
    val spread: Float,
)

private fun computeNodeDegrees(edges: List<MemoryGraphEdge>): Map<Long, Int> {
    if (edges.isEmpty()) return emptyMap()
    val degrees = mutableMapOf<Long, Int>()
    edges.forEach { edge ->
        degrees[edge.sourceFactId] = (degrees[edge.sourceFactId] ?: 0) + 1
        degrees[edge.targetFactId] = (degrees[edge.targetFactId] ?: 0) + 1
    }
    return degrees
}

private fun layoutClusterMembers(members: List<MemoryGraphNode>): List<ConstellationCenter> {
    if (members.isEmpty()) return emptyList()
    val xs = FloatArray(members.size)
    val ys = FloatArray(members.size)
    members.forEachIndexed { ni, node ->
        if (ni == 0) {
            xs[0] = 0f
            ys[0] = 0f
        } else {
            val a = ni * GOLDEN_ANGLE + (node.factId % 7) * 0.08f
            val r = 46f + sqrt(ni.toDouble()).toFloat() * 34f
            xs[ni] = cos(a) * r
            ys[ni] = sin(a) * r
        }
    }
    repeat(REPULSION_ITERATIONS) {
        for (i in members.indices) {
            for (j in i + 1 until members.size) {
                var dx = xs[j] - xs[i]
                var dy = ys[j] - ys[i]
                var dist = hypot(dx, dy)
                if (dist < 0.01f) {
                    val angle = (j - i) * GOLDEN_ANGLE
                    dx = cos(angle)
                    dy = sin(angle)
                    dist = 1f
                }
                if (dist < STAR_MIN_SEPARATION_DP) {
                    val push = (STAR_MIN_SEPARATION_DP - dist) * 0.52f / dist
                    val moveX = dx * push
                    val moveY = dy * push
                    if (i > 0) {
                        xs[i] -= moveX
                        ys[i] -= moveY
                    }
                    xs[j] += moveX
                    ys[j] += moveY
                }
            }
        }
    }
    return List(members.size) { idx -> ConstellationCenter(xs[idx], ys[idx]) }
}

private fun separateClusters(clusters: List<MutableCluster>, sizeDp: Float) {
    if (clusters.size <= 1) return
    val minCenter = CLUSTER_MARGIN_DP + 40f
    val maxCenter = (sizeDp - minCenter).coerceAtLeast(minCenter)
    repeat(CLUSTER_SEPARATION_ITERATIONS) {
        for (i in clusters.indices) {
            for (j in i + 1 until clusters.size) {
                val a = clusters[i]
                val b = clusters[j]
                var dx = b.cx - a.cx
                var dy = b.cy - a.cy
                var dist = hypot(dx, dy)
                if (dist < 0.01f) {
                    val angle = (j - i) * GOLDEN_ANGLE
                    dx = cos(angle)
                    dy = sin(angle)
                    dist = 1f
                }
                val required = a.spread + b.spread + 74f
                if (dist < required) {
                    val push = (required - dist) * 0.45f / dist
                    a.cx = (a.cx - dx * push).coerceIn(minCenter, maxCenter)
                    a.cy = (a.cy - dy * push).coerceIn(minCenter, maxCenter)
                    b.cx = (b.cx + dx * push).coerceIn(minCenter, maxCenter)
                    b.cy = (b.cy + dy * push).coerceIn(minCenter, maxCenter)
                }
            }
        }
    }
}

private fun buildClusterMst(members: List<MemoryGraphNode>, centers: Map<Long, ConstellationCenter>): List<Pair<Long, Long>> {
    if (members.size < 2) return emptyList()
    val inTree = BooleanArray(members.size)
    val minDist = FloatArray(members.size) { Float.MAX_VALUE }
    val parent = IntArray(members.size) { -1 }
    minDist[0] = 0f
    val result = ArrayList<Pair<Long, Long>>(members.size - 1)

    repeat(members.size) {
        val u = selectMstNode(inTree, minDist)
        if (u == -1) return result
        inTree[u] = true
        if (parent[u] != -1) {
            result += members[parent[u]].factId to members[u].factId
        }
        centers[members[u].factId]?.let { uPos ->
            updateMstDistances(members, centers, inTree, minDist, parent, u, uPos)
        }
    }
    return result
}

private fun selectMstNode(inTree: BooleanArray, minDist: FloatArray): Int {
    var selected = -1
    for (i in inTree.indices) {
        if (!inTree[i] && (selected == -1 || minDist[i] < minDist[selected])) selected = i
    }
    return selected
}

private fun updateMstDistances(
    members: List<MemoryGraphNode>,
    centers: Map<Long, ConstellationCenter>,
    inTree: BooleanArray,
    minDist: FloatArray,
    parent: IntArray,
    source: Int,
    sourcePosition: ConstellationCenter,
) {
    for (target in members.indices) {
        if (inTree[target]) continue
        centers[members[target].factId]?.let { targetPosition ->
            val distance = hypot(targetPosition.x - sourcePosition.x, targetPosition.y - sourcePosition.y)
            if (distance < minDist[target]) {
                minDist[target] = distance
                parent[target] = source
            }
        }
    }
}

/** 计算给定点集的包围盒(dp)，用于全图与分类星群平滑聚焦取景。 */
internal fun computeConstellationBounds(points: Collection<ConstellationCenter>, paddingDp: Float = 110f): ConstellationBounds {
    if (points.isEmpty()) return ConstellationBounds(0f, 0f, 100f, 100f)
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    points.forEach { p ->
        minX = min(minX, p.x)
        minY = min(minY, p.y)
        maxX = max(maxX, p.x)
        maxY = max(maxY, p.y)
    }
    return ConstellationBounds(
        minX = minX - paddingDp,
        minY = minY - paddingDp,
        maxX = maxX + paddingDp,
        maxY = maxY + paddingDp,
    )
}

/** 返回指定分类所有星体的包围盒(dp)，供点击顶部图例时飞镜聚焦。 */
internal fun focusBoundsForCategory(
    constellation: ClusterConstellation,
    nodes: List<MemoryGraphNode>,
    category: String,
): ConstellationBounds? {
    val target = category.lowercase()
    val pts = nodes
        .filter { it.category.lowercase() == target }
        .mapNotNull { constellation.nodeCenters[it.factId] }
    if (pts.isEmpty()) return null
    return computeConstellationBounds(pts, paddingDp = 120f)
}

/**
 * 屏幕空间标签碰撞避让：按优先级逐一尝试星体右侧与左侧候选框，
 * 跳过与任何已有障碍物(簇标签或已放标签)相交的项，彻底消除文字重叠。
 */
internal fun placeConstellationLabels(
    candidates: List<LabelCandidate>,
    existingObstacles: List<ScreenLabelBox>,
    maxCount: Int,
): List<PlacedNodeLabel> {
    if (candidates.isEmpty() || maxCount <= 0) return emptyList()
    val occupied = existingObstacles.toMutableList()
    val placed = mutableListOf<PlacedNodeLabel>()
    val sorted = candidates.sortedWith(
        compareByDescending<LabelCandidate> { it.priority }
            .thenBy { it.node.factId },
    )
    for (cand in sorted) {
        if (placed.size >= maxCount) break
        val gap = cand.starRadiusPx + 8f
        val rightBox = ScreenLabelBox(
            id = cand.node.factId,
            left = cand.starScreenX + gap,
            top = cand.starScreenY - cand.chipHeightPx / 2f,
            right = cand.starScreenX + gap + cand.chipWidthPx,
            bottom = cand.starScreenY + cand.chipHeightPx / 2f,
        )
        val leftBox = ScreenLabelBox(
            id = cand.node.factId,
            left = cand.starScreenX - gap - cand.chipWidthPx,
            top = cand.starScreenY - cand.chipHeightPx / 2f,
            right = cand.starScreenX - gap,
            bottom = cand.starScreenY + cand.chipHeightPx / 2f,
        )
        val chosen = when {
            occupied.none { boxesOverlap(rightBox, it) } -> rightBox to false
            occupied.none { boxesOverlap(leftBox, it) } -> leftBox to true
            else -> null
        }
        if (chosen != null) {
            occupied += chosen.first
            placed += PlacedNodeLabel(
                node = cand.node,
                box = chosen.first,
                anchorRight = chosen.second,
            )
        }
    }
    return placed
}

private fun boxesOverlap(a: ScreenLabelBox, b: ScreenLabelBox, gapPx: Float = 6f): Boolean = a.left < b.right + gapPx &&
    b.left < a.right + gapPx &&
    a.top < b.bottom + gapPx &&
    b.top < a.bottom + gapPx

/** 簇内节点环半径(dp),供画布尺寸估算与绘制共用。 */
internal fun clusterNodeRing(count: Int): Float = if (count <= 1) 0f else 36f + sqrt(count.toDouble()).toFloat() * 34f

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
    fun intersects(a: Rect, b: Rect, gap: Float = 8f): Boolean = a.left < b.right + gap && b.left < a.right + gap &&
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
