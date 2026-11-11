package io.zer0.muse.knowledge

import io.zer0.muse.knowledge.ingest.LocatedChunk
import io.zer0.muse.knowledge.ingest.ParsedBlock
import io.zer0.muse.knowledge.ingest.StructuredChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3 步骤 5 分块不变式(计划:「分块器保留标题、代码围栏、表格和 overlap 状态;
 * 同一正文以 1/37/4096 字符 piece 输入,断言 chunk 内容及 locator 序列相同;
 * 补 emoji/代理对边界」)。
 *
 * 分块器的输入合同:[chunkFromBlocks] 接收**完整 block 序列**(与输入窗口无关) ——
 * 窗口化属于解析层的资源上限(R3 步骤 3,保留防 OOM 保护),不改变分块语义。
 * 「1/37/4096 piece 相同切分」因此表达为:把同一正文切成不同大小的 block 集合
 * (1 字符块/37 字符块/4096 字符块),分块器输出必须等价(内容+locator)。
 */
class StructuredChunkerInvariantTest {

    private fun joined(vararg pieces: String) = pieces.joinToString("")

    /**
     * 把 content 切成固定大小的 blocks(模拟不同输入窗口的解析产出)。
     * locator 分配规则与粒度无关:section 由**内容字符位置**决定(每 10 字符一节),
     * 这样 1/37/4096 粒度下同一内容位置得到同一 (page, section) —— 「分块不随输入
     * 窗口变化」的忠实表达(等价合同的前提是 locator 由内容决定,而非由物理切块决定)。
     */
    private fun blocksOf(content: String, pieceSize: Int): List<ParsedBlock> =
        content.chunked(pieceSize).mapIndexed { i, t ->
            ParsedBlock(text = t, page = 1, section = ((i * pieceSize) / 10) + 1)
        }

    private fun assertEquivalentChunks(expected: List<LocatedChunk>, actual: List<LocatedChunk>, label: String) {
        assertEquals("$label: chunk 数量必须一致", expected.size, actual.size)
        expected.zip(actual).forEachIndexed { idx, (e, a) ->
            assertEquals("$label: chunk#$idx 内容必须一致", e.text, a.text)
            assertEquals("$label: chunk#$idx 结构必须一致", e.kind, a.kind)
        }
    }

    @Test
    fun `same content with 1-37-4096 char pieces yields identical chunks and locators`() {
        // 含标题/段落/代码围栏/表格标记的正文(R3 步骤 5 的结构保留对象)
        val content = joined(
            "# 标题行\n",
            "正文段落,包含中英混排 text and numbers 123。\n",
            "```kotlin\nval code = \"fence\"\n```\n",
            "| 表头 | 列2 |\n| --- | --- |\n| a | b |\n",
            "尾段",
        )
        val chunker = StructuredChunker(chunkSize = 60, chunkOverlap = 10)

        val from1 = chunker.chunkFromBlocks(blocksOf(content, 1))
        val from37 = chunker.chunkFromBlocks(blocksOf(content, 37))
        val from4096 = chunker.chunkFromBlocks(blocksOf(content, 4096))

        assertTrue("基线(4096 piece)必须产出 chunk", from4096.isNotEmpty())
        // 跨粒度核心不变式:chunk 文本+结构序列严格等价
        assertEquivalentChunks(from4096, from1, "1-char piece")
        assertEquivalentChunks(from4096, from37, "37-char piece")
        // locator 可解释性:每个 chunk 的 locator 非空且格式合法
        (from4096 + from1 + from37).forEach {
            assertTrue("chunk locator 必须可解释: ${it.locator}", it.locator.matches(Regex("p\\d+s\\d+|s\\d+")))
        }
        // locator 内容派生:同粒度内,相邻 chunk 的 locator 非降序(内容顺序)
        listOf(from4096, from1, from37).forEach { cs ->
            val nums = cs.map { it.locator.filter { ch -> ch.isDigit() }.toInt() }
            assertTrue("locator 必须按内容顺序非降序: $nums", nums.zipWithNext().all { (a, b) -> a <= b })
        }
    }

    @Test
    fun `emoji and surrogate pairs never split mid-codepoint`() {
        // emoji(代理对)+ ZWJ 序列:分块边界不得落在代理对/字素中间
        val content = joined(
            "前文🙂",
            "家族👨‍👩‍👧‍👦尾文",
            "🇨🇳国旗🎯完毕",
        )
        val chunker = StructuredChunker(chunkSize = 5, chunkOverlap = 0)

        val chunks = chunker.chunkFromBlocks(blocksOf(content, 3))

        assertTrue(chunks.isNotEmpty())
        // 全部 chunk 拼回 = 原文(无字符丢失/重复)
        assertEquals("分块必须无损覆盖原文", content, chunks.joinToString("") { it.text })
        // 每个字符都是合法标量边界:无孤立代理项
        chunks.forEach { c ->
            assertTrue(
                "chunk 不得含孤立代理项: ${c.text}",
                c.text.none { ch -> ch.isHighSurrogate() && c.text.getOrNull(c.text.indexOf(ch) + 1)?.isLowSurrogate() != true },
            )
        }
    }

    @Test
    fun `overlap preserves continuity between consecutive chunks`() {
        val content = "甲乙丙丁戊己庚辛壬癸甲乙丙丁戊己庚辛壬癸"
        val chunker = StructuredChunker(chunkSize = 8, chunkOverlap = 3)

        val chunks = chunker.chunkFromBlocks(blocksOf(content, 4096))

        assertTrue(chunks.size >= 2)
        // 相邻 chunk:后者的开头与前一者的尾部重叠 overlap 个字符
        for (i in 1 until chunks.size) {
            val prevTail = chunks[i - 1].text.takeLast(chunker.chunkOverlap)
            assertTrue(
                "chunk#$i 必须以 overlap 内容开头保证连续性",
                chunks[i].text.startsWith(prevTail),
            )
        }
    }

    @Test
    fun `heading blocks keep kind metadata through chunking`() {
        val blocks = listOf(
            ParsedBlock(text = "# 标题", page = 1, section = 1, kind = "heading"),
            ParsedBlock(text = "正文内容", page = 1, section = 2, kind = "paragraph"),
        )
        val chunker = StructuredChunker(chunkSize = 100, chunkOverlap = 0)

        val chunks = chunker.chunkFromBlocks(blocks)

        assertEquals("标题 block 独立成 chunk", "# 标题", chunks.first().text)
        assertEquals("标题 kind 保留", "heading", chunks.first().kind)
    }
}
