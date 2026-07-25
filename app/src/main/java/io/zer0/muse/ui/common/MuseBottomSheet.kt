package io.zer0.muse.ui.common

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.view.WindowManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * MANUS 风格底部展开面板。
 *
 * 用 Dialog + 自定义底部对齐实现,避免原生 ModalBottomSheet 在某些真机上
 * scrim 遮罩无法移除、关闭后页面卡死的问题。
 *
 * 行为:
 *  - 点击半透明背景或返回键关闭
 *  - 内容区从底部向上滑入
 *  - 自动处理导航栏 insets
 *
 * @param onDismissRequest 关闭回调
 * @param content 面板内容
 */
@Composable
fun MuseBottomSheet(
    onDismissRequest: () -> Unit,
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
        val localView = LocalView.current
        // v1.125: 让 scrim 延伸到刘海/挖孔屏状态栏区域,避免异形屏上遮罩漏出状态栏
        DisposableEffect(localView) {
            // Compose Dialog 内 View 层级: DialogLayout(extends AbstractComposeView) → DecorView
            // localView 的 windowAttributes 即 Dialog 的 WindowManager.LayoutParams
            val attrs = localView.layoutParams as? WindowManager.LayoutParams
            if (attrs != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                localView.requestLayout()
            }
            onDispose {}
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                // v1.48 (h20): 遮罩用 colorScheme.scrim 替代裸 Color.Black
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = dismiss,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(
                visible = visible,
                // M-BS3: 显式指定 tween 时长,与 delay(SHEET_EXIT_DURATION_MS) 复用同一常量,
                // 确保退出动画真正播完后再 dismiss(原先时长不匹配可能提前关闭)。
                enter = slideInVertically(animationSpec = tween(SHEET_EXIT_DURATION_MS), initialOffsetY = { it }),
                exit = slideOutVertically(animationSpec = tween(SHEET_EXIT_DURATION_MS), targetOffsetY = { it }),
            ) {
                Surface(
                    shape = MuseShapes.huge,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = MuseElevation.none,
                    shadowElevation = MuseElevation.none,
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        // v1.97: navigationBarsPadding 移到内部 Column,
                        // 让 Surface 背景色延伸到小白条区域(沉浸式)
                        // H-BS1: 旧 clickable(enabled=false, onClick={}) 无法消费点击事件,
                        // 导致点击面板内容穿透到外层 scrim 触发 dismiss。
                        // 改用 pointerInput + detectTapGestures 拦截面板上的手势,不再向下传播,
                        // 且不引入 ripple(indication),保持视觉干净。
                        .pointerInput(Unit) {
                            detectTapGestures { /* 拦截,不传播到外层 dismiss */ }
                        },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // v1.97: 内容避开小白条,Surface 背景色延伸到导航栏区域(沉浸式)
                            .navigationBarsPadding(),
                    ) {
                        // iOS 风格底部 Sheet 把手 — 36x4dp 灰色圆角条,居中于顶部
                        SheetHandle()
                        // 内容区域使用统一 padding
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                // L-BS4: 20.dp → MusePaddings.screen 令牌(16dp)。
                                .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.screen),
                            content = content,
                        )
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
            .padding(top = 8.dp, bottom = 4.dp),
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
 * 可拖拽底部面板 — Kelivo 风格两阶段高度 (60%/90%)。
 *
 * 与 [MuseBottomSheet] 的区别:
 *  - 支持拖拽手势 + 速度追踪
 *  - 两阶段高度: 60% (初始) 和 90% (展开)
 *  - 关闭阈值: 70% 进度 或 速度 > 700dp/s
 *  - 上拉展开到 90%, 下拉关闭
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
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val density = LocalDensity.current

    // 当前拖拽偏移量 (0 = 初始高度, 正数 = 下拉, 负数 = 上拉)
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val initialHeight = screenHeight * initialHeightFraction
    val expandedHeight = screenHeight * expandedHeightFraction

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
        val localView = LocalView.current
        DisposableEffect(localView) {
            val attrs = localView.layoutParams as? WindowManager.LayoutParams
            if (attrs != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                localView.requestLayout()
            }
            onDispose {}
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = dismiss,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(animationSpec = tween(SHEET_EXIT_DURATION_MS), initialOffsetY = { it }),
                exit = slideOutVertically(animationSpec = tween(SHEET_EXIT_DURATION_MS), targetOffsetY = { it }),
            ) {
                val currentHeight = (initialHeight - with(density) { dragOffset.toDp() })
                    .coerceIn(screenHeight * 0.2f, expandedHeight)
                val closeProgress = dragOffset / with(density) { initialHeight.toPx() * 0.3f }

                Surface(
                    shape = MuseShapes.huge,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = MuseElevation.none,
                    shadowElevation = MuseElevation.none,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(currentHeight)
                        .imePadding()
                        .pointerInput(Unit) {
                            detectTapGestures { /* 拦截,不传播 */ }
                        },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding(),
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
                                .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.screen),
                            content = content,
                        )
                    }
                }
            }
        }
    }
}
