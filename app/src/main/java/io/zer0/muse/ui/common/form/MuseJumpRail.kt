package io.zer0.muse.ui.common.form

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 右侧跳转条的**连续定位**手势（v2.4.5 统一化）。
 *
 * 背景:应用里有多处"右侧竖条快速定位"——聊天消息地图、设置教程小节条、小手机通讯录字母索引。
 * 旧实现统一是"按下 → 换算成某个条目 → scrollToItem",于是:
 *  - 只能停在条目边界,拖动时一格一格跳,手感不是线性滚动;
 *  - 长内容想跳到两屏之间,必须先落到某个条目上。
 *
 * 本修饰符把"拖动 → 比例"这一段收敛成一处实现,由调用方决定拿到比例后怎么定位
 * (不同页面的列表结构不同:有的前面有附加 item、有的条目与目标列表索引不一致)。
 *
 * 调用方通常再自己把比例换算成"粗定位 + 亚条条目微调"以获得线性手感 —— 参考
 * [io.zer0.muse.ui.chat.MessageMapBar] 与 `SettingsTutorialPage` 的用法。
 *
 * @param onFraction 拖动比例回调(0..1,已 clamp);y 越大代表越靠下。
 * @param onDragStateChange 拖动开始/结束回调,供调用方控制浮层显隐。
 */
@Composable
fun Modifier.museJumpRailDrag(onFraction: (Float) -> Unit, onDragStateChange: (Boolean) -> Unit = {}): Modifier = this.pointerInput(Unit) {
    detectDragGestures(
        onDragStart = { offset ->
            onDragStateChange(true)
            onFraction((offset.y / size.height).coerceIn(0f, 1f))
        },
        onDrag = { change, _ ->
            change.consume()
            onFraction((change.position.y / size.height).coerceIn(0f, 1f))
        },
        onDragEnd = { onDragStateChange(false) },
        onDragCancel = { onDragStateChange(false) },
    )
}

/**
 * 线性定位助手:把拖动比例换算成"目标条目 + 亚条条目微调量"。
 *
 * 返回的 [RailTarget.index] 是条目索引,[RailTarget.subFraction] 是该条目内部的纵向比例
 * (0..1)。调用方用 `listState.scrollToItem(offset + index)` 后,再按平均条目高度
 * `scrollBy(subFraction * avgItemPx)` 即可得到连续定位。
 */
data class RailTarget(val index: Int, val subFraction: Float)

/**
 * 把拖动比例换算为目标条目与亚条条目比例。
 *
 * @param fraction 拖动比例(0..1)。
 * @param itemCount 条目总数。
 */
fun railTargetFor(fraction: Float, itemCount: Int): RailTarget {
    if (itemCount <= 1) return RailTarget(0, 0f)
    val clamped = fraction.coerceIn(0f, 1f)
    val target = clamped * (itemCount - 1)
    val index = target.toInt().coerceIn(0, itemCount - 1)
    val subFraction = (target - index).coerceIn(0f, 0.99f)
    return RailTarget(index, subFraction)
}
