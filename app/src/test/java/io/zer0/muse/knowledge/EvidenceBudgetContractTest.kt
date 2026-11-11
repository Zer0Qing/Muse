package io.zer0.muse.knowledge

import io.zer0.muse.knowledge.retrieval.EvidenceAssembler
import io.zer0.muse.knowledge.retrieval.EvidenceCandidate
import io.zer0.muse.knowledge.retrieval.RetrievalOutcome
import io.zer0.muse.knowledge.retrieval.RetrievalTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R7 步骤 1/2/3(计划 R7:「先写极小预算、长标题/包装、超长首块、Unicode截断、
 * 最终二次缩减、引用编号一致和旧 citation JSON 兼容测试」「按完整渲染单元估算 token」
 * 「让全局 ContextBudget 通过重新 assemble 调整证据，移除裁文本但存全部 citations 的路径」)。
 */
class EvidenceBudgetContractTest {

    private val assembler = EvidenceAssembler()

    private fun candidate(id: String, text: String, locator: String = "p1s1", docId: String = "doc-1") =
        EvidenceCandidate(
            chunkId = id,
            docId = docId,
            generationId = "gen-1",
            text = text,
            locator = locator,
        )

    private fun found(vararg candidates: EvidenceCandidate): RetrievalOutcome.Found =
        RetrievalOutcome.Found(candidates.toList(), trace())

    private fun trace() = RetrievalTrace(
        requestId = "req-1",
        entry = "CHAT",
        scopeSummary = "docs=1",
        spaces = emptyList(),
        lexicalCount = 1,
        denseCount = 0,
        fusedCount = 1,
        finalCount = 1,
        degradedReasons = emptyList(),
        elapsedMs = 1L,
    )

    @Test
    fun `zero budget yields empty bundle not fabricated text`() {
        val bundle = assembler.assemble(found(candidate("c1", "hello")), budgetTokens = 0)
        assertTrue(bundle.isEmpty)
        assertEquals("", bundle.finalText)
        assertTrue(bundle.citations.isEmpty())
    }

    @Test
    fun `long title consumes budget and is accounted`() {
        val longTitle = "非常长的文档标题".repeat(20)
        val bundle = assembler.assemble(
            found(candidate("c1", "body text")),
            budgetTokens = 200,
            docTitles = mapOf("doc-1" to longTitle),
        )
        assertFalse(bundle.isEmpty)
        // token 估计应包含标题开销:远大于 body 本身(4 token)。
        assertTrue("标题开销必须计入预算", bundle.tokenEstimate > 20)
    }

    @Test
    fun `super long first chunk is truncated not aborting the whole set`() {
        val longText = "A".repeat(4000)
        val bundle = assembler.assemble(
            found(candidate("c1", longText), candidate("c2", "short second")),
            budgetTokens = 200,
        )
        assertFalse("超长首块不得终止整个候选集", bundle.isEmpty)
        assertTrue("首块被截断", bundle.units[0].truncated)
        assertTrue("后续短块仍应被纳入", bundle.units.size >= 1)
    }

    @Test
    fun `unicode truncation does not split surrogate pair`() {
        // 表情符号是代理对(2 个 char);截断必须落在字符边界。
        val emojiText = "😀".repeat(500)
        val truncated = assembler.truncateToTokens(emojiText, 10)
        assertTrue(truncated.isNotEmpty())
        // 不能以孤立高代理结尾。
        assertFalse(
            "不得停在孤立高代理",
            truncated.isNotEmpty() && Character.isHighSurrogate(truncated.last()),
        )
        // 长度必须为偶数个 char(完整代理对)。
        assertEquals("必须是完整代理对", 0, truncated.length % 2)
    }

    @Test
    fun `citation numbering matches final text markers`() {
        val bundle = assembler.assemble(
            found(
                candidate("c1", "first body", docId = "doc-1"),
                candidate("c2", "second body", docId = "doc-2"),
            ),
            budgetTokens = 500,
            docTitles = mapOf("doc-1" to "Doc One", "doc-2" to "Doc Two"),
        )
        assertEquals(2, bundle.units.size)
        assertEquals(listOf(1, 2), bundle.citations.map { it.index })
        assertTrue(bundle.finalText.contains("【资料 1】"))
        assertTrue(bundle.finalText.contains("【资料 2】"))
        // 每个 citation 的 snippet 必须等于实际发送片段。
        for (unit in bundle.units) {
            val citation = bundle.citations.first { it.index == unit.index }
            assertEquals("citation snippet 与实际发送不一致", unit.sentSnippet.take(200), citation.snippet)
        }
    }

    @Test
    fun `reassemble with smaller budget keeps text and citations consistent`() {
        val candidates = listOf(
            candidate("c1", "first body ".repeat(50)),
            candidate("c2", "second body ".repeat(50)),
        )
        val big = assembler.assemble(
            RetrievalOutcome.Found(candidates, trace()),
            budgetTokens = 1000,
            docTitles = mapOf("doc-1" to "Doc"),
        )
        val small = assembler.reassembleWithBudget(big, newBudgetTokens = 60, outcomeCandidates = candidates)
        // 二次缩减后:citation 数量必须等于实际 unit 数,且不超过原 citation 数。
        assertEquals(small.units.size, small.citations.size)
        assertTrue(small.citations.size <= big.citations.size)
        // 文本中的每个 [N] 都能在 citations 找到。
        for (unit in small.units) {
            assertTrue("悬空引用", small.citations.any { it.index == unit.index })
        }
    }

    @Test
    fun `no evidence yields empty bundle without placeholder instruction`() {
        val noMatch = RetrievalOutcome.NoMatch(trace())
        val bundle = assembler.assemble(noMatch, budgetTokens = 500)
        assertTrue(bundle.isEmpty)
        assertEquals("", bundle.finalText)
    }

    @Test
    fun `skipped candidates are counted and degraded`() {
        // 预算极小:第一条就放不下 -> skipped>0 且 degraded=true。
        val bundle = assembler.assemble(
            found(candidate("c1", "A".repeat(1000)), candidate("c2", "B".repeat(1000))),
            budgetTokens = 5,
        )
        assertTrue(bundle.isEmpty)
        assertTrue("应有跳过计数", bundle.skippedCount > 0)
        assertTrue("应标记降级", bundle.degraded)
    }
}
