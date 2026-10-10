package io.zer0.muse.ui

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「本次新并入摘要」条数计算测试（B3 压缩编排补测）。
 *
 * 函数注释里写明的易错点：消息 id 是随机 UUID 无序，任何基于 id 排序的推断
 * 都会在真实数据上算错——必须用有序消息列表数。
 */
class NewlyCoveredCountTest {

    private fun msg(content: String): UIMessage = UIMessage(
        id = Uuid.random(),
        role = MessageRole.USER,
        content = content,
    )

    @Test
    fun `empty covered ids count zero`() {
        val messages = listOf(msg("a"), msg("b"))
        assertEquals(0, ChatViewModel.newlyCoveredCountStatic(messages, emptySet(), null))
    }

    @Test
    fun `no previous boundary counts all covered`() {
        val m1 = msg("a")
        val m2 = msg("b")
        val m3 = msg("c")
        val messages = listOf(m1, m2, m3)
        assertEquals(3, ChatViewModel.newlyCoveredCountStatic(messages, setOf(m1.id.toString(), m2.id.toString(), m3.id.toString()), null))
    }

    @Test
    fun `previous boundary splits count to messages after it`() {
        val m1 = msg("a")
        val m2 = msg("b")
        val m3 = msg("c")
        val messages = listOf(m1, m2, m3)
        // 边界是 m2 → 只数 m3
        assertEquals(1, ChatViewModel.newlyCoveredCountStatic(messages, setOf(m2.id.toString(), m3.id.toString()), m2.id.toString()))
    }

    @Test
    fun `boundary not found falls back to counting from start`() {
        val m1 = msg("a")
        val m2 = msg("b")
        val messages = listOf(m1, m2)
        assertEquals(2, ChatViewModel.newlyCoveredCountStatic(messages, setOf(m1.id.toString(), m2.id.toString()), "missing-boundary"))
    }

    @Test
    fun `count only messages inside covered set`() {
        val m1 = msg("a")
        val m2 = msg("b")
        val m3 = msg("c")
        val messages = listOf(m1, m2, m3)
        // 覆盖集合只含 m1/m3（m2 未被压缩）
        assertEquals(2, ChatViewModel.newlyCoveredCountStatic(messages, setOf(m1.id.toString(), m3.id.toString()), null))
    }
}
