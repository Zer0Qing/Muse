package io.zer0.muse.knowledge

import io.zer0.muse.knowledge.integration.EvidenceSnapshotCodec
import io.zer0.muse.knowledge.retrieval.EvidenceAssembler
import io.zer0.muse.knowledge.retrieval.EvidenceCandidate
import io.zer0.muse.knowledge.retrieval.RetrievalOutcome
import io.zer0.muse.knowledge.retrieval.RetrievalTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R7 证据快照合同(计划 R7「主聊天每轮独立保存 EvidenceBundle；持久引用区分提供给模型
 * 与正文实际引用，不给未使用资料套『已使用』标签」)。
 */
class EvidenceSnapshotTest {

    private fun bundle(): io.zer0.muse.knowledge.retrieval.EvidenceBundle {
        val outcome = RetrievalOutcome.Found(
            listOf(
                EvidenceCandidate("c1", "doc-1", "gen-1", "第一段正文", "p1s1"),
                EvidenceCandidate("c2", "doc-2", "gen-2", "第二段正文", "p1s2"),
            ),
            RetrievalTrace(
                requestId = "req-1",
                entry = "CHAT",
                scopeSummary = "docs=2",
                spaces = emptyList(),
                lexicalCount = 2,
                denseCount = 0,
                fusedCount = 2,
                finalCount = 2,
                degradedReasons = emptyList(),
                elapsedMs = 1L,
            ),
        )
        return EvidenceAssembler().assemble(
            outcome = outcome,
            budgetTokens = 500,
            docTitles = mapOf("doc-1" to "文档一", "doc-2" to "文档二"),
            traceId = "trace-1",
        )
    }

    @Test
    fun `snapshot roundtrip preserves sent text and citations`() {
        val original = bundle()
        val encoded = EvidenceSnapshotCodec.encode(EvidenceSnapshotCodec.toSnapshot(original))
        val decoded = EvidenceSnapshotCodec.fromSnapshot(EvidenceSnapshotCodec.decode(encoded))

        assertEquals(original.finalText, decoded.finalText)
        assertEquals(original.units.size, decoded.units.size)
        assertEquals(original.citations, decoded.citations)
        assertEquals(original.traceId, decoded.traceId)
        // 实际发送 snippet 与 citation 必须一致。
        for (unit in decoded.units) {
            assertEquals(unit.sentSnippet.take(200), decoded.citations.first { it.index == unit.index }.snippet)
        }
    }

    @Test
    fun `provided to model is not marked as actually cited`() {
        val snapshot = EvidenceSnapshotCodec.toSnapshot(bundle())
        assertTrue("提供的资料应有记录", snapshot.providedToModel.isNotEmpty())
        assertTrue("未解析正文引用前不得标记已使用", snapshot.actuallyCited.isEmpty())
    }

    @Test
    fun `old snapshot without optional fields decodes with defaults`() {
        val old = """{"version":1,"finalText":"正文","units":[],"citations":[],"tokenEstimate":1,"traceId":"t"}"""
        val decoded = EvidenceSnapshotCodec.decode(old)
        assertEquals("正文", decoded.finalText)
        assertTrue(decoded.providedToModel.isEmpty())
        assertTrue(decoded.actuallyCited.isEmpty())
    }
}
