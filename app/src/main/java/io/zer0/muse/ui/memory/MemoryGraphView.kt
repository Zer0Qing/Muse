package io.zer0.muse.ui.memory

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.statusColors
import kotlin.math.hypot
import kotlin.math.min

/**
 * 记忆星座视图 (v2.0 重设计)。
 *
 * 视觉结构:
 *  - 按类别聚簇:同主题记忆形成"星座群",簇底一层柔光,簇顶是分类标签;
 *  - 节点 = 光晕 + 实心点 + 描边环,置顶节点外圈带十字星芒;
 *  - 关系边为二次贝塞尔曲线,矛盾关系用红色虚线;
 *  - 首次进入自动取景(整张星座缩放到视口内),手势缩放/拖动,右下角可复位;
 *  - 缩放较小时自动隐藏普通节点标签,只保留重要/置顶/选中节点,避免糊成一团。
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

    val nodes = state.nodes
    // 画布尺寸由最大簇的节点环半径决定,保证节点永远画在画布内。
    val sizeDp = remember(nodes) {
        val maxClusterSize = nodes.groupingBy { it.category.lowercase() }.eachCount().values.maxOrNull() ?: 1
        val ring = clusterNodeRing(maxClusterSize)
        maxOf(1100f, (ring + 220f) / 0.22f)
    }
    val constellation = remember(nodes, sizeDp) { buildClusterConstellation(nodes, sizeDp) }
    val categoryLabels = nodes.map { it.category.lowercase() }.distinct()
        .associateWith { categoryLabel(it) }

    val density = LocalDensity.current
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var framed by remember(nodes) { mutableStateOf(false) }

    // 首次进入 / 数据换批:整张星座取景居中。
    LaunchedEffect(viewportSize, sizeDp, framed) {
        if (framed || viewportSize == IntSize.Zero) return@LaunchedEffect
        val canvasPx = with(density) { sizeDp.dp.toPx() }
        val fit = min(viewportSize.width / canvasPx, viewportSize.height / canvasPx)
        val fitZoom = fit.coerceIn(0.45f, 1.05f)
        zoom = fitZoom
        pan = Offset(
            (viewportSize.width - canvasPx * fitZoom) / 2f,
            (viewportSize.height - canvasPx * fitZoom) / 2f,
        )
        framed = true
    }

    val resetView = {
        val canvasPx = with(density) { sizeDp.dp.toPx() }
        if (viewportSize != IntSize.Zero) {
            val fit = min(viewportSize.width / canvasPx, viewportSize.height / canvasPx)
            val fitZoom = fit.coerceIn(0.45f, 1.05f)
            zoom = fitZoom
            pan = Offset(
                (viewportSize.width - canvasPx * fitZoom) / 2f,
                (viewportSize.height - canvasPx * fitZoom) / 2f,
            )
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .onSizeChanged { viewportSize = it }
            .pointerInput(nodes) {
                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                    val newZoom = (zoom * zoomChange).coerceIn(0.35f, 2.5f)
                    val scale = newZoom / zoom
                    // 以手势中心为锚点缩放,再叠加拖动位移
                    pan = Offset(
                        centroid.x - (centroid.x - pan.x) * scale + panChange.x,
                        centroid.y - (centroid.y - pan.y) * scale + panChange.y,
                    )
                    zoom = newZoom
                }
            }
            .pointerInput(nodes, constellation) {
                detectTapGestures { tapOffset ->
                    val canvasX = (tapOffset.x - pan.x) / zoom
                    val canvasY = (tapOffset.y - pan.y) / zoom
                    val hit = nodes.minByOrNull { node ->
                        val center = constellation.nodeCenters[node.factId] ?: return@minByOrNull Float.MAX_VALUE
                        val cx = with(density) { center.x.dp.toPx() }
                        val cy = with(density) { center.y.dp.toPx() }
                        val dx = canvasX - cx
                        val dy = canvasY - cy
                        dx * dx + dy * dy
                    }
                    if (hit != null) {
                        val center = constellation.nodeCenters[hit.factId]
                        if (center != null) {
                            val cx = with(density) { center.x.dp.toPx() }
                            val cy = with(density) { center.y.dp.toPx() }
                            val hitRadius = with(density) {
                                (nodeRadiusDp(hit).dp + 12.dp).toPx()
                            } / zoom
                            val dx = canvasX - cx
                            val dy = canvasY - cy
                            if (dx * dx + dy * dy <= hitRadius * hitRadius) {
                                selectedNode = hit
                            }
                        }
                    }
                }
            },
    ) {
        val toneResolver: (String) -> Color = { category -> nodeToneColor(category, colors, statusColors) }
        val labelColor = colors.onSurface
        val labelBg = colors.surface
        val labelBorder = colors.outlineVariant
        val selectedColor = colors.primary

        Canvas(
            modifier = Modifier
                .size(sizeDp.dp)
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            val cw = size.width
            val ch = size.height

            // 背景:中心柔光 + 点阵网格,给出"星图"底子
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(colors.primary.copy(alpha = 0.05f), Color.Transparent),
                    center = Offset(cw / 2f, ch / 2f),
                    radius = cw * 0.55f,
                ),
            )
            val gridStep = 76.dp.toPx()
            val gridDot = 1.1.dp.toPx()
            var gx = gridStep
            while (gx < cw) {
                var gy = gridStep
                while (gy < ch) {
                    drawCircle(
                        color = colors.outline.copy(alpha = 0.14f),
                        radius = gridDot,
                        center = Offset(gx, gy),
                    )
                    gy += gridStep
                }
                gx += gridStep
            }

            // 簇柔光 + 分类标签
            constellation.clusters.forEach { anchor ->
                val tone = toneResolver(anchor.category)
                val clusterCenter = Offset(anchor.x.dp.toPx(), anchor.y.dp.toPx())
                val halo = anchor.spread * 1.55f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(tone.copy(alpha = 0.10f), Color.Transparent),
                        center = clusterCenter,
                        radius = halo.dp.toPx(),
                    ),
                    radius = halo.dp.toPx(),
                    center = clusterCenter,
                )
                val label = categoryLabels[anchor.category] ?: anchor.category
                drawChipLabel(
                    text = label,
                    center = Offset(clusterCenter.x, clusterCenter.y - (anchor.spread + 40f).dp.toPx()),
                    textColor = colors.onSurfaceVariant,
                    background = labelBg.copy(alpha = 0.9f),
                    border = labelBorder,
                    density = density,
                    fontSize = 10.sp, // 画布内嵌簇标签:固定字号(随图形缩放,不走主题字体)
                    letterSpacing = 1.5f,
                    maxWidthPx = 180.dp.toPx(),
                )
            }

            // 关系边:二次贝塞尔,矛盾关系红色虚线
            state.edges.forEach { edge ->
                val a = constellation.nodeCenters[edge.sourceFactId] ?: return@forEach
                val b = constellation.nodeCenters[edge.targetFactId] ?: return@forEach
                val aCenter = Offset(a.x.dp.toPx(), a.y.dp.toPx())
                val bCenter = Offset(b.x.dp.toPx(), b.y.dp.toPx())
                val dx = bCenter.x - aCenter.x
                val dy = bCenter.y - aCenter.y
                val dist = hypot(dx, dy).coerceAtLeast(0.001f)
                // 垂直方向偏移,画出轻微弧度,避免多条边完全重叠
                val curve = dist * 0.08f
                val mid = Offset(
                    (aCenter.x + bCenter.x) / 2f - dy / dist * curve,
                    (aCenter.y + bCenter.y) / 2f + dx / dist * curve,
                )
                val isContradiction = edge.relationType.equals("contradicts", ignoreCase = true)
                val lineColor = when {
                    isContradiction -> colors.error.copy(alpha = 0.55f)
                    edge.weight >= 0.7f -> colors.primary.copy(alpha = 0.34f)
                    else -> colors.outline.copy(alpha = 0.22f)
                }
                val path = Path().apply {
                    moveTo(aCenter.x, aCenter.y)
                    quadraticBezierTo(mid.x, mid.y, bCenter.x, bCenter.y)
                }
                drawPath(
                    path = path,
                    color = lineColor,
                    style = Stroke(
                        width = if (edge.weight < 0.3f) 0.9.dp.toPx() else 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = if (isContradiction) {
                            PathEffect.dashPathEffect(floatArrayOf(7f, 5f), 0f)
                        } else {
                            null
                        },
                    ),
                )
                if (edge.weight >= 0.7f && !isContradiction) {
                    // 强关系端点小光点,强化"星链"感
                    drawCircle(colors.primary.copy(alpha = 0.5f), radius = 2.2.dp.toPx(), center = mid)
                }
            }

            // 节点
            nodes.forEach { node ->
                val center = constellation.nodeCenters[node.factId] ?: return@forEach
                val nodeCenter = Offset(center.x.dp.toPx(), center.y.dp.toPx())
                val tone = toneResolver(node.category)
                val isSelected = selectedNode?.factId == node.factId
                val radius = nodeRadiusDp(node).dp.toPx()

                // 柔光三层,让节点像发光星体而不是"贴上去的圆"
                drawCircle(tone.copy(alpha = 0.09f), radius = radius * 2.2f, center = nodeCenter)
                drawCircle(tone.copy(alpha = 0.16f), radius = radius * 1.5f, center = nodeCenter)
                drawCircle(
                    color = tone.copy(alpha = if (isSelected) 1f else 0.92f),
                    radius = radius,
                    center = nodeCenter,
                )
                drawCircle(
                    color = if (isSelected) tone else colors.onSurfaceVariant.copy(alpha = 0.42f),
                    radius = radius,
                    center = nodeCenter,
                    style = Stroke(width = if (isSelected) 2.dp.toPx() else 0.9.dp.toPx()),
                )

                // 置顶节点:十字星芒
                if (node.isPinned) {
                    val beam = radius * 3.1f
                    val beamWidth = 1.3.dp.toPx()
                    val beamColor = tone.copy(alpha = 0.75f)
                    drawLine(beamColor, Offset(nodeCenter.x - beam, nodeCenter.y), Offset(nodeCenter.x + beam, nodeCenter.y), beamWidth, StrokeCap.Round)
                    drawLine(beamColor, Offset(nodeCenter.x, nodeCenter.y - beam), Offset(nodeCenter.x, nodeCenter.y + beam), beamWidth, StrokeCap.Round)
                }

                // 标签:缩放过小时只保留重要 / 置顶 / 选中节点
                val showLabel = isSelected || node.isPinned || node.importance >= 2 || zoom >= 0.72f
                if (showLabel) {
                    val labelGap = radius + 10.dp.toPx()
                    val toRight = nodeCenter.x < cw / 2f
                    val labelX = if (toRight) nodeCenter.x + labelGap else nodeCenter.x - labelGap
                    drawChipLabel(
                        text = node.title,
                        center = Offset(labelX, nodeCenter.y),
                        textColor = if (isSelected) selectedColor else labelColor,
                        background = if (isSelected) {
                            selectedColor.copy(alpha = 0.12f)
                        } else {
                            labelBg.copy(alpha = 0.86f)
                        },
                        border = if (isSelected) selectedColor.copy(alpha = 0.45f) else labelBorder,
                        density = density,
                        fontSize = 11.sp, // 画布内嵌节点标签:固定字号(同上)
                        anchorRight = !toRight,
                        maxWidthPx = 168.dp.toPx(),
                    )
                }
            }
        }

        // 复位按钮
        Surface(
            onClick = resetView,
            shape = RoundedCornerShape(12.dp),
            color = colors.surface.copy(alpha = 0.92f),
            border = androidx.compose.foundation.BorderStroke(0.6.dp, colors.outlineVariant),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(MusePaddings.cardInner),
        ) {
            Text(
                text = stringResource(R.string.memory_graph_reset_view),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
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

/** 节点半径:置顶/高重要度更大,普通节点最小。 */
private fun nodeRadiusDp(node: MemoryGraphNode): Float = when {
    node.isPinned || node.importance >= 2 -> 15f
    node.importance == 1 -> 12f
    else -> 9f
}

