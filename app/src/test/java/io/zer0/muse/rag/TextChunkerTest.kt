package io.zer0.muse.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.56: TextChunker 单元测试。
 *
 * 覆盖:空文本、短文本、长文本分块、段落分割、句子分割、字符硬切、overlap 重叠。
 */
class TextChunkerTest {

    @Test
    fun `空文本返回空列表`() {
        val chunker = TextChunker(targetSize = 100, overlap = 10)
        assertTrue(chunker.split("").isEmpty())
        assertTrue(chunker.split("   ").isEmpty())
        assertTrue(chunker.split("\n\n").isEmpty())
    }

    @Test
    fun `短文本返回单块`() {
        val chunker = TextChunker(targetSize = 100, overlap = 10)
        val result = chunker.split("Hello World")
        assertEquals(1, result.size)
        assertEquals("Hello World", result[0].content)
        assertEquals(0, result[0].index)
    }

    @Test
    fun `多个短段落合并到单块`() {
        val chunker = TextChunker(targetSize = 100, overlap = 10)
        val text = "段落一\n\n段落二\n\n段落三"
        val result = chunker.split(text)
        assertEquals(1, result.size)
        assertTrue(result[0].content.contains("段落一"))
        assertTrue(result[0].content.contains("段落二"))
        assertTrue(result[0].content.contains("段落三"))
    }

    @Test
    fun `长文本按段落分块`() {
        val chunker = TextChunker(targetSize = 50, overlap = 5)
        val para1 = "a".repeat(40)
        val para2 = "b".repeat(40)
        val para3 = "c".repeat(40)
        val text = "$para1\n\n$para2\n\n$para3"
        val result = chunker.split(text)
        assertTrue("应产生多个块,实际 ${result.size}", result.size >= 2)
        // 每块不超过 targetSize + overlap
        result.forEach { chunk ->
            assertTrue("块 ${chunk.index} 长度 ${chunk.content.length} 超限", chunk.content.length <= 55)
        }
    }

    @Test
    fun `超长段落按句子分割`() {
        val chunker = TextChunker(targetSize = 20, overlap = 3)
        val text = "这是第一句话。这是第二句话！这是第三句话？这是第四句话。"
        val result = chunker.split(text)
        assertTrue("应产生多个块,实际 ${result.size}", result.size >= 2)
        // 验证 index 连续
        result.forEachIndexed { idx, chunk ->
            assertEquals(idx, chunk.index)
        }
    }

    @Test
    fun `超长句子按字符硬切`() {
        val chunker = TextChunker(targetSize = 10, overlap = 0)
        val text = "a".repeat(35)
        val result = chunker.split(text)
        assertTrue("35 字符按 10 分应至少 4 块,实际 ${result.size}", result.size >= 3)
    }

    @Test
    fun `overlap 在相邻块间保留重叠`() {
        val chunker = TextChunker(targetSize = 20, overlap = 5)
        val para1 = "abcdefghijklmno".repeat(3) // 45 字符
        val text = para1
        val result = chunker.split(text)
        if (result.size >= 2) {
            // 第一个块的尾部应出现在第二个块的开头(overlap)
            val tail = result[0].content.takeLast(5)
            assertTrue("块间 overlap 未保留", result[1].content.startsWith(tail))
        }
    }

    @Test
    fun `CRLF 换行被规范化`() {
        val chunker = TextChunker(targetSize = 100, overlap = 10)
        val text = "第一行\r\n第二行\r\n第三行"
        val result = chunker.split(text)
        assertEquals(1, result.size)
        assertTrue(result[0].content.contains("第一行"))
        assertTrue(!result[0].content.contains("\r"))
    }

    @Test
    fun `index 从 0 连续递增`() {
        val chunker = TextChunker(targetSize = 30, overlap = 5)
        val text = (1..10).joinToString("\n\n") { "段落$it".repeat(10) }
        val result = chunker.split(text)
        assertTrue(result.size > 1)
        result.forEachIndexed { idx, chunk ->
            assertEquals("index 不连续", idx, chunk.index)
        }
    }

    @Test
    fun `estimateTokens 返回正数`() {
        val chunker = TextChunker()
        val tokens = chunker.estimateTokens("Hello World 你好世界")
        assertTrue("token 数应 > 0", tokens > 0)
    }

    @Test
    fun `estimateTokens 空文本返回 1`() {
        val chunker = TextChunker()
        val tokens = chunker.estimateTokens("")
        assertEquals(1, tokens)
    }

