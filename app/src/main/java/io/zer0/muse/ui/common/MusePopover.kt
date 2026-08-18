package io.zer0.muse.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup

// E4 (前端专项 H8): 通用锚点弹层(Popover) — 以锚点组件在窗口中的边界
// (boundsInWindow)为基准定位弹出内容,右缘贴锚点右缘、上缘在锚点上方 gapDp。
// 点击弹层外部自动触发 onDismiss(Popup 内建行为)。
// 用途:锚定操作的浮层卡片(消息操作菜单等);移动端与桌面通用。
@Composable
internal fun MusePopover(
    anchorBounds: Rect,
    widthDp: Int,
    gapDp: Int,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val widthPx = with(density) { widthDp.dp.toPx().toInt() }
    val gapPx = with(density) { gapDp.dp.toPx().toInt() }
    Popup(
        onDismissRequest = onDismiss,
        alignment = Alignment.TopStart,
        offset = IntOffset(
            // 右缘贴锚点右缘;空间不足时贴屏幕左缘(gapPx 兜底防溢出)
            x = (anchorBounds.right.toInt() - widthPx).coerceAtLeast(gapPx),
            y = (anchorBounds.top.toInt() - gapPx).coerceAtLeast(gapPx),
        ),
    ) {
        content()
    }
}
