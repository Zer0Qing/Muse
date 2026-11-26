package io.zer0.muse.rag

import io.zer0.ai.core.RagCitation
import io.zer0.muse.context.ContextBudget
import io.zer0.muse.context.ContextSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * citations 与注入文本同源:截断后只剩正文里还能回指的引用。
 */
class RagInjectionBudgetTest {

    private fun citation(index: Int) = RagCitation(
        index = index,
        docId = "doc-$index",
        docTitle = "文档 $index",
        chunkId = "chunk-$index",
        chunkIndex = 0,
        snippet = "片段 $index",
        score = 0.9f,
        matchType = "vector",
    )

    private fun injection(text: String, count: Int) = RagInjection(
        text = text,
        citations = (1..count).map(::citation),
        elapsedMs = 0,
    )

    @Test
    fun `citations beyond the truncation point are dropped`() {
        val body = buildString {
            appendLine("以下为资料:")
            appendLine("<knowledge_context>")
            appendLine("[1] 来源: 文档一")
            appendLine("第一段内容" + "甲".repeat(80))
            appendLine()
            appendLine("[2] 来源: 文档二")
            appendLine("第二段内容" + "乙".repeat(80))
            appendLine()
            appendLine("</knowledge_context>")
        }
        val budget = ContextBudget(mapOf(ContextSection.RAG_CITATION to 120))

        val result = RagInjectionBudget.clampAndTrim(injection(body, 2), budget, ContextSection.RAG_CITATION)

        assertTrue("测试前提:文本必须真的被截断", result.truncated)
        assertTrue("正文里已无 [2]", !result.text.contains("[2]"))
        assertEquals("只剩仍可回指的 [1]", listOf(1), result.citations.map { it.index })
        assertEquals(1, result.droppedCitations)
    }

    @Test
    fun `dangling knowledge context open tag gets closed after truncation`() {
        val body = buildString {
            appendLine("以下为资料:")
            appendLine("<knowledge_context>")
            appendLine("[1] 来源: 文档一")
            appendLine("甲".repeat(400))
            appendLine("</knowledge_context>")
        }
        val budget = ContextBudget(mapOf(ContextSection.RAG_CITATION to 150))

        // 先确认测试前提:光靠 clamp 会把闭合标签切掉。
        val clampedOnly = budget.clampText(ContextSection.RAG_CITATION, body)
        assertTrue("测试前提:闭合标签必须被切掉", !clampedOnly.contains("</knowledge_context>"))

        val result = RagInjectionBudget.clampAndTrim(injection(body, 1), budget, ContextSection.RAG_CITATION)

        assertTrue("开标签仍在", result.text.contains("<knowledge_context>"))
        assertTrue("截断后必须补回闭合标签", result.text.contains("</knowledge_context>"))
        assertEquals(
            "只能补一个闭合标签",
            1,
            Regex("</knowledge_context>").findAll(result.text).count(),
        )
        assertTrue(
            "闭合标签必须早于截断注记(注记不属于资料块)",
            result.text.indexOf("</knowledge_context>") < result.text.indexOf("[内容已按上下文预算截断:"),
        )
    }

    @Test
    fun `untruncated injection keeps every citation and text untouched`() {
        val body = "<knowledge_context>\n[1] 来源: 文档一\n短内容\n</knowledge_context>"

        val result = RagInjectionBudget.clampAndTrim(injection(body, 1))

        assertFalse(result.truncated)
        assertEquals(body, result.text)
        assertEquals(listOf(1), result.citations.map { it.index })
        assertEquals(0, result.droppedCitations)
    }

    @Test
    fun `citation numbers inside snippet body are not treated as citation markers`() {
        // 正文里出现 "[1]" 不是编号行,不能凭它保住引用;编号行一定行首且带来源前缀。
        val body = "<knowledge_context>\n[1] 来源: 文档一\n片段里引用了 [2] 这个序号\n</knowledge_context>"

        val result = RagInjectionBudget.clampAndTrim(injection(body, 2))

        assertEquals("只有行首编号行算引用", listOf(1), result.citations.map { it.index })
    }
}
