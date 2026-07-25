package io.zer0.muse.ui.workflow

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.tools.DelegationContract
import io.zer0.muse.ui.common.EmptyState
import io.zer0.muse.ui.common.MuseToast
import kotlinx.coroutines.launch

/**
 * 工作流画布的缩放与平移状态。
 *
 * 画布坐标系:原点在画布左上角,x 向右、y 向下。
 * [scale] 为缩放系数,[offset] 为平移偏移(px)。
 * 屏幕坐标 → 画布坐标:`canvas = (screen - offset) / scale`。
 */
class WorkflowViewportState {
    /** 缩放系数,1f = 原始大小。 */
    var scale: Float by mutableStateOf(1f)
        internal set
    /** 平移偏移(px)。 */
    var offset: Offset by mutableStateOf(Offset.Zero)
        internal set

    /** 屏幕坐标转画布坐标。 */
    fun toCanvasCoord(screen: Offset): Offset =
        Offset((screen.x - offset.x) / scale, (screen.y - offset.y) / scale)
}

/** 创建并记住一个 [WorkflowViewportState]。 */
@Composable
fun rememberWorkflowViewportState(): WorkflowViewportState = remember { WorkflowViewportState() }

/** 缩放上下限常量(与下方 detectTransformGestures 中的 coerceIn 保持一致)。 */
private const val MIN_SCALE = 0.4f
private const val MAX_SCALE = 2.5f
/** 缩放极限提示的去抖间隔(ms),避免捏合过程中反复弹 toast。 */
private const val ZOOM_TOAST_DEBOUNCE_MS = 800L

/**
 * 幽灵节点:已被 ViewModel 删除、但仍在播放淡出退场动画的节点。
 *
 * 因为 VM 的 [WorkflowEditorViewModel.removeNode] 会立即从 nodes/positions 中移除节点,
 * 画布无法直接拿到已删除节点的数据,所以本地维护 [nodeCache]/[posCache] 缓存,
 * 在检测到节点被移除时取出最后已知数据,渲染一个 scale 1→0 + alpha 1→0 的幽灵。
 */
private class GhostNode(
    val id: String,
    val node: DelegationContract.TeamWorkflowNode,
    val position: Offset,
    /** 退场动画进度:1f(完全可见) → 0f(完全消失)。 */
    val anim: Animatable<Float, AnimationVector1D> = Animatable(1f),
)

/**
 * 工作流可视化编排画布(可复用 Composable)。
 *
 * 渲染层级(从下到上):
 *  1. 网格背景(点阵)
 *  2. 连线(贝塞尔曲线,从 dependsOn 节点指向当前节点,带箭头 + 新建动画)
 *  3. 连线拖拽预览(从节点连接手柄到当前指针位置)
 *  4. 节点卡片(圆角矩形,显示 assistantId/name/taskTemplate 摘要)
 *  5. 幽灵节点(已删除节点的淡出残影)
 *
 * 交互手势:
 *  - 双指捏合缩放 + 单指拖拽平移画布(detectTransformGestures),触达极限时 toast 提示
 *  - 双击空白处 → [onAddNodeAt](传入画布坐标)
 *  - 单击空白处 → [onBackgroundTap](取消选中)
 *  - 单击节点 → [onNodeClick]
 *  - 长按节点 → [onNodeLongClick](弹出编辑/删除/复制菜单)
 *  - 拖拽节点本体 → [onNodeDrag](实时更新位置),拖拽中本节点放大浮起、其他节点降透明
 *  - 从节点右侧连接手柄拖出 → 拖到目标节点上释放建立 dependsOn([onConnect])
 *
 * @param nodes 工作流节点列表
 * @param positions 节点 id → 画布坐标
 * @param selectedNodeId 当前选中节点 id
 * @param viewport 缩放/平移状态
 * @param onAddNodeAt 双击空白时回调,参数为画布坐标
 * @param onBackgroundTap 单击空白时回调
 * @param onNodeClick 单击节点
 * @param onNodeLongClick 长按节点
 * @param onNodeDrag 节点拖拽,参数为(节点 id, 画布坐标系下的累计位移)
 * @param onConnect 从 from 节点拖连线到 to 节点
 */
