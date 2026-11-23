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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 审核门：执行完成不等于可以对外说"做完了"。
 *
 * ## 这里钉住的三件事
 *
 * 1. REWORK / PARTIAL / UNKNOWN 不得产生任何对外消息 —— 哪怕子 Agent 自述写得像已经完成；
 * 2. 只有审核明确允许（ACCEPT 且产物真的落地）才产生可发布事实，而且只发一次；
 * 3. 发布正文来自审核结论，子 Agent 的 summary 不能直接当发布文本。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReviewBeforePublicationTest {

    private lateinit var context: Context
    private lateinit var db: MuseDb
    private lateinit var repo: AgentBotRepository
    private lateinit var sessions: SessionRepository
    private lateinit var dir: File
    private lateinit var staging: StagedArtifactStore
    private lateinit var applier: BotResultApplier
    private lateinit var publication: PublicationService
    private lateinit var results: AgentBotResultPublisher
    private lateinit var inbox: BotInboxProcessor
    private var clock = 1_000L

    /** 目标资源的当前内容。审核与应用都读它。 */
    private val targets = mutableMapOf<String, String>()

    /**
     * 子 Agent 的自述。
     *
     * 它写得像"已经全部完成"，但审核没通过时**一个字都不能进公共消息**。
     */
    private val rawClaim = "我已经亲自打开并改好了所有错字，可以交付了"

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
            database = db,
            now = { clock },
        )
        sessions = SessionRepository(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            database = db,
            context = context,
            messageImageStore = MessageImageStore(File(context.cacheDir, "muse_images")),
        )
        dir = File(System.getProperty("java.io.tmpdir"), "review-gate-" + System.nanoTime())
        staging = StagedArtifactStore(dir)
        applier = BotResultApplier(
            repository = repo,
            staging = staging,
            reviewer = BotReviewWorker(),
            readTarget = { ref -> targets[ref] },
            writeTarget = { ref, content -> targets[ref] = content },
        )
        publication = PublicationService(db, repo, sessions)
        results = AgentBotResultPublisher(context, publication)
        inbox = BotInboxProcessor(repo)
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private suspend fun taskAndRun(
        sessionId: String,
        resourceScope: String = "notes.md",
    ): Pair<BotTaskEntity, TaskRunEntity> {
        val task = repo.createTask(
            botId = "bot-1",
            originSessionId = sessionId,
            requestMessageId = "m1",
            goal = "把 notes.md 的错字改掉",
            acceptanceCriteria = "只改错字",
            resourceScope = resourceScope,
        )
        val run = repo.openRun(task.taskId)!!
        repo.transition(task.taskId, BotTaskStatusMachine.Event.START)
        repo.transition(task.taskId, BotTaskStatusMachine.Event.EXECUTE_DONE)
        repo.closeRun(run.runId, TaskRunStatus.COMPLETED)
        return task to run
    }

    private fun payloadWithClaim(stagedId: String): String = rawClaim + "\n" + BotResultApplier.STAGED_MARKER + stagedId + "]"

    /**
     * 审核事实按 eventId 全量查，而不是只查未消费队列。
     *
     * REVIEW_READY 会被正常消费掉 —— 那正是它该有的归宿。断言"事实存在"
     * 不能用"它还在队列里"当证据。
     */
    private suspend fun reviewFact(taskId: String): ReviewDecision? {
        val raw = db.botEventDao().getAll()
            .filter { it.kind == BotEventKind.REVIEW_READY && it.taskId == taskId }
            .maxByOrNull { it.sequence }
            ?.payloadRef
            ?: return null
        return ReviewDecision.decode(raw)
    }

    private suspend fun publishableFacts(taskId: String): List<BotEventEntity> {
        return db.botEventDao().getAll()
            .filter { it.kind == BotEventKind.TASK_PUBLICATION_READY && it.taskId == taskId }
            .sortedBy { it.sequence }
    }

    private suspend fun messages(sessionId: String) = db.messageDao().getRecentBySession(sessionId, 50)

    private suspend fun outbox() = db.publicationOutboxDao().getAll()

    /** 像生产一样跑一轮收件箱：页面与后台共用的那条消费路径。 */
    private suspend fun drainInbox(sessionId: String) {
        inbox.runTurn("bot-1") { event -> results.publish(event, "bot-1", sessionId) }
    }

    @Test
    fun `write scoped result without artifact waits for the user`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        val (task, run) = taskAndRun(sessionId)

        // 任务声明了文件范围却没有结构化候选，执行摘要不能冒充文件产物。
        val outcome = applier.process(
            task = task,
            run = run,
            payloadRef = rawClaim,
            expectedBaseHash = "",
            criteria = "只改错字",
        )
        assertEquals(ReviewDisposition.NEEDS_USER, outcome.disposition)

        assertTrue("REWORK 不产生可发布事实", publishableFacts(task.taskId).isEmpty())
        drainInbox(sessionId)

        assertTrue("REWORK 不得产生任何对外消息", messages(sessionId).isEmpty())
        assertTrue("REWORK 不得写发件箱", outbox().isEmpty())

        val fact = reviewFact(task.taskId)
        assertNotNull("审核结论要落成事实，否则没人知道为什么没发布", fact)
        assertFalse(fact!!.publicationAllowed)
        assertEquals(BotTaskStatus.NEEDS_USER, fact.finalState)
        assertEquals(BotTaskStatus.NEEDS_USER, repo.getTask(task.taskId)?.status)
    }

    @Test
    fun `partial result stays internal until explicitly allowed`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        targets["notes.md"] = "原内容"
        val (task, run) = taskAndRun(sessionId)
        // 发过通知：已发生且无法回滚 → 只能判 PARTIAL。
        repo.recordStep(
            runId = run.runId,
            stepIndex = 0,
            toolName = "send_notification",
            argumentsHash = "h",
            baseHash = "",
            approvalId = "",
            sideEffect = StepSideEffect.NO_BLIND_REPLAY,
        )
        val baseHash = StagedArtifactStore.hashOf("原内容")
        val stagedId = staging.stage(
            StagedArtifactStore.Candidate("notes.md", "改好的内容", baseHash, run.taskRevision),
        )

        val outcome = applier.process(
            task = task,
            run = run,
            payloadRef = payloadWithClaim(stagedId),
            expectedBaseHash = baseHash,
            criteria = "只改错字",
        )
        assertEquals(ReviewDisposition.PARTIAL, outcome.disposition)

        assertTrue("PARTIAL 不产生可发布事实", publishableFacts(task.taskId).isEmpty())
        drainInbox(sessionId)
        assertTrue("PARTIAL 不得产生对外消息", messages(sessionId).isEmpty())
        assertTrue(outbox().isEmpty())

        val fact = reviewFact(task.taskId)
        assertNotNull(fact)
        assertFalse(fact!!.publicationAllowed)
        assertEquals(BotTaskStatus.PARTIAL, fact.finalState)
    }

    @Test
    fun `unknown result is never published`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        targets["notes.md"] = "原内容"
        val (task, run) = taskAndRun(sessionId)
        // 基线漂移：目标在任务跑的过程中被改过 → UNKNOWN。
        val staleHash = StagedArtifactStore.hashOf("任务开始时的内容")
        val stagedId = staging.stage(
            StagedArtifactStore.Candidate("notes.md", "改好的内容", staleHash, run.taskRevision),
        )

        val outcome = applier.process(
            task = task,
            run = run,
            payloadRef = payloadWithClaim(stagedId),
            expectedBaseHash = staleHash,
            criteria = "只改错字",
        )
        assertEquals(ReviewDisposition.UNKNOWN, outcome.disposition)

        assertTrue(publishableFacts(task.taskId).isEmpty())
        drainInbox(sessionId)
        assertTrue("UNKNOWN 不得产生对外消息", messages(sessionId).isEmpty())
        assertTrue(outbox().isEmpty())
        assertEquals(
            "结果无法确认时任务也必须停在 UNKNOWN",
            BotTaskStatus.UNKNOWN,
            repo.getTask(task.taskId)?.status,
        )

        val fact = reviewFact(task.taskId)
        assertNotNull(fact)
        assertFalse(fact!!.publicationAllowed)
    }

    @Test
    fun `stale candidate cannot mark task succeeded`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        targets["notes.md"] = "原内容"
        val (task, run) = taskAndRun(sessionId)
        val baseHash = StagedArtifactStore.hashOf("原内容")
        val stagedId = staging.stage(
            StagedArtifactStore.Candidate("notes.md", "改好的内容", baseHash, run.taskRevision),
        )
        repo.bumpRevision(task.taskId)

        val outcome = applier.process(
            task = task,
            run = run,
            payloadRef = payloadWithClaim(stagedId),
            expectedBaseHash = baseHash,
            criteria = "只改错字",
        )

        assertEquals("过期候选不能作为完成", ReviewDisposition.UNKNOWN, outcome.disposition)
        assertFalse("过期候选没有应用", outcome.applied)
        assertEquals(BotTaskStatus.UNKNOWN, repo.getTask(task.taskId)?.status)
        assertTrue(publishableFacts(task.taskId).isEmpty())
        val fact = reviewFact(task.taskId)
        assertNotNull(fact)
        assertEquals(ReviewDisposition.UNKNOWN, fact!!.disposition)
        assertEquals(BotTaskStatus.UNKNOWN, fact.finalState)
        assertFalse(fact.publicationAllowed)
        assertTrue(messages(sessionId).isEmpty())
    }

    @Test
    fun `read only task publishes its summary without creating a file candidate`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        val (task, run) = taskAndRun(sessionId, resourceScope = "")

        val outcome = applier.process(
            task = task,
            run = run,
            payloadRef = rawClaim,
            expectedBaseHash = "",
            criteria = "",
        )

        assertTrue(outcome.accepted)
        assertFalse(outcome.applied)
        assertEquals(BotTaskStatus.SUCCEEDED, repo.getTask(task.taskId)?.status)
        assertTrue("摘要不是文件候选", staging.listStaged().isEmpty())
        val publicationEvent = publishableFacts(task.taskId).single()
        assertTrue(results.publish(publicationEvent, "bot-1", sessionId))
        val published = messages(sessionId).single().content
        assertTrue(published.contains("只读任务摘要（未独立验证）"))
        assertTrue(published.contains(rawClaim))
    }

    @Test
    fun `write task without a structured artifact waits for the user`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        val (task, run) = taskAndRun(sessionId)

        val outcome = applier.process(
            task = task,
            run = run,
            payloadRef = rawClaim,
            expectedBaseHash = "",
            criteria = "",
        )

        assertEquals(ReviewDisposition.NEEDS_USER, outcome.disposition)
        assertEquals(BotTaskStatus.NEEDS_USER, repo.getTask(task.taskId)?.status)
        assertTrue(publishableFacts(task.taskId).isEmpty())
        assertTrue(messages(sessionId).isEmpty())
    }

    @Test
    fun `failure event retry publishes one stable notice`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        val failed = repo.appendEvent(
            botId = "bot-1",
            originSessionId = sessionId,
            taskId = "task-failed",
            runId = "run-failed",
            kind = BotEventKind.TASK_FAILED,
            dedupeKey = "task-failed:task-failed:run-failed",
        )

        assertTrue(results.publish(failed, "bot-1", sessionId))
        assertTrue(results.publish(failed, "bot-1", sessionId))

        assertEquals("重试失败事件不能重复发提示", 1, messages(sessionId).size)
        assertEquals("消息与发件箱保持一条", 1, outbox().size)
    }

    @Test
    fun `accepted result publishes exactly once with the review summary`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        targets["notes.md"] = "原内容"
        val (task, run) = taskAndRun(sessionId)
        val baseHash = StagedArtifactStore.hashOf("原内容")
        val stagedId = staging.stage(
            StagedArtifactStore.Candidate("notes.md", "改好的内容", baseHash, run.taskRevision),
        )

        val outcome = applier.process(
            task = task,
            run = run,
            payloadRef = payloadWithClaim(stagedId),
            expectedBaseHash = baseHash,
            criteria = "只改错字",
        )
        assertTrue("审核通过且真的写了目标", outcome.applied)
        assertEquals(ReviewDisposition.ACCEPT, outcome.disposition)

        val fact = reviewFact(task.taskId)
        assertNotNull(fact)
        assertTrue("审核明确允许发布", fact!!.publicationAllowed)
        assertEquals(BotTaskStatus.SUCCEEDED, fact.finalState)
        assertEquals(BotTaskStatus.SUCCEEDED, repo.getTask(task.taskId)?.status)

        val event = publishableFacts(task.taskId).single()
        // 重复投递（事件未被确认前重试）不能产生第二条消息。
        assertTrue(results.publish(event, "bot-1", sessionId))
        assertTrue(results.publish(event, "bot-1", sessionId))
        drainInbox(sessionId)

        val published = messages(sessionId)
        assertEquals("审核允许后只能有一条对外消息", 1, published.size)
        assertEquals("发件箱也只能有一条", 1, outbox().size)

        val text = published.single().content
        assertTrue("发布正文必须来自审核摘要", text.contains("审核结论"))
        assertTrue("要写清产物落到哪", text.contains("notes.md"))
        assertFalse(
            "执行 summary 不得直接成为发布文本",
            text.contains(rawClaim),
        )
    }

    @Test
    fun `raw execution result alone is not a publication trigger`() = runTest {
        val sessionId = sessions.createAgentSession("default")
        val event = repo.appendEvent(
            botId = "bot-1",
            originSessionId = sessionId,
            taskId = "task-1",
            runId = "run-1",
            kind = BotEventKind.TASK_RESULT_READY,
            dedupeKey = "task-result:task-1:run-1",
            payloadRef = rawClaim,
        )

        // 执行结果只是审核入口：没有审核允许时不得出现在对话里。
        results.publish(event, "bot-1", sessionId)
        assertTrue("执行完成本身不能公开", messages(sessionId).isEmpty())
        assertTrue(outbox().isEmpty())
        assertNull(reviewFact("task-1"))
    }
}
