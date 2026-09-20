package io.zer0.muse.ui.common.form

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.common.surface.museModalScrimColor
import io.zer0.muse.ui.theme.MuseCornerRadius
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MusePaddings

private val BottomSheetSurfaceShape = RoundedCornerShape(
    topStart = MuseCornerRadius.SHEET.dp,
    topEnd = MuseCornerRadius.SHEET.dp,
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
 * 底部操作面板(全项目唯一入口,取代已删除的 MuseBottomPopup)。
 *
 * CMP-06: 原 MuseBottomPopup 与此函数逐行相同,已删除;
 * 底部面板统一走本组件。这里故意使用 ModalBottomSheet 而不是 Popup:
 * Popup 自己拥有独立窗口,在 Android 15 的 edge-to-edge 和不同导航模式下,
 * windowSize 与宿主 Insets 可能不在同一坐标系,菜单就会出现"飞到上面"的现象。
 * ModalBottomSheet 统一处理 Dialog 的 bottom gravity、导航栏/手势区和 outside/back dismiss。
 *
 * 面板外框始终贴 Dialog 的物理底边;系统安全区只作为面板内部 padding,
 * 因此全面屏手势不会被误当成三键导航栏把整个菜单抬高。
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

    // 与旧 MuseBottomPopup 共用 Material3 的 modal sheet 窗口。
    // 系统负责 bottom gravity、导航栏/手势区、返回键和外部点击；这里仅负责
    // 面板样式、业务最大高度和内容滚动，避免自定义 Dialog 再次参与坐标计算。
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = BottomSheetSurfaceShape,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = MuseElevation.none,
        scrimColor = museModalScrimColor(),
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
