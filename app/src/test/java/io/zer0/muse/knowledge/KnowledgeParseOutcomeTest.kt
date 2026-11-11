package io.zer0.muse.knowledge

import io.zer0.muse.knowledge.ingest.KnowledgeParserRegistry
import io.zer0.muse.knowledge.ingest.ParseCoverage
import io.zer0.muse.knowledge.ingest.ParsedBlock
import io.zer0.muse.knowledge.ingest.ParseOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3 步骤 1 合同(计划:「先用扫描 PDF、页数上限、单页失败、无文字、加密/损坏文档
 * 断言准确状态;错误提示不得出现在任何 chunk 内容中」)。
 *
 * 判定用[KnowledgeParserRegistry.classify]按解析产出分类;夹具为合成
 * ParsedBlock 列表(不触真实 PDF 解码 —— PDF 二进制合同在 R0 夹具已钉,解码路径
 * 依赖真实 PDF 库,属 R3 后续接线)。
 */
class KnowledgeParseOutcomeTest {

    private fun blocks(vararg texts: String) = texts.mapIndexed { i, t ->
        ParsedBlock(text = t, page = i + 1, section = 1)
    }

    // ---------- 准确语义:非 Complete 不得伪装成功 ----------

    @Test
    fun `partial coverage with note is not complete`() {
        val outcome = ParseOutcome(
            coverage = ParseCoverage.PARTIAL,
            blocks = blocks("第1页正文", "第2页正文"),
            coverageNote = "页数上限 200,第 201-260 页未解析",
        )
        assertEquals(ParseCoverage.PARTIAL, KnowledgeParserRegistry.classify(outcome))
        assertFalse("PARTIAL 不得报 Complete", outcome.isUsableFullText)
        assertTrue("PARTIAL 必须带覆盖说明", outcome.coverageNote.isNotBlank())
    }

    @Test
    fun `no text is not an empty complete`() {
        val outcome = ParseOutcome(coverage = ParseCoverage.NO_TEXT, blocks = emptyList())
        assertEquals(ParseCoverage.NO_TEXT, KnowledgeParserRegistry.classify(outcome))
        assertFalse("无文字不得报 Complete", outcome.isUsableFullText)
    }

    @Test
    fun `failed parse carries error outside blocks`() {
        val err = "无法解析: 文档已加密"
        val outcome = ParseOutcome(coverage = ParseCoverage.FAILED, blocks = emptyList(), error = err)
        assertEquals(ParseCoverage.FAILED, KnowledgeParserRegistry.classify(outcome))
        assertFalse(outcome.isUsableFullText)
        // 错误提示不得出现在任何 chunk 内容中:FAILED 无 blocks,错误串只在 error 字段
        assertTrue("FAILED 不得产出任何块", outcome.blocks.isEmpty())
        assertTrue("错误串只存在于 error 字段", outcome.blocks.none { it.text.contains(err) })
    }

    @Test
    fun `complete requires full blocks and empty error`() {
        val outcome = ParseOutcome(coverage = ParseCoverage.COMPLETE, blocks = blocks("全", "文"))
        assertEquals(ParseCoverage.COMPLETE, KnowledgeParserRegistry.classify(outcome))
        assertTrue(outcome.isUsableFullText)
    }

    // ---------- 位置可解释:blocks 携带页/段 ----------

    @Test
    fun `blocks carry explainable locators`() {
        val bs = blocks("a", "b", "c")
        assertEquals(listOf(1, 2, 3), bs.map { it.page })
        assertTrue(bs.all { it.section >= 1 })
    }
}
