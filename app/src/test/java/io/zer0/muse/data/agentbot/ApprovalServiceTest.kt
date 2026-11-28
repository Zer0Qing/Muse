package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 审批结算的语义边界。
 *
 * 三条最容易被写错、后果最严重的：
 *  - 双击批准跑两遍；
 *  - 过期被记成用户拒绝；
 *  - 参数变了还照旧批准。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApprovalServiceTest {

    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository
    private lateinit var service: ApprovalService
    private var clock = 1_000L

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        repo = AgentBotRepository(
            taskDao = db.botTaskDao(),
            runDao = db.taskRunDao(),
            stepDao = db.taskStepDao(),
            eventDao = db.botEventDao(),
            approvalDao = db.approvalRequestDao(),
            publicationDao = db.publicationOutboxDao(),
            bindingDao = db.botBindingDao(),
            now = { clock },
        )
        service = ApprovalService(repo, now = { clock })
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * @param stepId 每个测试要传不同的值：同一步的第二次 createApproval 会复用第一张卡
     *   （V24 幂等），并行场景下会互相干扰。
     */
    private suspend fun newApproval(
        argumentsHash: String = "hash-1",
        resourceVersion: String = "v1",
        ttlMs: Long = 60_000L,
        stepId: String = "s-" + java.util.UUID.randomUUID().toString().take(8),
    ) = repo.createApproval(
        botId = "bot-1",
        originSessionId = "session-1",
        taskId = "t1",
        runId = "r1",
        stepId = stepId,
        taskRevision = 1,
        toolCallId = "c1",
        toolName = "workspace_write",
        argumentsRef = "artifact://args",
        argumentsHash = argumentsHash,
        resourceVersion = resourceVersion,
        risk = "NORMAL",
        ttlMs = ttlMs,
    )

    @Test
    fun `approving settles once`() = runTest {
        val approval = newApproval()
        val first = service.approve(approval.approvalId, "hash-1", "v1")
        assertTrue(first is ApprovalService.Outcome.Settled)
        assertEquals(ApprovalStatus.APPROVED, (first as ApprovalService.Outcome.Settled).status)

        val second = service.approve(approval.approvalId, "hash-1", "v1")
        assertTrue(
            "第二次点击不能再结算一次",
            second is ApprovalService.Outcome.AlreadySettled,
        )
    }

    @Test
    fun `changing arguments invalidates the approval instead of executing`() = runTest {
        val approval = newApproval(argumentsHash = "hash-1")
        val outcome = service.approve(approval.approvalId, "hash-2", "v1")

        assertTrue("参数变了旧批准必须失效", outcome is ApprovalService.Outcome.Stale)
        assertEquals(
            ApprovalStatus.INVALIDATED,
            repo.getApproval(approval.approvalId)!!.status,
        )
    }

    @Test
    fun `changing resource version invalidates the approval`() = runTest {
        val approval = newApproval(resourceVersion = "v1")
        val outcome = service.approve(approval.approvalId, "hash-1", "v2")

        assertTrue(outcome is ApprovalService.Outcome.Stale)
        assertEquals(
            ApprovalStatus.INVALIDATED,
            repo.getApproval(approval.approvalId)!!.status,
        )
    }

    @Test
    fun `approving after expiry is expired not approved`() = runTest {
        val approval = newApproval(ttlMs = 100L)
        clock = 10_000L

        val outcome = service.approve(approval.approvalId, "hash-1", "v1")
        assertTrue("过期不视为授权", outcome is ApprovalService.Outcome.Expired)
        assertEquals(
            ApprovalStatus.EXPIRED,
            repo.getApproval(approval.approvalId)!!.status,
        )
        assertEquals(
            "过期不能记成用户拒绝",
            "system",
            repo.getApproval(approval.approvalId)!!.decisionActor,
        )
    }

    @Test
    fun `denial does not need to match arguments`() = runTest {
        val approval = newApproval(argumentsHash = "hash-1")
        val outcome = service.deny(approval.approvalId, "不想让它改这个文件")

        assertTrue(outcome is ApprovalService.Outcome.Settled)
        assertEquals(ApprovalStatus.DENIED, repo.getApproval(approval.approvalId)!!.status)
    }

    @Test
    fun `denying twice reports already settled`() = runTest {
        val approval = newApproval()
        service.deny(approval.approvalId)
        val second = service.deny(approval.approvalId)
        assertTrue(second is ApprovalService.Outcome.AlreadySettled)
    }

    @Test
    fun `approval on a missing record is stale not a crash`() = runTest {
        val outcome = service.approve("does-not-exist", "hash-1", "v1")
        assertTrue(outcome is ApprovalService.Outcome.Stale)
    }

    @Test
    fun `cancelling a pending approval settles it as cancelled`() = runTest {
        val approval = newApproval()
        val outcome = service.cancel(approval.approvalId)
        assertTrue(outcome is ApprovalService.Outcome.Settled)
        assertEquals(ApprovalStatus.CANCELLED, repo.getApproval(approval.approvalId)!!.status)
    }

    @Test
    fun `sweep expires overdue approvals without touching fresh ones`() = runTest {
        val stale = newApproval(ttlMs = 100L)
        val fresh = newApproval(ttlMs = 600_000L)
        clock = 10_000L

        assertEquals(1, service.sweepExpired())
        assertEquals(ApprovalStatus.EXPIRED, repo.getApproval(stale.approvalId)!!.status)
        assertEquals(
            "没过期的不该被扫掉",
            ApprovalStatus.PENDING,
            repo.getApproval(fresh.approvalId)!!.status,
        )
    }

    @Test
    fun `bot can never self approve`() {
        assertFalse(
            "主 Bot 不能代替用户批准自己或子 Agent 的权限申请",
            service.canBotSelfApprove(),
        )
    }

    @Test
    fun `blank argument hash in the record does not block approval`() = runTest {
        // 有些审批（例如纯提示类）不绑参数；此时不该因为 hash 为空就拒绝。
        val approval = repo.createApproval(
            botId = "bot-1",
            originSessionId = "session-1",
            taskId = "t1",
            runId = "r1",
            stepId = "s1",
            taskRevision = 1,
            toolCallId = "c1",
            toolName = "notify",
            argumentsRef = "",
            argumentsHash = "",
            resourceVersion = "",
            risk = "SAFE",
        )
        val outcome = service.approve(approval.approvalId, "any", "")
        assertTrue(outcome is ApprovalService.Outcome.Settled)
    }
}
