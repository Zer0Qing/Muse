package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 验收清单 D 组的可静态验证部分。

 * 清单里 D 组（审批）有 6 项，其中 D2/D3/D4/D5 的**语义**可以在 JVM 里钉住 ——
 * 只有"卡片长什么样""点了有没有反应"必须设备看。

 * 这个类验的是语义，不是界面。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AcceptanceDApprovalTest {

    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository
    private lateinit var service: ApprovalService

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
        )
        service = ApprovalService(repository = repo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun setup(tag: String = "t1"): ApprovalRequestEntity {
        // 每次用不同 botId：同一个 Bot 下重复 openRun 会互相干扰。
        val task = repo.createTask(
            botId = "bot-" + tag,
            originSessionId = "s1",
            requestMessageId = "m1",
            goal = "改错字",
            acceptanceCriteria = "只改错字",
        )
        repo.transition(task.taskId, BotTaskStatusMachine.Event.START)
        val run = repo.openRun(task.taskId)!!
        repo.transition(task.taskId, BotTaskStatusMachine.Event.WAIT_APPROVAL)
        return repo.createApproval(
            botId = "bot-" + tag, originSessionId = "s1", taskId = task.taskId,
            runId = run.runId, stepId = "step-" + tag, taskRevision = 1,
            toolCallId = "c1", toolName = "workspace_write",
            argumentsRef = "a", argumentsHash = "h", resourceVersion = "v1", risk = "NORMAL",
        )
    }

    @Test
    fun `D2 approving twice does not execute twice`() = runTest {
        // 双击"允许"不能执行两遍 —— CAS 只结算一次。
        val approval = setup()
        val first = service.approve(approval.approvalId, "h", "v1")
        val second = service.approve(approval.approvalId, "h", "v1")

        assertTrue("第一次应结算", first is ApprovalService.Outcome.Settled)
        assertTrue(
            "第二次必须报已结算，不能重复执行",
            second is ApprovalService.Outcome.AlreadySettled,
        )
    }

    @Test
    fun `D3 denying marks denied not approved`() = runTest {
        val approval = setup()
        service.deny(approval.approvalId, "不想让它改")

        val stored = repo.getApproval(approval.approvalId)!!
        assertEquals("拒绝必须落成 DENIED", ApprovalStatus.DENIED, stored.status)
    }

    @Test
    fun `D4 expired is not denied`() = runTest {
        // 清单里点名"最容易写错的一条"：过期不是拒绝。
        val approval = setup()
        // 把期限改到过去。
        db.approvalRequestDao().upsert(approval.copy(expiresAt = System.currentTimeMillis() - 1000))

        val outcome = service.approve(approval.approvalId, "h", "v1")

        assertTrue("过期要报 Expired", outcome is ApprovalService.Outcome.Expired)
        assertEquals(
            "状态必须是 EXPIRED，不能是 DENIED",
            ApprovalStatus.EXPIRED,
            repo.getApproval(approval.approvalId)!!.status,
        )
    }

    @Test
    fun `D5 denying one approval leaves the other waiting`() = runTest {
        val a = setup("ta")
        val b = setup("tb")

        service.deny(a.approvalId, "拒绝第一个")

        val pending = repo.pendingApprovals()
        assertEquals("另一个必须还在等", 1, pending.size)
        assertEquals(b.approvalId, pending.single().approvalId)
    }
}
