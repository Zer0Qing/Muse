package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.knowledge.KnowledgeV2DocEntity
import io.zer0.muse.data.knowledge.KnowledgeV2RevisionEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.domain.JobStepResult
import io.zer0.muse.knowledge.integration.ContributionResult
import io.zer0.muse.knowledge.integration.KnowledgeRuntime
import io.zer0.muse.knowledge.integration.TrustedGenerationContext
import io.zer0.muse.knowledge.retrieval.KnowledgeEntryType
import io.zer0.muse.knowledge.storage.KnowledgeContentStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R4/R9 装配级集成(计划 R4「统一作业 runner」+ R7「统一证据」+ R9「便携备份」):
 * 在同一个运行时对象上跑通「文档 → 作业 → 词法发布 → 检索 → 证据装配 → 备份恢复」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgeRuntimeIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: MuseDb
    private lateinit var runtime: KnowledgeRuntime

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
        runtime = KnowledgeRuntime.create(db = db, blobsDirectory = tmp.newFolder("blobs"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedDoc(docId: String, content: String) {
        val hash = KnowledgeContentStore.hashOf(content.toByteArray())
        db.knowledgeV2Dao().insertDoc(
            KnowledgeV2DocEntity(
                docId = docId,
                ownerType = "UserLibrary",
                ownerId = "kb-1",
                currentRevisionId = "rev-$docId",
            ),
        )
        db.knowledgeV2Dao().insertRevision(
            KnowledgeV2RevisionEntity(
                revisionId = "rev-$docId",
                docId = docId,
                revisionNo = 1,
                contentHash = hash,
                fullContent = content,
                preview = content.take(100),
                origin = "ingest",
            ),
        )
    }

    @Test
    fun `runtime runs job then retrieval then evidence assembly`() = runBlocking {
        val content = "运行时集成正文 " + "填充 ".repeat(400) + " TAIL-RUNTIME"
        seedDoc("doc-1", content)

        val enq = runtime.jobEnqueue.enqueueReindex("UserLibrary", "kb-1", listOf("doc-1"))
        val result = runtime.jobRunner.run(enq.itemIds[0]) {
            runtime.processor.process(db.knowledgeJobDao().getJobItem(enq.itemIds[0])!!, this)
        }
        assertTrue("作业应成功: $result", result is JobStepResult.Succeeded)
        assertNotNull(runtime.jobDao.getActiveGeneration("doc-1"))

        val contribution = runtime.contributor.prepareEvidence(
            context = TrustedGenerationContext(
                entry = KnowledgeEntryType.CHAT,
                assistantId = "a1",
                sessionId = "s1",
                allowedScope = io.zer0.muse.knowledge.domain.KnowledgeScope.Resolved(setOf("doc-1")),
            ),
            query = "TAIL-RUNTIME",
            budgetTokens = 500,
            requestId = "runtime-1",
        )
        assertTrue("应产出证据: $contribution", contribution is ContributionResult.Evidence)
        val bundle = (contribution as ContributionResult.Evidence).bundle
        assertTrue(bundle.finalText.contains("TAIL-RUNTIME"))
        assertEquals(1, bundle.citations.size)
        assertEquals(bundle.units.single().sentSnippet.take(200), bundle.citations.single().snippet)
    }

    @Test
    fun `runtime portable backup restores into fresh database`() = runBlocking {
        seedDoc("doc-1", "备份正文 TAIL-BACKUP")
        val backup = runtime.portableBackup.export()

        val target = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
        try {
            val targetRuntime = KnowledgeRuntime.create(db = target, blobsDirectory = tmp.newFolder("blobs2"))
            val report = targetRuntime.portableBackup.restore(backup)
            assertTrue("恢复应成功: ${report.failureReason}", report.success)
            assertNotNull(target.knowledgeV2Dao().getDoc("doc-1"))
            assertEquals(1, report.keywordReadyDocs)
        } finally {
            target.close()
        }
    }
}
