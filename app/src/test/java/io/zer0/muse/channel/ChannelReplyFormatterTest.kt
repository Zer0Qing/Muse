package io.zer0.muse.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渠道回复整形：拆段与压空行。
 *
 * 重点覆盖三件事：QQ 一条入站消息最多 5 次被动回复、段落之间不能留下大空行、
 * 超过上限时合并尾部而不是硬切文本。
 */
class ChannelReplyFormatterTest {

    @Test
    fun `单段纯文本原样保留`() {
        val plan = ChannelReplyFormatter.plan("你好，今天怎么样")
        assertEquals(listOf("你好，今天怎么样"), plan)
    }

    @Test
    fun `空行分隔的多段落被拆成多条`() {
        val plan = ChannelReplyFormatter.plan("第一段\n\n第二段\n\n第三段")
        assertEquals(listOf("第一段", "第二段", "第三段"), plan)
    }

    @Test
    fun `连续空行不会留下空段`() {
        val plan = ChannelReplyFormatter.plan("开头\n\n\n\n结尾")
        assertEquals(listOf("开头", "结尾"), plan)
    }

    @Test
    fun `超过五段时合并尾部`() {
        val text = (1..8).joinToString("\n\n") { "第${it}段" }
        val plan = ChannelReplyFormatter.plan(text)
        // QQ 一条入站消息最多 5 次被动回复，第 5 段必须把 5~8 段并进去
        assertEquals(ChannelReplyFormatter.MAX_SEGMENTS, plan.size)
        assertTrue("末段应包含被合并的内容", plan.last().contains("第8段"))
        assertTrue("末段应包含第5段", plan.last().contains("第5段"))
    }

    @Test
    fun `全空白回复拆出空列表`() {
        assertEquals(emptyList<String>(), ChannelReplyFormatter.plan("   \n\n \t \n"))
    }

    @Test
    fun `单段内的多余空行被压平`() {
        val flat = ChannelReplyFormatter.flatten("第一行\n\n\n第二行")
        assertEquals("第一行\n第二行", flat)
    }

    @Test
    fun `段内的单换行保留`() {
        // 段内换行是排版的一部分（比如列表），不能一并压掉
        val plan = ChannelReplyFormatter.plan("第1行\n第2行")
        assertEquals(listOf("第1行\n第2行"), plan)
    }

    @Test
    fun `markdown 标记不被改写`() {
        val text = "**重点**\n\n```kotlin\nval a = 1\n```"
        val plan = ChannelReplyFormatter.plan(text)
        assertEquals(2, plan.size)
        assertTrue(plan[0].contains("**重点**"))
        assertTrue(plan[1].contains("```kotlin"))
    }

    @Test
    fun `CRLF 被归一`() {
        val plan = ChannelReplyFormatter.plan("甲\r\n\r\n乙")
        assertEquals(listOf("甲", "乙"), plan)
    }

    @Test
    fun `max 参数非法时返回空`() {
        assertEquals(emptyList<String>(), ChannelReplyFormatter.segments("甲\n\n乙", max = 0))
    }
}
