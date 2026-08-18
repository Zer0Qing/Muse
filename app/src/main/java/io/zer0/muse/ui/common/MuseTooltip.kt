package io.zer0.muse.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup

// E4 (前端专项 H8): 通用 Tooltip — 桌面 hover 显示气泡提示。
// 移动端触摸不产生 PointerEventType.Enter/Exit 事件,天然不触发,无需平台判断。
// 自研而非 material3 TooltipBox:项目 material3 为 1.4.0-alpha04 实验线,
// TooltipBox 为 ExperimentalMaterial3Api 且样式与项目 Muse 设计语言不一致。
// 定位:BottomCenter + 负 Y 偏移使气泡整体浮于锚点内容上方 6dp 处。
@Composable
internal fun MuseTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val gapPx = with(LocalDensity.current) { 6.dp.toPx().toInt() }
    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    when (event.type) {
                        PointerEventType.Enter -> visible = true
                        PointerEventType.Exit -> visible = false
                        else -> {}
                    }
                }
            }
        },
    ) {
        content()
        if (visible) {
            Popup(
                alignment = Alignment.BottomCenter,
                offset = IntOffset(0, -gapPx),
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    tonalElevation = 2.dp,
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
