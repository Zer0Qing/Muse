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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
 * 记忆星座视图。
 *
 * 节点采用确定性的黄金角螺旋布局，重要/置顶节点排序在前并靠近核心，
 * 节点固定尺寸参与布局，关系边与节点标签分离；内容支持缩放、拖动和双向滚动。
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
    val nodeWidth = 170.dp
    val nodeHeight = 68.dp
    val maxRing = remember(state.nodes) {
        kotlin.math.ceil(kotlin.math.sqrt(state.nodes.size.coerceAtLeast(1).toDouble())).toInt()
    }
    // 半径按 sqrt(n) 增长，画布留足边界避免大规模节点被 clamp 到同一侧。
    val contentWidth = (500 + maxRing * 380).dp
    val contentHeight = (500 + maxRing * 380).dp
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val density = LocalDensity.current
    val nodeCoordinates = remember(state.nodes, contentWidth, contentHeight) {
        buildConstellationCoordinates(state.nodes, contentWidth, contentHeight)
    }
    val categoryCoordinates = remember(groups, nodeCoordinates) {
        groups.keys.mapNotNull { category ->
            val points = groups[category].orEmpty().mapNotNull { nodeCoordinates[it.factId] }
            if (points.isEmpty()) null else category to ConstellationPoint(
                points.map { it.x.value }.average().dp,
                points.map { it.y.value }.average().dp,
            )
        }.toMap()
    }

    Box(modifier = modifier.clip(RoundedCornerShape(24.dp)).background(colors.surfaceVariant.copy(alpha = 0.28f))) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(horizontal)
                .verticalScroll(vertical),
        ) {
            Box(
                modifier = Modifier
                    .width(contentWidth)
                    .height(contentHeight)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, panChange, zoomChange, _ ->
                            zoom = (zoom * zoomChange).coerceIn(0.55f, 2.4f)
                            pan += panChange
                        }
                    }
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(contentWidth.toPx() / 2f, contentHeight.toPx() / 2f)
                    state.edges.forEach { edge ->
                        val a = nodeCoordinates[edge.sourceFactId] ?: return@forEach
                        val b = nodeCoordinates[edge.targetFactId] ?: return@forEach
                        drawLine(
                            color = colors.outline.copy(alpha = 0.24f),
                            start = Offset(a.x.toPx() + nodeWidth.toPx() / 2f, a.y.toPx() + nodeHeight.toPx() / 2f),
                            end = Offset(b.x.toPx() + nodeWidth.toPx() / 2f, b.y.toPx() + nodeHeight.toPx() / 2f),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    state.nodes.forEach { node ->
                        val point = nodeCoordinates[node.factId] ?: return@forEach
                        val nodeCenter = Offset(
                            point.x.toPx() + nodeWidth.toPx() / 2f,
                            point.y.toPx() + nodeHeight.toPx() / 2f,
                        )
                        drawLine(
                            color = nodeToneColor(node.category, colors, statusColors).copy(alpha = 0.32f),
                            start = center,
                            end = nodeCenter,
                            strokeWidth = if (node.importance >= 2 || node.isPinned) 2.dp.toPx() else 1.dp.toPx(),
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .offset { IntOffset((contentWidth.toPx() / 2f - 84.dp.toPx()).roundToInt(), (contentHeight.toPx() / 2f - 32.dp.toPx()).roundToInt()) },
                    shape = RoundedCornerShape(20.dp),
                    color = colors.primary.copy(alpha = 0.18f),
                    shadowElevation = 3.dp,
                ) {
                    Text(
                        text = stringResource(R.string.memory_graph_preview_title),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.primary,
                    )
                }

                categoryCoordinates.forEach { (category, point) ->
                    Surface(
                        modifier = Modifier.offset(x = point.x, y = point.y),
                        shape = RoundedCornerShape(12.dp),
                        color = colors.surface.copy(alpha = 0.82f),
                    ) {
                        Text(
                            text = categoryLabel(category),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                state.nodes.forEach { node ->
                    val point = nodeCoordinates[node.factId] ?: return@forEach
                    val tone = nodeToneColor(node.category, colors, statusColors)
                    Surface(
                        modifier = Modifier
                            .offset(x = point.x, y = point.y)
                            .width(nodeWidth)
                            .height(nodeHeight)
                            .clickable { selectedNode = node },
                        shape = RoundedCornerShape(16.dp),
                        color = tone.copy(alpha = if (node.isPinned || node.importance >= 2) 0.28f else 0.12f),
                        shadowElevation = if (node.isPinned || node.importance >= 2) 3.dp else 1.dp,
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
