package io.zer0.muse.miniphone.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 新消息提示的计数判定。
 *
 * 只验这一点:给定「上一次条数 / 这一次条数 / 人在不在底部 / 已经显示的条数」,
 * 该显示几条。全是纯函数,不需要手势、列表布局或界面。
 */
class ChatNewMessagesPillTest {

    @Test
    fun awayFromBottomCountsTheMessagesThatArrived() {
        assertEquals(
            "离底部时新到 3 条就显示 3",
            3,
            advanceNewMessageCount(previousTotal = 2, currentTotal = 5, atBottom = false, displayed = 0),
        )
    }

    @Test
    fun anotherArrivalAccumulatesOnTopOfTheDisplayedCount() {
        assertEquals(
            "已有的 3 条不清零,再 +1",
            4,
            advanceNewMessageCount(previousTotal = 5, currentTotal = 6, atBottom = false, displayed = 3),
        )
    }

    @Test
    fun atBottomNeverShowsAnything() {
        assertEquals(
            "人在底部时新消息直接被看到",
            0,
            advanceNewMessageCount(previousTotal = 2, currentTotal = 5, atBottom = true, displayed = 0),
        )
        assertEquals(
            "回到最新一条后把待显示的条数清掉",
            0,
            advanceNewMessageCount(previousTotal = 6, currentTotal = 6, atBottom = true, displayed = 3),
        )
    }

    @Test
    fun unchangedTotalKeepsWhatIsAlreadyShown() {
        assertEquals(
            "条数没变就保持原样",
            2,
            advanceNewMessageCount(previousTotal = 4, currentTotal = 4, atBottom = false, displayed = 2),
        )
    }

    @Test
    fun deletedMessageDoesNotMakeTheCountGoNegative() {
        assertEquals(
            "自己的消息被删掉时不清零、也不变成负数",
            2,
            advanceNewMessageCount(previousTotal = 5, currentTotal = 4, atBottom = false, displayed = 2),
        )
    }

    @Test
    fun firstObservationOnAnEmptyThreadShowsNothing() {
        assertEquals(
            "刚进页什么都没有时不弹提示",
            0,
            advanceNewMessageCount(previousTotal = 0, currentTotal = 0, atBottom = false, displayed = 0),
        )
    }

    @Test
    fun pillLabelReadsAsASentence() {
        assertEquals("3 条新消息", newMessagePillLabel(3))
        assertEquals("1 条新消息", newMessagePillLabel(1))
    }
}
