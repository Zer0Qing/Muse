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
 * V22：关闭再启用中枢时的对账。
 *
 * 核心断言：残留不能一刀切。该继续的继续、该问用户的问用户、
 * 该失效的失效 —— 全清会让用户交代过的活凭空消失。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentBotModeReconcilerTest {

    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository
    private lateinit var reconciler: AgentBotModeReconciler
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
        reconciler = AgentBotModeReconciler(repo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun newTask(goal: String = "改错字") = repo.createTask(
        botId = "bot-1",
        originSessionId = "s1",
        requestMessageId = "m1",
        goal = goal,
        acceptanceCriteria = "只改错字",
    )

    @Test
    fun `queued task survives the mode toggle`() = runTest {
        newTask()
        val report = reconciler.reconcileOnEnable("bot-1")

        assertEquals("排队中的任务应当可以继续", 1, report.resumable)
        assertTrue(report.hasAnything)
    }

    @Test
    fun `dead lease from the disabled window becomes unknown`() = runTest {
        val task = newTask()
        repo.transition(task.taskId, BotTaskStatusMachine.Event.START)
        val run = repo.openRun(task.taskId)!!
        // 关掉功能很久之后重新启用
        clock = run.leaseUntil + 1

        val report = reconciler.reconcileOnEnable("bot-1")

        assertEquals("死租约要让用户核验", 1, report.needsUser)
        assertEquals(
            BotTaskStatus.UNKNOWN,
            repo.getTask(task.taskId)!!.status,
        )
    }

    @Test
    fun `pending approval expires rather than being denied`() = runTest {
        val task = newTask()
        val run = repo.openRun(task.taskId)!!
        val approval = repo.createApproval(
            botId = "bot-1",
            originSessionId = "s1",
            taskId = task.taskId,
            runId = run.runId,
            stepId = "step-1",
            taskRevision = 1,
            toolCallId = "c1",
            toolName = "workspace_write",
            argumentsRef = "a",
            argumentsHash = "h",
            resourceVersion = "v1",
            risk = "NORMAL",
            ttlMs = 100L,
        )
        clock = 10_000L

        val report = reconciler.reconcileOnEnable("bot-1")

        assertEquals(1, report.expiredApprovals)
        assertEquals(
            "过期不是用户拒绝 —— 关掉期间用户根本没看到卡片",
            ApprovalStatus.EXPIRED,
            repo.getApproval(approval.approvalId)!!.status,
        )
    }

    @Test
    fun `unconsumed events are counted so they get reprocessed`() = runTest {
        repo.appendEvent("bot-1", "s1", "", "", BotEventKind.TASK_PROGRESSED, "k1")
        repo.appendEvent("bot-1", "s1", "", "", BotEventKind.TASK_PROGRESSED, "k2")

        val report = reconciler.reconcileOnEnable("bot-1")

        assertEquals(2, report.pendingEvents)
    }

    @Test
    fun `nothing pending reports an empty state`() = runTest {
        val report = reconciler.reconcileOnEnable("bot-1")
        assertFalse("没有残留时不该弹任何提示", report.hasAnything)
    }

    @Test
    fun `terminal tasks are not counted as resumable`() = runTest {
        val task = newTask()
        repo.transition(task.taskId, BotTaskStatusMachine.Event.START)
        repo.transition(task.taskId, BotTaskStatusMachine.Event.EXECUTE_DONE)
        repo.transition(task.taskId, BotTaskStatusMachine.Event.REVIEW_ACCEPT)

        val report = reconciler.reconcileOnEnable("bot-1")

        assertEquals("已完成的不该被重新执行", 0, report.resumable)
    }
}