@Composable
fun WorkflowCanvas(
    nodes: List<DelegationContract.TeamWorkflowNode>,
    positions: Map<String, Offset>,
    selectedNodeId: String?,
    viewport: WorkflowViewportState,
    modifier: Modifier = Modifier,
    onAddNodeAt: (Offset) -> Unit = {},
    onBackgroundTap: () -> Unit = {},
    onNodeClick: (String) -> Unit = {},
    onNodeLongClick: (String) -> Unit = {},
    onNodeDrag: (String, Offset) -> Unit = { _, _ -> },
    onConnect: (String, String) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    // 节点卡片尺寸(dp),与 NodeCard 内部布局保持一致
    val nodeWidthDp = 160.dp
    val nodeHeightDp = 88.dp
    val nodeWidthPx = with(density) { nodeWidthDp.toPx() }
    val nodeHeightPx = with(density) { nodeHeightDp.toPx() }

    // 连线拖拽中的临时状态:fromNodeId → 当前指针画布坐标
    var connectionDrag by remember { mutableStateOf<Pair<String, Offset>?>(null) }

    // ---- 交互增强状态 ----
    /** 当前正在被拖拽的节点 id;非空时本节点放大浮起、其他节点降透明突出当前操作。 */
    var draggedNodeId by remember { mutableStateOf<String?>(null) }
    /** 缩放极限 toast 的上次触发时间戳,用于去抖。 */
    var lastZoomToastAt by remember { mutableStateOf(0L) }
    /** 首帧初始化标记:初始加载的节点不播放入场动画(只有后续新增的"新节点"才动画)。 */
    var initialized by remember { mutableStateOf(false) }

    /** 连线动画进度缓存:key = "$from->$to" → 进度 Animatable(0f→1f)。 */
    val connectionAnimMap = remember { mutableStateMapOf<String, Animatable<Float, AnimationVector1D>>() }
    /** 连线动画初始化标记:初始加载的连线直接 snap 到 1f(不播放绘制动画),仅后续新增连线才动画。 */
    var connectionsInitialized by remember { mutableStateOf(false) }

    /** 节点数据缓存(用于删除时取出最后已知节点信息渲染幽灵)。 */
    val nodeCache = remember { mutableStateMapOf<String, DelegationContract.TeamWorkflowNode>() }
    /** 节点位置缓存(用于删除时取出最后已知位置渲染幽灵)。 */
    val posCache = remember { mutableStateMapOf<String, Offset>() }
    /** 幽灵节点列表(正在播放淡出动画的已删除节点)。 */
    val ghosts = remember { mutableStateListOf<GhostNode>() }
    /** 上一帧节点 id 集合,用于检测新增/删除。 */
    val prevIds = remember { mutableStateOf<Set<String>>(emptySet()) }

    // 每帧用当前 nodes/positions 刷新缓存;被删除的 id 不在当前集合中,
    // 其缓存条目自然保留为"上一帧的值",供幽灵渲染取用。
    nodes.forEach { nodeCache[it.id] = it }
    positions.forEach { (id, p) -> posCache[id] = p }

    /** 当前所有连线 key 集合(用于检测新增/移除连线)。 */
    val connectionKeys = remember(nodes) {
        buildSet {
            nodes.forEach { node ->
                node.dependsOn.forEach { from -> add("$from->${node.id}") }
            }
        }
    }

    // 连线动画:新建依赖时,连线从源节点动画绘制到目标节点(0→1)
    // 初始加载的连线直接 snap 到 1f,不播放绘制动画(与节点入场策略一致)
    LaunchedEffect(connectionKeys) {
        val firstLoad = !connectionsInitialized
        connectionsInitialized = true
        connectionKeys.forEach { k ->
            if (k !in connectionAnimMap) {
                val a = Animatable(0f)
                connectionAnimMap[k] = a
                launch {
                    if (firstLoad) a.snapTo(1f) else a.animateTo(1f, tween(450))
                }
            }
        }
        // 连线随节点删除直接移除(节点本身已有幽灵淡出,连线不再做退场动画)
        (connectionAnimMap.keys - connectionKeys).forEach { connectionAnimMap.remove(it) }
    }

    // 节点新增/删除检测:新增节点通过 NodeCard 自带的入场动画处理;删除节点生成幽灵淡出
    LaunchedEffect(nodes.map { it.id }) {
        val currentIds = nodes.map { it.id }.toSet()
        if (!initialized) {
            // 首帧:记录现有节点,不播放入场/退场动画
            prevIds.value = currentIds
            initialized = true
        } else {
            // 被删除的 id → 取出缓存数据生成幽灵,播放 scale 1→0 + alpha 1→0
            val removed = prevIds.value - currentIds
            removed.forEach { id ->
                val nd = nodeCache[id]
                val ps = posCache[id]
                if (nd != null && ps != null) {
                    val g = GhostNode(id, nd, ps)
                    ghosts.add(g)
                    launch {
                        g.anim.animateTo(0f, tween(220))
                        ghosts.remove(g)
                        // 清理缓存,避免内存中残留已彻底消失的节点
                        nodeCache.remove(id)
                        posCache.remove(id)
                    }
                } else {
                    nodeCache.remove(id)
                    posCache.remove(id)
                }
            }
            prevIds.value = currentIds
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.surfaceVariant.copy(alpha = 0.3f))
            // 1. 缩放 + 平移(双指捏合 / 单指拖拽空白区域)
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    // 以双指中心为锚点缩放,保持该点在画布坐标系不动
                    val rawScale = viewport.scale * zoom
                    val newScale = rawScale.coerceIn(MIN_SCALE, MAX_SCALE)
                    // 缩放被钳制到极限 → 提示(去抖,避免捏合过程反复弹出)
                    if (newScale != rawScale) {
                        val now = System.currentTimeMillis()
                        if (now - lastZoomToastAt > ZOOM_TOAST_DEBOUNCE_MS) {
                            MuseToast.show(context.getString(R.string.workflow_zoom_limit_reached))
                            lastZoomToastAt = now
                        }
                    }
                    val actualZoom = newScale / viewport.scale
                    viewport.offset = Offset(
                        x = centroid.x - (centroid.x - viewport.offset.x) * actualZoom + pan.x,
                        y = centroid.y - (centroid.y - viewport.offset.y) * actualZoom + pan.y,
                    )
                    viewport.scale = newScale
                }
            }
            // 2. 双击空白添加节点 / 单击空白取消选中
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        val canvasCoord = viewport.toCanvasCoord(tap)
                        onAddNodeAt(canvasCoord)
                    },
                    onTap = { onBackgroundTap() },
                )
            },
    ) {
        // ---- 底层:网格 + 连线(用 Canvas 绘制) ----
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 网格在屏幕坐标系绘制(内部已用 viewport.offset/scale 手动换算)
            drawGrid(viewport, colorScheme.outline.copy(alpha = 0.25f))

            // 连线统一在画布坐标系绘制,用 withTransform 应用一次 viewport 变换,
            // 避免 drawContext.transform.translate/scale 在循环中累积。
            withTransform({
                translate(viewport.offset.x, viewport.offset.y)
                scale(viewport.scale, viewport.scale)
            }) {
                // 绘制 dependsOn 连线(从 from 节点右侧 → to 节点左侧)
                // 新建连线带 0→1 绘制动画 + 末端箭头标识方向
                nodes.forEach { node ->
                    val toCenter = positions[node.id] ?: return@forEach
                    node.dependsOn.forEach { fromId ->
                        val fromCenter = positions[fromId] ?: return@forEach
                        val k = "$fromId->${node.id}"
                        val progress = connectionAnimMap[k]?.value ?: 1f
                        val start = Offset(fromCenter.x + nodeWidthPx, fromCenter.y + nodeHeightPx / 2f)
                        val end = Offset(toCenter.x, toCenter.y + nodeHeightPx / 2f)
                        drawAnimatedConnection(
                            start = start,
                            end = end,
                            color = colorScheme.primary.copy(alpha = 0.6f),
                            strokeWidth = if (node.id == selectedNodeId) 2.5.dp.toPx() else 2.dp.toPx(),
                            progress = progress,
                        )
                    }
                }

                // 绘制连线拖拽预览(虚线)
                connectionDrag?.let { (fromId, currentCanvas) ->
                    val fromCenter = positions[fromId] ?: return@let
                    val start = Offset(fromCenter.x + nodeWidthPx, fromCenter.y + nodeHeightPx / 2f)
                    val path = bezierPath(start, currentCanvas)
                    drawPath(
                        path = path,
                        color = colorScheme.tertiary,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                            cap = StrokeCap.Round,
                        ),
                    )
                }
            }
        }

        // ---- 空状态引导:画布为空时居中提示 ----
        // EmptyState 无 pointerInput,触摸事件会穿透到父 Box 的双击/单击手势,不影响添加节点
        if (nodes.isEmpty() && ghosts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = Icons.Outlined.Add,
                    title = stringResource(R.string.workflow_canvas_empty_title),
                    subtitle = stringResource(R.string.workflow_canvas_empty_subtitle),
                )
            }
        }

        // ---- 上层:节点卡片 ----
        nodes.forEach { node ->
            val pos = positions[node.id] ?: return@forEach
            val isSelected = node.id == selectedNodeId
            // 节点位置应用 viewport 变换:screen = canvas * scale + offset
            val screenX = with(density) { (pos.x * viewport.scale + viewport.offset.x).toDp() }
            val screenY = with(density) { (pos.y * viewport.scale + viewport.offset.y).toDp() }
            // 节点尺寸随缩放变化
            val scaledWidth = nodeWidthDp * viewport.scale
            val scaledHeight = nodeHeightDp * viewport.scale

            // 用 node.id 作为 key,保证新增/删除节点时各 NodeCard 的入场动画状态正确绑定
            key(node.id) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .absoluteOffset(x = screenX, y = screenY),
                ) {
                    NodeCard(
                        node = node,
                        selected = isSelected,
                        widthDp = scaledWidth,
                        heightDp = scaledHeight,
                        // 手柄视觉半径随缩放变化,但不小于 4dp 以保证可触控
                        handleRadiusDp = (8.dp * viewport.scale).coerceAtLeast(4.dp),
                        // 手柄在画布坐标系下的位置(节点右侧中点),用于连线拖拽坐标换算
                        handleCanvasPos = Offset(pos.x + nodeWidthPx, pos.y + nodeHeightPx / 2f),
                        // 缩放系数:手柄本地坐标增量需除以 scale 才得到画布坐标增量
                        viewportScale = viewport.scale,
                        // 当前正在拖拽的节点 id(用于本节点放大浮起 + 其他节点降透明)
                        draggedNodeId = draggedNodeId,
                        // 是否播放入场动画:仅首帧之后新增的节点才播放(初始加载节点跳过)
                        animateAppear = initialized,
                        onClick = { onNodeClick(node.id) },
                        onLongClick = { onNodeLongClick(node.id) },
                        onDragStart = { draggedNodeId = node.id },
                        onDragEnd = { draggedNodeId = null },
                        onDrag = { drag ->
                            // 把屏幕坐标位移转换为画布坐标位移
                            val canvasDrag = Offset(drag.x / viewport.scale, drag.y / viewport.scale)
                            onNodeDrag(node.id, canvasDrag)
                        },
                        onConnectionDragStart = { canvasPos ->
                            connectionDrag = node.id to canvasPos
                        },
                        onConnectionDrag = { canvasPos ->
                            connectionDrag = node.id to canvasPos
                        },
                        onConnectionDragEnd = { canvasPos ->
                            // 命中检测:找到指针落在哪个节点矩形内(画布坐标系)
                            val hit = nodes.firstOrNull { n ->
                                val p = positions[n.id] ?: return@firstOrNull false
                                canvasPos.x in p.x..(p.x + nodeWidthPx) &&
                                    canvasPos.y in p.y..(p.y + nodeHeightPx)
                            }
                            if (hit != null && hit.id != node.id) {
                                onConnect(node.id, hit.id)
                            }
                            connectionDrag = null
                        },
                    )
                }
            }
        }

        // ---- 幽灵节点:已删除节点的淡出残影(scale 1→0 + alpha 1→0) ----
        ghosts.forEach { ghost ->
            val g = ghost
            val screenX = with(density) { (g.position.x * viewport.scale + viewport.offset.x).toDp() }
            val screenY = with(density) { (g.position.y * viewport.scale + viewport.offset.y).toDp() }
            val scaledWidth = nodeWidthDp * viewport.scale
            val scaledHeight = nodeHeightDp * viewport.scale
            key("ghost-${g.id}") {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .absoluteOffset(x = screenX, y = screenY)
                        // 退场动画:scale + alpha 同步从 1→0
                        .graphicsLayer {
                            scaleX = g.anim.value
                            scaleY = g.anim.value
                            alpha = g.anim.value
                        },
                ) {
                    GhostNodeCard(
                        node = g.node,
                        widthDp = scaledWidth,
                        heightDp = scaledHeight,
                    )
                }
            }
        }
    }
}