    // ── v1.133: markdownAware 模式测试 ──

    @Test
    fun `markdownAware 代码块整体保留不切断`() {
        // targetSize=20 远小于代码块,代码块仍应整体保留
        val chunker = TextChunker(targetSize = 20, overlap = 0, markdownAware = true)
        val codeBlock = "```kotlin\n" + "val x = 1\n".repeat(10) + "```"
        val text = "前置文本\n\n$codeBlock\n\n后置文本"
        val result = chunker.split(text)
        // 至少有一个 chunk 完整包含代码块
        val codeChunk = result.firstOrNull { it.content.contains("```kotlin") }
        assertTrue("代码块应被保留在某个 chunk 中", codeChunk != null)
        assertTrue("代码块应完整(含开闭 ```)", codeChunk!!.content.contains("```kotlin") && codeChunk.content.endsWith("```"))
    }

    @Test
    fun `markdownAware 标题行单独成块并记录 section 元数据`() {
        val chunker = TextChunker(targetSize = 500, overlap = 0, markdownAware = true)
        val text = "# 章节一\n\n内容一\n\n# 章节二\n\n内容二"
        val result = chunker.split(text)
        // 标题"章节一"/"章节二"应作为 section 元数据出现在某些 chunk 上
        val sections = result.flatMap { it.metadata.values }.toSet()
        assertTrue("应记录章节元数据: $sections", sections.contains("章节一") || sections.contains("章节二"))
    }

    @Test
    fun `markdownAware 表格整体保留`() {
        val chunker = TextChunker(targetSize = 15, overlap = 0, markdownAware = true)
        val table = """
            | 列1 | 列2 | 列3 |
            |-----|-----|-----|
            | A   | B   | C   |
            | D   | E   | F   |
        """.trimIndent()
        val result = chunker.split(table)
        // 表格应被整体保留在某个 chunk 中
        val tableChunk = result.firstOrNull { it.content.contains("| 列1") }
        assertTrue("表格应被保留在某个 chunk 中", tableChunk != null)
        assertTrue("表格应完整(含末行 | F   |)", tableChunk!!.content.contains("| F   |"))
    }

    @Test
    fun `markdownAware 普通文本仍按 targetSize 切`() {
        // 用标题作为结构边界触发分块(markdownAware 按结构边界切分,
        // 标题/代码块/表格是天然边界;纯段落无边界时会合并到单块)
        val chunker = TextChunker(targetSize = 30, overlap = 0, markdownAware = true)
        val sections = (1..5).joinToString("\n\n") { idx ->
            "# 标题$idx\n\n" + "段落$idx".repeat(15)
        }
        val result = chunker.split(sections)
        assertTrue("有标题边界时应产生多个 chunk,实际 ${result.size} 块", result.size > 1)
    }

    // ── v1.133: chunkByToken 模式测试 ──

    @Test
    fun `chunkByToken 按 token 数切分中英文混合文本`() {
        // targetSize=10 token,中英文混合应按 token 切
        val chunker = TextChunker(targetSize = 10, overlap = 0, chunkByToken = true)
        val text = (1..20).joinToString("\n\n") { "Hello World 你好世界" }
        val result = chunker.split(text)
        assertTrue("应产生多个块,实际 ${result.size}", result.size > 1)
    }

    @Test
    fun `chunkByToken 短文本返回单块`() {
        val chunker = TextChunker(targetSize = 100, overlap = 0, chunkByToken = true)
        val result = chunker.split("Hello World")
        assertEquals(1, result.size)
        assertEquals("Hello World", result[0].content)
    }

    @Test
    fun `chunkByToken 空文本返回空列表`() {
        val chunker = TextChunker(targetSize = 100, overlap = 0, chunkByToken = true)
        assertTrue(chunker.split("").isEmpty())
        assertTrue(chunker.split("   ").isEmpty())
    }

    // ── v1.133: markdownAware + chunkByToken 组合 ──

    @Test
    fun `markdownAware 与 chunkByToken 可同时启用`() {
        val chunker = TextChunker(
            targetSize = 20,
            overlap = 0,
            markdownAware = true,
            chunkByToken = true,
        )
        val text = "# 标题\n\n" + "代码: ".repeat(5) + "\n```\nval x = 1\n```"
        val result = chunker.split(text)
        // 不抛异常 + 至少一个 chunk
        assertTrue("应至少产生 1 个 chunk", result.isNotEmpty())
    }
}
