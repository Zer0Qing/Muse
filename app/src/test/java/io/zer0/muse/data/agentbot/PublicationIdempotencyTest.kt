package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionRepository
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
import java.io.File

/**
 * 发布的幂等性：同一个 publicationKey 只能对应一条对外消息。
 *
 * ## 为什么这条必须钉死
 *
 * 发布链路是"写消息 + 写发件箱 + 确认事件"三步。进程可能在任意两步之间被杀：
 *
 *  - 消息已写、事件未确认 → 重试若再写一条，用户会看到重复的两句话；
 *  - 发件箱已 COMMITTED、消息还没落 → 重试若新建 item，发件箱里会出现两条。
 *
 * 所以发布必须按**稳定 key** 认事实：已有就返回已有 messageId，没有才创建。
 *
 * ## 另一半：不是"有 key"就能发
 *
 * 幂等只解决重复，不解决"该不该发"。发布事实在队列里排队时，任务可能被取消、
 * 被追加约束、审核又判了返工 —— 消费前必须重新读库核对，否则一条过期事实
 * 会被当成审核的结论发出去。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PublicationIdempotencyTest {

    private lateinit var context: Context
    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository
    private lateinit var sessions: SessionRepository
    private lateinit var publication: PublicationService
    private lateinit var results: AgentBotResultPublisher
    private lateinit var inbox: BotInboxProcessor
    private var clock = 1_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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
        sessions = SessionRepository(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            database = db,
            context = context,
            messageImageStore = MessageImageStore(File(context.cacheDir, "muse_images")),
        )
        publication = PublicationService(db, repo, sessions)
        results = AgentBotResultPublisher(context, publication)
        inbox = BotInboxProcessor(repo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun scope(sessionId: String, taskId: String = "task-1", runId: String = "run-1"): PublicationService.PublicationScope {
        return PublicationService.PublicationScope(
            botId = "bot-1",
            originSessionId = sessionId,
            taskId = taskId,
            runId = runId,
            taskRevision = 1,
        )
    }

    private fun content(text: String = "「把 notes.md 的错字改掉」审核结论：已完成"): PublicationService.PublishedContent {
        return PublicationService.PublishedContent(text = text)
    }

    private suspend fun messages(sessionId: String) = db.messageDao().getRecentBySession(sessionId, 50)

    private suspend fun outbox() = db.publicationOutboxDao().getAll()

    /** 一个"审核通过"的任务 + 轮次。发布复核会真的去读这两张表。 */
    private suspend fun succeededTask(sessionId: String): Pair<BotTaskEntity, TaskRunEntity> {
        val task = repo.createTask(
            botId = "bot-1",
            originSessionId = sessionId,
            requestMessageId = "m1",
            goal = "把 notes.md 的错字改掉",
            acceptanceCriteria = "只改错字",
            resourceScope = "notes.md",
        )
        val run = repo.openRun(task.taskId) ?: error("新建的任务必然能开出一轮")
        repo.transition(task.taskId, BotTaskStatusMachine.Event.START) ?: error("QUEUED 必须能 START")
        repo.transition(task.taskId, BotTaskStatusMachine.Event.EXECUTE_DONE)
            ?: error("RUNNING 必须能 EXECUTE_DONE")
        val succeeded = repo.transition(task.taskId, BotTaskStatusMachine.Event.REVIEW_ACCEPT)
            ?: error("REVIEWING 必须能 REVIEW_ACCEPT")
        return succeeded to run
    }

    private fun allowedDecision(task: BotTaskEntity, run: TaskRunEntity): ReviewDecision = ReviewDecision(
        disposition = ReviewDisposition.ACCEPT,
        publicationAllowed = true,
        finalState = BotTaskStatus.SUCCEEDED,
        userVisibleSummary = "「把 notes.md 的错字改掉」审核结论：已完成\n产物：notes.md",
        candidateRefs = listOf("notes.md"),
        publicationKey = PublicationService.publicationKeyFor(task.taskId, run.runId, run.taskRevision),
        taskRevision = run.taskRevision,
    )

    private suspend fun appendReview(task: BotTaskEntity, run: TaskRunEntity, decision: ReviewDecision) {
        repo.appendEvent(
            botId = "bot-1",
            originSessionId = task.originSessionId,
            taskId = task.taskId,
            runId = run.runId,
            kind = BotEventKind.REVIEW_READY,
            dedupeKey = "review-ready:${task.taskId}:${run.runId}",
            payloadRef = decision.encode(),
        )
    }

    private suspend fun appendPublication(
        task: BotTaskEntity,
        run: TaskRunEntity,
        decision: ReviewDecision,
        sessionId: String = task.originSessionId,
    ): BotEventEntity = repo.appendEvent(
        botId = "bot-1",
        originSessionId = sessionId,
        taskId = task.taskId,
        runId = run.runId,
        kind = BotEventKind.TASK_PUBLICATION_READY,
        dedupeKey = "publication-ready:${task.taskId}:${run.runId}",
        payloadRef = decision.encode(),
    )

    @Test
    fun `same publication key returns the existing message id`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        val key = PublicationService.publicationKeyFor("task-1", "run-1", 1)
        val scope = scope(sessionId)
        val payload = content()

        val first = publication.publishIdempotent(key, scope, payload)
        val second = publication.publishIdempotent(key, scope, payload)

        assertEquals("第一次要真的写一条", 1, first.messageIds.size)
        assertTrue("第一次是新建", first.created)
        assertEquals("第二次必须返回已有 messageId", first.messageIds, second.messageIds)
        assertFalse("第二次不能算新建", second.created)

        assertEquals("同一 key 在发件箱里只能有一行", 1, outbox().size)
        assertEquals("同一 key 只能有一条消息", 1, messages(sessionId).size)
    }

    @Test
    fun `persisted newer input supersedes a foreground publication`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        val first = repo.appendEvent(
            botId = "bot-1",
            originSessionId = sessionId,
            taskId = "",
            runId = "",
            kind = BotEventKind.USER_INPUT_RECEIVED,
            dedupeKey = "input:first",
        )
        repo.appendEvent(
            botId = "bot-1",
            originSessionId = sessionId,
            taskId = "",
            runId = "",
            kind = BotEventKind.USER_INPUT_RECEIVED,
            dedupeKey = "input:second",
        )

        val result = publication.publish(
            botId = "bot-1",
            originSessionId = sessionId,
            causeEventIds = first.eventId,
            requestRevision = first.sequence,
            taskRevision = 1,
            items = listOf(PublicationService.Item(PublicationKind.TEXT, "旧回复")),
            currentRequestRevision = first.sequence,
        )

        assertEquals("新输入已落库时不能发布旧回复", 0, result.committed)
        assertEquals("过期回合不能创建发件箱记录", 0, outbox().size)
        assertTrue(messages(sessionId).isEmpty())
    }

    @Test
    fun `different keys publish separately`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        val first = publication.publishIdempotent(
            PublicationService.publicationKeyFor("task-1", "run-1", 1),
            scope(sessionId, runId = "run-1"),
            content("第一条"),
        )
        val second = publication.publishIdempotent(
            PublicationService.publicationKeyFor("task-1", "run-2", 1),
            scope(sessionId, runId = "run-2"),
            content("第二条"),
        )

        assertTrue(first.messageIds.single() != second.messageIds.single())
        assertEquals(2, messages(sessionId).size)
        assertEquals(2, outbox().size)
    }

    @Test
    fun `missing origin session is refused and writes nothing`() = runTest {
        val result = publication.publishIdempotent(
            PublicationService.publicationKeyFor("task-1", "run-1", 1),
            scope(sessionId = ""),
            content(),
        )

        assertEquals(0, result.messageIds.size)
        assertEquals(1, result.failed)
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun `recovery window between message write and event ack does not publish twice`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        val (task, run) = succeededTask(sessionId)
        val decision = allowedDecision(task, run)
        appendReview(task, run, decision)
        val event = appendPublication(task, run, decision)

        // 第一次：消息落库、发件箱标 COMMITTED，但事件**故意不确认** —— 模拟恢复窗口。
        assertTrue(results.publish(event, "bot-1", sessionId))
        assertEquals(0L, repo.pendingEvents("bot-1", 10).single { it.eventId == event.eventId }.consumedAt)

        // 恢复后重投同一条事件：必须复用已有 messageId，而不是再发一条。
        assertTrue(results.publish(event, "bot-1", sessionId))

        assertEquals("恢复窗口里重试不能产生第二条消息", 1, messages(sessionId).size)
        assertEquals("发件箱也不能多出第二条", 1, outbox().size)
    }

    @Test
    fun `publication fact without review fact is refused`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        val (task, run) = succeededTask(sessionId)
        // 只有发布事实、没有审核依据：拿什么证明"审核允许过"？
        val event = appendPublication(task, run, allowedDecision(task, run))

        results.publish(event, "bot-1", sessionId)
        assertTrue("没有审核事实不得产生消息", messages(sessionId).isEmpty())
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun `publication fact with a rework review is refused`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        val (task, run) = succeededTask(sessionId)
        val rework = allowedDecision(task, run).copy(
            disposition = ReviewDisposition.REWORK,
            publicationAllowed = false,
            finalState = BotTaskStatus.QUEUED,
        )
        appendReview(task, run, rework)
        // 伪造一条"允许发布"的事实：审核说返工，这句子 agent 的自述一个字都不能出去。
        val forged = appendPublication(task, run, allowedDecision(task, run))

        results.publish(forged, "bot-1", sessionId)
        assertTrue("审核结论是 REWORK 时不得发布", messages(sessionId).isEmpty())
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun `publication fact for another session scope is refused`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        val otherSessionId = sessions.createAgentSession("default")
        val (task, run) = succeededTask(sessionId)
        val decision = allowedDecision(task, run)
        appendReview(task, run, decision)
        // 事件指向别的会话：作用域不对就不发，否则结果会掉进不相关的对话。
        val event = appendPublication(task, run, decision, sessionId = otherSessionId)

        results.publish(event, "bot-1", otherSessionId)
        assertTrue("作用域不符时不得发布", messages(otherSessionId).isEmpty())
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun `publication fact whose run revision moved on is refused`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        val (task, run) = succeededTask(sessionId)
        // 事实声称的版本比这一轮实际版本新 —— 说明它不是这一轮审核的产物。
        val drifted = allowedDecision(task, run).copy(
            publicationKey = PublicationService.publicationKeyFor(task.taskId, run.runId, run.taskRevision + 1),
            taskRevision = run.taskRevision + 1,
        )
        appendReview(task, run, drifted)
        val event = appendPublication(task, run, drifted)

        results.publish(event, "bot-1", sessionId)
        assertTrue("轮次版本对不上时不得发布", messages(sessionId).isEmpty())
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun `publication is refused after task constraints advance`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        val (task, run) = succeededTask(sessionId)
        val decision = allowedDecision(task, run)
        appendReview(task, run, decision)
        appendPublication(task, run, decision)
        repo.bumpRevision(task.taskId)

        val result = publication.publishReviewed(
            decision = decision,
            scope = scope(sessionId, task.taskId, run.runId),
        )

        assertTrue("旧约束版本不能发布", result.messageIds.isEmpty())
        assertTrue("拒绝应说明任务版本已变化", result.refusedReason.contains("任务版本"))
        assertTrue("版本过期不能留下消息", messages(sessionId).isEmpty())
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun `refused fact is consumed instead of clogging the inbox`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        val (task, run) = succeededTask(sessionId)
        val event = appendPublication(task, run, allowedDecision(task, run))

        // 收件箱按优先级跑一轮：被拒绝的事实必须有归宿，不能永远占着消费配额。
        val turn = inbox.runTurn("bot-1") { pending -> results.publish(pending, "bot-1", sessionId) }
        assertTrue("这一轮确实处理了事件", turn.consumed >= 1)
        assertTrue(
            "被拒绝的发布事实不能留在队列里",
            repo.pendingEvents("bot-1", 50).none { it.eventId == event.eventId },
        )
        assertTrue(messages(sessionId).isEmpty())
    }

    @Test
    fun `publication key is stable for the same task run and revision`() {
        val a = PublicationService.publicationKeyFor("task-1", "run-1", 3)
        val b = PublicationService.publicationKeyFor("task-1", "run-1", 3)
        val c = PublicationService.publicationKeyFor("task-1", "run-2", 3)
        val d = PublicationService.publicationKeyFor("task-1", "run-1", 4)

        assertEquals("同一次审核必须得到同一个 key", a, b)
        assertTrue("换 run 必须是新 key", a != c)
        assertTrue("换 revision 必须是新 key", a != d)
    }
}
