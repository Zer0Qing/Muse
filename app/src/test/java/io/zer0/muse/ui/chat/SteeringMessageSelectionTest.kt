package io.zer0.muse.ui.chat

import io.zer0.muse.ui.PendingMessage
import io.zer0.muse.ui.selectSteeringMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插话取件判定（selectSteeringMessages）。
 *
 * 核心是防跨会话污染：把属于别的会话的排队消息当成插话注入，会让用户在对话里看到
 * 自己没说过的话，而那句话还会成为模型后续推理的依据。所以归属不明的条目一律不取，
 * 留在队列里走原有发送路径 —— 漏取只是晚一步，错取是数据污染。
 */
class SteeringMessageSelectionTest {

    @Test
    fun `only messages enqueued in the same session are steerable`() {
        val queue = listOf(
            PendingMessage("A 会话的话", sessionId = SESSION_A),
            PendingMessage("B 会话的话", sessionId = SESSION_B),
        )

        val selected = selectSteeringMessages(queue, SESSION_A)

        assertEquals(listOf("A 会话的话"), selected.map { it.text })
    }

    @Test
    fun `message with unknown session is not steerable`() {
        val queue = listOf(
            PendingMessage("入队时没有会话", sessionId = null),
            PendingMessage("旧数据没这个字段", sessionId = null),
        )

        assertTrue(selectSteeringMessages(queue, SESSION_A).isEmpty())
    }

    @Test
    fun `message with images is not steerable even in the same session`() {
        val queue = listOf(
            PendingMessage("带图的消息", images = listOf("base64"), sessionId = SESSION_A),
            PendingMessage("纯文本", sessionId = SESSION_A),
        )

        assertEquals(listOf("纯文本"), selectSteeringMessages(queue, SESSION_A).map { it.text })
    }

    @Test
    fun `blank text is not steerable`() {
        val queue = listOf(PendingMessage("   ", sessionId = SESSION_A))

        assertTrue(selectSteeringMessages(queue, SESSION_A).isEmpty())
    }

    private companion object {
        const val SESSION_A = "session-a"
        const val SESSION_B = "session-b"
    }
}
