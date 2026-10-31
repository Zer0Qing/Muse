package io.zer0.muse.miniphone.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * 新消息提示的计数推进:一次「列表状态 + 消息条数」的观察 → 新的待显示条数。
 *
 * 三条规则,都只看状态、不看手势:
 * - 人已经在底部:没有「错过了什么」,计数一律归零(新消息立刻被看见)
 * - 人不在底部且条数变多:把这一段增量累加进已有计数
 * - 条数没变(或变少,比如自己删了一条):计数不动,不清零也不变成负数
 *
 * @param previousTotal 上一次观察到的消息条数
 * @param currentTotal 这一次观察到的消息条数
 * @param atBottom 这一次观察时人是否停在最新一条
 * @param displayed 当前已经显示的条数
 */
internal fun advanceNewMessageCount(previousTotal: Int, currentTotal: Int, atBottom: Boolean, displayed: Int): Int {
    if (atBottom) return 0
    val arrived = currentTotal - previousTotal
    return if (arrived > 0) displayed.coerceAtLeast(0) + arrived else displayed.coerceAtLeast(0)
}

/**
 * 人是不是停在最新一条上。
 *
 * 倒序布局(`reverseLayout`)里下标 0 就是最新一条,贴着视口起点;一旦往上翻,它就滑出视口,
 * 首屏可见的就不再含下标 0。判据只读列表的布局结果,不累计滚动距离 ——
 * 快速甩动、内容高度变化都不会让它变歪。
 */
internal fun LazyListState.isAtNewestItem(): Boolean {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return true
    val newest = info.visibleItemsInfo.firstOrNull { item -> item.index == 0 } ?: return false
    // 静止在底部时,最新一条的起点正好落在「视口起点 + 前置内边距」上
    return newest.offset - (info.viewportStartOffset + info.beforeContentPadding) <= BOTTOM_TOLERANCE_PX
}

/**
 * 盯着列表状态与消息条数,把推导出的待显示条数报给调用方。
 *
 * 用 [snapshotFlow] 而不是滚动回调:列表布局与消息条数本身就是可观察状态,
 * 每一跳都重新推导一次「在不在底部」,不必累加滚动距离。
 *
 * @param messageTotal 当前消息总条数(不含时间分隔与「正在输入」这类占位行)
 * @param onCountChanged 待显示条数变化时回调;为 0 表示不该显示胶囊
 */
@Composable
internal fun NewMessagesEffect(listState: LazyListState, messageTotal: Int, onCountChanged: (Int) -> Unit) {
    val latestTotal by rememberUpdatedState(messageTotal)
    val latestCallback by rememberUpdatedState(onCountChanged)
    LaunchedEffect(listState) {
        var previous = latestTotal
        var shown = 0
        snapshotFlow { latestTotal to listState.isAtNewestItem() }
            .collect { (total, atBottom) ->
                val next = advanceNewMessageCount(
                    previousTotal = previous,
                    currentTotal = total,
                    atBottom = atBottom,
                    displayed = shown,
                )
                previous = total
                if (next != shown) {
                    shown = next
                    latestCallback(next)
                }
            }
    }
}

/** 提示胶囊:一条浮起的短条,点一下回到最新。 */
@Composable
internal fun ChatNewMessagesPill(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Text(
        text = newMessagePillLabel(count),
        style = typography.caption.copy(color = colors.onAccent),
        modifier = modifier
            .testTag(MiniPhoneChatTags.NEW_MESSAGES_PILL)
            .clip(RoundedCornerShape(pillCorner))
            .background(colors.accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** 胶囊上的字:条数 + 一句「有几条没看」。 */
internal fun newMessagePillLabel(count: Int): String = "$count 条新消息"

/** 判定「贴底」允许的像素级偏差:滚动停稳时未必严格落在整数像素上。 */
private const val BOTTOM_TOLERANCE_PX: Int = 2

private val pillCorner = 50.dp
