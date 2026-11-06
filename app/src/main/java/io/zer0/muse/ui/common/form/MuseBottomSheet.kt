@file:Suppress("FunctionNaming", "LongMethod")

package io.zer0.muse.ui.common.form

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.zer0.muse.ui.common.surface.LocalMuseCompactList
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.common.surface.museModalScrim
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseCornerRadius
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpringTier
import io.zer0.muse.ui.theme.fakeGlassSurface
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val BottomSheetSurfaceShape = RoundedCornerShape(
    topStart = MuseCornerRadius.SHEET.dp,
    topEnd = MuseCornerRadius.SHEET.dp,
)

/** 计算包含底部导航安全区后的面板最大业务高度。 */
internal fun calculateBottomSheetHeight(
    maxHeight: androidx.compose.ui.unit.Dp,
    fraction: Float,
    bottomInset: androidx.compose.ui.unit.Dp,
): androidx.compose.ui.unit.Dp = ((maxHeight - bottomInset).coerceAtLeast(0.dp) * fraction.coerceIn(0f, 1f)).coerceAtLeast(0.dp)

/** 保留旧 Popup 几何回归测试；当前面板不读取跨窗口坐标。 */
internal fun calculateBottomPopupPosition(windowSize: IntSize, popupContentSize: IntSize, bottomInsetPx: Int, gapPx: Int): IntOffset {
    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
    val x = ((windowSize.width - popupContentSize.width) / 2).coerceIn(0, maxX)
    val y = (windowSize.height - bottomInsetPx.coerceAtLeast(0) - gapPx.coerceAtLeast(0) - popupContentSize.height).coerceAtLeast(0)
    return IntOffset(x, y)
}

/**
 * F3.2: 拖拽释放后的去向决策（纯函数，便于把边界语义钉成断言）。
 *
 * 规则：位移超过阈值直接关闭；速度足够大且位移过了阈值三成算轻弹（fling）关闭；
 * 其余回弹。把规则抽出来的原因：内联在手势回调里时，任何一条边界改动都
 * 只能靠真机复现才能发现，而这个决策完全可以离线判定。
 */
internal enum class SheetSettleTarget { DISMISS, SETTLE }

internal fun sheetSettleDecision(
    dragPx: Float,
    velocityPxPerSec: Float,
    dismissThresholdPx: Float,
    flingVelocityPxPerSec: Float = 800f,
): SheetSettleTarget = when {
    dragPx >= dismissThresholdPx -> SheetSettleTarget.DISMISS
    velocityPxPerSec >= flingVelocityPxPerSec && dragPx >= dismissThresholdPx * 0.35f -> SheetSettleTarget.DISMISS
    else -> SheetSettleTarget.SETTLE
}

/**
 * 全项目底部面板入口。
 *
 * Dialog 内统一测量安全区和底部位置，避免 Popup 的宿主/弹层坐标系差异。
 * 遮罩单独裁掉导航栏；面板底色铺至屏幕底，内容留出手势区和键盘。
 * Material3 的内置全屏 scrim 无法单独排除导航栏，因此在这里使用受控外壳。
 *
 * F3.2 跟手拖拽：把手垂直拖动实时平移面板（上越界橡胶带阻尼），
 * 释放后由 [sheetSettleDecision] 决定回弹（BOUNCY 弹簧）或滑出关闭；
 * 滑出路径改由 offset 动画完成，exit 过渡只留淡出残边，避免两段位移叠加。
 */
