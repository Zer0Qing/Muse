package io.zer0.muse.ui.memory

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import io.zer0.muse.R
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.statusColors
import kotlin.math.roundToInt

/**
 * 记忆树视图。
 *
 * v1.0.90: 从散点星座改为树状布局。
 * - 根节点: 记忆树
 * - 一级枝条: category 主题
 * - 叶片: 单条 fact
 * - 叶片固定 184dp × 60dp,同一层按行列排布,节点尺寸参与布局,保证文字不重叠
 * - 内容区可横向/竖向滚动,记忆多时不会压缩成一团
 * - 关系边仍使用 memory_links 绘制,不改变任何存储数据
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
    var selectedNode by remember { mutableStateOf<MemoryGraphNode?>(null) }

    if (state.isLoading) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) { MuseLoadingState() }
        return
    }
    if (state.error != null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(state.error, color = colors.error, style = MaterialTheme.typography.bodyMedium)
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

    val groups = remember(state.nodes) {
        state.nodes.groupBy { it.category.ifBlank { "general" } }.toSortedMap()
    }
    val laneWidth = 220.dp
    val leafWidth = 184.dp
    val leafHeight = 60.dp
    val rowGap = 14.dp
    val laneGap = 22.dp
    val contentWidth = (laneWidth * groups.size + laneGap * (groups.size - 1).coerceAtLeast(0)).coerceAtLeast(360.dp)
    val maxLeaves = groups.values.maxOfOrNull { it.size } ?: 1
    val rows = maxLeaves // one leaf per row in each branch; fixed vertical rhythm
    val contentHeight = (250.dp + (leafHeight + rowGap) * rows).coerceAtLeast(420.dp)

    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val density = LocalDensity.current
    val nodeCoordinates = remember(state.nodes, groups) {
        buildTreeCoordinates(groups, laneWidth, laneGap, leafWidth, leafHeight, rowGap)
    }

    Box(modifier = modifier.clip(RoundedCornerShape(24.dp)).background(colors.surfaceVariant.copy(alpha = 0.28f))) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(horizontal)
                .verticalScroll(vertical),
        ) {
            Box(modifier = Modifier.width(contentWidth).height(contentHeight)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val root = Offset(with(density) { contentWidth.toPx() / 2f }, with(density) { 58.dp.toPx() })
                    groups.keys.forEachIndexed { index, category ->
                        val laneCenter = with(density) { (laneWidth * index + (laneWidth + laneGap) / 2).toPx() }
                        val branch = Offset(laneCenter, 142.dp.toPx())
                        drawLine(
                            color = colors.primary.copy(alpha = 0.35f),
                            start = root,
                            end = branch,
                            strokeWidth = 2.dp.toPx(),
                        )
                        groups[category].orEmpty().forEach { node ->
                            val leaf = nodeCoordinates[node.factId] ?: return@forEach
                            drawLine(
                                color = nodeToneColor(node.category, colors, statusColors).copy(alpha = 0.28f),
                                start = branch,
                                end = Offset(with(density) { leaf.x.toPx() + leafWidth.toPx() / 2f }, with(density) { leaf.y.toPx() }),
                                strokeWidth = 1.5.dp.toPx(),
                            )
                        }
                    }
                    // 关系边以淡色曲线替代主枝,只做辅助,不破坏树的阅读顺序。
                    state.edges.forEach { edge ->
                        val a = nodeCoordinates[edge.sourceFactId] ?: return@forEach
                        val b = nodeCoordinates[edge.targetFactId] ?: return@forEach
                        drawLine(
                            color = colors.outline.copy(alpha = 0.16f),
                            start = Offset(with(density) { a.x.toPx() + leafWidth.toPx() }, with(density) { a.y.toPx() + leafHeight.toPx() / 2f }),
                            end = Offset(with(density) { b.x.toPx() }, with(density) { b.y.toPx() + leafHeight.toPx() / 2f }),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .offset { IntOffset((contentWidth.toPx() / 2f - 70.dp.toPx()).roundToInt(), 20.dp.roundToPx()) },
                    shape = RoundedCornerShape(20.dp),
                    color = colors.primary.copy(alpha = 0.16f),
                    shadowElevation = 2.dp,
                ) {
                    Text(
                        text = stringResource(R.string.memory_graph_preview_title),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.primary,
                    )
                }

                groups.entries.forEachIndexed { laneIndex, (category, nodes) ->
                    val laneX = (laneWidth + laneGap) * laneIndex
                    Surface(
                        modifier = Modifier
                            .offset(x = laneX + 30.dp, y = 112.dp)
                            .width(laneWidth - 60.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = colors.surface.copy(alpha = 0.9f),
                    ) {
                        Text(
                            text = categoryLabel(category),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    nodes.forEach { node ->
                        val p = nodeCoordinates[node.factId] ?: return@forEach
                        val tone = nodeToneColor(node.category, colors, statusColors)
                        Surface(
                            modifier = Modifier
                                .offset(x = p.x, y = p.y)
                                .width(leafWidth)
                                .height(leafHeight)
                                .clickable { selectedNode = node },
                            shape = RoundedCornerShape(16.dp),
                            color = tone.copy(alpha = if (node.isPinned) 0.24f else 0.12f),
                            shadowElevation = if (node.isPinned) 3.dp else 1.dp,
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    text = node.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (node.isPinned) stringResource(R.string.memory_graph_node_pinned) else categoryLabel(node.category),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }

        selectedNode?.let { node ->
            val related = state.edges.filter { it.sourceFactId == node.factId || it.targetFactId == node.factId }
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(MusePaddings.cardInner),
                shape = RoundedCornerShape(18.dp),
                color = colors.surface.copy(alpha = 0.97f),
                shadowElevation = 5.dp,
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(node.title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = "${categoryLabel(node.category)} · ${stringResource(R.string.memory_graph_node_related, related.size)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        onNodeAction?.let { action ->
                            TextButton(onClick = { action(node, NodeAction.EDIT) }) { Text(stringResource(R.string.memory_menu_edit)) }
                            TextButton(onClick = { action(node, NodeAction.PIN) }) { Text(stringResource(R.string.memory_menu_pin)) }
                            TextButton(onClick = { action(node, NodeAction.DELETE); selectedNode = null }) { Text(stringResource(R.string.memory_menu_delete), color = colors.error) }
                        }
                        TextButton(onClick = { selectedNode = null }) { Text(stringResource(R.string.action_close)) }
                    }
                }
            }
        }
    }
}

private data class TreePoint(val x: Dp, val y: Dp)

private fun buildTreeCoordinates(
    groups: Map<String, List<MemoryGraphNode>>,
    laneWidth: Dp,
    laneGap: Dp,
    leafWidth: Dp,
    leafHeight: Dp,
    rowGap: Dp,
): Map<Long, TreePoint> {
    val result = mutableMapOf<Long, TreePoint>()
    groups.entries.forEachIndexed { laneIndex, (_, nodes) ->
        val laneLeft = laneWidth * laneIndex + (laneWidth + laneGap) * 0 + 18.dp
        nodes.forEachIndexed { row, node ->
            val y = 220.dp + (leafHeight + rowGap) * row
            result[node.factId] = TreePoint(laneLeft, y)
        }
    }
    return result
}

enum class NodeAction { EDIT, DELETE, PIN }
enum class EdgeAction { CONFIRM, DELETE }

@Composable
private fun categoryLabel(category: String): String = stringResource(categoryStringRes(category))

private fun categoryStringRes(category: String): Int = when (category.lowercase()) {
    "preference" -> R.string.memory_graph_category_preference
    "identity" -> R.string.memory_graph_category_identity
    "event" -> R.string.memory_graph_category_event
    "relationship" -> R.string.memory_graph_category_relationship
    "goal" -> R.string.memory_graph_category_goal
    "medical" -> R.string.memory_graph_category_medical
    else -> R.string.memory_graph_category_general
}

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
