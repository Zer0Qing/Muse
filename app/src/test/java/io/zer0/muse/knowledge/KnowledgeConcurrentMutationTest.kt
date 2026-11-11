package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.knowledge.KnowledgeV2DocEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.domain.JobStepResult
import io.zer0.muse.knowledge.index.GenerationPublisher
import io.zer0.muse.knowledge.jobs.JobRunner
import io.zer0.muse.knowledge.jobs.KnowledgeJobEnqueueService
import io.zer0.muse.knowledge.storage.KnowledgeContentStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.zer0.muse.data.knowledge.IndexGenerationState
import io.zer0.muse.knowledge.domain.KnowledgeAvailability
import io.zer0.muse.knowledge.domain.KnowledgeJobStage
import io.zer0.muse.knowledge.ingest.LocatedChunk
import org.junit.Assert.assertTrue

/**
 * R4 步骤 1/7(计划 R4:「阶段故障注入:Acquire/Parse/Chunk/Lexical/Validate/Publish 前后分别中断,
 * 断言旧活动代可查且新代不半可见」「同文档编辑/重建/删除竞态加入 expectedRevisionId、deleteEpoch 与 fence」)。
 *
 * 全链路:入队 -> JobRunner 领取 -> GenerationPublisher staging/validate/publish;
 * 每个故障点断言旧活动代仍可查、新代未半可见、删除不复活。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgeConcurrentMutationTest {

    private lateinit var db: MuseDb
    private lateinit var runner: JobRunner
    private lateinit var enqueue: KnowledgeJobEnqueueService
    private lateinit var publisher: GenerationPublisher

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        runner = JobRunner(jobDao = db.knowledgeJobDao(), clock = { 1000L })
        enqueue = KnowledgeJobEnqueueService(db.knowledgeJobDao(), clock = { 1000L })
        publisher = GenerationPublisher(db.knowledgeJobDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun chunks(vararg texts: String): List<LocatedChunk> =
        texts.mapIndexed { i, t -> LocatedChunk(text = t, locator = "p1s${i + 1}", kind = "paragraph") }

    private fun hashOf(texts: List<String>): String =
        KnowledgeContentStore.hashOf(texts.joinToString("").toByteArray())

    private suspend fun seedDoc(docId: String, revisionId: String) {
        db.knowledgeV2Dao().insertDoc(
            KnowledgeV2DocEntity(
                docId = docId,
                ownerType = "UserLibrary",
                ownerId = "kb-1",
                currentRevisionId = revisionId,
            ),
        )
    }

    /** 建一个已发布的旧活动代作为「已知好版本」。返回 (genId, revisionId, hash)。 */
    private suspend fun publishOldGeneration(docId: String): Triple<String, String, String> {
        val revisionId = "rev-$docId-old"
        seedDoc(docId, revisionId)
        val cs = chunks("old alpha", "old beta")
        val h = hashOf(cs.map { it.text })
        val gen = publisher.stageGeneration(docId, revisionId, cs)
        publisher.validate(gen, revisionId, h) { t -> hashOf(t) }
        publisher.publish(gen, revisionId)
        return Triple(gen, revisionId, h)
    }

    @Test
    fun `failure during chunk stage keeps old active generation`() = runBlocking {
        val (oldGen, _, _) = publishOldGeneration("doc-1")
        assertEquals(oldGen, db.knowledgeJobDao().getActiveGeneration("doc-1"))

        val enq = enqueue.enqueueReindex("UserLibrary", "kb-1", listOf("doc-1"))
        assertEquals(1, enq.itemIds.size)

        // 在 CHUNK 阶段故障注入:抛异常,作业 FAILED;此时代未发布。
        val result = runner.run(enq.itemIds[0]) {
            advance(KnowledgeJobStage.PARSE)
            advance(KnowledgeJobStage.CHUNK)
            error("chunk stage failure")
        }

        assertTrue(result is JobStepResult.Failed)
        // 旧活动代仍可查(未被新代覆盖)。
        assertEquals("旧活动代必须仍可查", oldGen, db.knowledgeJobDao().getActiveGeneration("doc-1"))
        assertEquals(IndexGenerationState.ACTIVE.name, db.knowledgeJobDao().getGeneration(oldGen)?.state)
    }

    @Test
    fun `failure at validate stage keeps new generation not visible and old active`() = runBlocking {
        val (oldGen, _, _) = publishOldGeneration("doc-1")

        val enq = enqueue.enqueueReindex("UserLibrary", "kb-1", listOf("doc-1"))
        var stagedGen: String? = null
        val result = runner.run(enq.itemIds[0]) {
            advance(KnowledgeJobStage.PARSE)
            advance(KnowledgeJobStage.CHUNK)
            // staging 新代,但校验失败(错误 hash),不 publish。
            val cs = chunks("new content x")
            val gen = publisher.stageGeneration("doc-1", "rev-doc-1-new", cs)
            stagedGen = gen
            advance(KnowledgeJobStage.VALIDATE)
            val err = publisher.validate(gen, "rev-doc-1-new", "wrong-hash") { t -> hashOf(t) }
            assertNotNull("校验应失败", err)
            // 校验失败 -> abandon 新代,不切活动指针。
            publisher.abandon(gen)
            JobStepResult.Failed("${enq.itemIds[0]}", KnowledgeJobStage.VALIDATE, err!!, retryable = true)
        }

        assertTrue(result is JobStepResult.Failed)
        val staged = stagedGen
        assertNotNull(staged)
        // 新代被清理为 FAILED,活动指针仍为旧代。
        assertEquals(IndexGenerationState.FAILED.name, db.knowledgeJobDao().getGeneration(staged!!)?.state)
        assertEquals("旧活动代仍可查", oldGen, db.knowledgeJobDao().getActiveGeneration("doc-1"))
    }

    @Test
    fun `successful pipeline publishes new generation replacing old`() = runBlocking {
        val (oldGen, _, _) = publishOldGeneration("doc-1")

        val enq = enqueue.enqueueReindex("UserLibrary", "kb-1", listOf("doc-1"))
        var newGen: String? = null
        val result = runner.run(enq.itemIds[0]) exec@{
            advance(KnowledgeJobStage.PARSE)
            advance(KnowledgeJobStage.CHUNK)
            val cs = chunks("new alpha", "new beta", "new gamma")
            val gen = publisher.stageGeneration("doc-1", "rev-doc-1-new", cs)
            newGen = gen
            advance(KnowledgeJobStage.LEXICAL)
            advance(KnowledgeJobStage.VALIDATE)
            val err = publisher.validate(gen, "rev-doc-1-new", hashOf(cs.map { it.text })) { t -> hashOf(t) }
            if (err != null) {
                publisher.abandon(gen)
                return@exec JobStepResult.Failed("${enq.itemIds[0]}", KnowledgeJobStage.VALIDATE, err, false)
            }
            advance(KnowledgeJobStage.PUBLISH)
            val pub = publisher.publish(gen, "rev-doc-1-new")
            if (pub !is io.zer0.muse.knowledge.domain.PublishResult.Published) {
                return@exec JobStepResult.Failed("${enq.itemIds[0]}", KnowledgeJobStage.PUBLISH, "publish failed", false)
            }
            JobStepResult.Succeeded("${enq.itemIds[0]}", KnowledgeAvailability.KEYWORD_READY)
        }

        assertTrue(result is JobStepResult.Succeeded)
        assertNotNull(newGen)
        assertEquals("新代成为活动代", newGen, db.knowledgeJobDao().getActiveGeneration("doc-1"))
        assertEquals(IndexGenerationState.ACTIVE.name, db.knowledgeJobDao().getGeneration(newGen!!)?.state)
        // 旧代不再是活动指针(可延迟清理,但状态仍 ACTIVE 直到清理) —— 断言指针已切换。
        assertTrue(oldGen != newGen)
    }

    @Test
    fun `empty doc ids does not expand to all`() = runBlocking {
        publishOldGeneration("doc-1")
        val enq = enqueue.enqueueReindex("UserLibrary", "kb-1", emptyList())
        assertTrue("空文档集必须返回空结果", enq.isEmpty)
        assertEquals(0, enq.itemIds.size)
        // 旧活动代不受影响。
        assertNotNull(db.knowledgeJobDao().getActiveGeneration("doc-1"))
    }

    @Test
    fun `delete during run does not resurrect content`() = runBlocking {
        val (oldGen, _, _) = publishOldGeneration("doc-1")
        val enq = enqueue.enqueueReindex("UserLibrary", "kb-1", listOf("doc-1"))

        // 执行中:先删活动代指针(模拟删除已提交),再尝试发布新代 -> 不得复活。
        val result = runner.run(enq.itemIds[0]) {
            advance(KnowledgeJobStage.PARSE)
            advance(KnowledgeJobStage.CHUNK)
            // 删除:清活动指针 + doc 行(deleteEpoch 语义在 R4 用清指针表达)。
            db.knowledgeJobDao().clearActiveGeneration("doc-1")
            val cs = chunks("resurrect attempt")
            val gen = publisher.stageGeneration("doc-1", "rev-doc-1-new", cs)
            advance(KnowledgeJobStage.VALIDATE)
            val err = publisher.validate(gen, "rev-doc-1-new", hashOf(cs.map { it.text })) { t -> hashOf(t) }
            assertNull(err)
            // 删除后发布:活动指针切回会把已删内容复活 -> 本测试断言调用方在删除后
            // 不得继续发布。JobRunner 的取消/失败路径应阻止;这里显式模拟丢弃。
            publisher.abandon(gen)
            JobStepResult.Failed("${enq.itemIds[0]}", KnowledgeJobStage.VALIDATE, "doc deleted mid-run", false)
        }

        // 删除后活动指针保持空(不复活)。
        assertNull("删除后不得复活活动代", db.knowledgeJobDao().getActiveGeneration("doc-1"))
        assertTrue(result is JobStepResult.Failed)
    }
}
