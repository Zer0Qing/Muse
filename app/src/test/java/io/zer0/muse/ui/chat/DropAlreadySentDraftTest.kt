package io.zer0.muse.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 二-13 的判据：崩溃正好落在「outbox 已落库」与「草稿已清空」之间时，
 * 库里那条草稿就是已经发出去的那句话，恢复回去等于邀请用户再发一次。
 *
 * 这里钉住判据本身（而不是整条切换链路），因为链路那端全是 mock，
 * 断言容易跟着实现一起漂；判据是纯函数，出问题只可能是判断条件本身。
 */
class DropAlreadySentDraftTest {

    @Test
    fun `与待发原文逐字相同的草稿判为已发送并丢弃`() {
        val draft = "hellokillmidrun"
        assertEquals("", dropAlreadySentDraft(draft, listOf("hellokillmidrun")))
    }

    @Test
    fun `多条待发原文中命中任意一条同样丢弃`() {
        assertEquals("", dropAlreadySentDraft("重发的那句", listOf("别的", "重发的那句")))
    }

    @Test
    fun `没发出去的草稿照常还回去`() {
        val draft = "昨天没发出去的一段话"
        assertEquals(draft, dropAlreadySentDraft(draft, listOf("另一条已发送的文本")))
    }

    @Test
    fun `空草稿保持为空`() {
        assertEquals("", dropAlreadySentDraft("   ", emptyList()))
        assertEquals("", dropAlreadySentDraft("", listOf("   ")))
    }

    @Test
    fun `没有待发队列时不吞掉任何草稿`() {
        val draft = "只是打了一半"
        assertEquals(draft, dropAlreadySentDraft(draft, emptyList()))
    }

    @Test
    fun `只有前缀相同不算已发送`() {
        // 判据是整段原文相等：把"hello"从"hello world"的待发记录里判成已发送，
        // 会误删用户真的还没发出去的内容。
        assertEquals("hello", dropAlreadySentDraft("hello", listOf("hello world")))
    }
}
