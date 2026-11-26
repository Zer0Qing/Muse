package io.zer0.muse.rag

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 孤儿会话附件清理:进程重启后内存状态丢失,旧附件索引必须被回收。
 */
class SessionAttachmentCleanupTest {

    private class Harness(
        val service: SessionAttachmentService,
        val ragService: RagService,
        val docDao: KnowledgeDocDao,
    )

    private fun harness(): Harness {
        val ragService = mockk<RagService>(relaxed = true)
        val settings = mockk<SettingsRepository>(relaxed = true)
        val docDao = mockk<KnowledgeDocDao>(relaxed = true)
        every { settings.ragConfigFlow } returns flowOf(RagConfig())
        val service = SessionAttachmentService(ragService, settings, docDao)
        return Harness(service, ragService, docDao)
    }

    /** 让索引一直挂着,直到测试显式放行 —— 这样内存里始终有一条「活动」附件。 */
    private suspend fun startBlockedIndexing(h: Harness, sessionId: String): CompletableDeferred<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery {
            h.ragService.indexDocument(docId = any(), content = any(), ragConfig = any(), onProgress = any())
        } coAnswers {
            started.complete(Unit)
            release.await()
            1
        }
        h.service.indexSessionAttachment(sessionId, "note.txt", "content")
        withTimeout(2_000) { started.await() }
        return release
    }

    @Test
    fun `orphaned attachments are removed while active ones stay`() = runBlocking {
        val h = harness()
        val release = startBlockedIndexing(h, "session-live")
        // 活动 docId 由服务自己生成,必须从内存状态里取,不能自行拼一个。
        val activeDocId = h.service.attachmentsBySession("session-live").single().docId
        val orphanDocId = SessionAttachment.buildDocId("session-dead", "attach-dead")
        coEvery { h.docDao.getAttachmentCandidateIds(any(), any()) } returns listOf(activeDocId, orphanDocId)

        val cleaned = h.service.cleanupOrphanedAttachments()
        release.complete(Unit)

        assertEquals("只清理不在内存活动列表里的附件", 1, cleaned)
        coVerify(exactly = 1) { h.ragService.deleteDocIndex(orphanDocId) }
        coVerify(exactly = 1) { h.docDao.delete(orphanDocId) }
        coVerify(exactly = 0) { h.ragService.deleteDocIndex(activeDocId) }
        coVerify(exactly = 0) { h.docDao.delete(activeDocId) }
        assertTrue("活动附件不得从内存状态中消失", h.service.attachmentsBySession("session-live").isNotEmpty())
    }

    @Test
    fun `attachment listing failure skips cleanup instead of deleting everything`() = runBlocking {
        val h = harness()
        coEvery { h.docDao.getAttachmentCandidateIds(any(), any()) } throws IllegalStateException("db unavailable")

        assertEquals("读库失败不能当作「全部是孤儿」", 0, h.service.cleanupOrphanedAttachments())
        coVerify(exactly = 0) { h.ragService.deleteDocIndex(any()) }
        coVerify(exactly = 0) { h.docDao.delete(any()) }
    }

    @Test
    fun `active attachment doc ids are exposed for scoped retrieval`() = runBlocking<Unit> {
        val h = harness()
        val release = startBlockedIndexing(h, "session-x")

        assertEquals(
            "本会话附件 docId 必须可用于限定召回",
            1,
            h.service.activeAttachmentDocIds("session-x").size,
        )
        assertTrue("其它会话不得拿到这些附件", h.service.activeAttachmentDocIds("session-y").isEmpty())
        release.complete(Unit)
    }
}
