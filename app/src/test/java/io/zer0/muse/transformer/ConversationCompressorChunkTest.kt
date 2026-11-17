package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConversationCompressor] 分块与预算逻辑单测。
 *
 * 覆盖 2026-09-29 审查发现:「单块 prompt 上限 ≈38.4 万字符(256×1500)必然超压缩模型窗口」。
 */
class ConversationCompressorChunkTest {
    private fun msg(chars: Int): UIMessage = UIMessage(role = MessageRole.USER, content = "x".repeat(chars))

    @Test
    fun `单块预算按模型窗口计算并受上下限约束`() {
        // 窗口未知 → 保守默认(32k × 0.4)
        assertEquals(12_800, ConversationCompressor.chunkBudgetChars(null))
        assertEquals(12_800, ConversationCompressor.chunkBudgetChars(0))
        // 大窗口 → 不超过上限(避免单块过大)
        assertEquals(48_000, ConversationCompressor.chunkBudgetChars(512_000))
        // 小窗口 → 不低于下限(否则会切得过碎)
        assertEquals(6_000, ConversationCompressor.chunkBudgetChars(8_000))
    }

    @Test
    fun `长会话按字符预算切块而不是切出一个超窗口巨块`() {
        // 旧实现:256 条(上限)× 1500 字符(单条上限)= 384_000 字符挤在一个 prompt 里
        val messages = List(256) { msg(1_500) }
        val budget = ConversationCompressor.chunkBudgetChars(null)
        val chunks = ConversationCompressor.chunkMessages(messages, budget)

        assertTrue("应切成多块,实际 ${chunks.size} 块", chunks.size >= 30)
        chunks.forEach { chunk ->
            val chars = chunk.sumOf { minOf(it.content.length, 1_500) }
            assertTrue("单块 $chars 字符超出预算 $budget(块内 ${chunk.size} 条)", chars <= budget)
        }
        assertEquals("切块不能丢消息", 256, chunks.sumOf { it.size })
    }

    @Test
    fun `条数上限依然生效`() {
        val messages = List(300) { msg(10) }
        val chunks = ConversationCompressor.chunkMessages(messages, 1_000_000)
        assertEquals(2, chunks.size)
        assertEquals(256, chunks[0].size)
        assertEquals(44, chunks[1].size)
    }

    @Test
    fun `超长消息按截断后的长度计费且不丢消息`() {
        // 送进 prompt 的单条消息会被截断到 MAX_MSG_CHARS(1500),分块按截断后的长度计费:
        // 10 条 2 万字符的消息 ≈ 每条只算 1500 → 预算 5000 时每块 3 条
        val messages = List(10) { msg(20_000) }
        val budget = 5_000
        val chunks = ConversationCompressor.chunkMessages(messages, budget)

        assertEquals(4, chunks.size)
        assertEquals(listOf(3, 3, 3, 1), chunks.map { it.size })
        assertEquals("切块不能丢消息", 10, chunks.sumOf { it.size })
        chunks.forEach { chunk ->
            val chars = chunk.sumOf { minOf(it.content.length, 1_500) }
            assertTrue("单块 $chars 字符超出预算 $budget", chars <= budget)
        }
    }

    @Test
    fun `空列表返回空块`() {
        assertTrue(ConversationCompressor.chunkMessages(emptyList(), 1_000).isEmpty())
    }
    @Test
    fun `已否定段单独抽出并保留到下一个同级标题前`() {
        val summary = """
            ### 已完成
            - 把 Node 运行环境移出主包
            ### 已否定
            - 不再使用 HTML 卡片承载安装卡
            - 不做 PWA 下线
            ### 待办
            - 真机验证
        """.trimIndent()
        assertEquals(
            "- 不再使用 HTML 卡片承载安装卡\n- 不做 PWA 下线",
            ConversationCompressor.extractNegatedSection(summary),
        )
    }

    @Test
    fun `没有已否定段时返回空串`() {
        val summary = "### 已完成\n- a\n### 待办\n- b"
        assertEquals("", ConversationCompressor.extractNegatedSection(summary))
        assertEquals("", ConversationCompressor.extractNegatedSection(""))
    }

    @Test
    fun `显式无内容的已否定段不算遗漏`() {
        val summary = "### 已否定\n- 无"
        assertEquals("- 无", ConversationCompressor.extractNegatedSection(summary))
    }
}