/**
 * 画一个"胶囊标签":浅底圆角矩形 + 描边 + 单行文字。
 *
 * @param center 标签锚点中心;anchorRight=true 表示文字贴在锚点左侧(右对齐)
 */
private fun DrawScope.drawChipLabel(
    text: String,
    center: Offset,
    textColor: Color,
    background: Color,
    border: Color,
    density: androidx.compose.ui.unit.Density,
    fontSize: androidx.compose.ui.unit.TextUnit,
    maxWidthPx: Float,
    anchorRight: Boolean = false,
    letterSpacing: Float = 0f,
) {
    if (text.isBlank()) return
    val paint = android.graphics.Paint().apply {
        this.color = textColor.toArgb()
        textSize = with(density) { fontSize.toPx() }
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.LEFT
        // letterSpacing 单位是 em,这里按字号的倍数换算
        this.letterSpacing = letterSpacing / fontSize.value.coerceAtLeast(1f)
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
    }
    val ellipsis = "…"
    val maxTextWidth = maxWidthPx - 16.dp.toPx()
    val displayText = if (paint.measureText(text) > maxTextWidth) {
        var cut = text.length
        while (cut > 0 && paint.measureText(text.substring(0, cut) + ellipsis) > maxTextWidth) cut--
        text.substring(0, cut) + ellipsis
    } else {
        text
    }
    val textWidth = paint.measureText(displayText)
    val chipWidth = textWidth + 16.dp.toPx()
    val chipHeight = paint.textSize + 12.dp.toPx()
    val left = if (anchorRight) center.x - chipWidth else center.x
    val top = center.y - chipHeight / 2f
    val corner = chipHeight / 2f
    drawRoundRect(
        color = background,
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(chipWidth, chipHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
    )
    drawRoundRect(
        color = border,
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(chipWidth, chipHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
        style = Stroke(width = 0.7.dp.toPx()),
    )
    drawContext.canvas.nativeCanvas.drawText(
        displayText,
        left + 8.dp.toPx(),
        top + chipHeight / 2f + paint.textSize / 3f,
        paint,
    )
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)

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
