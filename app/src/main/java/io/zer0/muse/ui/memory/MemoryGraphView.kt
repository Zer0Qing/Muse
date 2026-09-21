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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.statusColors

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

    // UI-FIX: 节点三档半径 —— 原来是 12dp/14dp，只差 17%，肉眼分不出主次。
    val nodeRadiusSmall = 8.dp
    val nodeRadiusMedium = 11.dp
    val nodeRadiusLarge = 15.dp
    val labelOffset = 14.dp
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
    LaunchedEffect(nodeCoordinates, viewportSize) {
        if (nodeCoordinates.isEmpty() || viewportSize == androidx.compose.ui.unit.IntSize.Zero) return@LaunchedEffect
        // UI-FIX: 坐标记的是节点左上角，居中要用节点中心（+nodeWidth/2 / +nodeHeight/2），
        // 原来少算一半，每次自动居中会偏约 85dp。
        val centersX = nodeCoordinates.values.map { it.x.value + 85f }
        val centersY = nodeCoordinates.values.map { it.y.value + 34f }
        val minX = centersX.min()
        val maxX = centersX.max() + 150f
        val minY = centersY.min()
        val maxY = centersY.max() + 60f
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
                                    val hitRadius = with(density) { (nodeRadiusMedium + 8.dp).toPx() }
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
                    // 画边：UI-FIX 按 relationType / weight 做语义编码（虚线=矛盾，粗细=强度）。
                    // 原来一律 0.2 alpha 灰细线，和节点辐射线视觉等价。
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
                        // UI-FIX: 关系类型/权重做语义编码
                        val isContradiction = edge.relationType.equals("contradicts", ignoreCase = true)
                        val lineColor = when {
                            isContradiction -> colors.error.copy(alpha = 0.5f)
                            edge.weight >= 0.7f -> primaryColor.copy(alpha = 0.38f)
                            else -> outlineColor.copy(alpha = 0.28f)
                        }
                        drawLine(
                            color = lineColor,
                            start = aCenter,
                            end = bCenter,
                            strokeWidth = if (edge.weight < 0.3f) 0.8.dp.toPx() else 1.4.dp.toPx(),
                            pathEffect = if (isContradiction) PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f) else null,
                        )
                    }

                    // UI-FIX: 去掉中心辐射线与 "Muse" 装饰字 —— 那层线把每个节点都连到画布中心，
                    // 和真实关系边混在一起，整张图变成蜘蛛网。
                    val canvasCenterX = contentWidth.toPx() / 2f
                    state.nodes.forEach { node ->
                        val point = nodeCoordinates[node.factId] ?: return@forEach
                        val nodeCenter = Offset(
                            point.x.toPx() + nodeWidth.toPx() / 2f,
                            point.y.toPx() + nodeHeight.toPx() / 2f,
                        )
                        val tone = nodeToneColor(node.category, colors, statusColors)
                        val isSelected = selectedNode?.factId == node.factId

                        // UI-FIX: 三档半径 + 描边，替代原来深色模式下看不见的白色高光
                        val r = when {
                            node.isPinned || node.importance >= 2 -> nodeRadiusLarge
                            node.importance == 1 -> nodeRadiusMedium
                            else -> nodeRadiusSmall
                        }
                        val radiusPx = r.toPx()

                        drawCircle(
                            color = tone.copy(alpha = if (isSelected) 1f else 0.92f),
                            radius = radiusPx,
                            center = nodeCenter,
                        )
                        drawCircle(
                            color = if (isSelected) tone else onSurfaceVariantColor.copy(alpha = 0.45f),
                            radius = radiusPx,
                            center = nodeCenter,
                            style = Stroke(width = if (isSelected) 2.dp.toPx() else 1.dp.toPx()),
                        )

                        // UI-FIX: 标签按左右半区向外摆放，原来统一向右，密集时必然重叠
                        val labelEnd = nodeCenter.x >= canvasCenterX
                        val labelGap = radiusPx + with(density) { labelOffset.toPx() }
                        drawTextNode(
                            text = node.title,
                            x = if (labelEnd) nodeCenter.x - labelGap else nodeCenter.x + labelGap,
                            y = nodeCenter.y,
                            color = if (isSelected) primaryColor else onSurfaceColor,
                            density = density,
                            maxWidth = 150.dp,
                            alignEnd = labelEnd,
                        )
                    }
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
                            // UI-FIX: 文字按钮没有尺寸保障，触摸区不足 48dp
                            val actionModifier = Modifier.heightIn(min = 44.dp)
                            MuseCapsuleButton(
                                text = stringResource(R.string.memory_menu_edit),
                                onClick = { action(node, NodeAction.EDIT) },
                                variant = IosCapsuleButtonVariant.Text,
                                fillWidth = false,
                                modifier = actionModifier,
                            )
                            MuseCapsuleButton(
                                text = stringResource(R.string.memory_menu_pin),
                                onClick = { action(node, NodeAction.PIN) },
                                variant = IosCapsuleButtonVariant.Text,
                                fillWidth = false,
                                modifier = actionModifier,
                            )
                            MuseCapsuleButton(
                                text = stringResource(R.string.memory_menu_delete),
                                onClick = { action(node, NodeAction.DELETE); selectedNode = null },
                                variant = IosCapsuleButtonVariant.Text,
                                fillWidth = false,
                                modifier = actionModifier,
                            )
                        }
                        MuseCapsuleButton(
                            text = stringResource(R.string.action_close),
                            onClick = { selectedNode = null },
                            variant = IosCapsuleButtonVariant.Text,
                            fillWidth = false,
                            modifier = Modifier.heightIn(min = 44.dp),
                        )
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
    alignEnd: Boolean = false,
) {
    val paint = android.graphics.Paint().apply {
        this.color = color.toArgb()
        textSize = with(density) { 12.sp.toPx() }
        isAntiAlias = true
        // UI-FIX: 标签摆在节点左侧时右对齐，否则文字会横跨到圆点上
        textAlign = if (alignEnd) android.graphics.Paint.Align.RIGHT else android.graphics.Paint.Align.LEFT
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
