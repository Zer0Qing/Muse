package io.zer0.muse.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListState
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.launch

/**
 * A6: 长会话消息地图 — 聊天区右侧竖条导航。
 *
 * 长会话(消息数 ≥ [MESSAGE_MAP_MIN_MESSAGES])时显示,帮助用户快速定位:
 * - 每消息一个 1.5dp 圆角标记,按角色着色(用户/助手/其他)
 * - 当前可见窗口以半透明条高亮
 * - 点击/拖动任意位置 → 滚动到对应消息(拖拽持续滚动)
 * - 桌面 hover 时在条左侧预览该位置消息前 40 字符(触屏无 hover 事件,天然不触发)
 *
 * 跨会话滚动位置保留由 ChatViewModel v1.45 的 listState 缓存负责,本组件只管导航。
 * 不新增字符串资源:预览内容为消息原文,无固定文案。
 */
internal const val MESSAGE_MAP_MIN_MESSAGES = 25

// A6: 指针事件循环(点击/拖动/悬浮三态分发)为固有分支结构,复杂度仅超阈值 2,豁免
@Suppress("CyclomaticComplexMethod")
@Composable
internal fun MessageMapBar(
    messages: List<UIMessage>,
    listState: LazyListState,
    messageStartIndex: Int,
    modifier: Modifier = Modifier,
) {
    val total = messages.size
    if (total == 0) return
    val scope = rememberCoroutineScope()
    var hoverIndex by remember { mutableStateOf<Int?>(null) }
    var pressedIndex by remember { mutableStateOf<Int?>(null) }

    BoxWithConstraints(
        modifier = modifier
            .width(10.dp)
            .fillMaxHeight()
            // A6: 手写指针事件循环(点击/拖动/悬浮统一换算为消息下标)
            .pointerInput(total, messageStartIndex) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val position = event.changes.firstOrNull()?.position ?: continue
                        val idx = ((position.y / size.height) * total)
                            .toInt()
                            .coerceIn(0, total - 1)
                        when (event.type) {
                            PointerEventType.Move -> hoverIndex = idx
                            PointerEventType.Press -> {
                                pressedIndex = idx
                                hoverIndex = idx
                                scope.launch { listState.scrollToItem(messageStartIndex + idx) }
                            }
                            PointerEventType.Release -> pressedIndex = null
                            else -> {}
                        }
                        // 拖动:按下状态下手部移动持续跳转
                        val pressed = pressedIndex
                        if (pressed != null && pressed != idx) {
                            pressedIndex = idx
                            hoverIndex = idx
                            scope.launch { listState.scrollToItem(messageStartIndex + idx) }
                        }
                    }
                }
            },
    ) {
        val barWidth = constraints.maxWidth.toFloat()
        val barHeight = constraints.maxHeight.toFloat()
        // 可见窗口:布局信息取首尾可见项(经消息区起始偏移换算为消息下标)
        val visibleInfo = listState.layoutInfo.visibleItemsInfo
        val first = (visibleInfo.firstOrNull()?.index ?: listState.firstVisibleItemIndex)
            .let { (it - messageStartIndex).coerceIn(0, total - 1) }
        val last = (visibleInfo.lastOrNull()?.index ?: listState.firstVisibleItemIndex)
            .let { (it - messageStartIndex).coerceIn(0, total - 1) }
        // 主题色在 Composable 上下文读取后传入 Canvas(DrawScope 内不可调 @Composable)
        val userColor = MaterialTheme.colorScheme.primaryContainer
        val assistantColor = MaterialTheme.colorScheme.secondary
        val otherColor = MaterialTheme.colorScheme.outlineVariant
        val windowColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)
        Canvas(Modifier.fillMaxSize()) {
            drawMessageMap(
                messages = messages,
                firstVisible = first,
                lastVisible = last,
                total = total,
                barWidth = barWidth,
                barHeight = barHeight,
                userColor = userColor,
                assistantColor = assistantColor,
                otherColor = otherColor,
                windowColor = windowColor,
            )
        }
        // 3. hover/按压预览:条左侧浮层显示该位置消息前 40 字符
        val previewMessage = hoverIndex?.let { messages.getOrNull(it) }
        if (previewMessage != null) {
            MessageMapTooltip(
                msg = previewMessage,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = (-16).dp),
            )
        }
    }
}

/** A6: 消息地图绘制 — 可见窗口高亮 + 每消息角色着色标记。
 * 颜色由调用方(Composable 上下文)取好传入,DrawScope 内不可读主题。
 * 参数均为纯数据快照(下标/总数/尺寸/颜色),打包进 data class 会牺牲可读性,保留平铺参数。
 */
@Suppress("LongParameterList")
private fun DrawScope.drawMessageMap(
    messages: List<UIMessage>,
    firstVisible: Int,
    lastVisible: Int,
    total: Int,
    barWidth: Float,
    barHeight: Float,
    userColor: Color,
    assistantColor: Color,
    otherColor: Color,
    windowColor: Color,
) {
    // 1. 可见窗口高亮(半透明条,置于标记下层)
    val winY1 = (firstVisible + 0.5f) / total * barHeight
    val winY2 = (lastVisible + 0.5f) / total * barHeight
    drawRoundRect(
        color = windowColor,
        topLeft = Offset(0f, winY1),
        size = Size(barWidth, (winY2 - winY1).coerceAtLeast(1f)),
        cornerRadius = CornerRadius(2.dp.toPx()),
    )
    // 2. 每消息标记(1.5dp 圆角线段,按角色着色)
    val markerH = 1.5.dp.toPx()
    messages.forEachIndexed { i, msg ->
        val y = (i + 0.5f) / total * barHeight
        val color = when (msg.role) {
            MessageRole.USER -> userColor
            MessageRole.ASSISTANT -> assistantColor
            else -> otherColor
        }
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, y - markerH / 2),
            size = Size(barWidth, markerH),
            cornerRadius = CornerRadius(markerH / 2),
        )
    }
}

/** A6: 消息地图 hover 预览浮层 — 显示该位置消息前 40 字符(桌面 hover 触发)。 */
@Composable
private fun MessageMapTooltip(msg: UIMessage, modifier: Modifier = Modifier) {
    val previewText = msg.content
        .replace('\n', ' ')
        .trim()
        .take(40)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        shape = MuseShapes.medium,
        tonalElevation = 2.dp,
        modifier = modifier.widthIn(max = 220.dp),
    ) {
        Text(
            text = previewText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = MusePaddings.tightGap, vertical = MusePaddings.tinyGap),
        )
    }
}
