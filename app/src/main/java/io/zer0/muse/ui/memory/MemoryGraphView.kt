package io.zer0.muse.ui.memory

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.zer0.muse.R
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.statusColors
import kotlin.math.roundToInt

/**
 * 记忆星座视图。
 *
 * 纯 Canvas 绘制：节点是圆点 + 旁边的文字标签，边是连线。
 * 不再叠 Surface 卡片，消除半透明色脏色叠加问题。
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

    val nodeRadius = 12.dp
    val labelOffset = 18.dp
    val maxRing = remember(state.nodes) {
        kotlin.math.ceil(kotlin.math.sqrt(state.nodes.size.coerceAtLeast(1).toDouble())).toInt()
    }
    val contentWidth = (400 + maxRing * 280).dp
    val contentHeight = (400 + maxRing * 280).dp
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val density = LocalDensity.current
    var viewportSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val nodeCoordinates = remember(state.nodes, contentWidth, contentHeight) {
        buildConstellationCoordinates(state.nodes, contentWidth, contentHeight)
    }
    LaunchedEffect(nodeCoordinates, viewportSize, contentWidth, contentHeight) {
        if (nodeCoordinates.isEmpty() || viewportSize == androidx.compose.ui.unit.IntSize.Zero) return@LaunchedEffect
        val minX = nodeCoordinates.values.minOf { it.x.value }
        val maxX = nodeCoordinates.values.maxOf { it.x.value } + 170f
        val minY = nodeCoordinates.values.minOf { it.y.value }
        val maxY = nodeCoordinates.values.maxOf { it.y.value } + 68f
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f
        val vwDp = with(density) { viewportSize.width.toDp().value }
        val vhDp = with(density) { viewportSize.height.toDp().value }
        val targetX = with(density) { (centerX - vwDp / 2f).coerceAtLeast(0f).dp.toPx() }.toInt()
        val targetY = with(density) { (centerY - vhDp / 2f).coerceAtLeast(0f).dp.toPx() }.toInt()
        horizontal.scrollTo(targetX)
        vertical.scrollTo(targetY)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .onSizeChanged { viewportSize = it },
    ) {
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
                            zoom = (zoom * zoomChange).coerceIn(0.5f, 3f)
                            pan += panChange
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { tapOffset ->
                            // 把点击坐标从视口转换到画布坐标（考虑 zoom + pan）
                            val canvasX = (tapOffset.x - pan.x) / zoom
                            val canvasY = (tapOffset.y - pan.y) / zoom
                            // 找最近的节点（在 nodeRadius + 一定容差范围内）
                            val hit = state.nodes.minByOrNull { node ->
                                val p = nodeCoordinates[node.factId] ?: return@minByOrNull Float.MAX_VALUE
                                val cx = with(density) { (p.x + nodeWidth / 2).toPx() }
                                val cy = with(density) { (p.y + nodeHeight / 2).toPx() }
                                val dx = canvasX - cx
                                val dy = canvasY - cy
                                dx * dx + dy * dy
                            }
                            if (hit != null) {
                                val p = nodeCoordinates[hit.factId]
                                if (p != null) {
                                    val cx = with(density) { (p.x + nodeWidth / 2).toPx() }
                                    val cy = with(density) { (p.y + nodeHeight / 2).toPx() }
                                    val dx = canvasX - cx
                                    val dy = canvasY - cy
                                    val hitRadius = with(density) { (nodeRadius + 8.dp).toPx() }
                                    if (dx * dx + dy * dy <= hitRadius * hitRadius) {
                                        selectedNode = hit
                                    }
                                }
                            }
                        }
                    }
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    },
            ) {
                val onSurfaceColor = colors.onSurface
                val outlineColor = colors.outline
                val surfaceVariantColor = colors.surfaceVariant
                val primaryColor = colors.primary
                val onSurfaceVariantColor = colors.onSurfaceVariant

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(contentWidth.toPx() / 2f, contentHeight.toPx() / 2f)

                    // 画边
                    state.edges.forEach { edge ->
                        val a = nodeCoordinates[edge.sourceFactId] ?: return@forEach
                        val b = nodeCoordinates[edge.targetFactId] ?: return@forEach
                        val aCenter = Offset(
                            a.x.toPx() + nodeWidth.toPx() / 2f,
                            a.y.toPx() + nodeHeight.toPx() / 2f,
                        )
                        val bCenter = Offset(
                            b.x.toPx() + nodeWidth.toPx() / 2f,
                            b.y.toPx() + nodeHeight.toPx() / 2f,
                        )
                        drawLine(
                            color = outlineColor.copy(alpha = 0.2f),
                            start = aCenter,
                            end = bCenter,
                            strokeWidth = 1.dp.toPx(),
                        )
                    }

                    // 画中心辐射线 + 节点圆点 + 标签
                    state.nodes.forEach { node ->
                        val point = nodeCoordinates[node.factId] ?: return@forEach
                        val nodeCenter = Offset(
                            point.x.toPx() + nodeWidth.toPx() / 2f,
                            point.y.toPx() + nodeHeight.toPx() / 2f,
                        )
                        val tone = nodeToneColor(node.category, colors, statusColors)
                        val isSelected = selectedNode?.factId == node.factId

                        // 辐射线
                        drawLine(
                            color = tone.copy(alpha = if (isSelected) 0.5f else 0.2f),
                            start = center,
                            end = nodeCenter,
                            strokeWidth = if (node.importance >= 2 || node.isPinned) 1.5.dp.toPx() else 0.8.dp.toPx(),
                        )

                        // 节点圆点
                        val r = if (node.importance >= 2 || node.isPinned) nodeRadius + 2.dp else nodeRadius
                        drawCircle(
                            color = tone.copy(alpha = if (isSelected) 1f else 0.85f),
                            radius = r.toPx(),
                            center = nodeCenter,
                        )
                        // 圆点高光
                        drawCircle(
                            color = Color.White.copy(alpha = 0.3f),
                            radius = r.toPx() * 0.4f,
                            center = Offset(nodeCenter.x - r.toPx() * 0.25f, nodeCenter.y - r.toPx() * 0.25f),
                        )

                        // 标签文字
                        val labelX = nodeCenter.x + r.toPx() + with(density) { labelOffset.toPx() }
                        val labelY = nodeCenter.y
                        drawTextNode(
                            text = node.title,
                            x = labelX,
                            y = labelY,
                            color = if (isSelected) primaryColor else onSurfaceColor,
                            density = density,
                            maxWidth = 160.dp,
                        )
                    }

                    // 中心标题
                    drawTextNode(
                        text = "Muse",
                        x = center.x,
                        y = center.y,
                        color = primaryColor.copy(alpha = 0.5f),
                        density = density,
                        maxWidth = 80.dp,
                    )
                }
            }
        }

        // 选中节点的操作面板
        selectedNode?.let { node ->
            val related = state.edges.filter { it.sourceFactId == node.factId || it.targetFactId == node.factId }
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(MusePaddings.cardInner),
                shape = RoundedCornerShape(16.dp),
                color = colors.surface,
                tonalElevation = 3.dp,
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = node.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${categoryLabel(node.category)} · ${stringResource(R.string.memory_graph_node_related, related.size)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        onNodeAction?.let { action ->
                            TextButton(onClick = { action(node, NodeAction.EDIT) }) { Text(stringResource(R.string.memory_menu_edit)) }
                            TextButton(onClick = { action(node, NodeAction.PIN) }) { Text(stringResource(R.string.memory_menu_pin)) }
                            TextButton(onClick = { action(node, NodeAction.DELETE); selectedNode = null }) {
                                Text(stringResource(R.string.memory_menu_delete), color = colors.error)
                            }
                        }
                        TextButton(onClick = { selectedNode = null }) { Text(stringResource(R.string.action_close)) }
                    }
                }
            }
        }
    }
}

/** 在 Canvas 上绘制自动换行的文字标签。 */
private fun DrawScope.drawTextNode(
    text: String,
    x: Float,
    y: Float,
    color: Color,
    density: androidx.compose.ui.unit.Density,
    maxWidth: androidx.compose.ui.unit.Dp,
) {
    val paint = android.graphics.Paint().apply {
        this.color = color.toArgb()
        textSize = with(density) { 12.sp.toPx() }
        isAntiAlias = true
    }
    val maxWidthPx = with(density) { maxWidth.toPx() }
    // 简单截断：超过宽度用省略号
    val displayText = if (paint.measureText(text) > maxWidthPx) {
        val ellipsis = "…"
        var cut = text.length
        while (cut > 0 && paint.measureText(text.substring(0, cut) + ellipsis) > maxWidthPx) {
            cut--
        }
        text.substring(0, cut) + ellipsis
    } else {
        text
    }
    drawContext.canvas.nativeCanvas.drawText(
        displayText,
        x,
        y + paint.textSize / 3f, // 基线对齐到圆点中心
        paint,
    )
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)

// 保留旧布局用的 nodeWidth/nodeHeight（buildConstellationCoordinates 需要它来偏移坐标）
private val nodeWidth = 170.dp
private val nodeHeight = 68.dp


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
