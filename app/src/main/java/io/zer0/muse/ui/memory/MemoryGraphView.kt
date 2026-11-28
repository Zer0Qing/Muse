@file:Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod", "ReturnCount", "LongParameterList")

package io.zer0.muse.ui.memory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseConstellationPalette
import io.zer0.muse.ui.theme.MuseCornerRadius
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.constellationColors
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

private const val MIN_ZOOM = 0.35f
private const val MAX_ZOOM = 3.2f
private const val STARDUST_COUNT = 85

/**
 * 记忆星座星图（v3.0 重设计）。
 *
 * 视觉与交互亮点：
 *  - 默认星夜观星台底板，一键切换星夜 / 跟随明暗的纸质星图；
 *  - 确定性簇内排斥 + 簇间分离布局 + MST 星座骨架 + 屏幕空间矩形碰撞避让标签；
 *  - 支持顶部星群图例点击平滑飞镜聚焦、星体点击焦点高亮（亮显当前星 + 邻星 + 关系弧）、
 *    完整正文检视与关联管理、双向/因果/矛盾关系弧样式与手动连线引导。
 */
@Composable
fun MemoryGraphView(
    state: MemoryGraphViewModel.GraphState,
    modifier: Modifier = Modifier,
    onNodeAction: ((MemoryGraphNode, NodeAction) -> Unit)? = null,
    onEdgeAction: ((MemoryGraphEdge, EdgeAction) -> Unit)? = null,
    onManualLink: ((MemoryGraphNode, MemoryGraphNode, String) -> Unit)? = null,
    onLoadFullContent: ((Long, (String?) -> Unit) -> Unit)? = null,
    onRebuildEdges: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val constellationColors = MaterialTheme.constellationColors
    var useObservatorySky by rememberSaveable { mutableStateOf(true) }
    val palette = if (useObservatorySky) constellationColors.observatory else constellationColors.paper

    var selectedNodeId by remember { mutableStateOf<Long?>(null) }
    var focusedCategory by remember { mutableStateOf<String?>(null) }
    var linkSourceNode by remember { mutableStateOf<MemoryGraphNode?>(null) }
    var linkTargetNode by remember { mutableStateOf<MemoryGraphNode?>(null) }

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
    val nodesById = remember(nodes) { nodes.associateBy { it.factId } }
    val selectedNode = selectedNodeId?.let { nodesById[it] }

    var fullContentCache by remember(selectedNode?.factId) { mutableStateOf<String?>(null) }
    var loadingFullContent by remember(selectedNode?.factId) { mutableStateOf(false) }
    LaunchedEffect(selectedNode?.factId) {
        val target = selectedNode ?: return@LaunchedEffect
        val loader = onLoadFullContent
        if (loader == null) {
            fullContentCache = target.title
            loadingFullContent = false
        } else {
            loadingFullContent = true
            loader(target.factId) { loaded ->
                fullContentCache = loaded ?: target.title
                loadingFullContent = false
            }
        }
    }

    val sizeDp = remember(nodes) {
        val maxClusterSize = nodes.groupingBy { it.category.lowercase() }.eachCount().values.maxOrNull() ?: 1
        val categoryCount = nodes.map { it.category.lowercase() }.distinct().size
        val ring = clusterNodeRing(maxClusterSize)
        maxOf(1280f, (ring + 260f) / 0.22f, categoryCount * 220f)
    }
    val constellation = remember(nodes, state.edges, sizeDp) {
        buildClusterConstellation(nodes, sizeDp, state.edges)
    }
    val clusterLabels = constellation.clusters.associate { anchor ->
        anchor.category to stringResource(
            R.string.memory_constellation_cluster_chip,
            memoryCategoryLabel(anchor.category),
            anchor.memberCount,
        )
    }
    val relationLabels = mapOf(
        "related_to" to stringResource(R.string.memory_graph_link_type_related),
        "causes" to stringResource(R.string.memory_graph_link_type_causes),
        "part_of" to stringResource(R.string.memory_graph_link_type_part_of),
        "contradicts" to stringResource(R.string.memory_graph_link_type_contradicts),
    )

    val density = LocalDensity.current
    val cameraScope = rememberCoroutineScope()
    val normalTween = MuseMotion.tween<Float>(MuseAnimation.NORMAL_MS)
    val fastNormalTween = MuseMotion.tween<Float>(MuseAnimation.FAST_NORMAL_MS)
    val zoomAnim = remember { Animatable(1f) }
    val panXAnim = remember { Animatable(0f) }
    val panYAnim = remember { Animatable(0f) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var framed by remember(nodes) { mutableStateOf(false) }

    fun frameBoundsImmediate(bounds: ConstellationBounds, maxZoomCap: Float = 1.15f) {
        if (viewportSize == IntSize.Zero) return
        val wPx = with(density) { (bounds.maxX - bounds.minX).coerceAtLeast(160f).dp.toPx() }
        val hPx = with(density) { (bounds.maxY - bounds.minY).coerceAtLeast(160f).dp.toPx() }
        val targetZoom = min(viewportSize.width / wPx, viewportSize.height / hPx).coerceIn(MIN_ZOOM, maxZoomCap)
        val cxPx = with(density) { ((bounds.minX + bounds.maxX) / 2f).dp.toPx() }
        val cyPx = with(density) { ((bounds.minY + bounds.maxY) / 2f).dp.toPx() }
        val targetPanX = viewportSize.width / 2f - cxPx * targetZoom
        val targetPanY = viewportSize.height / 2f - cyPx * targetZoom
        cameraScope.launch {
            launch { zoomAnim.animateTo(targetZoom, normalTween) }
            launch { panXAnim.animateTo(targetPanX, normalTween) }
            launch { panYAnim.animateTo(targetPanY, normalTween) }
        }
    }

    fun snapBounds(bounds: ConstellationBounds) {
        if (viewportSize == IntSize.Zero) return
        val wPx = with(density) { (bounds.maxX - bounds.minX).coerceAtLeast(160f).dp.toPx() }
        val hPx = with(density) { (bounds.maxY - bounds.minY).coerceAtLeast(160f).dp.toPx() }
        val targetZoom = min(viewportSize.width / wPx, viewportSize.height / hPx).coerceIn(MIN_ZOOM, 1.08f)
        val cxPx = with(density) { ((bounds.minX + bounds.maxX) / 2f).dp.toPx() }
        val cyPx = with(density) { ((bounds.minY + bounds.maxY) / 2f).dp.toPx() }
        cameraScope.launch {
            zoomAnim.snapTo(targetZoom)
            panXAnim.snapTo(viewportSize.width / 2f - cxPx * targetZoom)
            panYAnim.snapTo(viewportSize.height / 2f - cyPx * targetZoom)
        }
    }

    fun focusOnStar(node: MemoryGraphNode, targetZoomMin: Float = 1.25f) {
        val center = constellation.nodeCenters[node.factId] ?: return
        if (viewportSize == IntSize.Zero) return
        val targetZoom = maxOf(zoomAnim.value, targetZoomMin).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val cxPx = with(density) { center.x.dp.toPx() }
        val cyPx = with(density) { center.y.dp.toPx() }
        val targetPanX = viewportSize.width / 2f - cxPx * targetZoom
        val targetPanY = viewportSize.height * 0.36f - cyPx * targetZoom
        cameraScope.launch {
            launch { zoomAnim.animateTo(targetZoom, normalTween) }
            launch { panXAnim.animateTo(targetPanX, normalTween) }
            launch { panYAnim.animateTo(targetPanY, normalTween) }
        }
    }

    fun zoomAroundViewportCenter(factor: Float) {
        if (viewportSize == IntSize.Zero) return
        val oldZoom = zoomAnim.value
        val newZoom = (oldZoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val scale = newZoom / oldZoom
        val cx = viewportSize.width / 2f
        val cy = viewportSize.height / 2f
        val newPanX = cx - (cx - panXAnim.value) * scale
        val newPanY = cy - (cy - panYAnim.value) * scale
        cameraScope.launch {
            launch { zoomAnim.animateTo(newZoom, fastNormalTween) }
            launch { panXAnim.animateTo(newPanX, fastNormalTween) }
            launch { panYAnim.animateTo(newPanY, fastNormalTween) }
        }
    }

    LaunchedEffect(viewportSize, sizeDp, framed) {
        if (framed || viewportSize == IntSize.Zero) return@LaunchedEffect
        val bounds = computeConstellationBounds(constellation.nodeCenters.values)
        snapBounds(bounds)
        framed = true
    }

    val resetView = {
        focusedCategory = null
        val bounds = computeConstellationBounds(constellation.nodeCenters.values)
        frameBoundsImmediate(bounds, maxZoomCap = 1.08f)
    }

    val nodeLabelTextSizePx = with(density) { MaterialTheme.typography.labelSmall.fontSize.toPx() }
    val clusterTextSizePx = with(density) { MaterialTheme.typography.labelSmall.fontSize.toPx() }
    val relationTextSizePx = with(density) { MaterialTheme.typography.labelSmall.fontSize.toPx() }
    val labelPaint = remember(nodeLabelTextSizePx) {
        android.graphics.Paint().apply {
            textSize = nodeLabelTextSizePx
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.LEFT
        }
    }
    val clusterPaint = remember(clusterTextSizePx) {
        android.graphics.Paint().apply {
            textSize = clusterTextSizePx
            isAntiAlias = true
            isFakeBoldText = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    val relationPaint = remember(relationTextSizePx) {
        android.graphics.Paint().apply {
            textSize = relationTextSizePx * 0.9f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    val neighborIds = remember(selectedNodeId, state.edges) {
        val sid = selectedNodeId ?: return@remember emptySet()
        buildSet {
            add(sid)
            state.edges.forEach { e ->
                if (e.sourceFactId == sid) add(e.targetFactId)
                if (e.targetFactId == sid) add(e.sourceFactId)
            }
        }
    }

    var entryStarted by remember { mutableStateOf(false) }
    LaunchedEffect(nodes) { entryStarted = true }
    val entryAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (entryStarted) 1f else 0f,
        animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS),
        label = "memoryGraphEntry",
    )

    val containerShape = if (isFullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(MuseCornerRadius.CARD.dp)

    Box(
        modifier = modifier
            .clip(containerShape)
            .background(palette.canvasBg)
            .graphicsLayer { alpha = entryAlpha }
            .onSizeChanged { viewportSize = it }
            .pointerInput(nodes) {
                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                    val oldZoom = zoomAnim.value
                    val newZoom = (oldZoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    val scale = newZoom / oldZoom
                    val nextPanX = centroid.x - (centroid.x - panXAnim.value) * scale + panChange.x
                    val nextPanY = centroid.y - (centroid.y - panYAnim.value) * scale + panChange.y
                    cameraScope.launch {
                        zoomAnim.snapTo(newZoom)
                        panXAnim.snapTo(nextPanX)
                        panYAnim.snapTo(nextPanY)
                    }
                }
            }
            .pointerInput(nodes, constellation) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        val curZoom = zoomAnim.value
                        if (curZoom > 1.4f) {
                            resetView()
                        } else {
                            val newZoom = (curZoom * 1.65f).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            val scale = newZoom / curZoom
                            val targetPanX = tapOffset.x - (tapOffset.x - panXAnim.value) * scale
                            val targetPanY = tapOffset.y - (tapOffset.y - panYAnim.value) * scale
                            cameraScope.launch {
                                launch { zoomAnim.animateTo(newZoom, fastNormalTween) }
                                launch { panXAnim.animateTo(targetPanX, fastNormalTween) }
                                launch { panYAnim.animateTo(targetPanY, fastNormalTween) }
                            }
                        }
                    },
                    onTap = { tapOffset ->
                        val curZoom = zoomAnim.value
                        val canvasX = (tapOffset.x - panXAnim.value) / curZoom
                        val canvasY = (tapOffset.y - panYAnim.value) / curZoom
                        val hit = nodes.minByOrNull { node ->
                            val c = constellation.nodeCenters[node.factId] ?: return@minByOrNull Float.MAX_VALUE
                            val cx = with(density) { c.x.dp.toPx() }
                            val cy = with(density) { c.y.dp.toPx() }
                            val dx = canvasX - cx
                            val dy = canvasY - cy
                            dx * dx + dy * dy
                        }
                        val hitCenter = hit?.let { constellation.nodeCenters[it.factId] }
                        val tappedNode = if (hit != null && hitCenter != null) {
                            val cx = with(density) { hitCenter.x.dp.toPx() }
                            val cy = with(density) { hitCenter.y.dp.toPx() }
                            val degree = constellation.nodeDegrees[hit.factId] ?: 0
                            val rPx = with(density) { (starRadiusDp(hit, degree).dp + 14.dp).toPx() } / curZoom
                            val dx = canvasX - cx
                            val dy = canvasY - cy
                            if (dx * dx + dy * dy <= rPx * rPx) hit else null
                        } else {
                            null
                        }
                        if (tappedNode != null) {
                            val src = linkSourceNode
                            if (src != null && src.factId != tappedNode.factId) {
                                linkTargetNode = tappedNode
                            } else {
                                selectedNodeId = tappedNode.factId
                            }
                        } else {
                            if (selectedNodeId != null) {
                                selectedNodeId = null
                            } else {
                                focusedCategory = null
                            }
                        }
                    },
                )
            },
    ) {
        val currentZoom = zoomAnim.value
        val currentPanX = panXAnim.value
        val currentPanY = panYAnim.value

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawStarfieldBackdrop(palette, size)

            fun toScreen(center: ConstellationCenter): Offset = Offset(
                center.x.dp.toPx() * currentZoom + currentPanX,
                center.y.dp.toPx() * currentZoom + currentPanY,
            )

            // 1. 星座星云软光晕
            constellation.clusters.forEach { anchor ->
                val tone = memoryCategoryColor(anchor.category, palette)
                val center = toScreen(ConstellationCenter(anchor.x, anchor.y))
                val haloPx = (anchor.spread * 1.45f).dp.toPx() * currentZoom
                if (center.x + haloPx < 0f || center.x - haloPx > size.width ||
                    center.y + haloPx < 0f || center.y - haloPx > size.height
                ) {
                    return@forEach
                }
                val clusterFocused = focusedCategory == null || focusedCategory.equals(anchor.category, ignoreCase = true)
                val alphaFactor = when {
                    selectedNode != null -> 0.45f
                    clusterFocused -> 1f
                    else -> 0.25f
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(tone.copy(alpha = 0.14f * alphaFactor), Color.Transparent),
                        center = center,
                        radius = haloPx.coerceAtLeast(1f),
                    ),
                    radius = haloPx.coerceAtLeast(1f),
                    center = center,
                )
            }

            // 2. 簇内最小生成树星座骨架连线
            constellation.skeletonEdges.forEach { (aId, bId) ->
                val aNode = nodesById[aId] ?: return@forEach
                val aPos = constellation.nodeCenters[aId]?.let(::toScreen) ?: return@forEach
                val bPos = constellation.nodeCenters[bId]?.let(::toScreen) ?: return@forEach
                val activeCluster = focusedCategory == null || focusedCategory.equals(aNode.category, ignoreCase = true)
                val skeletonAlpha = when {
                    selectedNode != null -> if (aId in neighborIds && bId in neighborIds) 0.28f else 0.05f
                    activeCluster -> 0.18f
                    else -> 0.04f
                }
                drawLine(
                    color = palette.skeletonEdge.copy(alpha = skeletonAlpha),
                    start = aPos,
                    end = bPos,
                    strokeWidth = 0.9.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            // 3. 显式关系弧线（因果/相关/包含/矛盾）
            val edgeMidPoints = mutableListOf<Triple<MemoryGraphEdge, Offset, Color>>()
            state.edges.forEach { edge ->
                val aPos = constellation.nodeCenters[edge.sourceFactId]?.let(::toScreen) ?: return@forEach
                val bPos = constellation.nodeCenters[edge.targetFactId]?.let(::toScreen) ?: return@forEach
                val dx = bPos.x - aPos.x
                val dy = bPos.y - aPos.y
                val dist = hypot(dx, dy).coerceAtLeast(0.001f)
                val curve = dist * 0.10f
                val ctrl = Offset(
                    (aPos.x + bPos.x) / 2f - dy / dist * curve,
                    (aPos.y + bPos.y) / 2f + dx / dist * curve,
                )
                val midOnCurve = Offset(
                    0.25f * aPos.x + 0.5f * ctrl.x + 0.25f * bPos.x,
                    0.25f * aPos.y + 0.5f * ctrl.y + 0.25f * bPos.y,
                )
                val isContradiction = edge.relationType.equals("contradicts", ignoreCase = true)
                val isCauses = edge.relationType.equals("causes", ignoreCase = true)
                val relColor = memoryRelationColor(edge.relationType, palette)
                val isIncident = selectedNodeId != null &&
                    (edge.sourceFactId == selectedNodeId || edge.targetFactId == selectedNodeId)
                val edgeAlpha = when {
                    selectedNodeId != null -> if (isIncident) 0.94f else 0.09f
                    focusedCategory != null -> {
                        val sCat = nodesById[edge.sourceFactId]?.category
                        val tCat = nodesById[edge.targetFactId]?.category
                        if (sCat.equals(focusedCategory, true) || tCat.equals(focusedCategory, true)) 0.72f else 0.12f
                    }
                    else -> if (edge.weight >= 0.7f) 0.58f else 0.34f
                }
                val path = Path().apply {
                    moveTo(aPos.x, aPos.y)
                    quadraticBezierTo(ctrl.x, ctrl.y, bPos.x, bPos.y)
                }
                drawPath(
                    path = path,
                    color = relColor.copy(alpha = edgeAlpha),
                    style = Stroke(
                        width = (if (isIncident) 2.2f else if (edge.weight >= 0.7f) 1.6f else 1.1f).dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = if (isContradiction) {
                            PathEffect.dashPathEffect(floatArrayOf(10f, 7f), 0f)
                        } else {
                            null
                        },
                    ),
                )
                if (isCauses && edgeAlpha > 0.25f) {
                    drawCauseArrow(midOnCurve, dx, dy, relColor.copy(alpha = edgeAlpha))
                } else if (edge.weight >= 0.7f && !isContradiction && edgeAlpha > 0.25f) {
                    drawCircle(relColor.copy(alpha = edgeAlpha), radius = 2.4.dp.toPx(), center = midOnCurve)
                }
                if (isIncident) {
                    edgeMidPoints += Triple(edge, midOnCurve, relColor)
                }
            }

            // 4. 发光星体
            nodes.forEach { node ->
                val rawCenter = constellation.nodeCenters[node.factId] ?: return@forEach
                val pos = toScreen(rawCenter)
                val degree = constellation.nodeDegrees[node.factId] ?: 0
                val radiusPx = starRadiusDp(node, degree).dp.toPx() * currentZoom.coerceIn(0.65f, 1.35f)
                if (pos.x + radiusPx * 4 < 0f || pos.x - radiusPx * 4 > size.width ||
                    pos.y + radiusPx * 4 < 0f || pos.y - radiusPx * 4 > size.height
                ) {
                    return@forEach
                }
                val tone = memoryCategoryColor(node.category, palette)
                val isSelected = selectedNodeId == node.factId
                val isLinkSource = linkSourceNode?.factId == node.factId
                val inFocus = when {
                    selectedNodeId != null -> node.factId in neighborIds
                    focusedCategory != null -> node.category.equals(focusedCategory, ignoreCase = true)
                    else -> true
                }
                val dim = if (inFocus) 1f else 0.20f

                drawCircle(tone.copy(alpha = 0.14f * dim), radius = radiusPx * 2.5f, center = pos)
                drawCircle(tone.copy(alpha = 0.28f * dim), radius = radiusPx * 1.65f, center = pos)
                drawCircle(tone.copy(alpha = 0.94f * dim), radius = radiusPx, center = pos)
                drawCircle(palette.starCore.copy(alpha = 0.85f * dim), radius = radiusPx * 0.38f, center = pos)

                if (node.isPinned) {
                    drawStarFlare(pos, radiusPx * 2.55f, tone.copy(alpha = 0.85f * dim))
                }
                if (isSelected) {
                    drawCircle(
                        color = palette.selectionGlow,
                        radius = radiusPx * 1.65f,
                        center = pos,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                } else if (isLinkSource) {
                    drawCircle(
                        color = palette.edgeCauses,
                        radius = radiusPx * 1.85f,
                        center = pos,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                        ),
                    )
                }
            }

            // 5. 焦点模式：邻边中点关系标签
            edgeMidPoints.forEach { (edge, mid, relColor) ->
                val relText = relationLabels[edge.relationType.lowercase()] ?: relationLabels["related_to"].orEmpty()
                if (relText.isNotEmpty()) {
                    drawRelationBadge(
                        text = relText,
                        center = mid,
                        textColor = relColor,
                        bgColor = palette.labelBg,
                        borderColor = relColor.copy(alpha = 0.55f),
                        paint = relationPaint,
                        density = density,
                    )
                }
            }

            // 6. 星群标题徽标（记录为障碍物，避免节点标签与之重叠）
            val obstacles = mutableListOf<ScreenLabelBox>()
            if (selectedNodeId == null) {
                constellation.clusters.forEach { anchor ->
                    val clusterVisible = focusedCategory == null || focusedCategory.equals(anchor.category, ignoreCase = true)
                    if (!clusterVisible) return@forEach
                    val label = clusterLabels[anchor.category] ?: anchor.category
                    val anchorPos = toScreen(ConstellationCenter(anchor.x, anchor.y))
                    val labelY = anchorPos.y - (anchor.spread * 0.88f).dp.toPx() * currentZoom
                    if (anchorPos.x in -80f..(size.width + 80f) && labelY in 40.dp.toPx()..(size.height - 60.dp.toPx())) {
                        val tone = memoryCategoryColor(anchor.category, palette)
                        val box = drawClusterBadge(
                            id = -(anchor.category.hashCode().toLong()),
                            text = label,
                            center = Offset(anchorPos.x, labelY),
                            textColor = palette.clusterLabelText,
                            bgColor = palette.clusterLabelBg,
                            borderColor = tone.copy(alpha = 0.5f),
                            paint = clusterPaint,
                            density = density,
                        )
                        obstacles += box
                    }
                }
            }

            // 7. 节点标签（碰撞避让 + 缩放分层数量限制）
            val labelMaxCount = when {
                selectedNodeId != null -> 14
                currentZoom < 0.65f -> 10
                currentZoom < 1.1f -> 22
                else -> 46
            }
            val topMarginPx = 64.dp.toPx()
            val bottomReservePx = if (selectedNode != null) 250.dp.toPx() else 64.dp.toPx()
            val padH = 16.dp.toPx()
            val chipH = labelPaint.textSize + 10.dp.toPx()
            val maxChipW = 152.dp.toPx()

            val candidates = nodes.mapNotNull { node ->
                val isSelected = node.factId == selectedNodeId
                val isNeighbor = selectedNodeId != null && node.factId in neighborIds
                if (selectedNodeId != null && !isNeighbor) return@mapNotNull null
                if (focusedCategory != null && !node.category.equals(focusedCategory, ignoreCase = true) && !isSelected) {
                    return@mapNotNull null
                }
                val degree = constellation.nodeDegrees[node.factId] ?: 0
                val showAtCurrentZoom = isSelected || isNeighbor || node.isPinned || node.importance >= 2 ||
                    currentZoom >= 0.68f || (currentZoom >= 0.50f && node.importance >= 1)
                if (!showAtCurrentZoom) return@mapNotNull null

                val center = constellation.nodeCenters[node.factId] ?: return@mapNotNull null
                val pos = toScreen(center)
                if (pos.x < 12.dp.toPx() || pos.x > size.width - 12.dp.toPx() ||
                    pos.y < topMarginPx || pos.y > size.height - bottomReservePx
                ) {
                    return@mapNotNull null
                }
                val radiusPx = starRadiusDp(node, degree).dp.toPx() * currentZoom.coerceIn(0.65f, 1.35f)
                val display = ellipsizeText(node.title, labelPaint, maxChipW - padH)
                val chipW = labelPaint.measureText(display) + padH
                val priority = (if (isSelected) 10_000 else 0) +
                    (if (isNeighbor) 4_000 else 0) +
                    (if (node.isPinned) 1_000 else 0) +
                    node.importance * 200 +
                    degree * 40
                LabelCandidate(
                    node = node,
                    starScreenX = pos.x,
                    starScreenY = pos.y,
                    starRadiusPx = radiusPx,
                    chipWidthPx = chipW,
                    chipHeightPx = chipH,
                    priority = priority,
                )
            }

            val placed = placeConstellationLabels(candidates, obstacles, labelMaxCount)
            placed.forEach { item ->
                val isSelected = item.node.factId == selectedNodeId
                val tone = memoryCategoryColor(item.node.category, palette)
                val display = ellipsizeText(item.node.title, labelPaint, maxChipW - padH)
                drawRoundRect(
                    color = if (isSelected) tone.copy(alpha = 0.26f) else palette.labelBg,
                    topLeft = Offset(item.box.left, item.box.top),
                    size = Size(item.box.right - item.box.left, item.box.bottom - item.box.top),
                    cornerRadius = CornerRadius(chipH / 2f, chipH / 2f),
                )
                drawRoundRect(
                    color = if (isSelected) tone else palette.labelBorder,
                    topLeft = Offset(item.box.left, item.box.top),
                    size = Size(item.box.right - item.box.left, item.box.bottom - item.box.top),
                    cornerRadius = CornerRadius(chipH / 2f, chipH / 2f),
                    style = Stroke(width = if (isSelected) 1.2.dp.toPx() else 0.7.dp.toPx()),
                )
                labelPaint.color = palette.labelText.toArgbInt()
                drawContext.canvas.nativeCanvas.drawText(
                    display,
                    item.box.left + padH / 2f,
                    (item.box.top + item.box.bottom) / 2f + labelPaint.textSize / 3f,
                    labelPaint,
                )
            }
        }

        // 顶部悬浮栏：分类星群 Chip + 观星台控制按钮
        ConstellationTopOverlay(
            clusters = constellation.clusters,
            focusedCategory = focusedCategory,
            palette = palette,
            useObservatorySky = useObservatorySky,
            isFullscreen = isFullscreen,
            onSelectCategory = { cat ->
                focusedCategory = cat
                selectedNodeId = null
                if (cat == null) {
                    val bounds = computeConstellationBounds(constellation.nodeCenters.values)
                    frameBoundsImmediate(bounds, maxZoomCap = 1.08f)
                } else {
                    focusBoundsForCategory(constellation, nodes, cat)?.let { bounds ->
                        frameBoundsImmediate(bounds, maxZoomCap = 1.85f)
                    }
                }
            },
            onToggleSkyTheme = { useObservatorySky = !useObservatorySky },
            onRebuildEdges = onRebuildEdges,
            onOpenSettings = onOpenSettings,
            onToggleFullscreen = onToggleFullscreen,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // 连线模式引导条
        linkSourceNode?.let { src ->
            ConstellationLinkBanner(
                sourceNode = src,
                palette = palette,
                onCancel = { linkSourceNode = null },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp),
            )
        }

        // 底部控件：未选中星体时展示统计与缩放按钮；选中时展示详细星体检视卡片
        AnimatedVisibility(
            visible = selectedNode == null,
            enter = fadeIn(MuseMotion.tween(MuseAnimation.FAST_MS)),
            exit = fadeOut(MuseMotion.tween(MuseAnimation.FAST_MS)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ConstellationBottomControls(
                nodeCount = nodes.size,
                edgeCount = state.edges.size,
                palette = palette,
                onZoomIn = { zoomAroundViewportCenter(1.35f) },
                onZoomOut = { zoomAroundViewportCenter(0.74f) },
                onResetView = resetView,
            )
        }

        selectedNode?.let { node ->
            val related = state.edges.filter {
                it.sourceFactId == node.factId || it.targetFactId == node.factId
            }
            ConstellationNodeInspectorCard(
                node = node,
                fullContent = fullContentCache,
                loadingFullContent = loadingFullContent,
                nodesById = nodesById,
                relatedEdges = related,
                palette = palette,
                canManualLink = onManualLink != null,
                onSelectNeighbor = { neighbor ->
                    selectedNodeId = neighbor.factId
                    focusOnStar(neighbor)
                },
                onEdgeAction = onEdgeAction,
                onStartLink = {
                    linkSourceNode = node
                    selectedNodeId = null
                },
                onNodeAction = onNodeAction,
                onClose = { selectedNodeId = null },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // 手动连线关系类型选择弹窗
        val srcNode = linkSourceNode
        val tgtNode = linkTargetNode
        if (onManualLink != null && srcNode != null && tgtNode != null) {
            io.zer0.muse.ui.common.feedback.MuseDialog(
                onDismissRequest = { linkTargetNode = null },
                title = stringResource(R.string.memory_graph_link_type_title),
                dismissText = stringResource(R.string.common_cancel),
                onDismiss = { linkTargetNode = null },
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm)) {
                        listOf(
                            "related_to" to R.string.memory_graph_link_type_related,
                            "causes" to R.string.memory_graph_link_type_causes,
                            "part_of" to R.string.memory_graph_link_type_part_of,
                            "contradicts" to R.string.memory_graph_link_type_contradicts,
                        ).forEach { (type, labelRes) ->
                            MuseCapsuleButton(
                                text = stringResource(labelRes),
                                onClick = {
                                    onManualLink(srcNode, tgtNode, type)
                                    linkSourceNode = null
                                    linkTargetNode = null
                                    selectedNodeId = tgtNode.factId
                                },
                                variant = IosCapsuleButtonVariant.Text,
                                fillWidth = true,
                            )
                        }
                    }
                },
            )
        }
    }
}

private fun starRadiusDp(node: MemoryGraphNode, degree: Int): Float = when {
    node.isPinned -> 14.5f
    node.importance >= 2 -> 13.5f
    node.importance == 1 || degree >= 3 -> 11f
    else -> 8.5f
}

private fun DrawScope.drawStarfieldBackdrop(palette: MuseConstellationPalette, canvasSize: Size) {
    drawRect(color = palette.canvasBg)
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(palette.canvasNebulaPrimary.copy(alpha = 0.24f), Color.Transparent),
            center = Offset(canvasSize.width * 0.28f, canvasSize.height * 0.30f),
            radius = maxOf(canvasSize.width, canvasSize.height) * 0.65f,
        ),
    )
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(palette.canvasNebulaSecondary.copy(alpha = 0.18f), Color.Transparent),
            center = Offset(canvasSize.width * 0.76f, canvasSize.height * 0.72f),
            radius = maxOf(canvasSize.width, canvasSize.height) * 0.55f,
        ),
    )
    for (i in 0 until STARDUST_COUNT) {
        val fx = (((i * 73 + 19) % 997) / 997f) * canvasSize.width
        val fy = (((i * 137 + 43) % 991) / 991f) * canvasSize.height
        val r = (0.7f + (i % 3) * 0.45f).dp.toPx()
        val a = 0.08f + (i % 4) * 0.045f
        drawCircle(
            color = palette.stardust.copy(alpha = a),
            radius = r,
            center = Offset(fx, fy),
        )
    }
}

