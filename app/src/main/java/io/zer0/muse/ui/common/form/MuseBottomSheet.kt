package io.zer0.muse.ui.common.form

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.theme.MuseCornerRadius
import io.zer0.muse.ui.theme.MusePaddings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val BottomSheetSurfaceShape = RoundedCornerShape(
    topStart = MuseCornerRadius.HUGE.dp,
    topEnd = MuseCornerRadius.HUGE.dp,
    bottomEnd = 0.dp,
    bottomStart = 0.dp,
)

/** 计算包含底部导航安全区后的面板最大业务高度。 */
internal fun calculateBottomSheetHeight(
    maxHeight: androidx.compose.ui.unit.Dp,
    fraction: Float,
    bottomInset: androidx.compose.ui.unit.Dp,
): androidx.compose.ui.unit.Dp =
    ((maxHeight - bottomInset).coerceAtLeast(0.dp) * fraction.coerceIn(0f, 1f)).coerceAtLeast(0.dp)

/** 将弹层底边固定在 Popup 窗口底边,键盘避让由调用方传入的 inset 负责。 */
internal fun calculateBottomPopupPosition(
    windowSize: IntSize,
    popupContentSize: IntSize,
    bottomInsetPx: Int,
    gapPx: Int,
): IntOffset {
    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
    val x = ((windowSize.width - popupContentSize.width) / 2).coerceIn(0, maxX)
    val y = (
        windowSize.height -
            bottomInsetPx.coerceAtLeast(0) -
            gapPx.coerceAtLeast(0) -
            popupContentSize.height
        ).coerceAtLeast(0)
    return IntOffset(x = x, y = y)
}

/**
 * 底部操作面板。
 *
 * 这里故意使用全屏 Dialog 而不是 Popup：Popup 自己拥有独立窗口，
 * 在 Android 15 的 edge-to-edge 和不同导航模式下，windowSize 与宿主 Insets
 * 可能不在同一坐标系，菜单就会出现“飞到上面”的现象。
 *
 * 面板外框始终贴 Dialog 的物理底边；系统安全区只作为面板内部 padding，
 * 因此全面屏手势不会被误当成三键导航栏把整个菜单抬高。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MuseBottomPopup(
    onDismissRequest: () -> Unit,
    maxHeightFraction: Float = 0.85f,
    horizontalPadding: androidx.compose.ui.unit.Dp = MusePaddings.itemGap,
    bottomContentSpacing: androidx.compose.ui.unit.Dp = MusePaddings.largeGap,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val popupMaxHeight = LocalConfiguration.current.screenHeightDp.dp *
        maxHeightFraction.coerceIn(0.1f, 1f)

    // 底部菜单改用 Material3 的 modal sheet 窗口。
    // 它统一处理 Dialog 的 bottom gravity、导航栏/手势区和 outside/back dismiss，
    // 避免自定义 Dialog 在不同 ROM 的 edge-to-edge 坐标系中重复计算 inset。
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = BottomSheetSurfaceShape,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = MuseElevation.none,
        scrimColor = Color.Transparent,
        dragHandle = { SheetHandle() },
    ) {
        // 高度约束必须放在面板内容上，不能放在 ModalBottomSheet 根节点。
        // 根节点就是独立 Window；限制它会让 Material3 在一个缩短的 Window
        // 内做 bottom alignment，面板因此会出现在屏幕中间或“飞到天上”。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = popupMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = MusePaddings.screen),
        ) {
            content()
            if (bottomContentSpacing > 0.dp) {
                Spacer(Modifier.height(bottomContentSpacing))
            }
        }
    }
}

/**
 * MANUS 风格底部展开面板。
 *
 * 用 Dialog + 自定义底部对齐实现,避免原生 ModalBottomSheet 在某些真机上
 * scrim 遮罩无法移除、关闭后页面卡死的问题。
 *
 * 行为:
 *  - 点击面板外背景或返回键关闭
 *  - 内容区从底部向上滑入
 *  - 自动处理导航栏 insets
 *
 * @param onDismissRequest 关闭回调
 * @param content 面板内容
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuseBottomSheet(
    onDismissRequest: () -> Unit,
    maxHeightFraction: Float = 0.85f,
    // v1.0.72: 内容区左右留白可配置
    horizontalPadding: androidx.compose.ui.unit.Dp = MusePaddings.itemGap,
    // 内容区底部冗余可关闭；加号菜单需要只保留实际三段内容。
    bottomContentSpacing: androidx.compose.ui.unit.Dp = MusePaddings.largeGap,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetMaxHeight = LocalConfiguration.current.screenHeightDp.dp *
        maxHeightFraction.coerceIn(0.1f, 1f)

    // 与 MuseBottomPopup 共用 Material3 的 modal sheet 窗口。
    // 系统负责 bottom gravity、导航栏/手势区、返回键和外部点击；这里仅负责
    // 面板样式、业务最大高度和内容滚动，避免自定义 Dialog 再次参与坐标计算。
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = BottomSheetSurfaceShape,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = MuseElevation.none,
        scrimColor = Color.Transparent,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = sheetMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = MusePaddings.screen),
        ) {
            content()
            if (bottomContentSpacing > 0.dp) {
                Spacer(Modifier.height(bottomContentSpacing))
            }
        }
    }
}

/** M-BS3: 面板退出动画时长(毫秒),enter/exit tween 与 dismiss delay 复用同一常量。 */
private const val SHEET_EXIT_DURATION_MS = 300

