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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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

/** 将弹层底边固定在导航栏/手势区上缘,而不是固定在触发控件附近。 */
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

/** Popup 版底部定位器,供输入工具菜单和会话操作菜单共用。 */
internal class MuseBottomPopupPositionProvider(
    private val bottomInsetPx: Int,
    private val gapPx: Int = 0,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = calculateBottomPopupPosition(
        windowSize = windowSize,
        popupContentSize = popupContentSize,
        bottomInsetPx = bottomInsetPx,
        gapPx = gapPx,
    )
}

/**
 * 不创建全屏 Dialog 的底部操作面板。
 *
 * Popup 窗口只包住实际内容,底边按系统安全区定位,因此不会额外生成一层覆盖整个页面的白色窗口。
 */
@Composable
internal fun MuseBottomPopup(
    onDismissRequest: () -> Unit,
    maxHeightFraction: Float = 0.85f,
    horizontalPadding: androidx.compose.ui.unit.Dp = MusePaddings.itemGap,
    bottomContentSpacing: androidx.compose.ui.unit.Dp = MusePaddings.largeGap,
    content: @Composable ColumnScope.() -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val bottomInsetPx = maxOf(
        WindowInsets.safeDrawing.getBottom(density),
        WindowInsets.ime.getBottom(density),
    )
    val popupWidth = configuration.screenWidthDp.coerceAtLeast(1).dp
    val popupMaxHeight = configuration.screenHeightDp.coerceAtLeast(1).dp *
        maxHeightFraction.coerceIn(0f, 1f)
    Popup(
        onDismissRequest = onDismissRequest,
        popupPositionProvider = remember(bottomInsetPx) {
            MuseBottomPopupPositionProvider(bottomInsetPx)
        },
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            clippingEnabled = true,
        ),
    ) {
        Surface(
            modifier = Modifier
                .width(popupWidth)
                .heightIn(max = popupMaxHeight),
            shape = BottomSheetSurfaceShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = MuseElevation.none,
            shadowElevation = MuseElevation.none,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                SheetHandle()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = horizontalPadding, vertical = MusePaddings.screen),
                    content = content,
                )
                if (bottomContentSpacing > 0.dp) {
                    Spacer(Modifier.height(bottomContentSpacing))
                }
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
    // M-BS2: 用 rememberSaveable 持久化进入/退出动画状态,配置变更(旋转/暗色切换)
    // 时不再丢失 visible/shouldDismiss 导致面板卡在半退出状态。
    var visible by rememberSaveable { mutableStateOf(false) }
    var shouldDismiss by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    LaunchedEffect(shouldDismiss) {
        if (shouldDismiss) {
            visible = false
            // M-BS3: 等待退出动画结束,时长与下方 slideOutVertically 的 tween 复用同一常量。
            kotlinx.coroutines.delay(SHEET_EXIT_DURATION_MS.toLong())
            onDismissRequest()
        }
    }

    val dismiss = {
        shouldDismiss = true
    }

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
        ),
    ) {
        MuseDialogWindowEffect(forceFullScreen = true)
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
            // v1.0.27 修复 Bug 2: 长按会话菜单"删除被遮挡"。
            // 使用 Dialog 实际约束而不是配置屏幕高度，并把导航栏底边从业务高度中扣除。
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val bottomInset = with(density) {
                    WindowInsets.safeDrawing.getBottom(this).toDp()
                }
                val sheetMaxHeight = calculateBottomSheetHeight(maxHeight, maxHeightFraction, bottomInset)
                AnimatedVisibility(
                    visible = visible,
                    // M-BS3: 显式指定 tween 时长,与 delay(SHEET_EXIT_DURATION_MS) 复用同一常量,
                    // 确保退出动画真正播完后再 dismiss(原先时长不匹配可能提前关闭)。
                    enter = slideInVertically(animationSpec = tween(SHEET_EXIT_DURATION_MS), initialOffsetY = { it }),
                    exit = slideOutVertically(animationSpec = tween(SHEET_EXIT_DURATION_MS), targetOffsetY = { it }),
                ) {
                Surface(
                    shape = BottomSheetSurfaceShape,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = MuseElevation.none,
                    shadowElevation = MuseElevation.none,
                    modifier = Modifier
                        .fillMaxWidth()
                        // v1.0.29: maxHeightFraction 可配置,加号菜单用较小值避免面板过高影响观感
                        .heightIn(max = sheetMaxHeight)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                        .imePadding()
                        // H-BS1: 旧 clickable(enabled=false, onClick={}) 无法消费点击事件,
                        // 导致点击面板内容穿透到外层背景触发 dismiss。
                        // 改用 pointerInput + detectTapGestures 拦截面板上的手势,不再向下传播,
                        // 且不引入 ripple(indication),保持视觉干净。
                        .pointerInput(Unit) {
                            detectTapGestures { /* 拦截,不传播到外层 dismiss */ }
                        },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        // iOS 风格底部 Sheet 把手 — 36x4dp 灰色圆角条,居中于顶部
                        SheetHandle()
                        // 内容区域使用统一 padding
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                // L-BS4: 20.dp → MusePaddings.screen 令牌(16dp)。
                                // v1.0.29: 左右留空减小为 12dp,使底部面板内容更舒展
                                // v1.0.72: 留白可配置(加号菜单传 0 不留白)
                            .padding(horizontal = horizontalPadding, vertical = MusePaddings.screen),
                            content = content,
                        )
                        // v1.0.29: 底部增加额外冗余,让底部菜单整体上抬,
                        // 避免内容紧贴系统导航条/手势条,提升操作舒适度。
                        if (bottomContentSpacing > 0.dp) {
                            Spacer(Modifier.height(bottomContentSpacing))
                        }
                    }
                }
                }
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
            decorFitsSystemWindows = false,
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
        ),
    ) {
        MuseDialogWindowEffect(forceFullScreen = true)
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
            // 使用 Dialog 实际约束计算可拖拽高度，导航栏安全区不参与业务高度。
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val bottomInset = with(density) {
                    WindowInsets.safeDrawing.getBottom(this).toDp()
                }
                val screenHeight = (maxHeight - bottomInset).coerceAtLeast(0.dp)
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
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                        .imePadding()
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