// =====================================================================
// 节点卡片
// =====================================================================

/**
 * 单个节点卡片 Composable。
 *
 * 圆角矩形卡片,显示:助手图标 + name + assistantId + taskTemplate 摘要(单行)。
 * 右侧带一个圆形连接手柄,拖拽该手柄可向其它节点拉出 dependsOn 连线。
 *
 * 交互增强:
 *  - 入场动画:首次组合时 scale 0→1 + alpha 0→1(从中心放大出现,仅新增节点播放)
 *  - 选中:边框高亮(primary 色)+ 阴影提升
 *  - 拖拽:本节点放大 1.05x + 阴影增强(浮起感),释放时弹簧回弹;其他节点降透明 0.6
 *
 * 手势:
 *  - 单击 → [onClick](选中)
 *  - 长按 → [onLongClick](弹出菜单)
 *  - 拖拽卡片本体 → [onDrag](移动节点,参数为屏幕坐标系下的位移增量)
 *  - 拖拽连接手柄 → 建立连线(回调传屏幕坐标)
 *
 * @param animateAppear 是否播放入场动画;true 时从 scale 0 + alpha 0 弹入
 */
@Composable
private fun NodeCard(
    node: DelegationContract.TeamWorkflowNode,
    selected: Boolean,
    widthDp: Dp,
    heightDp: Dp,
    handleRadiusDp: Dp,
    handleCanvasPos: Offset,
    viewportScale: Float,
    draggedNodeId: String?,
    animateAppear: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDrag: (Offset) -> Unit,
    onConnectionDragStart: (Offset) -> Unit,
    onConnectionDrag: (Offset) -> Unit,
    onConnectionDragEnd: (Offset) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    // ---- 入场动画:首次组合时 scale 0→1 + alpha 0→1(从中心放大出现) ----
    // shouldAnimateAppear 只在首次组合时捕获一次,避免后续 initialized 翻转导致已显示节点重播
    val shouldAnimateAppear = remember(node.id) { animateAppear }
    val appearAnim = remember(node.id) { Animatable(if (shouldAnimateAppear) 0f else 1f) }
    LaunchedEffect(node.id) {
        if (shouldAnimateAppear) {
            appearAnim.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
            )
        }
    }

    // ---- 拖拽手感:本节点拖拽时 1.05x + 浮起,释放时弹簧回弹 ----
    val isThisDragging = node.id == draggedNodeId
    // 其他节点正在被拖拽时,本节点降透明以突出当前操作
    val isOtherDragging = draggedNodeId != null && draggedNodeId != node.id
    val dragScale by animateFloatAsState(
        targetValue = if (isThisDragging) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "node_drag_scale",
    )
    val dimAlpha by animateFloatAsState(
        targetValue = if (isOtherDragging) 0.6f else 1f,
        animationSpec = tween(150),
        label = "node_dim_alpha",
    )

    // ---- 选中视觉:边框高亮(primary 色)+ 阴影提升;未选中无阴影 ----
    val borderColor = if (selected) colorScheme.primary else colorScheme.outline.copy(alpha = 0.4f)
    val borderWidth = if (selected) 2.dp else 1.dp
    val shadowElevation = when {
        isThisDragging -> 12.dp   // 拖拽中:强浮起,模拟"拿起来"的悬浮感
        selected -> 6.dp          // 选中:轻微阴影提升
        else -> 0.dp              // 普通:无阴影(扁平)
    }

    Box(
        modifier = Modifier.graphicsLayer {
            // 入场 scale 与拖拽 scale 相乘;alpha 由入场 + 拖拽降透明共同决定
            scaleX = appearAnim.value * dragScale
            scaleY = appearAnim.value * dragScale
            alpha = appearAnim.value * dimAlpha
        },
    ) {
        Surface(
            modifier = Modifier
                .size(width = widthDp, height = heightDp)
                // 单击 + 长按(用 detectTapGestures)
                .pointerInput(node.id) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = { onLongClick() },
                    )
                }
                // 拖拽移动(独立 pointerInput,与 tap 配合:移动超过 slop 后由 drag 接管)
                .pointerInput(node.id) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount)
                        },
                    )
                },
            shape = RoundedCornerShape(12.dp),
            color = colorScheme.surface,
            shadowElevation = shadowElevation,
            border = BorderStroke(borderWidth, borderColor),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                // 第一行:助手图标 + 节点名称
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.AccountCircle,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = node.name.ifBlank { "未命名节点" },
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.height(4.dp))
                // 第二行:assistantId
                Text(
                    text = if (node.assistantId.isBlank()) "未绑定助手" else "→ ${node.assistantId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                // 第三行:taskTemplate 摘要
                Text(
                    text = node.taskTemplate.ifBlank { "(无任务模板)" },
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.outline.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 右侧连接手柄:拖拽可向其它节点拉出 dependsOn 连线
        ConnectionHandle(
            modifier = Modifier.align(Alignment.CenterEnd),
            radiusDp = handleRadiusDp,
            handleCanvasPos = handleCanvasPos,
            viewportScale = viewportScale,
            onDragStart = onConnectionDragStart,
            onDrag = onConnectionDrag,
            onDragEnd = onConnectionDragEnd,
        )
    }
}

