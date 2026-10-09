@file:Suppress("FunctionNaming")

package io.zer0.muse.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.UIMessage
import io.zer0.muse.ui.common.form.LinearScrollGesture
import io.zer0.muse.ui.common.form.RAIL_TOUCH_WIDTH
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * A6: 长会话快速定位 — 聊天区右侧热区。
 *
 * 长会话（消息数 ≥ [MESSAGE_MAP_MIN_MESSAGES]）时保留右缘透明热区：
 * - 平时完全不可见；
 * - **长按**激活（轻震动）后上下滑动即可线性滚动（v2.5.9 起不再画可视轨道，
 *   理由见 [LinearScrollGesture] 的增量映射说明）；
 * - 滑动中浮出该位置消息的预览，帮助定位。
 *
 * 遮挡：热区为贴右缘细条，不覆盖消息底部操作行；操作行自身也向内让位。
 * 跨会话滚动位置保留由 ChatViewModel 的 listState 缓存负责，本组件只管导航。
 */
internal const val MESSAGE_MAP_MIN_MESSAGES = 25

/**
 * 触摸热区宽度（= 导航条占用的右侧空间）。
 *
 * v2.5.0 fix: 24dp 热区贴死屏幕右缘，被 Android 10+ 系统返回手势吃掉，用户实测"拖不动"。
 * 加宽回 40dp 并配合右缘内缩（见 ChatScreen 调用点 padding）。
 */
internal val MESSAGE_MAP_TOUCH_WIDTH = RAIL_TOUCH_WIDTH

/** 消息列表右侧为热区预留的总宽度（热区 + 呼吸间隙）。 */
internal val MESSAGE_MAP_RESERVED_WIDTH = MESSAGE_MAP_TOUCH_WIDTH + 4.dp

@Composable
internal fun MessageMapBar(
    messages: List<UIMessage>,
    listState: LazyListState,
    messageStartIndex: Int,
    modifier: Modifier = Modifier,
) {
    val total = messages.size
    if (total == 0) return

    var activeIndex by remember { mutableStateOf<Int?>(null) }

    Box(modifier = modifier) {
        LinearScrollGesture(
            listState = listState,
            onActiveIndexChange = { listIndex ->
                activeIndex = listIndex?.let { (it - messageStartIndex).takeIf { rel -> rel in 0 until total } }
            },
        )

        val previewMessage = activeIndex?.let { messages.getOrNull(it) }
        if (previewMessage != null) {
            MessageMapTooltip(
                msg = previewMessage,
                modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = (-(RAIL_TOUCH_WIDTH - 4.dp)))
                    .padding(vertical = 0.dp),
            )
        }
    }
}

/** A6: 消息地图滑动预览浮层 — 显示该位置消息前 40 字符。 */
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
            modifier =
            Modifier.padding(
                horizontal = MusePaddings.tightGap,
                vertical = MusePaddings.tinyGap,
            ),
        )
    }
}
