package io.zer0.muse.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A6: 长会话消息地图 — 聊天区右侧隐藏式导航。
 *
 * 长会话(消息数 ≥ [MESSAGE_MAP_MIN_MESSAGES])时保留右缘透明热区:
 * - 平时完全不可见,不因普通聊天滚动自动出现
 * - 手指在最右侧上下拖动时浮出半透明胶囊轨道
 * - 拖动过程中按纵向位置跳转到对应消息
 * - 松手后短暂停留,随后自动淡出
 * - 桌面端也使用同一拖动热区,不改变消息数据和分页逻辑
 *
 * 跨会话滚动位置保留由 ChatViewModel v1.45 的 listState 缓存负责,本组件只管导航。
 */
internal const val MESSAGE_MAP_MIN_MESSAGES = 25

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
    var isDragging by remember { mutableStateOf(false) }
    var showBar by remember { mutableStateOf(false) }
    var activeIndex by remember { mutableStateOf<Int?>(null) }
    val scrollJob = remember { mutableStateOf<Job?>(null) }

    // 普通列表滚动不会触发显示;只在右缘拖动结束后负责自动收起。
    LaunchedEffect(isDragging, showBar) {
        if (!isDragging && showBar) {
            delay(900)
            showBar = false
            activeIndex = null
        }
    }

    val barAlpha by animateFloatAsState(
        targetValue = if (showBar) 1f else 0f,
        animationSpec = tween(180),
        label = "mapbar-alpha",
    )

    fun jumpTo(index: Int) {
        scrollJob.value?.cancel()
        scrollJob.value = scope.launch {
            listState.scrollToItem(messageStartIndex + index)
        }
    }

    // 热区比视觉轨道更宽,让用户无需精确摸到细条就能唤出导航。
    Box(
        modifier = modifier
            .width(36.dp)
            .fillMaxHeight()
            .pointerInput(total, messageStartIndex) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        showBar = true
                        val index = messageIndexForY(offset.y, size.height, total)
                        activeIndex = index
                        jumpTo(index)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        isDragging = true
                        showBar = true
                        val index = messageIndexForY(change.position.y, size.height, total)
                        if (index != activeIndex) {
                            activeIndex = index
                            jumpTo(index)
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                    },
                    onDragCancel = {
                        isDragging = false
                    },
                )
            },
    ) {
        val visibleInfo = listState.layoutInfo.visibleItemsInfo
        val first = (visibleInfo.firstOrNull()?.index ?: listState.firstVisibleItemIndex)
            .let { (it - messageStartIndex).coerceIn(0, total - 1) }
        val last = (visibleInfo.lastOrNull()?.index ?: listState.firstVisibleItemIndex)
            .let { (it - messageStartIndex).coerceIn(0, total - 1) }

        val userColor = MaterialTheme.colorScheme.primaryContainer
        val assistantColor = MaterialTheme.colorScheme.secondary
        val otherColor = MaterialTheme.colorScheme.outlineVariant
        val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.84f)
        val windowColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)

        // 轨道只在拖动后显示;透明热区本身没有背景和阴影。
        Canvas(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(26.dp)
                .fillMaxHeight()
                .alpha(barAlpha),
        ) {
            drawMessageMap(
                messages = messages,
                firstVisible = first,
                lastVisible = last,
                total = total,
                barWidth = size.width,
                barHeight = size.height,
                userColor = userColor,
                assistantColor = assistantColor,
                otherColor = otherColor,
                trackColor = trackColor,
                windowColor = windowColor,
            )
        }

        val previewMessage = activeIndex?.let { messages.getOrNull(it) }
        if (showBar && previewMessage != null) {
            MessageMapTooltip(
                msg = previewMessage,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = (-28).dp),
            )
        }
    }
}

/** 消息地图绘制:胶囊轨道、消息密度标记和当前窗口滑块。 */
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
    trackColor: Color,
    windowColor: Color,
) {
    val trackRadius = CornerRadius(barWidth / 2f)
    drawRoundRect(
        color = trackColor,
        topLeft = Offset.Zero,
        size = Size(barWidth, barHeight),
        cornerRadius = trackRadius,
    )
    drawRoundRect(
        color = otherColor.copy(alpha = 0.45f),
        topLeft = Offset(0.5.dp.toPx(), 0.5.dp.toPx()),
        size = Size(barWidth - 1.dp.toPx(), barHeight - 1.dp.toPx()),
        cornerRadius = CornerRadius((barWidth - 1.dp.toPx()) / 2f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
    )

    // 每条消息压缩为轨道中央短标记,避免旧版满屏散落的细横线。
    val markerWidth = 5.dp.toPx().coerceAtMost(barWidth - 8.dp.toPx())
    val markerHeight = 2.dp.toPx()
    messages.forEachIndexed { index, message ->
        val y = (index + 0.5f) / total * barHeight
        val color = when (message.role) {
            MessageRole.USER -> userColor
            MessageRole.ASSISTANT -> assistantColor
            else -> otherColor
        }
        drawRoundRect(
            color = color.copy(alpha = 0.86f),
            topLeft = Offset((barWidth - markerWidth) / 2f, y - markerHeight / 2f),
            size = Size(markerWidth, markerHeight),
            cornerRadius = CornerRadius(markerHeight / 2f),
        )
    }

    // 当前可见窗口使用亮色滑块,拖动时能看出当前位置和覆盖范围。
    val windowY1 = (firstVisible + 0.5f) / total * barHeight
    val windowY2 = (lastVisible + 0.5f) / total * barHeight
    drawRoundRect(
        color = windowColor,
        topLeft = Offset(1.dp.toPx(), windowY1),
        size = Size(
            barWidth - 2.dp.toPx(),
            (windowY2 - windowY1).coerceAtLeast(10.dp.toPx()),
        ),
        cornerRadius = CornerRadius((barWidth - 2.dp.toPx()) / 2f),
    )
}

private fun messageIndexForY(y: Float, height: Int, total: Int): Int {
    if (height <= 0 || total <= 1) return 0
    return ((y / height) * total).toInt().coerceIn(0, total - 1)
}

/** A6: 消息地图拖动预览浮层 — 显示该位置消息前 40 字符。 */
@Composable
private fun MessageMapTooltip(msg: UIMessage, modifier: Modifier = Modifier) {
    val previewText = msg.content
        .replace('\n', ' ')
        .trim()
        .take(40)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
        shape = MuseShapes.medium,
        tonalElevation = 2.dp,
        modifier = modifier.widthIn(max = 220.dp),
    ) {
        Text(
            text = previewText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(
                horizontal = MusePaddings.tightGap,
                vertical = MusePaddings.tinyGap,
            ),
        )
    }
}
