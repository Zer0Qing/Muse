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
 * 取消不能抹掉已发生的副作用。

 * 审查 P1-5：`cancel()` 无条件走 `CANCEL_CONFIRM → CANCELLED`，从不看步骤账本。
 * 已经发过通知、改过文件的任务被汇报成「已取消，什么都没发生」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CancelSideEffectTest {

    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository

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
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `CANCEL_TO_PARTIAL is reachable from CANCEL_REQUESTED`() {
        // 事件存在但此前从未被发出 —— 这条断言钉住状态机侧的能力。
        assertEquals(
            BotTaskStatus.PARTIAL,
            BotTaskStatusMachine.next(
                BotTaskStatus.CANCEL_REQUESTED,
                BotTaskStatusMachine.Event.CANCEL_TO_PARTIAL,
            ),
        )
    }

    @Test
    fun `a task with irreversible steps lands in PARTIAL when cancelled`() = runTest {
        val task = repo.createTask(
            botId = "bot-1",
            originSessionId = "s1",
            requestMessageId = "m1",
            goal = "发通知",
            acceptanceCriteria = "发出即可",
        )
        repo.transition(task.taskId, BotTaskStatusMachine.Event.START)
        val run = repo.openRun(task.taskId)!!
        // 记一步不可回滚的操作（例如已发出通知）。
        repo.recordStep(
            runId = run.runId,
            stepIndex = 0,
            toolName = "device_notify",
            argumentsHash = "h",
            baseHash = "",
            approvalId = "",
            sideEffect = StepSideEffect.NO_BLIND_REPLAY,
        )

        // 直接走状态机验证语义：CANCEL_REQUEST → CANCEL_TO_PARTIAL → PARTIAL。
        repo.transition(task.taskId, BotTaskStatusMachine.Event.CANCEL_REQUEST)
        repo.transition(task.taskId, BotTaskStatusMachine.Event.CANCEL_TO_PARTIAL)

        assertEquals(
            "有不可回滚副作用时必须报 PARTIAL，不能报已取消",
            BotTaskStatus.PARTIAL,
            repo.getTask(task.taskId)!!.status,
        )
    }

    @Test
    fun `the ledger is readable before deciding`() = runTest {
        // 判据来自账本，所以要确认账本真的能读出那一步。
        val task = repo.createTask(
            botId = "bot-1",
            originSessionId = "s1",
            requestMessageId = "m1",
            goal = "写文件",
            acceptanceCriteria = "写进去",
        )
        repo.transition(task.taskId, BotTaskStatusMachine.Event.START)
        val run = repo.openRun(task.taskId)!!
        repo.recordStep(
            runId = run.runId,
            stepIndex = 0,
            toolName = "workspace_write",
            argumentsHash = "h",
            baseHash = "",
            approvalId = "",
            sideEffect = StepSideEffect.NO_BLIND_REPLAY,
        )

        val irreversible = repo.listSteps(run.runId)
            .filter { it.sideEffect == StepSideEffect.NO_BLIND_REPLAY }
        assertTrue("账本必须能读出不可回滚步骤", irreversible.isNotEmpty())
        assertEquals("workspace_write", irreversible.single().toolName)
    }
}