/**
 * iOS 风格底部 Sheet 把手 — 36x4dp 灰色圆角条。
 *
 * 设计稿 Sheet 顶部统一有此把手,居中显示。
 */
@Composable
fun SheetHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MusePaddings.contentGap, bottom = MusePaddings.tightGap),
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                ),
        )
    }
}

/**
 * 可拖拽底部面板 — 既有实现 风格两阶段高度 (60%/90%)。
 *
 * 与 [MuseBottomSheet] 的区别:
 *  - 支持拖拽手势 + 速度追踪
 *  - 两阶段高度: [initialHeightFraction] (初始) 和 [expandedHeightFraction] (展开)
 *  - 关闭阈值: 70% 进度 或 速度 > 700dp/s
 *  - v1.0.74 fix (前端审计 3.2): 手势仅支持下滑关闭;高度由两个 fraction 参数初始决定,
 *    不支持下拉后上拉扩高(与文档宣称的"上拉展开 90%"对齐实际行为)。
 *
 * 用法:
 * ```
 * MuseDraggableBottomSheet(
 *     onDismissRequest = { showSheet = false },
 * ) {
 *     // 内容
 * }
 * ```
 */
@Composable
fun MuseDraggableBottomSheet(
    onDismissRequest: () -> Unit,
    initialHeightFraction: Float = 0.6f,
    expandedHeightFraction: Float = 0.9f,
    content: @Composable ColumnScope.() -> Unit,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    var shouldDismiss by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current

    var dragOffset by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) { visible = true }
    LaunchedEffect(shouldDismiss) {
        if (shouldDismiss) {
            visible = false
            kotlinx.coroutines.delay(SHEET_EXIT_DURATION_MS.toLong())
            onDismissRequest()
        }
    }

    val dismiss = { shouldDismiss = true }

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true,
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
        ),
    ) {
        MuseDialogWindowEffect(forceFullScreen = true, bottomAligned = true)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = dismiss,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // 平台 bottomAligned Dialog 提供已避让系统栏的真实可用高度。
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val screenHeight = maxHeight.coerceAtLeast(0.dp)
                // 当前拖拽偏移量 (0 = 初始高度, 正数 = 下拉; 上拉被 coerceAtLeast(0f) 限制)
                val initialHeight = screenHeight * initialHeightFraction
                val expandedHeight = screenHeight * expandedHeightFraction
                AnimatedVisibility(
                    visible = visible,
                    enter = slideInVertically(animationSpec = tween(SHEET_EXIT_DURATION_MS), initialOffsetY = { it }),
                    exit = slideOutVertically(animationSpec = tween(SHEET_EXIT_DURATION_MS), targetOffsetY = { it }),
                ) {
                    val currentHeight = (initialHeight - with(density) { dragOffset.toDp() })
                        .coerceIn(screenHeight * 0.2f, expandedHeight)

                    Surface(
                    shape = BottomSheetSurfaceShape,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = MuseElevation.none,
                    shadowElevation = MuseElevation.none,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(currentHeight)
                        .pointerInput(Unit) {
                            detectTapGestures { /* 拦截,不传播 */ }
                        },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        // 拖拽把手区域
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragEnd = {
                                            val closeThreshold = with(density) { initialHeight.toPx() * 0.3f }
                                            if (dragOffset > closeThreshold) {
                                                dismiss()
                                            } else {
                                                dragOffset = 0f
                                            }
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffset = (dragOffset + dragAmount.y).coerceAtLeast(0f)
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            SheetHandle()
                        }
                        // 内容区域
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                // v1.0.29: 左右留空减小为 12dp,使底部面板内容更舒展
                                .padding(horizontal = MusePaddings.itemGap, vertical = MusePaddings.screen),
                            content = content,
                        )
                    }
                    }
                }
            }
        }
    }
}
