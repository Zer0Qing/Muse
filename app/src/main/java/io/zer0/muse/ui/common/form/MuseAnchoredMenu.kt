package io.zer0.muse.ui.common.form

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * 锚定菜单 — 替代 Material3 `DropdownMenu`。
 *
 * 与 `DropdownMenu` 的区别：外壳走项目令牌（圆角/底色/阴影），条目由调用方传
 * [io.zer0.muse.ui.common.surface.MuseListItem]，所以整块菜单和页面其他部分同一套视觉。
 *
 * 定位机制与 `DropdownMenu` 相同：读取**锚点容器**（调用点所在的可组合项）的边界，
 * 默认向下展开、左对齐锚点；下方空间不足时向上翻转；水平越界时收敛到窗口内。
 * 因此调用方式与 `DropdownMenu` 一致 —— 放在触发控件所在的同一容器里即可。
 *
 * 用法:
 * ```
 * Box {
 *     MuseTactileButton(icon = ..., onClick = { expanded = true }, contentDescription = "更多")
 *     MuseAnchoredMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
 *         MuseListItem(onClick = { ... }) { Text("重命名") }
 *     }
 * }
 * ```
 *
 * @param expanded 是否展开
 * @param onDismissRequest 点击外部或返回键时回调
 * @param modifier 修饰符
 * @param gap 菜单与锚点之间的间距
 * @param alignEnd true 时右对齐锚点（"更多"类触发控件更自然），默认左对齐
 * @param minWidth 菜单最小宽度
 * @param maxWidth 菜单最大宽度
 * @param maxHeight 菜单最大高度（超出滚动）
 * @param focusable 是否抢占窗口焦点。操作型菜单用 true（点外部可关闭）；
 *   输入框自动补全（如 @mention）必须用 false —— 抢焦点会把键盘与输入框焦点带走，
 *   表现为“刚输入 @ 键盘就消失、补全不出来”。
 * @param content 菜单内容（通常是一列 [io.zer0.muse.ui.common.surface.MuseListItem]）
 */
@Composable
fun MuseAnchoredMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    gap: Dp = MusePaddings.tightGap,
    alignEnd: Boolean = false,
    minWidth: Dp = 180.dp,
    maxWidth: Dp = 280.dp,
    maxHeight: Dp = 320.dp,
    focusable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val density = LocalDensity.current
    val gapPx = with(density) { gap.roundToPx() }
    val properties = remember(focusable) { PopupProperties(focusable = focusable) }
    val positionProvider = remember(gapPx, alignEnd) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                val rawX = if (alignEnd) {
                    anchorBounds.right - popupContentSize.width
                } else {
                    anchorBounds.left
                }
                val x = rawX.coerceIn(0, maxX)
                val below = anchorBounds.bottom + gapPx
                val y = if (below + popupContentSize.height <= windowSize.height) {
                    below
                } else {
                    (anchorBounds.top - gapPx - popupContentSize.height).coerceAtLeast(0)
                }
                return IntOffset(x, y)
            }
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        Surface(
            modifier = modifier
                .widthIn(min = minWidth, max = maxWidth)
                .heightIn(max = maxHeight),
            shape = MuseShapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = MuseElevation.none,
            shadowElevation = MuseElevation.modal,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = MusePaddings.contentGap),
                content = content,
            )
        }
    }
}
