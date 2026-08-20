package io.zer0.muse.ui.memory

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.statusColors
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 记忆星座视图。
 *
 * 阶段 2: 接入真实 memory_links 和 facts 数据。
 * 阶段 3: 节点详情、关系详情、来源查看、编辑/删除/确认。
 * 阶段 4: 星座聚类与布局保存。
 */
@Composable
fun MemoryGraphView(
    state: MemoryGraphViewModel.GraphState,
    modifier: Modifier = Modifier,
    onNodeAction: ((MemoryGraphNode, NodeAction) -> Unit)? = null,
    onEdgeAction: ((MemoryGraphEdge, EdgeAction) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val statusColors = MaterialTheme.statusColors

    if (state.isLoading) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) { MuseLoadingState() }
        return
    }
    if (state.error != null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(text = state.error, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        return
    }
    if (state.nodes.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            MuseEmptyState(
                title = stringResource(R.string.memory_graph_empty_title),
                subtitle = stringResource(R.string.memory_graph_empty_subtitle),
            )
        }
        return
    }

    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var selectedEdgeIndex by remember { mutableStateOf<Int?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    // 阶段 4: 如果有聚类数据,按 cluster 分组布局;否则环形布局
    val nodePositions = remember(state.nodes, state.clusters) {
        val n = state.nodes.size
        if (state.clusters.isNotEmpty()) {
            // 按聚类分组:同一 cluster 内环形排列,cluster 中心按大环分布
            val byCluster = state.nodes.withIndex().groupBy { (_, node) ->
                state.clusters.firstOrNull { it.nodeIds.contains(node.factId) }?.id
            }
            val clusterCount = byCluster.keys.filterNotNull().distinct().size.coerceAtLeast(1)
            var clusterIdx = 0
            val positions = mutableListOf<GraphPosition>()
            // 无 cluster 的节点(孤立节点)单独放在外圈
            val orphans = byCluster[null] ?: emptyList()
            val clustered = byCluster.filterKeys { it != null }
            clustered.forEach { (clusterId, nodesInCluster) ->
                val clusterAngle = (clusterIdx * 2.0 * Math.PI / clusterCount)
                val clusterR = 0.28f
                val cx = (0.5f + clusterR * cos(clusterAngle)).toFloat()
                val cy = (0.5f + clusterR * sin(clusterAngle)).toFloat()
                nodesInCluster.forEachIndexed { i, (origIdx, _) ->
                    val innerAngle = (i * 2.0 * Math.PI / nodesInCluster.size.coerceAtLeast(1))
                    val innerR = 0.12f
                    positions.add(GraphPosition(
                        (cx + innerR * cos(innerAngle)).toFloat(),
                        (cy + innerR * sin(innerAngle)).toFloat(),
                    ))
                }
                clusterIdx++
            }
            // 孤立节点放底部一排
            orphans.forEachIndexed { i, (origIdx, _) ->
                val x = 0.2f + 0.6f * (i.toFloat() / orphans.size.coerceAtLeast(1))
                positions.add(GraphPosition(x, 0.92f))
            }
            // 按 state.nodes 顺序对齐
            val result = arrayOfNulls<GraphPosition>(n)
            var posIdx = 0
            clustered.forEach { (_, nodesInCluster) ->
                nodesInCluster.forEach { (origIdx, _) ->
                    if (origIdx < n && posIdx < positions.size) result[origIdx] = positions[posIdx++]
                }
            }
            orphans.forEach { (origIdx, _) ->
                if (origIdx < n && posIdx < positions.size) result[origIdx] = positions[posIdx++]
            }
            result.mapIndexed { idx, pos -> pos ?: GraphPosition(0.5f, 0.5f) }
        } else {
            // 无聚类:简单环形
            state.nodes.mapIndexed { index, _ ->
                val angle = (index * 2.0 * Math.PI / n)
                val radius = if (n <= 5) 0.25f else 0.35f
                GraphPosition(
                    (0.5f + radius * cos(angle)).toFloat(),
                    (0.5f + radius * sin(angle)).toFloat(),
                )
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surfaceVariant.copy(alpha = 0.34f))
            .pointerInput(Unit) {
                detectTransformGestures { _, panChange, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.82f, 2.2f)
                    pan += panChange
                }
            },
    ) {
        val canvasW = maxWidth
        val canvasH = maxHeight

        // 关系线
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            state.edges.forEachIndexed { edgeIdx, edge ->
                val sourceIdx = state.nodes.indexOfFirst { it.factId == edge.sourceFactId }
                val targetIdx = state.nodes.indexOfFirst { it.factId == edge.targetFactId }
                if (sourceIdx < 0 || targetIdx < 0) return@forEachIndexed
                val srcPos = nodePositions[sourceIdx]
                val tgtPos = nodePositions[targetIdx]
                val start = Offset(
                    srcPos.x * w * scale + pan.x + w * (1f - scale) / 2f,
                    srcPos.y * h * scale + pan.y + h * (1f - scale) / 2f,
                )
                val end = Offset(
                    tgtPos.x * w * scale + pan.x + w * (1f - scale) / 2f,
                    tgtPos.y * h * scale + pan.y + h * (1f - scale) / 2f,
                )
                val focused = selectedIndex == null || selectedIndex == sourceIdx || selectedIndex == targetIdx
                val edgeSelected = selectedEdgeIndex == edgeIdx
                drawLine(
                    color = colors.outline.copy(alpha = if (edgeSelected) 0.6f else if (focused) 0.34f else 0.08f),
                    start = start,
                    end = end,
                    strokeWidth = if (edgeSelected) 3.dp.toPx() else if (selectedIndex == sourceIdx || selectedIndex == targetIdx) 2.2.dp.toPx() else 1.dp.toPx(),
                )
            }
        }

        // 节点
        state.nodes.forEachIndexed { index, node ->
            val pos = nodePositions[index]
            val isSelected = selectedIndex == index
            val tone = nodeToneColor(node.category, colors, statusColors)
            Surface(
                modifier = Modifier
                    .offset {
                        val x = pos.x * canvasW.toPx() * scale + pan.x + canvasW.toPx() * (1f - scale) / 2f
                        val y = pos.y * canvasH.toPx() * scale + pan.y + canvasH.toPx() * (1f - scale) / 2f
                        IntOffset((x - 55.dp.toPx()).roundToInt(), (y - 20.dp.toPx()).roundToInt())
                    }
                    .widthIn(min = 72.dp, max = 132.dp)
                    .clip(RoundedCornerShape(if (isSelected) 18.dp else 14.dp))
                    .clickable {
                        selectedIndex = if (selectedIndex == index) null else index
                        selectedEdgeIndex = null
                    },
                color = if (isSelected) tone.copy(alpha = 0.24f) else colors.surface.copy(alpha = 0.92f),
                shadowElevation = if (isSelected) 4.dp else 1.dp,
                shape = RoundedCornerShape(if (isSelected) 18.dp else 14.dp),
            ) {
                Text(
                    text = node.title,
                    style = if (isSelected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                    color = if (node.isExpired) colors.onSurfaceVariant.copy(alpha = 0.4f) else colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        // 阶段 3: 节点详情面板
        if (selectedIndex != null && selectedIndex!! < state.nodes.size) {
            val node = state.nodes[selectedIndex!!]
            val relatedEdges = state.edges.filter { it.sourceFactId == node.factId || it.targetFactId == node.factId }
            val categoryLabel = stringResource(categoryStringRes(node.category))
            val pinnedLabel = stringResource(R.string.memory_graph_node_pinned)
            val expiredLabel = stringResource(R.string.memory_graph_node_expired)
            val relatedLabel = stringResource(R.string.memory_graph_node_related, relatedEdges.size)
            val typeLabel = stringResource(R.string.memory_graph_node_type_label, categoryLabel)
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(MusePaddings.cardInner),
                color = colors.surface.copy(alpha = 0.94f),
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 3.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = node.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(typeLabel)
                            if (node.isPinned) append(" · ").append(pinnedLabel)
                            if (node.isExpired) append(" · ").append(expiredLabel)
                            append(" · ").append(relatedLabel)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                    // 阶段 3: 节点操作按钮
                    if (onNodeAction != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onNodeAction(node, NodeAction.EDIT) }) {
                                Text(stringResource(R.string.memory_menu_edit), style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { onNodeAction(node, NodeAction.DELETE) }) {
                                Text(stringResource(R.string.memory_menu_delete), style = MaterialTheme.typography.labelSmall, color = colors.error)
                            }
                            TextButton(onClick = { onNodeAction(node, NodeAction.PIN) }) {
                                Text(stringResource(R.string.memory_menu_pin), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    // 关联关系列表
                    relatedEdges.take(5).forEachIndexed { idx, edge ->
                        Text(
                            text = "${edge.sourceTitle} -- ${relationTypeLabel(edge.relationType)} -- ${edge.targetTitle}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable {
                                selectedEdgeIndex = state.edges.indexOf(edge)
                            },
                        )
                    }
                }
            }
        }

        // 阶段 3: 关系详情面板
        if (selectedEdgeIndex != null && selectedEdgeIndex!! < state.edges.size) {
            val edge = state.edges[selectedEdgeIndex!!]
            val relLabel = stringResource(relationTypeStringRes(edge.relationType))
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(MusePaddings.cardInner),
                color = colors.surface.copy(alpha = 0.94f),
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 3.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "${edge.sourceTitle} -- $relLabel -- ${edge.targetTitle}",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.memory_graph_edge_weight, (edge.weight * 100).toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                    if (onEdgeAction != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onEdgeAction(edge, EdgeAction.CONFIRM) }) {
                                Text(stringResource(R.string.memory_graph_edge_confirm), style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { onEdgeAction(edge, EdgeAction.DELETE) }) {
                                Text(stringResource(R.string.memory_graph_edge_delete), style = MaterialTheme.typography.labelSmall, color = colors.error)
                            }
                        }
                    }
                }
            }
        }

        // 阶段 4: 星座标签(如果有聚类)
        state.clusters.forEach { cluster ->
            val clusterNodes = state.nodes.filter { it.factId in cluster.nodeIds }
            if (clusterNodes.isEmpty()) return@forEach
            val clusterIdx = state.nodes.indexOfFirst { it.factId in cluster.nodeIds }
            if (clusterIdx < 0 || clusterIdx >= nodePositions.size) return@forEach
            val pos = nodePositions[clusterIdx]
            Text(
                text = cluster.name,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .offset {
                        val x = pos.x * canvasW.toPx() * scale + pan.x + canvasW.toPx() * (1f - scale) / 2f
                        val y = pos.y * canvasH.toPx() * scale + pan.y + canvasH.toPx() * (1f - scale) / 2f
                        IntOffset((x - 30.dp.toPx()).roundToInt(), (y - 40.dp.toPx()).roundToInt())
                    }
                    .padding(4.dp),
            )
        }
    }
}