/**
 * 幽灵节点卡片:已删除节点的轻量非交互残影,仅用于淡出动画。
 *
 * 视觉与 [NodeCard] 接近(图标 + name + assistantId),但不带任何手势与连接手柄,
 * scale/alpha 由外层 [Modifier.graphicsLayer] 驱动。
 */
@Composable
private fun GhostNodeCard(
    node: DelegationContract.TeamWorkflowNode,
    widthDp: Dp,
    heightDp: Dp,
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.size(width = widthDp, height = heightDp),
        shape = RoundedCornerShape(12.dp),
        color = colorScheme.surface,
        border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.AccountCircle,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = node.name.ifBlank { "未命名节点" },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (node.assistantId.isBlank()) "未绑定助手" else "→ ${node.assistantId}",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 节点右侧的连接手柄(圆形)。
 *
 * 拖拽该手柄时,通过 [handleCanvasPos](手柄在画布坐标系下的固定位置) +
 * 手柄本地坐标系下的位移增量,换算出指针在画布坐标系下的实时位置,
 * 回传给上层用于绘制预览线 + 命中检测。
 *
 * 这样避免了 change.position 的局部坐标空间问题(它是相对于手柄自身的)。
 *
 * @param handleCanvasPos 手柄在画布坐标系下的位置(节点右侧中点)
 */
@Composable
private fun ConnectionHandle(
    modifier: Modifier = Modifier,
    radiusDp: Dp,
    handleCanvasPos: Offset,
    viewportScale: Float,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: (Offset) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    // 拖拽起始时手柄本地坐标(用于计算位移增量)
    var dragStartLocal by remember { mutableStateOf(Offset.Zero) }
    // 拖拽过程中最新的画布坐标(用于 onDragEnd 命中检测)
    var lastCanvasPos by remember { mutableStateOf(handleCanvasPos) }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragStartLocal = offset
                        lastCanvasPos = handleCanvasPos
                        onDragStart(handleCanvasPos)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        // 屏幕增量(手柄本地坐标系下) → 画布增量(除以缩放系数)
                        val screenDelta = change.position - dragStartLocal
                        val canvasDelta = Offset(screenDelta.x / viewportScale, screenDelta.y / viewportScale)
                        val canvasPos = handleCanvasPos + canvasDelta
                        lastCanvasPos = canvasPos
                        onDrag(canvasPos)
                    },
                    onDragEnd = {
                        onDragEnd(lastCanvasPos)
                    },
                    onDragCancel = {
                        onDragEnd(lastCanvasPos)
                    },
                )
            },
    ) {
        // 用一个固定可点击区域承载手柄(避免太小难以触控)
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(radiusDp * 2)
                    .background(colorScheme.primary, RoundedCornerShape(50)),
            )
        }
    }
}

