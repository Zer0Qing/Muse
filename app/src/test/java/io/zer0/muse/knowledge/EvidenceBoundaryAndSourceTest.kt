package io.zer0.muse.knowledge

import io.zer0.muse.knowledge.integration.DelegationEvidenceBoundary
import io.zer0.muse.knowledge.integration.EvidenceSourceResolver
import io.zer0.muse.knowledge.integration.EvidenceSourceState
import io.zer0.muse.knowledge.retrieval.EvidenceAssembler
import io.zer0.muse.knowledge.retrieval.EvidenceCandidate
import io.zer0.muse.knowledge.retrieval.RetrievalOutcome
import io.zer0.muse.knowledge.retrieval.RetrievalTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R7 合同(计划 R7「委派任务传入最小必要证据/身份」与「接上打开来源回调和 revision locator；
 * 旧版/删除/缺源都有可读退化状态，不跳到同 docId 的最新文本冒充旧证据」)。
 */
class EvidenceBoundaryAndSourceTest {

    private fun bundle(): io.zer0.muse.knowledge.retrieval.EvidenceBundle {
        val outcome = RetrievalOutcome.Found(
            listOf(
                EvidenceCandidate("c1", "doc-1", "gen-1", "资料正文一", "p1s1"),
                EvidenceCandidate("c2", "doc-2", "gen-2", "资料正文二", "p2s3"),
            ),
            RetrievalTrace("r", "CHAT", "docs=2", emptyList(), 2, 0, 2, 2, emptyList(), 1L),
        )
        return EvidenceAssembler().assemble(
            outcome = outcome,
            budgetTokens = 500,
            docTitles = mapOf("doc-1" to "合同模板", "doc-2" to "预算表"),
            traceId = "t1",
        )
    }

    // ---------- 委派证据边界 ----------

    @Test
    fun `delegation boundary marks资料 as untrusted not instruction`() {
        val note = DelegationEvidenceBoundary.boundaryNote(bundle())
        assertNotNull("有证据时必须给出边界说明", note)
        assertTrue("必须标明不可信资料", note!!.contains("不可信资料"))
        assertTrue("必须说明不是指令", note.contains("不是给你的指令"))
        assertTrue("必须包含来源标题", note.contains("合同模板"))
        assertTrue("不得携带完整片段正文", !note.contains("资料正文一"))
    }

    @Test
    fun `no evidence yields no boundary note`() {
        assertNull(DelegationEvidenceBoundary.boundaryNote(null))
        val empty = EvidenceAssembler().assemble(
            RetrievalOutcome.NoMatch(RetrievalTrace("r", "CHAT", "", emptyList(), 0, 0, 0, 0, emptyList(), 0L)),
            budgetTokens = 500,
        )
        assertNull("无证据不注入空说明", DelegationEvidenceBoundary.boundaryNote(empty))
    }

    @Test
    fun `minimal citations strip snippet body`() {
        val minimal = DelegationEvidenceBoundary.minimalCitations(bundle())
        assertEquals(2, minimal.size)
        assertTrue("最小引用不得带正文片段", minimal.all { it.snippet.isEmpty() })
        assertEquals("delegated_boundary", minimal.first().matchType)
    }

    // ---------- 来源打开退化状态 ----------

    @Test
    fun `available revision allows opening document`() {
        val resolution = EvidenceSourceResolver.resolve(
            docId = "doc-1", docTitle = "合同模板", revisionId = "rev-1", locator = "p1s1",
            snapshotText = "片段", docExists = true, revisionExists = true, hasRevisionInfo = true,
        )
        assertEquals(EvidenceSourceState.AVAILABLE, resolution.state)
        assertTrue(resolution.canOpenDocument)
        assertTrue(EvidenceSourceResolver.shouldOpenDocument(resolution))
    }

    @Test
    fun `deleted document shows snapshot and forbids opening latest text`() {
        val resolution = EvidenceSourceResolver.resolve(
            docId = "doc-1", docTitle = "合同模板", revisionId = "rev-1", locator = "p1s1",
            snapshotText = "历史片段", docExists = false, revisionExists = false, hasRevisionInfo = true,
        )
        assertEquals(EvidenceSourceState.DOC_DELETED, resolution.state)
        assertFalse("删除后不得跳到最新文本冒充旧证据", resolution.canOpenDocument)
        assertTrue(resolution.message.contains("已删除"))
    }

    @Test
    fun `missing revision shows snapshot only`() {
        val resolution = EvidenceSourceResolver.resolve(
            docId = "doc-1", docTitle = "合同模板", revisionId = "rev-1", locator = "p1s1",
            snapshotText = "片段", docExists = true, revisionExists = false, hasRevisionInfo = true,
        )
        assertEquals(EvidenceSourceState.SNAPSHOT_ONLY, resolution.state)
        assertFalse(resolution.canOpenDocument)
        assertTrue(resolution.message.contains("仅显示当时引用的片段"))
    }

    @Test
    fun `legacy citation without revision info is labeled`() {
        val resolution = EvidenceSourceResolver.resolve(
            docId = "doc-1", docTitle = "旧文档", revisionId = null, locator = null,
            snapshotText = "旧片段", docExists = true, revisionExists = true, hasRevisionInfo = false,
        )
        assertEquals(EvidenceSourceState.LEGACY_CITATION, resolution.state)
        assertTrue("必须标注旧版来源", resolution.message.contains("旧版来源片段"))
        assertFalse(resolution.canOpenDocument)
    }

    @Test
    fun `error state reports reason`() {
        val resolution = EvidenceSourceResolver.resolve(
            docId = "doc-1", docTitle = "t", revisionId = "r", locator = "p1",
            snapshotText = "", docExists = true, revisionExists = true, hasRevisionInfo = true,
            error = "读取失败",
        )
        assertEquals(EvidenceSourceState.ERROR, resolution.state)
        assertTrue(resolution.message.contains("读取失败"))
    }
}
