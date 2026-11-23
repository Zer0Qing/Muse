package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 任务账本仓储的并发与幂等契约。
 *
 * 断言的是"出错时会发生什么"，不是"顺利时能存进去"：
 *  - 迟到事件不得改写终态；
 *  - 重复派发只产生一个任务；
 *  - 审批只结算一次；
 *  - 取消顺序反了会伪造"已完成"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentBotRepositoryTest {

    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository
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
            database = db,
            now = { clock },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun newTask(idempotency: String = "") = repo.createTask(
        botId = "bot-1",
        originSessionId = "agent-session-1",
        requestMessageId = "msg-1",
        goal = "把这份文本里的错别字改掉",
        acceptanceCriteria = "只改错字，不改语气",
        resourceScope = "workspace/notes.md",
        dedupeKey = idempotency,
    )

    @Test
    fun `create task emits an accepted event`() = runTest {
        val task = newTask()
        assertEquals(BotTaskStatus.QUEUED, task.status)
        val events = repo.pendingEvents("bot-1")
        assertEquals(1, events.size)
        assertEquals(BotEventKind.TASK_ACCEPTED, events.single().kind)
        assertEquals(task.taskId, events.single().taskId)
    }

    @Test
    fun `same idempotency key does not create a second task`() = runTest {
        val a = newTask("input:msg-1")
        val b = newTask("input:msg-1")
        assertEquals("同一次输入只能有一个任务", a.taskId, b.taskId)
        assertEquals(1, repo.listTasks("bot-1").size)
    }

    @Test
    fun `similar but distinct input still creates a new task`() = runTest {
        val a = newTask("input:msg-1")
        val b = newTask("input:msg-2")
        assertTrue("用户再提一次同样的事仍然是一个新任务", a.taskId != b.taskId)
        assertEquals(2, repo.listTasks("bot-1").size)
    }

    @Test
    fun `late failure event cannot overwrite a succeeded task`() = runTest {
        val task = newTask()
        repo.transition(task.taskId, BotTaskStatusMachine.Event.START)
        repo.transition(task.taskId, BotTaskStatusMachine.Event.EXECUTE_DONE)
        repo.transition(task.taskId, BotTaskStatusMachine.Event.REVIEW_ACCEPT)

        val late = repo.transition(task.taskId, BotTaskStatusMachine.Event.MARK_UNKNOWN)
        assertNull("终态必须拒绝迟到事件", late)
        assertEquals(BotTaskStatus.SUCCEEDED, repo.getTask(task.taskId)!!.status)
    }

    @Test
    fun `bumping revision keeps the old run stale`() = runTest {
        val task = newTask()
        val run = repo.openRun(task.taskId)!!
        assertEquals(1, run.taskRevision)

        val bumped = repo.bumpRevision(task.taskId)!!
        assertEquals(2, bumped.revision)
        assertEquals(
            "旧 run 的 taskRevision 不变，审核据此判 stale",
            1,
            repo.listRuns(task.taskId).single().taskRevision,
        )
    }

    @Test
    fun `approval request atomically records the step and pauses its task`() = runTest {
        val task = newTask()
        val run = repo.openRun(task.taskId)!!
        repo.transition(task.taskId, BotTaskStatusMachine.Event.START)
        val step = repo.recordStep(
            runId = run.runId,
            stepIndex = 0,
            toolName = "workspace_write",
            argumentsHash = "hash-1",
            baseHash = "",
            approvalId = "",
            sideEffect = StepSideEffect.RETRY_SAFE,
        )

        val approval = repo.requestApproval(
            taskId = task.taskId,
            runId = run.runId,
            taskRevision = run.taskRevision,
            stepId = step.stepId,
            toolCallId = "call-1",
            toolName = "workspace_write",
            arguments = "{\"path\":\"notes.md\"}",
            argumentsHash = "hash-1",
            baseHash = "base-1",
            sideEffect = StepSideEffect.RETRY_SAFE,
            risk = "NORMAL",
        )

        assertNotNull(approval)
        assertEquals(BotTaskStatus.WAITING_APPROVAL, repo.getTask(task.taskId)?.status)
        val recordedStep = repo.listSteps(run.runId).single()
        assertEquals(approval!!.approvalId, recordedStep.approvalId)
        assertEquals("hash-1", recordedStep.argumentsHash)
        assertEquals(approval.approvalId, repo.getApproval(approval.approvalId)?.approvalId)
        assertTrue(repo.pendingEvents("bot-1").any { it.kind == BotEventKind.APPROVAL_REQUIRED })
    }

    @Test
    fun `stale run cannot create a pending approval`() = runTest {
        val task = newTask()
        val run = repo.openRun(task.taskId)!!
        repo.transition(task.taskId, BotTaskStatusMachine.Event.START)
        val step = repo.recordStep(
            runId = run.runId,
            stepIndex = 0,
            toolName = "workspace_write",
            argumentsHash = "hash-1",
            baseHash = "",
            approvalId = "",
            sideEffect = StepSideEffect.RETRY_SAFE,
        )
        repo.bumpRevision(task.taskId)

        val approval = repo.requestApproval(
            taskId = task.taskId,
            runId = run.runId,
            taskRevision = run.taskRevision,
            stepId = step.stepId,
            toolCallId = "call-1",
            toolName = "workspace_write",
            arguments = "{}",
            argumentsHash = "hash-1",
            baseHash = "base-1",
            sideEffect = StepSideEffect.RETRY_SAFE,
            risk = "NORMAL",
        )

        assertNull(approval)
        assertEquals(BotTaskStatus.RUNNING, repo.getTask(task.taskId)?.status)
        assertEquals("原步骤保持未授权状态", "", repo.listSteps(run.runId).single().approvalId)
        assertTrue(repo.pendingApprovals().isEmpty())
        assertTrue(repo.pendingEvents("bot-1").none { it.kind == BotEventKind.APPROVAL_REQUIRED })
    }

    @Test
    fun `task dispatch rejects a stale input and preserves scope on current input`() = runTest {
        val first = repo.appendEvent(
            botId = "bot-1",
            originSessionId = "agent-session-1",
            taskId = "",
            runId = "",
            kind = BotEventKind.USER_INPUT_RECEIVED,
            dedupeKey = "input:first",
        )
        val firstIdentity = AgentTurnIdentity("bot-1", "agent-session-1", "message-first", first.sequence)
        val latest = repo.appendEvent(
            botId = "bot-1",
            originSessionId = "agent-session-1",
            taskId = "",
            runId = "",
            kind = BotEventKind.USER_INPUT_RECEIVED,
            dedupeKey = "input:latest",
        )

        assertNull(
            repo.createTaskForInput(
                identity = firstIdentity,
                goal = "旧目标",
                acceptanceCriteria = "旧条件",
                resourceScope = "old.md",
                dedupeKey = "task-dispatch:message-first",
            ),
        )
        val current = repo.createTaskForInput(
            TaskCreationInput(
                identity = AgentTurnIdentity("bot-1", "agent-session-1", "message-latest", latest.sequence),
                goal = "新目标",
                acceptanceCriteria = "新条件",
                resourceScope = "new.md",
                permissionScopeId = "permission-a",
            ),
        )

        assertNotNull(current)
        assertEquals("message-latest", current!!.requestMessageId)
        assertEquals("new.md", current.resourceScope)
        assertEquals("新条件", current.acceptanceCriteria)
        assertEquals("permission-a", current.permissionScopeId)
    }

    @Test
    fun `approved tool grant is consumed once only for the exact resumed step`() = runTest {
        val task = newTask()
        repo.transition(task.taskId, BotTaskStatusMachine.Event.START)
        val firstRun = repo.openRun(task.taskId)!!
        repo.attachRunThread(firstRun.runId, "thread-task-a")
        val firstStep = repo.recordStep(
            runId = firstRun.runId,
            stepIndex = 0,
            toolName = "workspace_write",
            argumentsHash = "hash-write",
            baseHash = "base-v1",
            approvalId = "",
            sideEffect = StepSideEffect.NO_BLIND_REPLAY,
        )
        val approval = repo.createApproval(
            botId = task.botId,
            originSessionId = task.originSessionId,
            taskId = task.taskId,
            runId = firstRun.runId,
            stepId = firstStep.stepId,
            taskRevision = firstRun.taskRevision,
            toolCallId = "call-write",
            toolName = "workspace_write",
            argumentsRef = "args",
            argumentsHash = "hash-write",
            resourceVersion = "base-v1",
            risk = "HIGH",
        )
        repo.transition(task.taskId, BotTaskStatusMachine.Event.WAIT_APPROVAL)
        repo.closeRun(firstRun.runId, TaskRunStatus.WAITING_APPROVAL)
        repo.settleApproval(approval.approvalId, ApprovalStatus.APPROVED, "user")
        repo.transition(task.taskId, BotTaskStatusMachine.Event.RESUME_FROM_APPROVAL)
        repo.transition(task.taskId, BotTaskStatusMachine.Event.USER_CONFIRMED_CONTINUE)
        repo.transition(task.taskId, BotTaskStatusMachine.Event.START)
        val resumedRun = repo.openRun(task.taskId)!!
        val resumedStep = repo.recordStep(
            runId = resumedRun.runId,
            stepIndex = 0,
            toolName = "workspace_write",
            argumentsHash = "hash-write",
            baseHash = "base-v1",
            approvalId = "",
            sideEffect = StepSideEffect.NO_BLIND_REPLAY,
        )

        assertEquals("续跑必须沿用原 Agent 线程", "thread-task-a", resumedRun.threadId)
        assertNull(
            repo.consumeApprovedApproval(
                taskId = task.taskId,
                runId = resumedRun.runId,
                taskRevision = resumedRun.taskRevision,
                stepId = resumedStep.stepId,
                toolName = "workspace_write",
                argumentsHash = "changed-hash",
                resourceVersion = "base-v1",
                sideEffect = StepSideEffect.NO_BLIND_REPLAY,
            ),
        )
        val consumed = repo.consumeApprovedApproval(
            taskId = task.taskId,
            runId = resumedRun.runId,
            taskRevision = resumedRun.taskRevision,
            stepId = resumedStep.stepId,
            toolName = "workspace_write",
            argumentsHash = "hash-write",
            resourceVersion = "base-v1",
            sideEffect = StepSideEffect.NO_BLIND_REPLAY,
        )

        assertEquals(approval.approvalId, consumed?.approvalId)
        assertEquals("consumed_for_resume", repo.getApproval(approval.approvalId)?.decisionReason)
        assertEquals(approval.approvalId, repo.listSteps(resumedRun.runId).single().approvalId)
        assertNull(
            "一次性批准不能重复授权",
            repo.consumeApprovedApproval(
                taskId = task.taskId,
                runId = resumedRun.runId,
                taskRevision = resumedRun.taskRevision,
                stepId = resumedStep.stepId,
                toolName = "workspace_write",
                argumentsHash = "hash-write",
                resourceVersion = "base-v1",
                sideEffect = StepSideEffect.NO_BLIND_REPLAY,
            ),
        )
    }

    @Test
    fun `approval settles once and second click is a no-op`() = runTest {
        val task = newTask()
        val run = repo.openRun(task.taskId)!!
        val approval = repo.createApproval(
            botId = "bot-1",
            originSessionId = "agent-session-1",
            taskId = task.taskId,
            runId = run.runId,
            stepId = "step-1",
            taskRevision = 1,
            toolCallId = "call-1",
            toolName = "workspace_write",
            argumentsRef = "artifact://args",
            argumentsHash = "hash-1",
            resourceVersion = "v1",
            risk = "NORMAL",
        )

        assertTrue(repo.settleApproval(approval.approvalId, ApprovalStatus.APPROVED, "user"))
        assertFalse(
            "第二次点击不能再结算一次",
            repo.settleApproval(approval.approvalId, ApprovalStatus.DENIED, "user"),
        )
        assertEquals(
            ApprovalStatus.APPROVED,
            repo.getApproval(approval.approvalId)!!.status,
        )
    }

    @Test
    fun `expired approval is not a denial`() = runTest {
        val task = newTask()
        val run = repo.openRun(task.taskId)!!
        repo.createApproval(
            botId = "bot-1",
            originSessionId = "agent-session-1",
            taskId = task.taskId,
            runId = run.runId,
            stepId = "step-1",
            taskRevision = 1,
            toolCallId = "call-1",
            toolName = "workspace_write",
            argumentsRef = "artifact://args",
            argumentsHash = "hash-1",
            resourceVersion = "v1",
            risk = "NORMAL",
            ttlMs = 100L,
        )

        clock = 10_000L
        assertEquals(1, repo.expireApprovals())
        val expired = repo.pendingApprovals()
        assertTrue("过期的审批不该还挂在待办里", expired.isEmpty())
        val all = repo.getApproval(db.approvalRequestDao().getAll().single().approvalId)!!
        assertEquals(ApprovalStatus.EXPIRED, all.status)
        assertEquals("system-expired", all.decisionActor)
    }

    @Test
    fun `publication supersede never rolls back a committed item`() = runTest {
        val publicationId = repo.enqueuePublication(
            botId = "bot-1",
            originSessionId = "agent-session-1",
            causeEventIds = "e1",
            requestRevision = 1L,
            taskRevision = 1,
            items = listOf(
                PublicationKind.TEXT to "first",
                PublicationKind.TEXT to "second",
            ),
        )

        assertTrue(repo.commitPublication("$publicationId:0", "m-0"))
        assertEquals(1, repo.supersedePublication(publicationId))

        val items = db.publicationOutboxDao().listByPublication(publicationId).associateBy { it.itemIndex }
        assertEquals(
            "已发出的条目不许回滚",
            PublicationStatus.COMMITTED,
            items[0]!!.status,
        )
        assertEquals(PublicationStatus.SUPERSEDED, items[1]!!.status)
    }

    @Test
    fun `unknown publication kind is rejected instead of silently stored`() = runTest {
        var threw = false
        try {
            repo.enqueuePublication(
                botId = "bot-1",
                originSessionId = "agent-session-1",
                causeEventIds = "",
                requestRevision = 0L,
                taskRevision = 1,
                items = listOf("MODEL_RAW_TEXT" to "should not pass"),
            )
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("模型正文不得进入公共发布通道", threw)
    }

    @Test
    fun `unknown event kind is rejected`() = runTest {
        var threw = false
        try {
            repo.appendEvent(
                botId = "bot-1",
                originSessionId = "s1",
                taskId = "t1",
                runId = "",
                kind = "SomethingElse",
                dedupeKey = "x",
            )
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `duplicate event returns the existing row`() = runTest {
        val a = repo.appendEvent("bot-1", "s1", "t1", "", BotEventKind.TASK_PROGRESSED, "k1")
        val b = repo.appendEvent("bot-1", "s1", "t1", "", BotEventKind.TASK_PROGRESSED, "k1")
        assertEquals(a.eventId, b.eventId)
        assertEquals(1, db.botEventDao().getAll().size)
    }

    @Test
    fun `consuming twice only advances once`() = runTest {
        val e = repo.appendEvent("bot-1", "s1", "t1", "", BotEventKind.TASK_PROGRESSED, "k1")
        assertTrue(repo.markConsumed(e.eventId))
        assertFalse(repo.markConsumed(e.eventId))
        assertNotNull("消费后不删行，否则无法对账", db.botEventDao().getById(e.eventId))
    }

    @Test
    fun `execution scope rejects missing origin session`() {
        val scope = ExecutionScope(
            botId = "bot-1",
            assistantId = "default",
            originAgentSessionId = "",
            taskId = "t1",
            runId = "r1",
        )
        val invalid = scope.validate()
        assertNotNull("缺 originAgentSessionId 必须拒绝执行", invalid)
        assertTrue(invalid!!.contains("originAgentSessionId"))
    }

    @Test
    fun `execution scope accepts a complete identity`() {
        val scope = ExecutionScope(
            botId = "bot-1",
            assistantId = "default",
            originAgentSessionId = "agent-session-1",
            taskId = "t1",
            runId = "r1",
        )
        assertNull(scope.validate())
    }

    @Test
    fun `stale lease is visible after restart`() = runTest {
        val task = newTask()
        val run = repo.openRun(task.taskId)!!
        clock = run.leaseUntil + 1
        assertEquals(listOf(run.runId), repo.staleRuns().map { it.runId })
    }

    @Test
    fun `renewing with a stale fence token is refused`() = runTest {
        val task = newTask()
        val run = repo.openRun(task.taskId)!!
        assertTrue(repo.renewLease(run.runId, run.fenceToken))
        assertFalse(
            "旧 worker 拿着过期令牌不能续租",
            repo.renewLease(run.runId, run.fenceToken),
        )
    }
}
