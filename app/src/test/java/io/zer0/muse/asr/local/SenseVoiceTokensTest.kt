package io.zer0.muse.asr.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SenseVoiceTokensTest {

    @Test
    fun `parse handles tokens containing spaces and blank lines`() {
        val content = """
            <unk> 0
            ▁你 1
            好 2
            the world 3
            <|zh|> 4

        """.trimIndent()
        val vocab = SenseVoiceTokens.parse(content)
        assertEquals(5, vocab.size)
        assertEquals("<unk>", vocab[0])
        assertEquals("▁你", vocab[1])
        assertEquals("the world", vocab[3])
        assertEquals("<|zh|>", vocab[4])
    }

    @Test
    fun `parse ignores malformed lines`() {
        val vocab = SenseVoiceTokens.parse("noid\nx abc 1\n 2\n")
        assertEquals(1, vocab.size)
        assertEquals("x abc", vocab[1])
    }

    /** CTC greedy：连续重复合并、blank(0) 丢弃、跨 blank 的重复保留。 */
    @Test
    fun `ctcGreedy dedupes and removes blanks`() {
        val ids = intArrayOf(1, 1, 1, 0, 0, 2, 2, 3, 1, 1)
        assertEquals(listOf(1, 2, 3, 1), SenseVoiceTokens.ctcGreedy(ids))
    }

    @Test
    fun `ctcGreedy all blank yields empty`() {
        assertTrue(SenseVoiceTokens.ctcGreedy(intArrayOf(0, 0, 0)).isEmpty())
    }

    /** 解码跳过前 4 个控制 token（语言/情绪/事件/ITN），并去掉 ▁ 词边界。 */
    @Test
    fun `decode skips leading control tokens and strips word boundary`() {
        val vocab = mapOf(
            10 to "<|zh|>",
            11 to "<|NEUTRAL|>",
            12 to "<|Speech|>",
            13 to "<|withitn|>",
            14 to "▁今天",
            15 to "天气",
            16 to "▁不错",
        )
        val ids = listOf(10, 11, 12, 13, 14, 15, 16)
        assertEquals("今天天气不错", SenseVoiceTokens.decode(ids, vocab))
    }

    /** 未知 id 静默跳过，不抛异常（模型输出 id 越界时仍能出部分文本）。 */
    @Test
    fun `decode skips unknown ids`() {
        val vocab = mapOf(5 to "好")
        // 前 4 位是控制序列占位（哪怕 id 未知也按位置跳过），之后未知 id 丢弃、已知 id 出字
        assertEquals("好", SenseVoiceTokens.decode(listOf(100, 101, 102, 103, 999, 5, -1), vocab))
    }

    @Test
    fun `leading control count is 4`() {
        assertEquals(4, SenseVoiceTokens.LEADING_CONTROL_COUNT)
    }
}