@Composable
fun MuseBottomSheet(
    onDismissRequest: () -> Unit,
    maxHeightFraction: Float = 0.78f,
    horizontalPadding: androidx.compose.ui.unit.Dp = MusePaddings.screen,
    bottomContentSpacing: androidx.compose.ui.unit.Dp = MusePaddings.contentGap,
    content: @Composable ColumnScope.() -> Unit,
) {
    val latestOnDismiss by rememberUpdatedState(onDismissRequest)
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val dismiss = { visible.targetState = false }
    LaunchedEffect(visible.isIdle, visible.currentState, visible.targetState) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) latestOnDismiss()
    }
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        MuseDialogWindowEffect(forceFullScreen = true)
        val density = LocalDensity.current
        val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
        val glassApply = io.zer0.muse.ui.theme.LocalLiquidGlass.current.enabled
        val glassBase = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visibleState = visible,
                enter = fadeIn(MuseMotion.tween(180)),
                exit = fadeOut(MuseMotion.tween(140)),
            ) {
                Box(
                    Modifier.fillMaxSize().museModalScrim().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = dismiss,
                    ).testTag("muse-sheet-scrim"),
                )
            }
            BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                val sheetMaxHeight = calculateBottomSheetHeight(maxHeight, maxHeightFraction, bottomInset) + bottomInset
                // F3.2: 面板拖拽位移（正=向下跟手）。上越界由手势里的橡胶带阻尼后写入。
                val dragOffsetY = remember { Animatable(0f) }
                val sheetScope = rememberCoroutineScope()
                AnimatedVisibility(
                    visibleState = visible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically(animationSpec = MuseMotion.springSpec(MuseSpringTier.STEADY)) { it },
                    // F3.2: 滑出关闭由 offset 动画负责到位，这里只淡残边。
                    exit = fadeOut(MuseMotion.tween(120)),
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = sheetMaxHeight)
                            .offset { IntOffset(0, dragOffsetY.value.roundToInt()) }
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
                        shape = BottomSheetSurfaceShape,
                        color = if (glassApply) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface,
                        tonalElevation = MuseElevation.none,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().then(
                                if (glassApply) Modifier.fakeGlassSurface(BottomSheetSurfaceShape, glassBase) else Modifier,
                            ),
                        ) {
                            val dragThresholdPx = with(density) { 32.dp.toPx() }
                            val rubberCapPx = with(density) { 24.dp.toPx() }
                            val slideOutPx = with(density) { sheetMaxHeight.toPx() }
                            Box(
                                Modifier.pointerInput(dragThresholdPx) {
                                    // 速度用最小可靠实现手工采样（EMA）：不依赖跨版本
                                    // 位置漂移的速度追踪器类，语义与阈值判定同为纯边界。
                                    var lastSampleTime = 0L
                                    var lastSampleY = 0f
                                    var velocityY = 0f
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            lastSampleTime = 0L
                                            lastSampleY = 0f
                                            velocityY = 0f
                                        },
                                        onDragEnd = {
                                            val decision = sheetSettleDecision(
                                                dragPx = dragOffsetY.value,
                                                velocityPxPerSec = velocityY,
                                                dismissThresholdPx = dragThresholdPx,
                                            )
                                            when (decision) {
                                                SheetSettleTarget.DISMISS -> sheetScope.launch {
                                                    // 弹簧滑到屏幕外再走 exit（exit 只剩淡出，不叠加两段位移）。
                                                    dragOffsetY.animateTo(
                                                        slideOutPx,
                                                        androidx.compose.animation.core.spring(
                                                            dampingRatio = MuseAnimation.STEADY_DAMPING,
                                                            stiffness = MuseAnimation.STEADY_STIFFNESS,
                                                        ),
                                                    )
                                                    dismiss()
                                                }
                                                SheetSettleTarget.SETTLE -> sheetScope.launch {
                                                    dragOffsetY.animateTo(
                                                        0f,
                                                        androidx.compose.animation.core.spring(
                                                            dampingRatio = MuseAnimation.BOUNCY_DAMPING,
                                                            stiffness = MuseAnimation.BOUNCY_STIFFNESS,
                                                        ),
                                                    )
                                                }
                                            }
                                        },
                                        onDragCancel = { sheetScope.launch { dragOffsetY.animateTo(0f) } },
                                    ) { change, amount ->
                                        change.consume()
                                        // 采样两帧间 dY/dt，指数平滑（α=0.5）抑制单帧噪声。
                                        val now = change.uptimeMillis
                                        val dy = change.position.y - lastSampleY
                                        if (lastSampleTime > 0L && now > lastSampleTime) {
                                            val instant = dy * 1000f / (now - lastSampleTime)
                                            velocityY = if (velocityY == 0f) instant else (velocityY + instant) * 0.5f
                                        }
                                        lastSampleTime = now
                                        lastSampleY = change.position.y
                                        val raw = dragOffsetY.value + amount
                                        // 上越界橡胶带：四分之阻力 + 24dp 封顶。
                                        val target = if (raw < 0f) {
                                            (raw * 0.25f).coerceAtLeast(-rubberCapPx)
                                        } else {
                                            raw
                                        }
                                        sheetScope.launch { dragOffsetY.snapTo(target) }
                                    }
                                },
                            ) { SheetHandle() }
                            CompositionLocalProvider(LocalMuseCompactList provides true) {
                                Column(
                                    Modifier.fillMaxWidth().weight(1f, fill = false)
                                        .verticalScroll(rememberScrollState())
                                        .navigationBarsPadding()
                                        .padding(horizontal = horizontalPadding, vertical = MusePaddings.contentGap),
                                ) {
                                    content()
                                    if (bottomContentSpacing > 0.dp) Spacer(Modifier.height(bottomContentSpacing))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 顶部把手保留 32dp 交互带，支持向下滑动关闭。 */
@Composable
fun SheetHandle() {
    Box(
        modifier = Modifier.fillMaxWidth().height(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)),
        )
    }
}