// =====================================================================
// 绘制工具:网格 / 连线 / 箭头
// =====================================================================

/**
 * 在画布上绘制点阵网格背景。
 *
 * 网格间距固定 32dp,随 [viewport] 缩放与平移。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid(
    viewport: WorkflowViewportState,
    color: Color,
) {
    val spacing = 32.dp.toPx() * viewport.scale
    if (spacing < 8f) return  // 缩太小不画,避免噪声

    val offsetX = viewport.offset.x % spacing
    val offsetY = viewport.offset.y % spacing
    val radius = (1.5f).coerceAtMost(spacing / 8f)

    var x = offsetX
    while (x < size.width) {
        var y = offsetY
        while (y < size.height) {
            drawCircle(
                color = color,
                radius = radius,
                center = Offset(x, y),
            )
            y += spacing
        }
        x += spacing
    }
}

/**
 * 绘制一条带动画进度的贝塞尔曲线连线(from → to),末端带箭头标识方向。
 *
 * - [progress] >= 1f:完整连线 + 末端箭头
 * - [progress] < 1f:用 [PathMeasure] 取路径前 progress 段绘制,并在当前末端绘制箭头,
 *   形成"连线从源节点动画绘制到目标节点"的效果(新建依赖时触发)。
 *
 * 注意:此函数假设调用方已通过 [withTransform] 应用 viewport 变换,
 * 因此 [start]/[end] 直接使用画布坐标系坐标。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAnimatedConnection(
    start: Offset,
    end: Offset,
    color: Color,
    strokeWidth: Float,
    progress: Float,
) {
    val c1 = bezierControl1(start, end)
    val c2 = bezierControl2(start, end)
    val fullPath = bezierPath(start, end)
    val p = progress.coerceIn(0f, 1f)

    // 进度为 0 时不绘制,避免动画起始帧在源节点处闪现箭头
    if (p <= 0f) return

    if (p >= 1f) {
        // 完整连线 + 末端箭头(沿末端切线方向)
        drawPath(
            path = fullPath,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
        drawArrowAt(end, cubicBezierTangent(start, c1, c2, end, 1f), color)
        return
    }

    // 动画中:取路径前 p 段绘制
    val pathMeasure = PathMeasure().apply { setPath(fullPath, false) }
    val stopLen = pathMeasure.length * p
    val segment = Path().apply { pathMeasure.getSegment(0f, stopLen, this, true) }
    drawPath(
        path = segment,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
    // 在当前末端(bezier 参数 t=p 处)绘制箭头,跟随绘制头移动
    val tipPos = cubicBezierPoint(start, c1, c2, end, p)
    val tipTangent = cubicBezierTangent(start, c1, c2, end, p)
    drawArrowAt(tipPos, tipTangent, color)
}

/**
 * 在 [pos] 处沿 [tangent] 方向绘制三角形箭头。
 *
 * @param tangent 切线向量(无需归一化,内部归一化处理);为零向量时跳过绘制
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrowAt(
    pos: Offset,
    tangent: Offset,
    color: Color,
) {
    val len = kotlin.math.hypot(tangent.x, tangent.y)
    if (len < 0.0001f) return
    val ux = tangent.x / len
    val uy = tangent.y / len
    val arrowSize = 8.dp.toPx()
    // 箭头尖端在 pos,底边在沿反方向延伸 arrowSize 处,左右各偏 arrowSize/2
    val backX = pos.x - ux * arrowSize
    val backY = pos.y - uy * arrowSize
    val perpX = -uy * (arrowSize / 2f)
    val perpY = ux * (arrowSize / 2f)
    val arrowPath = Path().apply {
        moveTo(pos.x, pos.y)
        lineTo(backX + perpX, backY + perpY)
        lineTo(backX - perpX, backY - perpY)
        close()
    }
    drawPath(path = arrowPath, color = color)
}

/**
 * 生成从 [start] 到 [end] 的三次贝塞尔曲线路径。
 *
 * 两个控制点在水平方向偏移 dx = |end.x - start.x| / 2,形成 S 形。
 */
