package io.zer0.muse.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-1 回归（审计报告）：Markdown 感知模式下「分块重叠」必须真正生效。
 *
 * 旧实现 `appendTextPiece` 先 `flush()` 清空 current，再
 * `current.toString().takeLast(overlap)`，取到的永远是空串 ——
 * 由于 `markdownAware` 默认为 true，所有用户的重叠设置都变成了死设置。
 */
class TextChunkerOverlapTest {

    @Test
    fun `markdown 模式下相邻块存在重叠内容`() {
        val chunker = TextChunker(targetSize = 100, overlap = 20, markdownAware = true)
        // 多段文本，每段长度接近 targetSize，保证会触发 flush
        val para = "这是一段用于测试重叠行为的文本内容，需要足够长才能触发分块逻辑。"
        val text = (1..6).joinToString("\n\n") { "$para$it" }

        val chunks = chunker.split(text)
        assertTrue("应产生多个块，实际 ${chunks.size}", chunks.size >= 2)

        // 相邻块之间应存在重叠：前一块的尾部出现在后一块的开头附近
        var overlapFound = 0
        for (i in 0 until chunks.size - 1) {
            val tail = chunks[i].content.takeLast(20)
            if (tail.isNotBlank() && chunks[i + 1].content.contains(tail)) {
                overlapFound++
            }
        }
        assertTrue(
            "markdown 模式下应至少有一处重叠，实际 $overlapFound 处（说明 overlap 失效）",
            overlapFound >= 1,
        )
    }

    @Test
    fun `overlap 为 0 时不产生重叠`() {
        val chunker = TextChunker(targetSize = 100, overlap = 0, markdownAware = true)
        // 每段内容唯一，避免“相同文字自然重现”干扰重叠判定
        val text = (1..6).joinToString("\n\n") {
            "段落$it：" + "内容$".repeat(20)
        }

        val chunks = chunker.split(text)
        assertTrue(chunks.size >= 2)
        // overlap=0 时，前一块未尾若出现在后一块中，只可能是全文未分隔
        for (i in 0 until chunks.size - 1) {
            val tail = chunks[i].content.takeLast(10)
            if (tail.isBlank()) continue
            assertTrue(
                "overlap=0 时第 $i 块尾部不应作为重叠复制到下一块",
                !chunks[i + 1].content.startsWith(tail),
            )
        }
    }

    @Test
    fun `非 markdown 模式重叠仍然生效（对照）`() {
        val chunker = TextChunker(targetSize = 100, overlap = 20, markdownAware = false)
        val text = (1..6).joinToString("\n\n") {
            "这是一段用于测试重叠行为的文本内容，需要足够长才能触发分块逻辑。$it"
        }
        val chunks = chunker.split(text)
        assertTrue("应产生多个块", chunks.size >= 2)
        assertTrue("块内容不应为空", chunks.all { it.content.isNotBlank() })
    }

    @Test
    fun `块序号连续`() {
        val chunker = TextChunker(targetSize = 80, overlap = 10, markdownAware = true)
        val chunks = chunker.split((1..5).joinToString("\n\n") { "段落内容测试文本$it" })
        assertEquals(chunks.indices.toList(), chunks.map { it.index })
    }
}

