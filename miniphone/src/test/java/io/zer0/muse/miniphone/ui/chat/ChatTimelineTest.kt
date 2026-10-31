package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间分隔的插入规则:第一条必有,之后按 5 分钟断一次。
 *
 * 单独测这一条,是因为它决定「列表里多出来的行」—— 差一秒就该插、差一秒就不该插,
 * 靠界面去测太贵了。
 */
class ChatTimelineTest {

    @Test
    fun everyConversationOpensWithATimeRow() {
        val items = buildChatItems(listOf(ui("m1", at = MINUTE_MS)))

        assertEquals(2, items.size)
        assertTrue(items.first() is ChatItem.Time)
        assertEquals(MINUTE_MS, (items.first() as ChatItem.Time).at)
    }

    @Test
    fun gapOfFiveMinutesInsertsARowAndAnythingShorterDoesNot() {
        val shortGap = buildChatItems(
            listOf(ui("m1", at = MINUTE_MS), ui("m2", at = MINUTE_MS + CHAT_TIME_GAP_MS - 1)),
        )
        assertEquals(1, shortGap.count { it is ChatItem.Time })

        val fullGap = buildChatItems(
            listOf(ui("m1", at = MINUTE_MS), ui("m2", at = MINUTE_MS + CHAT_TIME_GAP_MS)),
        )
        assertEquals(2, fullGap.count { it is ChatItem.Time })
        assertEquals("message-m2", fullGap.last().key)
    }

    @Test
    fun rowsKeepAscendingOrderAndUniqueKeys() {
        val messages = (1..5).map { ui("m$it", at = it * MINUTE_MS) }

        val items = buildChatItems(messages)

        assertEquals(items.size, items.map { it.key }.distinct().size)
        val ids = items.filterIsInstance<ChatItem.Message>().map { it.message.id }
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), ids)
    }

    private fun ui(id: String, at: Long): ChatMessageUi = ChatMessageUi(
        id = id,
        sender = MpActorType.USER,
        type = MpMessageType.TEXT,
        content = id,
        createdAt = at,
    )

    private companion object {
        const val MINUTE_MS: Long = 60_000L
    }
}
