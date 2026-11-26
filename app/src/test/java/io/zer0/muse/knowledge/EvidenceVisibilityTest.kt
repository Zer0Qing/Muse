package io.zer0.muse.knowledge

import io.zer0.muse.knowledge.retrieval.DocumentVisibilityIndex
import io.zer0.muse.knowledge.retrieval.EvidenceAssembler
import io.zer0.muse.knowledge.retrieval.EvidenceCandidate
import io.zer0.muse.knowledge.retrieval.EvidenceVisibility
import io.zer0.muse.knowledge.retrieval.RetrievalOutcome
import io.zer0.muse.knowledge.retrieval.RetrievalTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 证据可见性合同(修复「isInternal 固定写 false」):
 *
 *  - 引用上的 isInternal 必须来自装配层的可见性判定,不能硬编码;
 *  - 已知内部资料只在显式允许时注入;
 *  - 判定不了归属时**默认不注入** —— 保守方向是「少给上下文」而不是「泄漏内部资料」。
 */
class EvidenceVisibilityTest {

    private fun candidate(id: String, docId: String, text: String = "正文") = EvidenceCandidate(
        chunkId = id,
        docId = docId,
        generationId = "gen-$docId",
        text = text,
        locator = "p1s1",
    )

    private fun found(vararg candidates: EvidenceCandidate) = RetrievalOutcome.Found(
        candidates.toList(),
        RetrievalTrace(
            requestId = "req",
            entry = "CHAT",
            scopeSummary = "docs=${candidates.size}",
            spaces = emptyList(),
            lexicalCount = candidates.size,
            denseCount = 0,
            fusedCount = candidates.size,
            finalCount = candidates.size,
            degradedReasons = emptyList(),
            elapsedMs = 1L,
        ),
    )

    @Test
    fun `internal citation is marked instead of hardcoded false`() {
        val assembler = EvidenceAssembler(
            visibilityOf = { docId ->
                if (docId == "devdoc-1") EvidenceVisibility.INTERNAL else EvidenceVisibility.USER_VISIBLE
            },
            includeInternal = true,
        )
        val bundle = assembler.assemble(
            found(candidate("c1", "devdoc-1"), candidate("c2", "doc-2")),
            budgetTokens = 500,
        )

        assertEquals(2, bundle.units.size)
        assertTrue(
            "内部资料引用必须标记 isInternal",
            bundle.citations.first { it.docId == "devdoc-1" }.isInternal,
        )
        assertFalse(
            "用户资料不得被标成内部",
            bundle.citations.first { it.docId == "doc-2" }.isInternal,
        )
        assertEquals(EvidenceVisibility.INTERNAL, bundle.units.first { it.docId == "devdoc-1" }.visibility)
    }

    @Test
    fun `internal content is suppressed when not explicitly allowed`() {
        val assembler = EvidenceAssembler(
            visibilityOf = { docId ->
                if (docId == "devdoc-1") EvidenceVisibility.INTERNAL else EvidenceVisibility.USER_VISIBLE
            },
        )
        val bundle = assembler.assemble(
            found(candidate("c1", "devdoc-1", "内部开发文档正文"), candidate("c2", "doc-2", "用户正文")),
            budgetTokens = 500,
        )

        assertEquals(1, bundle.units.size)
        assertEquals("doc-2", bundle.units.single().docId)
        assertFalse("内部正文不得进入上下文", bundle.finalText.contains("内部开发文档正文"))
        assertEquals(1, bundle.suppressedInternalCount)
        assertTrue("抑制必须标记降级", bundle.degraded)
    }

    @Test
    fun `unknown visibility is conservatively suppressed`() {
        // 默认构造(未接可见性来源):一律按未知处理,不注入。
        val bundle = EvidenceAssembler().assemble(
            found(candidate("c1", "doc-unknown", "未知归属正文")),
            budgetTokens = 500,
        )

        assertTrue("判定不了归属时必须不注入", bundle.isEmpty)
        assertEquals("", bundle.finalText)
        assertEquals(1, bundle.suppressedUnknownCount)
        assertEquals(0, bundle.suppressedInternalCount)
    }

    @Test
    fun `visibility index drives assembly and refresh drops stale entries`() {
        val index = DocumentVisibilityIndex()
        index.replaceAll(
            mapOf(
                "doc-user" to EvidenceVisibility.USER_VISIBLE,
                "devdoc-1" to EvidenceVisibility.INTERNAL,
            ),
        )
        val assembler = EvidenceAssembler(visibilityIndex = index)

        val bundle = assembler.assemble(
            found(candidate("c1", "doc-user", "用户正文"), candidate("c2", "devdoc-1", "内部正文")),
            budgetTokens = 500,
        )
        assertEquals(listOf("doc-user"), bundle.units.map { it.docId })
        assertEquals(1, bundle.suppressedInternalCount)

        // 刷新后旧判定不残留:被移除的文档回到 UNKNOWN(不注入)。
        index.replaceAll(emptyMap())
        val afterRefresh = assembler.assemble(
            found(candidate("c1", "doc-user", "用户正文")),
            budgetTokens = 500,
        )
        assertTrue("索引刷新后未收录文档必须不注入", afterRefresh.isEmpty)
        assertEquals(1, afterRefresh.suppressedUnknownCount)
    }

    @Test
    fun `per call includeInternal can open builtin help entry`() {
        val assembler = EvidenceAssembler(
            visibilityOf = { EvidenceVisibility.INTERNAL },
            includeInternal = false,
        )
        val suppressed = assembler.assemble(found(candidate("c1", "devdoc-1")), budgetTokens = 500)
        assertTrue(suppressed.isEmpty)

        val allowed = assembler.assemble(
            found(candidate("c1", "devdoc-1")),
            budgetTokens = 500,
            allowInternal = true,
        )
        assertEquals(1, allowed.units.size)
        assertTrue(allowed.citations.single().isInternal)
    }

    @Test
    fun `suppressed candidates do not consume citation numbering`() {
        val assembler = EvidenceAssembler(
            visibilityOf = { docId ->
                when (docId) {
                    "devdoc-1" -> EvidenceVisibility.INTERNAL
                    "doc-unknown" -> EvidenceVisibility.UNKNOWN
                    else -> EvidenceVisibility.USER_VISIBLE
                }
            },
        )
        val bundle = assembler.assemble(
            found(
                candidate("c1", "devdoc-1"),
                candidate("c2", "doc-unknown"),
                candidate("c3", "doc-visible"),
            ),
            budgetTokens = 500,
            docTitles = mapOf("doc-visible" to "可见资料"),
        )

        assertEquals(1, bundle.units.size)
        assertEquals("编号必须连续,不留空洞", listOf(1), bundle.citations.map { it.index })
        assertTrue(bundle.finalText.contains("【资料 1】"))
        assertFalse(bundle.finalText.contains("【资料 2】"))
    }
}
