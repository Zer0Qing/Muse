package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片身份与数据绑定的单测。
 *
 * 卡片的身份是承载它的消息 id，而模型看不到这个 id。这条链路上任何一环断了，
 * 表现都只是「卡片数据更新了但卡片没变」，不报错、不打日志 —— 所以用测试钉住。
 */
class ShowCardToolTest {

    @Test
    fun `省略 card_id 时指向当前消息`() {
        assertEquals("msg-1", ShowCardTool.resolveCardTarget(null, "msg-1"))
        assertEquals("msg-1", ShowCardTool.resolveCardTarget("", "msg-1"))
        assertEquals("msg-1", ShowCardTool.resolveCardTarget("   ", "msg-1"))
    }

    @Test
    fun `显式给的 card_id 优先于当前消息`() {
        assertEquals("msg-old", ShowCardTool.resolveCardTarget("msg-old", "msg-now"))
        assertEquals("msg-old", ShowCardTool.resolveCardTarget("  msg-old  ", "msg-now"))
    }

    @Test
    fun `没有上下文消息 id 时解析为空`() {
        assertEquals("", ShowCardTool.resolveCardTarget(null, null))
        assertEquals("", ShowCardTool.resolveCardTarget("", null))
    }

    @Test
    fun `回执里带上卡片绑定的消息 id`() {
        val result = ShowCardTool.execute(
            args = mapOf("title" to "demo", "code" to "<div>hi</div>"),
            messageId = "msg-42",
        )

        assertTrue("模型需要知道这张卡绑在哪条消息上", result.contains("msg-42"))
    }

    @Test
    fun `没有消息 id 时回执不多嘴`() {
        val result = ShowCardTool.execute(
            args = mapOf("title" to "demo", "code" to "<div>hi</div>"),
            messageId = null,
        )

        assertFalse(result.contains("Card bound to message"))
        assertTrue(result.contains("rendered"))
    }
}
