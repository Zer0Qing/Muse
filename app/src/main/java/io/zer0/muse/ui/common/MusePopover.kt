@file:Suppress("FunctionNaming")

package io.zer0.muse.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.roundToInt

// E4 (前端专项 H8): 通用锚点弹层(Popover) — 以锚点组件在窗口中的边界
// (boundsInWindow)为基准定位弹出内容,右缘贴锚点右缘、上缘在锚点上方 gapDp。
// 点击弹层外部自动触发 onDismiss(Popup 内建行为)。
// 用途:锚定操作的浮层卡片(消息操作菜单等);移动端与桌面通用。
@Composable
internal fun MusePopover(
    anchorBounds: Rect,
    gapDp: Int,
    onDismiss: () -> Unit,
    anchorPointInWindow: Offset? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { gapDp.dp.toPx().toInt() }
    val positionProvider = remember(anchorBounds, anchorPointInWindow, gapPx) {
        MusePopoverPositionProvider(
            fallbackAnchorBounds = anchorBounds,
            anchorPointInWindow = anchorPointInWindow,
            gapPx = gapPx,
        )
    }
    Popup(
        onDismissRequest = onDismiss,
        popupPositionProvider = positionProvider,
    ) {
        content()
    }
}

/**
 * 根据按压点放置弹层。
 *
 * 按压点存在时,弹层水平居中于手指,优先显示在手指上方;上方空间不足时才放到下方。
 * 没有按压点时保留旧的气泡右上方定位,用于旋转/进程恢复等没有手势上下文的场景。
 */
internal class MusePopoverPositionProvider(
    private val fallbackAnchorBounds: Rect,
    private val anchorPointInWindow: Offset?,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val safeGap = gapPx.coerceAtLeast(0)
        val minX = safeGap
        val maxX = (windowSize.width - popupContentSize.width - safeGap).coerceAtLeast(minX)
        val minY = safeGap
        val maxY = (windowSize.height - popupContentSize.height - safeGap).coerceAtLeast(minY)

        val pressPoint = anchorPointInWindow
        if (pressPoint != null) {
            val x = (pressPoint.x - popupContentSize.width / 2f)
                .roundToInt()
                .coerceIn(minX, maxX)
            val spaceAbove = pressPoint.y - safeGap
            val spaceBelow = windowSize.height - pressPoint.y - safeGap
            val y = if (spaceAbove >= popupContentSize.height || spaceAbove >= spaceBelow) {
                pressPoint.y - popupContentSize.height - safeGap
            } else {
                pressPoint.y + safeGap
            }
                .roundToInt()
                .coerceIn(minY, maxY)
            return IntOffset(x, y)
        }

        val fallbackX = if (layoutDirection == LayoutDirection.Ltr) {
            fallbackAnchorBounds.right - popupContentSize.width
        } else {
            fallbackAnchorBounds.left
        }
        val fallbackY = fallbackAnchorBounds.top - popupContentSize.height - safeGap
        // v1.0.90: 锚点未测量完成(bounds 为 0)时，旧逻辑算出负数再被 coerce 到左上角，
        // 菜单会莫名其妙出现在屏幕左上、盖住正文。宁可按右下角放置，也不要落在阅读区外。
        if (fallbackAnchorBounds.width <= 0f && fallbackAnchorBounds.height <= 0f) {
            return IntOffset(maxX, maxY)
        }
        return IntOffset(
            fallbackX.roundToInt().coerceIn(minX, maxX),
            fallbackY.roundToInt().coerceIn(minY, maxY),
        )
    }
}
