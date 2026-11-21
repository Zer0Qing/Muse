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
 * 审批结算后任务必须复位。
 *
 * 缺了这一步，任务会永远停在 WAITING_APPROVAL —— 用户点了"允许"，
 * 卡片消失，但什么都没发生。那是最容易让人失去信任的失败方式。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApprovalResumeTest {

    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository
    private lateinit var service: ApprovalService
    private val resumed = mutableListOf<Triple<String, String, Boolean>>()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build();
        repo = AgentBotRepository(
            taskDao = db.botTaskDao(),
            runDao = db.taskRunDao(),
            stepDao = db.taskStepDao(),
            eventDao = db.botEventDao(),
            approvalDao = db.approvalRequestDao(),
            publicationDao = db.publicationOutboxDao(),
            bindingDao = db.botBindingDao(),
        );
        service = ApprovalService(
            repository = repo,
            onSettled = { taskId, approvalId, approved ->
                resumed += Triple(taskId, approvalId, approved);
            },
        );
    }

    @After
    fun tearDown() {
        db.close();
    }

    private suspend fun setup(): Pair<BotTaskEntity, ApprovalRequestEntity> {
        val task = repo.createTask(
            botId = "bot-1", originSessionId = "s1", requestMessageId = "m1",
            goal = "改错字", acceptanceCriteria = "只改错字",
        );
        repo.transition(task.taskId, BotTaskStatusMachine.Event.START);
        val run = repo.openRun(task.taskId)!!;
        repo.transition(task.taskId, BotTaskStatusMachine.Event.WAIT_APPROVAL);
        val approval = repo.createApproval(
            botId = "bot-1", originSessionId = "s1", taskId = task.taskId,
            runId = run.runId, stepId = "step-0", taskRevision = 1,
            toolCallId = "c1", toolName = "workspace_write",
            argumentsRef = "a", argumentsHash = "h", resourceVersion = "v1", risk = "NORMAL",
        );
        return task to approval;
    }

    @Test
    fun `approving notifies the resume callback`() = runTest {
        val (task, approval) = setup();
        service.approve(approval.approvalId, "h", "v1");

        assertEquals(1, resumed.size);
        assertEquals(task.taskId, resumed.single().first);
        assertTrue("批准要标记为 approved", resumed.single().third);
    }

    @Test
    fun `denying notifies the resume callback as not approved`() = runTest {
        val (task, approval) = setup();
        service.deny(approval.approvalId, "不想让它改");

        assertEquals(1, resumed.size);
        assertEquals(task.taskId, resumed.single().first);
        assertTrue("拒绝要标记为 not approved", !resumed.single().third);
    }

    @Test
    fun `a stale approval does not trigger resume`() = runTest {
        val (_, approval) = setup();
        // 参数变了 → INVALIDATED。
        //
        // 但**仍然要回调**：卡已结算（用户看不到它了），任务却还停在
        // WAITING_APPROVAL。不回调的话它永远出不来 —— expireApprovals 只扫
        // PENDING，卡已结算，扫不到。
        //
        // 回调时 approved=false，所以上层知道"这不是用户批准的"。
        val outcome = service.approve(approval.approvalId, "different-hash", "v1");

        assertTrue("必须是 Stale 结论", outcome is ApprovalService.Outcome.Stale);
        assertEquals("早退分支也要复位任务", 1, resumed.size);
        assertTrue("但要标记成未批准", !resumed.single().third);
    }

    @Test
    fun `a failing resume callback does not undo the settlement`() = runTest {
        val (_, approval) = setup();
        val failing = ApprovalService(
            repository = repo,
            onSettled = { _, _, _ -> error("复位失败") },
        );

        val outcome = failing.approve(approval.approvalId, "h", "v1");

        assertTrue(
            "审批已经结算成功，不能因为复位失败就告诉用户审批失败",
            outcome is ApprovalService.Outcome.Settled,
        );
        assertEquals(
            ApprovalStatus.APPROVED,
            repo.getApproval(approval.approvalId)!!.status,
        );
    }
}