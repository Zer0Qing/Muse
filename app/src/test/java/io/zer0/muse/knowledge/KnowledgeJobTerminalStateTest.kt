package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.knowledge.KnowledgeJobEntity
import io.zer0.muse.data.knowledge.KnowledgeJobItemEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.domain.JobStepResult
import io.zer0.muse.knowledge.domain.KnowledgeJobStage
import io.zer0.muse.knowledge.domain.KnowledgeJobState
import io.zer0.muse.knowledge.jobs.JobRunner
import io.zer0.muse.knowledge.jobs.KnowledgeJobEnqueueService
import io.zer0.muse.knowledge.jobs.KnowledgeJobFinalizer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R4 作业终态合同(计划 R4「按 job/item 结果写终态」):
 *
 *  - job 终态按**账本**汇总,不按本轮返回值;
 *  - 可重试失败在未达上限时把 item 退回 QUEUED 并请求重试(否则重试只是空转);
 *  - 不可重试失败、尝试耗尽、回排被拒都直接落 FAILED,不无限重试;
 *  - job + items 入队在同一事务内完成,失败不留下半套账本。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgeJobTerminalStateTest {

    private lateinit var db: MuseDb
    private lateinit var runner: JobRunner
    private lateinit var enqueue: KnowledgeJobEnqueueService
    private lateinit var finalizer: KnowledgeJobFinalizer

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
        runner = JobRunner(db.knowledgeJobDao(), clock = { 1000L })
        enqueue = KnowledgeJobEnqueueService(
            jobDao = db.knowledgeJobDao(),
            clock = { 1000L },
            newJobId = { "job-fixed" },
            newItemId = { "ji-${idCounter++}" },
            transactionRunner = io.zer0.muse.data.knowledge.KnowledgeTransactionRunner.room(db),
        )
        finalizer = KnowledgeJobFinalizer(db.knowledgeJobDao(), maxAttempts = 3, clock = { 2000L })
    }

    private var idCounter = 0

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun jobState(jobId: String): String? = db.knowledgeJobDao().getJob(jobId)?.state

    @Test
    fun `enqueue writes job and items atomically`() = runBlocking {
        val enq = enqueue.enqueueReindex("UserLibrary", "kb-1", listOf("doc-1", "doc-2", "doc-1"))
        assertEquals(2, enq.itemIds.size)
        assertEquals(2, db.knowledgeJobDao().getJobItems(enq.jobId).size)
        assertEquals(KnowledgeJobState.QUEUED.name, jobState(enq.jobId))
    }

    @Test
    fun `enqueue failure leaves no half written job`() = runBlocking {
        // 预占第一条 item 的主键:job 行写入成功后 item 写入冲突,事务必须整体回滚。
        db.knowledgeJobDao().insertJobItem(
            KnowledgeJobItemEntity(
                jobItemId = "ji-0",
                jobId = "job-other",
                docId = "doc-other",
                state = KnowledgeJobState.QUEUED.name,
                stage = KnowledgeJobStage.ACQUIRE.name,
            ),
        )
        val threw = runCatching {
            enqueue.enqueueReindex("UserLibrary", "kb-1", listOf("doc-1", "doc-2"))
        }.isFailure

        assertTrue("item 冲突必须抛出而不是静默半写", threw)
        assertNull("job 行必须随事务回滚", db.knowledgeJobDao().getJob("job-fixed"))
        assertEquals("不得留下孤立 item", 0, db.knowledgeJobDao().getJobItems("job-fixed").size)
        assertEquals("预存行的 jobId 不被改写", "job-other", db.knowledgeJobDao().getJobItem("ji-0")?.jobId)
    }

    @Test
    fun `all items succeeding writes SUCCEEDED`() = runBlocking {
        val enq = enqueue.enqueueReindex("UserLibrary", "kb-1", listOf("doc-1", "doc-2"))
        val outcomes = enq.itemIds.map { itemId ->
            itemId to runner.run(itemId) {
                JobStepResult.Succeeded(itemId, io.zer0.muse.knowledge.domain.KnowledgeAvailability.KEYWORD_READY)
            }
        }
        val decision = finalizer.settle(enq.jobId, outcomes, previouslyTerminal = emptySet(), runAttempt = 0)

        assertEquals(KnowledgeJobFinalizer.Decision.Done(KnowledgeJobState.SUCCEEDED), decision)
        assertEquals(KnowledgeJobState.SUCCEEDED.name, jobState(enq.jobId))
    }

    @Test
    fun `warnings produce SUCCEEDED_WITH_WARNINGS`() = runBlocking {
        val enq = enqueue.enqueueReindex("UserLibrary", "kb-1", listOf("doc-1"))
        val outcomes = enq.itemIds.map { itemId ->
            itemId to runner.run(itemId) {
                JobStepResult.Succeeded(
                    itemId,
                    io.zer0.muse.knowledge.domain.KnowledgeAvailability.KEYWORD_READY,
                    warnings = listOf("词法降级"),
                )
            }
        }
        finalizer.settle(enq.jobId, outcomes, previouslyTerminal = emptySet(), runAttempt = 0)

        assertEquals(KnowledgeJobState.SUCCEEDED_WITH_WARNINGS.name, jobState(enq.jobId))
    }

    @Test
    fun `non retryable failure writes FAILED`() = runBlocking {
        val enq = enqueue.enqueueReindex("UserLibrary", "kb-1", listOf("doc-1"))
        val outcomes = enq.itemIds.map { itemId ->
            itemId to runner.run(itemId) {
                JobStepResult.Failed(itemId, KnowledgeJobStage.PARSE, "文档不存在", retryable = false)
            }
        }
        val decision = finalizer.settle(enq.jobId, outcomes, previouslyTerminal = emptySet(), runAttempt = 0)

        assertEquals(KnowledgeJobFinalizer.Decision.Done(KnowledgeJobState.FAILED), decision)
        assertEquals(KnowledgeJobState.FAILED.name, jobState(enq.jobId))
        assertEquals(
            "item 终态必须由 JobRunner 落库",
            KnowledgeJobState.FAILED.name,
            db.knowledgeJobDao().getJobItem(enq.itemIds[0])?.state,
        )
    }

    @Test
    fun `retryable failure requeues item and asks for retry`() = runBlocking {
        val enq = enqueue.enqueueReindex("UserLibrary", "kb-1", listOf("doc-1"))
        val outcomes = enq.itemIds.map { itemId ->
            itemId to runner.run(itemId) {
                JobStepResult.Failed(itemId, KnowledgeJobStage.LEXICAL, "staging 失败", retryable = true)
            }
        }
        val decision = finalizer.settle(enq.jobId, outcomes, previouslyTerminal = emptySet(), runAttempt = 0)

        assertEquals(KnowledgeJobFinalizer.Decision.Retry, decision)
        assertEquals(
            "可重试项必须退回 QUEUED,否则重试只会空转",
            KnowledgeJobState.QUEUED.name,
            db.knowledgeJobDao().getJobItem(enq.itemIds[0])?.state,
        )
        assertEquals("job 保持 RUNNING", KnowledgeJobState.RUNNING.name, jobState(enq.jobId))
    }

    @Test
    fun `retryable failure stops retrying after attempt limit`() = runBlocking {
        val enq = enqueue.enqueueReindex("UserLibrary", "kb-1", listOf("doc-1"))
        val outcomes = enq.itemIds.map { itemId ->
            itemId to runner.run(itemId) {
                JobStepResult.Failed(itemId, KnowledgeJobStage.LEXICAL, "staging 失败", retryable = true)
            }
        }
        // 已跑到第 3 次(0-based runAttempt=2)-> 达到上限。
        val decision = finalizer.settle(enq.jobId, outcomes, previouslyTerminal = emptySet(), runAttempt = 2)

        assertEquals(KnowledgeJobFinalizer.Decision.Done(KnowledgeJobState.FAILED), decision)
        assertEquals(KnowledgeJobState.FAILED.name, jobState(enq.jobId))
        assertEquals(
            "达到上限后不得再回排",
            KnowledgeJobState.FAILED.name,
            db.knowledgeJobDao().getJobItem(enq.itemIds[0])?.state,
        )
    }

    @Test
    fun `previously terminal failures do not trigger retry`() = runBlocking {
        val enq = enqueue.enqueueReindex("UserLibrary", "kb-1", listOf("doc-1"))
        // 先让 item 落到 FAILED 终态,再把它当作「本轮开始前已终态」。
        runner.run(enq.itemIds[0]) {
            JobStepResult.Failed(enq.itemIds[0], KnowledgeJobStage.PARSE, "旧失败", retryable = true)
        }
        val replay = runner.run(enq.itemIds[0]) { error("不应重新执行已终态项") }

        val decision = finalizer.settle(
            enq.jobId,
            listOf(enq.itemIds[0] to replay),
            previouslyTerminal = setOf(enq.itemIds[0]),
            runAttempt = 0,
        )

        assertEquals(KnowledgeJobFinalizer.Decision.Done(KnowledgeJobState.FAILED), decision)
        assertEquals(KnowledgeJobState.FAILED.name, jobState(enq.jobId))
    }

    @Test
    fun `cancelled item makes job CANCELLED`() = runBlocking {
        val enq = enqueue.enqueueReindex("UserLibrary", "kb-1", listOf("doc-1"))
        runner.requestCancel(enq.itemIds[0])
        val outcomes = enq.itemIds.map { itemId ->
            itemId to runner.run(itemId) { error("取消后不应执行") }
        }
        val decision = finalizer.settle(enq.jobId, outcomes, previouslyTerminal = emptySet(), runAttempt = 0)

        assertEquals(KnowledgeJobFinalizer.Decision.Done(KnowledgeJobState.CANCELLED), decision)
        assertEquals(KnowledgeJobState.CANCELLED.name, jobState(enq.jobId))
    }

    @Test
    fun `missing items marks job failed instead of silent success`() = runBlocking {
        db.knowledgeJobDao().insertJob(
            KnowledgeJobEntity(
                jobId = "job-empty",
                ownerType = "UserLibrary",
                ownerId = "kb-1",
                state = KnowledgeJobState.QUEUED.name,
                datasetEpoch = 0L,
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
        finalizer.markFailed("job-empty", "作业无逐文件项")

        assertEquals(KnowledgeJobState.FAILED.name, jobState("job-empty"))
    }

    @Test
    fun `unterminated item prevents fake success`() = runBlocking {
        val enq = enqueue.enqueueReindex("UserLibrary", "kb-1", listOf("doc-1"))
        // 手工把 item 留在 QUEUED(模拟本轮写入被 fence 拒绝),汇总不得报成功。
        val decision = finalizer.settle(
            enq.jobId,
            listOf(
                enq.itemIds[0] to JobStepResult.Fenced(
                    enq.itemIds[0],
                    io.zer0.muse.knowledge.domain.JobFence(0L, 0L),
                    null,
                ),
            ),
            previouslyTerminal = emptySet(),
            runAttempt = 0,
        )

        assertEquals(KnowledgeJobFinalizer.Decision.Done(KnowledgeJobState.FAILED), decision)
        assertEquals(KnowledgeJobState.FAILED.name, jobState(enq.jobId))
    }

    @Test
    fun `item entity insert order preserves distinct docs`() = runBlocking {
        val enq = enqueue.enqueueReindex("UserLibrary", "kb-1", listOf("doc-a", "doc-b"))
        val items = db.knowledgeJobDao().getJobItems(enq.jobId)
        // createdAt 相同,排序不稳定:按集合断言而不是依赖 SQLite 的隐式行序。
        assertEquals(setOf("doc-a", "doc-b"), items.map { it.docId }.toSet())
        assertTrue(
            "入队项必须是 QUEUED + ACQUIRE",
            items.all { it.state == KnowledgeJobState.QUEUED.name && it.stage == KnowledgeJobStage.ACQUIRE.name },
        )
    }

    @Test
    fun `enqueue item entity carries dataset epoch`() = runBlocking {
        val enq = enqueue.enqueueReindex("UserLibrary", "kb-1", listOf("doc-1"), datasetEpoch = 7L)
        assertEquals(7L, db.knowledgeJobDao().getJob(enq.jobId)?.datasetEpoch)
        assertEquals(7L, db.knowledgeJobDao().getJobItem(enq.itemIds[0])?.datasetEpoch)
    }
}