private fun bezierPath(start: Offset, end: Offset): Path = Path().apply {
    moveTo(start.x, start.y)
    val c1 = bezierControl1(start, end)
    val c2 = bezierControl2(start, end)
    cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
}

/** 贝塞尔曲线第一控制点(与 [bezierPath] 保持一致,供箭头切线计算复用)。 */
private fun bezierControl1(start: Offset, end: Offset): Offset {
    val dx = kotlin.math.abs(end.x - start.x) / 2f
    val controlOffset = if (dx < 30f) 60f else dx
    return Offset(start.x + controlOffset, start.y)
}

/** 贝塞尔曲线第二控制点(与 [bezierPath] 保持一致,供箭头切线计算复用)。 */
private fun bezierControl2(start: Offset, end: Offset): Offset {
    val dx = kotlin.math.abs(end.x - start.x) / 2f
    val controlOffset = if (dx < 30f) 60f else dx
    return Offset(end.x - controlOffset, end.y)
}

/** 三次贝塞尔曲线在参数 t∈[0,1] 处的点坐标。 */
private fun cubicBezierPoint(p0: Offset, p1: Offset, p2: Offset, p3: Offset, t: Float): Offset {
    val mt = 1f - t
    val a = mt * mt * mt
    val b = 3f * mt * mt * t
    val c = 3f * mt * t * t
    val d = t * t * t
    return Offset(
        a * p0.x + b * p1.x + c * p2.x + d * p3.x,
        a * p0.y + b * p1.y + c * p2.y + d * p3.y,
    )
}

/** 三次贝塞尔曲线在参数 t∈[0,1] 处的切线向量(未归一化),用于计算箭头朝向。 */
private fun cubicBezierTangent(p0: Offset, p1: Offset, p2: Offset, p3: Offset, t: Float): Offset {
    val mt = 1f - t
    val a = 3f * mt * mt
    val b = 6f * mt * t
    val c = 3f * t * t
    return Offset(
        a * (p1.x - p0.x) + b * (p2.x - p1.x) + c * (p3.x - p2.x),
        a * (p1.y - p0.y) + b * (p2.y - p1.y) + c * (p3.y - p2.y),
    )
}
