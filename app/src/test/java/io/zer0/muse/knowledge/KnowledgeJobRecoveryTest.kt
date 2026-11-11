package io.zer0.muse.knowledge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.knowledge.KnowledgeJobEntity
import io.zer0.muse.data.knowledge.KnowledgeJobItemEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.knowledge.domain.JobStepResult
import io.zer0.muse.knowledge.domain.KnowledgeAvailability
import io.zer0.muse.knowledge.domain.KnowledgeJobStage
import io.zer0.muse.knowledge.domain.KnowledgeJobState
import io.zer0.muse.knowledge.jobs.JobRunner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R4 步骤 1/2/3(计划 R4:「阶段故障注入…旧活动代可查」「原子领取 lease/fence 与 checkpoint」
 * 「取消意图落库…取消后剩余项为 Cancelled」「重启 resume 幂等」)。
 *
 * 全部用真实 in-memory Room + 真实 JobRunner/KnowledgeJobDao;断言的是库内真实状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KnowledgeJobRecoveryTest {

    private lateinit var db: MuseDb
    private lateinit var runner: JobRunner
    private var holderCounter = 0

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        runner = JobRunner(
            jobDao = db.knowledgeJobDao(),
            clock = { 1000L },
            newHolderId = { "worker-${holderCounter++}" },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedItem(
        jobItemId: String = "item-1",
        state: KnowledgeJobState = KnowledgeJobState.QUEUED,
        cancelRequested: Int = 0,
    ) {
        db.knowledgeJobDao().insertJob(
            KnowledgeJobEntity(
                jobId = "job-1",
                ownerType = "UserLibrary",
                ownerId = "kb-1",
                state = KnowledgeJobState.QUEUED.name,
                createdAt = 1000L,
                updatedAt = 1000L,
            ),
        )
        db.knowledgeJobDao().insertJobItem(
            KnowledgeJobItemEntity(
                jobItemId = jobItemId,
                jobId = "job-1",
                docId = "doc-1",
                state = state.name,
                stage = KnowledgeJobStage.ACQUIRE.name,
                cancelRequested = cancelRequested,
                createdAt = 1000L,
                updatedAt = 1000L,
            ),
        )
    }

    @Test
    fun `successful run commits terminal and advances stages`() = runBlocking {
        seedItem()

        val result = runner.run("item-1") {
            advance(KnowledgeJobStage.PARSE, "pages=1")
            advance(KnowledgeJobStage.CHUNK, "chunks=3")
            advance(KnowledgeJobStage.LEXICAL, "lexical=3")
            JobStepResult.Succeeded("item-1", KnowledgeAvailability.KEYWORD_READY)
        }

        assertTrue("应成功", result is JobStepResult.Succeeded)
        val stored = db.knowledgeJobDao().getJobItem("item-1")!!
        assertEquals(KnowledgeJobState.SUCCEEDED.name, stored.state)
        assertEquals(KnowledgeJobStage.CLEANUP.name, stored.stage)
        assertEquals("lease 释放", "", stored.leaseHolder)
        assertEquals("尝试计数 +1", 1, stored.attempts)
    }

    @Test
    fun `resume on terminal item is idempotent`() = runBlocking {
        seedItem()
        runner.run("item-1") {
            JobStepResult.Succeeded("item-1", KnowledgeAvailability.KEYWORD_READY)
        }
        var executed = false
        val result = runner.run("item-1") {
            executed = true
            JobStepResult.Succeeded("item-1", KnowledgeAvailability.KEYWORD_READY)
        }
        assertTrue("已终态应直接返回", result is JobStepResult.Succeeded)
        assertEquals("已终态不得重复执行", false, executed)
    }

    @Test
    fun `cancel request turns item into cancelled and no later completed`() = runBlocking {
        seedItem()
        // 先请求取消(落库),再 run -> 直接落 CANCELLED,execute 不执行。
        runner.requestCancel("item-1")
        var executed = false
        val result = runner.run("item-1") {
            executed = true
            JobStepResult.Succeeded("item-1", KnowledgeAvailability.KEYWORD_READY)
        }
        assertTrue("应为取消终态", result is JobStepResult.Cancelled)
        assertEquals("取消后不得执行", false, executed)
        assertEquals(KnowledgeJobState.CANCELLED.name, db.knowledgeJobDao().getJobItem("item-1")!!.state)
    }

    @Test
    fun `cancel during execution wins over completed write`() = runBlocking {
        seedItem()
        // 在 execute 中途请求取消(模拟外部取消意图落到库)。
        val result = runner.run("item-1") {
            advance(KnowledgeJobStage.PARSE)
            runner.requestCancel("item-1")
            // 即便此时返回 Succeeded,commitTerminal 的 CAS(lease_holder/state)也会拒绝——
            // 但更早:JobRunner 在 commitResult 前的路径要在最终提交时以库中现状为准。
            JobStepResult.Succeeded("item-1", KnowledgeAvailability.KEYWORD_READY)
        }
        val stored = db.knowledgeJobDao().getJobItem("item-1")!!
        assertTrue(
            "取消意图使最终状态非 SUCCEEDED",
            stored.state == KnowledgeJobState.CANCELLED.name ||
                stored.state == KnowledgeJobState.CANCEL_REQUESTED.name,
        )
        // 无论返回类型,库内不得出现 SUCCEEDED 覆盖取消。
        assertTrue("库内不得是 SUCCEEDED", stored.state != KnowledgeJobState.SUCCEEDED.name)
        // result 也非成功终态。
        assertTrue(result !is JobStepResult.Succeeded)
    }

    @Test
    fun `exception in execution marks failure retryable`() = runBlocking {
        seedItem()
        val result = runner.run("item-1") {
            advance(KnowledgeJobStage.PARSE)
            error("parse blew up")
        }
        assertTrue("应失败", result is JobStepResult.Failed)
        val stored = db.knowledgeJobDao().getJobItem("item-1")!!
        assertEquals(KnowledgeJobState.FAILED.name, stored.state)
    }

    @Test
    fun `second worker cannot acquire non expired lease`() = runBlocking {
        seedItem()
        // 用固定 clock 让第一次领取 lease 不过期;第二次 run 应拿不到(不推进)。
        var firstAdvanceStage: KnowledgeJobStage? = null
        runner.run("item-1") {
            advance(KnowledgeJobStage.PARSE)
            firstAdvanceStage = KnowledgeJobStage.PARSE
            // 不立即完成,返回 Progressed(保持 RUNNING + lease 持有)。
            JobStepResult.Progressed("item-1", KnowledgeJobStage.PARSE, KnowledgeAvailability.STORED_ONLY)
        }
        // 此时 item 仍 RUNNING 且 lease_holder 非空。
        val afterFirst = db.knowledgeJobDao().getJobItem("item-1")!!
        assertEquals(KnowledgeJobState.RUNNING.name, afterFirst.state)
        assertTrue("lease 被持有", afterFirst.leaseHolder.isNotBlank())

        // 第二个 Worker(新 holder)在 lease 未过期时领取 -> 拒绝(不推进,返回 Fenced)。
        var secondExecuted = false
        val result = runner.run("item-1") {
            secondExecuted = true
            JobStepResult.Succeeded("item-1", KnowledgeAvailability.KEYWORD_READY)
        }
        assertEquals("lease 未过期时第二个 Worker 不得执行", false, secondExecuted)
        assertTrue(result is JobStepResult.Fenced)
    }

    @Test
    fun `restore gate pauses job claim`() = runBlocking {
        seedItem()
        val gatedRunner = JobRunner(
            jobDao = db.knowledgeJobDao(),
            clock = { 1000L },
            writeAllowed = { false },
        )
        var executed = false
        val result = gatedRunner.run("item-1") {
            executed = true
            JobStepResult.Succeeded("item-1", KnowledgeAvailability.KEYWORD_READY)
        }
        assertEquals("恢复期间不得执行", false, executed)
        assertTrue(result is JobStepResult.Failed)
        assertTrue((result as JobStepResult.Failed).reason.contains("恢复进行中"))
    }

    @Test
    fun `stale dataset epoch is fenced and not committed`() = runBlocking {
        // item 记录 epoch=1;当前数据集已升到 2(恢复后)。
        db.knowledgeJobDao().insertJob(
            KnowledgeJobEntity(
                jobId = "job-1",
                ownerType = "UserLibrary",
                ownerId = "kb-1",
                state = KnowledgeJobState.QUEUED.name,
            ),
        )
        db.knowledgeJobDao().insertJobItem(
            KnowledgeJobItemEntity(
                jobItemId = "item-epoch",
                jobId = "job-1",
                docId = "doc-1",
                state = KnowledgeJobState.QUEUED.name,
                stage = KnowledgeJobStage.ACQUIRE.name,
                datasetEpoch = 1L,
            ),
        )
        val epochRunner = JobRunner(
            jobDao = db.knowledgeJobDao(),
            clock = { 1000L },
            latestEpochProvider = { 2L },
        )
        val result = epochRunner.run("item-epoch") {
            JobStepResult.Succeeded("item-epoch", KnowledgeAvailability.KEYWORD_READY)
        }
        assertTrue("旧 epoch 必须被 fence", result is JobStepResult.Fenced)
        assertEquals(
            "旧 epoch 提交不得把状态写成成功",
            KnowledgeJobState.RUNNING.name,
            db.knowledgeJobDao().getJobItem("item-epoch")!!.state,
        )
    }
}