private fun DrawScope.drawStarFlare(center: Offset, armPx: Float, color: Color) {
    val corePx = armPx * 0.18f
    val path = Path().apply {
        moveTo(center.x, center.y - armPx)
        quadraticBezierTo(center.x, center.y, center.x + corePx, center.y - corePx)
        lineTo(center.x + armPx, center.y)
        quadraticBezierTo(center.x, center.y, center.x + corePx, center.y + corePx)
        lineTo(center.x, center.y + armPx)
        quadraticBezierTo(center.x, center.y, center.x - corePx, center.y + corePx)
        lineTo(center.x - armPx, center.y)
        quadraticBezierTo(center.x, center.y, center.x - corePx, center.y - corePx)
        close()
    }
    drawPath(path = path, color = color)
}

private fun DrawScope.drawCauseArrow(mid: Offset, dx: Float, dy: Float, color: Color) {
    val angle = atan2(dy, dx)
    val arrowLen = 7.dp.toPx()
    val spread = (PI / 5.5).toFloat()
    val left = Offset(
        mid.x - cos(angle - spread) * arrowLen,
        mid.y - sin(angle - spread) * arrowLen,
    )
    val right = Offset(
        mid.x - cos(angle + spread) * arrowLen,
        mid.y - sin(angle + spread) * arrowLen,
    )
    drawLine(color, mid, left, strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, mid, right, strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
}

private fun DrawScope.drawRelationBadge(
    text: String,
    center: Offset,
    textColor: Color,
    bgColor: Color,
    borderColor: Color,
    paint: android.graphics.Paint,
    density: Density,
) {
    val padH = with(density) { 10.dp.toPx() }
    val w = paint.measureText(text) + padH
    val h = paint.textSize + with(density) { 7.dp.toPx() }
    val left = center.x - w / 2f
    val top = center.y - h / 2f
    drawRoundRect(
        color = bgColor,
        topLeft = Offset(left, top),
        size = Size(w, h),
        cornerRadius = CornerRadius(h / 2f, h / 2f),
    )
    drawRoundRect(
        color = borderColor,
        topLeft = Offset(left, top),
        size = Size(w, h),
        cornerRadius = CornerRadius(h / 2f, h / 2f),
        style = Stroke(width = 0.8.dp.toPx()),
    )
    paint.color = textColor.toArgbInt()
    drawContext.canvas.nativeCanvas.drawText(
        text,
        center.x,
        top + h / 2f + paint.textSize / 3f,
        paint,
    )
}

private fun DrawScope.drawClusterBadge(
    id: Long,
    text: String,
    center: Offset,
    textColor: Color,
    bgColor: Color,
    borderColor: Color,
    paint: android.graphics.Paint,
    density: Density,
): ScreenLabelBox {
    val padH = with(density) { 16.dp.toPx() }
    val w = paint.measureText(text) + padH
    val h = paint.textSize + with(density) { 10.dp.toPx() }
    val left = center.x - w / 2f
    val top = center.y - h / 2f
    drawRoundRect(
        color = bgColor,
        topLeft = Offset(left, top),
        size = Size(w, h),
        cornerRadius = CornerRadius(h / 2f, h / 2f),
    )
    drawRoundRect(
        color = borderColor,
        topLeft = Offset(left, top),
        size = Size(w, h),
        cornerRadius = CornerRadius(h / 2f, h / 2f),
        style = Stroke(width = 0.8.dp.toPx()),
    )
    paint.color = textColor.toArgbInt()
    drawContext.canvas.nativeCanvas.drawText(
        text,
        center.x,
        top + h / 2f + paint.textSize / 3f,
        paint,
    )
    return ScreenLabelBox(id = id, left = left, top = top, right = left + w, bottom = top + h)
}

private fun ellipsizeText(text: String, paint: android.graphics.Paint, maxWidthPx: Float): String {
    if (text.isBlank() || paint.measureText(text) <= maxWidthPx) return text
    val ellipsis = "…"
    var cut = text.length
    while (cut > 0 && paint.measureText(text.substring(0, cut) + ellipsis) > maxWidthPx) {
        cut--
    }
    return if (cut <= 0) ellipsis else text.substring(0, cut) + ellipsis
}

private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)

enum class NodeAction { EDIT, DELETE, PIN, LINK }
enum class EdgeAction { CONFIRM, DELETE }
