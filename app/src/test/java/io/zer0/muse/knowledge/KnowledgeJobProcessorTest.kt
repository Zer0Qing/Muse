package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.knowledge.KnowledgeV2DocEntity
import io.zer0.muse.data.knowledge.KnowledgeV2RevisionEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.domain.JobStepResult
import io.zer0.muse.knowledge.domain.KnowledgeScope
import io.zer0.muse.knowledge.domain.RetrievalPolicy
import io.zer0.muse.knowledge.index.GenerationPublisher
import io.zer0.muse.knowledge.jobs.DefaultKnowledgeJobProcessor
import io.zer0.muse.knowledge.jobs.JobRunner
import io.zer0.muse.knowledge.jobs.KnowledgeJobEnqueueService
import io.zer0.muse.knowledge.retrieval.KnowledgeEntryType
import io.zer0.muse.knowledge.retrieval.KnowledgeSearchRequest
import io.zer0.muse.knowledge.retrieval.V2LexicalRetriever
import io.zer0.muse.knowledge.storage.KnowledgeContentStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R4 作业处理器合同(计划 R4「将全量重建、单篇重建、KB 重建及附件清理接到同一作业 runner」):
 * 真实 revision → 分块 → staging → 校验 → 发布,产出可检索的词法 generation;
 * ChunkOnly/空正文不得冒充成功。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgeJobProcessorTest {

    private lateinit var db: MuseDb
    private lateinit var runner: JobRunner
    private lateinit var enqueue: KnowledgeJobEnqueueService
    private lateinit var processor: DefaultKnowledgeJobProcessor

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
        runner = JobRunner(db.knowledgeJobDao(), clock = { 1000L })
        enqueue = KnowledgeJobEnqueueService(db.knowledgeJobDao(), clock = { 1000L })
        processor = DefaultKnowledgeJobProcessor(
            v2Dao = db.knowledgeV2Dao(),
            publisher = GenerationPublisher(db.knowledgeJobDao()),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedDoc(docId: String, content: String) {
        val hash = KnowledgeContentStore.hashOf(content.toByteArray())
        val revId = "rev-$docId-1"
        db.knowledgeV2Dao().insertDoc(
            KnowledgeV2DocEntity(
                docId = docId,
                ownerType = "UserLibrary",
                ownerId = "kb-1",
                currentRevisionId = revId,
            ),
        )
        db.knowledgeV2Dao().insertRevision(
            KnowledgeV2RevisionEntity(
                revisionId = revId,
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
    fun `processor rebuilds lexical generation end to end`() = runBlocking {
        val content = "开头内容 " + "填充段落。 ".repeat(300) + " TAIL-MARKER"
        seedDoc("doc-1", content)
        val enq = enqueue.enqueueReindex("UserLibrary", "kb-1", listOf("doc-1"))

        val result = runner.run(enq.itemIds[0]) {
            processor.process(db.knowledgeJobDao().getJobItem(enq.itemIds[0])!!, this)
        }

        assertTrue("处理应成功: $result", result is JobStepResult.Succeeded)
        val active = db.knowledgeJobDao().getActiveGeneration("doc-1")
        assertNotNull("必须发布活动代", active)
        assertTrue("代内应有多个 chunk", db.knowledgeJobDao().countGenChunks(active!!) > 1)

        // 重建后应可检索到完整正文尾部。
        val retriever = V2LexicalRetriever(db.knowledgeJobDao())
        val recall = retriever.search(
            KnowledgeSearchRequest(
                requestId = "q",
                query = "TAIL-MARKER",
                entry = KnowledgeEntryType.TOOL,
                scope = KnowledgeScope.Resolved(setOf("doc-1")),
                policy = RetrievalPolicy(5, 0.35f, 0.5f, false, 1f, 1f, false, "", "", 0),
                datasetEpoch = 1L,
                deadlineMs = 5000L,
            ),
            listOf(active),
            5,
        )
        assertTrue("重建后应命中尾部标记", recall.hits.any { it.text.contains("TAIL-MARKER") })
    }

    @Test
    fun `chunk only doc fails honestly instead of fake success`() = runBlocking {
        db.knowledgeV2Dao().insertDoc(
            KnowledgeV2DocEntity(
                docId = "doc-legacy",
                ownerType = "UserLibrary",
                ownerId = "kb-1",
                currentRevisionId = "rev-legacy-1",
            ),
        )
        db.knowledgeV2Dao().insertRevision(
            KnowledgeV2RevisionEntity(
                revisionId = "rev-legacy-1",
                docId = "doc-legacy",
                revisionNo = 1,
                contentHash = "",
                fullContent = "",
                preview = "preview only",
                origin = "legacy-import:CHUNK_ONLY",
            ),
        )
        val enq = enqueue.enqueueReindex("UserLibrary", "kb-1", listOf("doc-legacy"))
        val result = runner.run(enq.itemIds[0]) {
            processor.process(db.knowledgeJobDao().getJobItem(enq.itemIds[0])!!, this)
        }
        assertTrue("ChunkOnly 不得假成功", result is JobStepResult.Failed)
        assertEquals("不得发布活动代", null, db.knowledgeJobDao().getActiveGeneration("doc-legacy"))
    }
}
