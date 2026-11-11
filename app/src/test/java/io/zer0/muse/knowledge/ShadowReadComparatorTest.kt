package io.zer0.muse.knowledge

import io.zer0.muse.knowledge.integration.ShadowReadComparator
import io.zer0.muse.knowledge.retrieval.EvidenceCandidate
import io.zer0.muse.knowledge.retrieval.RetrievalOutcome
import io.zer0.muse.knowledge.retrieval.RetrievalTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R9 影子读比对合同(计划 R9「影子读比对候选/范围/证据后再开启新引擎读」)。
 *
 * 断言:一致时无需复核;V2 漏召回/越界/降级时进入复核;聚合结果驱动 switchable 判定。
 */
class ShadowReadComparatorTest {

    private fun trace(id: String) = RetrievalTrace(
        requestId = id,
        entry = "DEBUG",
        scopeSummary = "",
        spaces = emptyList(),
        lexicalCount = 0,
        denseCount = 0,
        fusedCount = 0,
        finalCount = 0,
        degradedReasons = emptyList(),
        elapsedMs = 0L,
    )

    private fun found(vararg docIds: String) = RetrievalOutcome.Found(
        docIds.mapIndexed { index, docId ->
            EvidenceCandidate(
                chunkId = "c-$docId",
                docId = docId,
                generationId = "g-$docId",
                text = "text",
                locator = "p1s1",
            ).copy(fusionRank = index)
        },
        trace("t"),
    )

    @Test
    fun `identical results need no review`() {
        val report = ShadowReadComparator.compare(
            query = "q",
            legacy = found("doc-1", "doc-2"),
            v2 = found("doc-1", "doc-2"),
        )
        assertEquals(1.0, report.jaccard, 0.0001)
        assertEquals(1.0, report.topKAgreement, 0.0001)
        assertFalse(report.needsReview)
        assertTrue(report.onlyInLegacy.isEmpty())
        assertTrue(report.onlyInV2.isEmpty())
    }

    @Test
    fun `v2 missing all results requires review`() {
        val report = ShadowReadComparator.compare(
            query = "q",
            legacy = found("doc-1", "doc-2"),
            v2 = RetrievalOutcome.NoMatch(trace("t")),
        )
        assertEquals(0.0, report.jaccard, 0.0001)
        assertTrue(report.needsReview)
        assertTrue(report.reviewReasons.any { it.contains("零命中") })
        assertEquals(listOf("doc-1", "doc-2"), report.onlyInLegacy)
    }

    @Test
    fun `low overlap requires review but partial overlap recorded`() {
        val report = ShadowReadComparator.compare(
            query = "q",
            legacy = found("doc-1", "doc-2", "doc-3", "doc-4"),
            v2 = found("doc-1", "doc-9"),
        )
        assertTrue("重叠率应低于门槛", report.jaccard < ShadowReadComparator.MIN_JACCARD_FOR_REVIEW)
        assertTrue(report.needsReview)
        assertTrue(report.onlyInV2.contains("doc-9"))
    }

    @Test
    fun `degraded path requires review`() {
        val degraded = RetrievalOutcome.Partial(
            candidates = listOf(
                EvidenceCandidate("c-1", "doc-1", "g-1", "t", "p1s1"),
            ),
            missingPaths = listOf("dense 不可用"),
            trace = trace("t"),
        )
        val report = ShadowReadComparator.compare("q", legacy = found("doc-1"), v2 = degraded)
        assertTrue(report.needsReview)
        assertTrue(report.v2Degraded)
        assertTrue(report.reviewReasons.any { it.contains("V2 降级") })
    }

    @Test
    fun `empty both sides counts as full agreement`() {
        val report = ShadowReadComparator.compare(
            query = "q",
            legacy = RetrievalOutcome.NoMatch(trace("t")),
            v2 = RetrievalOutcome.NoMatch(trace("t")),
        )
        assertEquals(1.0, report.jaccard, 0.0001)
        assertFalse(report.needsReview)
    }

    @Test
    fun `aggregate drives switchable decision`() {
        val clean = (1..5).map {
            ShadowReadComparator.compare("q$it", found("doc-1", "doc-2"), found("doc-1", "doc-2"))
        }
        val cleanAggregate = ShadowReadComparator.aggregate(clean)
        assertEquals(5, cleanAggregate.queryCount)
        assertEquals(1.0, cleanAggregate.avgJaccard, 0.0001)
        assertTrue("全部一致才建议可切换", cleanAggregate.switchable)

        val withReview = clean + ShadowReadComparator.compare(
            "bad",
            found("doc-1"),
            RetrievalOutcome.NoMatch(trace("t")),
        )
        val mixedAggregate = ShadowReadComparator.aggregate(withReview)
        assertEquals(1, mixedAggregate.reviewCount)
        assertFalse("存在复核项不得建议切换", mixedAggregate.switchable)
    }
}