// ── 阶段 3: 操作类型 ──
enum class NodeAction { EDIT, DELETE, PIN }
enum class EdgeAction { CONFIRM, DELETE }

// ── 工具函数 ──
private data class GraphPosition(val x: Float, val y: Float)

private fun nodeToneColor(
    category: String,
    scheme: androidx.compose.material3.ColorScheme,
    statusColors: io.zer0.muse.ui.theme.MuseStatusColors,
): Color = when (category.lowercase()) {
    "preference" -> scheme.secondary
    "identity" -> scheme.primary
    "event" -> scheme.tertiary
    "relationship" -> scheme.primary.copy(alpha = 0.82f)
    "goal" -> statusColors.success
    "medical" -> statusColors.warning
    else -> scheme.primary
}

private fun categoryStringRes(category: String): Int = when (category.lowercase()) {
    "preference" -> R.string.memory_graph_category_preference
    "identity" -> R.string.memory_graph_category_identity
    "event" -> R.string.memory_graph_category_event
    "relationship" -> R.string.memory_graph_category_relationship
    "goal" -> R.string.memory_graph_category_goal
    "medical" -> R.string.memory_graph_category_medical
    else -> R.string.memory_graph_category_general
}

private fun relationTypeStringRes(relationType: String): Int = when (relationType.lowercase()) {
    "causes" -> R.string.memory_graph_relation_causes
    "explains" -> R.string.memory_graph_relation_explains
    "part_of" -> R.string.memory_graph_relation_part_of
    "contradicts" -> R.string.memory_graph_relation_contradicts
    "supports" -> R.string.memory_graph_relation_supports
    else -> R.string.memory_graph_relation_related_to
}

private fun relationTypeLabel(relationType: String): String = when (relationType.lowercase()) {
    "causes" -> "causes"
    "explains" -> "explains"
    "part_of" -> "part_of"
    "contradicts" -> "contradicts"
    "supports" -> "supports"
    else -> "related_to"
}
