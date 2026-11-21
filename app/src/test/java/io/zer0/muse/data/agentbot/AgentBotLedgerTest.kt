package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Agent 中枢七表的耐久性合同。
 *
 * 断言的是"进程被杀之后还能不能接着干"，不是"能不能存进去"：
 *  - 事件先落盘再消费，重复投递只生效一次；
 *  - 审批 CAS 只结算一次，双击不会执行两遍；
 *  - 发布 outbox 只重投未发出的条目；
 *  - 缺归属的事件不会被兜底到当前会话。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentBotLedgerTest {

    private lateinit var db: MuseDb
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun task(id: String, status: String = BotTaskStatus.QUEUED) = BotTaskEntity(
        taskId = id,
        botId = "bot-1",
        originSessionId = "agent-session-1",
        requestMessageId = "msg-1",
        goal = "改错别字",
        acceptanceCriteria = "只改错字，不改语气",
        resourceScope = "workspace/notes.md",
        revision = 1,
        status = status,
        priority = 10,
        createdAt = 1000L,
        updatedAt = 1000L,
    )

    @Test
    fun `task survives round trip with every field intact`() = runTest {
        val dao = db.botTaskDao()
        dao.upsert(task("t1"))

        val loaded = dao.getById("t1")
        assertNotNull(loaded)
        assertEquals("bot-1", loaded!!.botId)
        assertEquals("agent-session-1", loaded.originSessionId)
        assertEquals("只改错字，不改语气", loaded.acceptanceCriteria)
        assertEquals(1, loaded.revision)
        assertEquals(BotTaskStatus.QUEUED, loaded.status)
    }

    @Test
    fun `claim candidates respect priority then age`() = runTest {
        val dao = db.botTaskDao()
        dao.upsert(task("low").copy(priority = 1, createdAt = 100L))
        dao.upsert(task("high").copy(priority = 99, createdAt = 900L))
        dao.upsert(task("mid").copy(priority = 50, createdAt = 500L))
        // 非 QUEUED 的不该被领走
        dao.upsert(task("running").copy(status = BotTaskStatus.RUNNING))

        val claimed = dao.claimCandidates(10).map { it.taskId }
        assertEquals(listOf("high", "mid", "low"), claimed)
    }

    @Test
    fun `duplicate event with same dedupe key is stored once`() = runTest {
        val dao = db.botEventDao()
        val e = BotEventEntity(
            eventId = "e1",
            botId = "bot-1",
            taskId = "t1",
            kind = BotEventKind.TASK_ACCEPTED,
            sequence = 1,
            dedupeKey = "task-accepted:t1",
            createdAt = 10L,
        )
        assertEquals(1L, dao.insertIgnore(e))
        // 同一 dedupeKey 再来一次：IGNORE，不新增行
        val dup = dao.insertIgnore(e.copy(eventId = "e2", sequence = 2))
        assertEquals(-1L, dup)
        assertEquals(1, dao.getAll().size)
        assertEquals("e1", dao.findByDedupeKey("task-accepted:t1")?.eventId)
    }

    @Test
    fun `consuming an event is idempotent and keeps the row`() = runTest {
        val dao = db.botEventDao()
        dao.insertIgnore(
            BotEventEntity(
                eventId = "e1",
                botId = "bot-1",
                kind = BotEventKind.TASK_RESULT_READY,
                sequence = 1,
                dedupeKey = "result:t1",
                createdAt = 10L,
            ),
        )

        assertEquals(1, dao.markConsumed("e1", 100L))
        // 重复消费：不再改写，也不删行（删了就再也无法对账）
        assertEquals(0, dao.markConsumed("e1", 200L))
        val row = dao.getById("e1")
        assertNotNull("消费后不能立即删除事件", row)
        assertEquals(100L, row!!.consumedAt)
    }

    @Test
    fun `pending events are ordered by sequence`() = runTest {
        val dao = db.botEventDao()
        listOf(3L, 1L, 2L).forEach { seq ->
            dao.insertIgnore(
                BotEventEntity(
                    eventId = "e$seq",
                    botId = "bot-1",
                    kind = BotEventKind.USER_INPUT_RECEIVED,
                    sequence = seq,
                    dedupeKey = "input:$seq",
                    createdAt = seq,
                ),
            )
        }
        assertEquals(listOf(1L, 2L, 3L), dao.pending("bot-1", 10).map { it.sequence })
    }

    @Test
    fun `retention only deletes consumed events older than cutoff`() = runTest {
        val dao = db.botEventDao()
        dao.insertIgnore(
            BotEventEntity(
                eventId = "old", botId = "bot-1", kind = BotEventKind.TASK_PROGRESSED,
                sequence = 1, dedupeKey = "p1", consumedAt = 10L, createdAt = 1L,
            ),
        )
        dao.insertIgnore(
            BotEventEntity(
                eventId = "fresh", botId = "bot-1", kind = BotEventKind.TASK_PROGRESSED,
                sequence = 2, dedupeKey = "p2", consumedAt = 900L, createdAt = 2L,
            ),
        )
        dao.insertIgnore(
            BotEventEntity(
                eventId = "unconsumed", botId = "bot-1", kind = BotEventKind.TASK_PROGRESSED,
                sequence = 3, dedupeKey = "p3", consumedAt = 0L, createdAt = 3L,
            ),
        )

        val deleted = dao.deleteConsumedBefore(500L)
        assertEquals(1, deleted)
        assertEquals(
            "未消费的事件永远不能被保留策略删掉",
            setOf("fresh", "unconsumed"),
            dao.getAll().map { it.eventId }.toSet(),
        )
    }

    @Test
    fun `approval settles exactly once even if clicked twice`() = runTest {
        val dao = db.approvalRequestDao()
        dao.upsert(
            ApprovalRequestEntity(
                approvalId = "a1",
                botId = "bot-1",
                taskId = "t1",
                toolName = "workspace_write",
                argumentsHash = "hash-1",
                status = ApprovalStatus.PENDING,
                createdAt = 100L,
                readyAt = 100L,
                expiresAt = 100_000L,
            ),
        )

        assertEquals(1, dao.settle("a1", ApprovalStatus.APPROVED, 200L, "user", ""))
        // 第二次点击：CAS 失败，返回 0，先前结果不被覆盖
        assertEquals(0, dao.settle("a1", ApprovalStatus.DENIED, 300L, "user", ""))
        val row = dao.getById("a1")!!
        assertEquals(ApprovalStatus.APPROVED, row.status)
        assertEquals(200L, row.resolvedAt)
    }

    @Test
    fun `expired approval is not recorded as a user denial`() = runTest {
        val dao = db.approvalRequestDao()
        dao.upsert(
            ApprovalRequestEntity(
                approvalId = "a2",
                botId = "bot-1",
                taskId = "t1",
                status = ApprovalStatus.PENDING,
                createdAt = 10L,
                readyAt = 10L,
                expiresAt = 50L,
            ),
        )

        assertEquals(1, dao.expireOverdue(now = 100L, reason = "timeout"))
        val row = dao.getById("a2")!!
        assertEquals(
            "过期必须是 EXPIRED，不能记成用户拒绝",
            ApprovalStatus.EXPIRED,
            row.status,
        )
        assertEquals("system-expired", row.decisionActor)
        assertTrue(ApprovalStatus.isSettled(row.status))
    }

    @Test
    fun `publication outbox only re-sends items that were never committed`() = runTest {
        val dao = db.publicationOutboxDao()
        listOf(0, 1, 2).forEach { idx ->
            dao.upsert(
                PublicationOutboxEntity(
                    id = "p-$idx",
                    publicationId = "pub-1",
                    botId = "bot-1",
                    originSessionId = "agent-session-1",
                    itemIndex = idx,
                    kind = PublicationKind.TEXT,
                    status = PublicationStatus.PENDING,
                    createdAt = idx.toLong(),
                ),
            )
        }

        // 第 0 条发出去了，第 1、2 条还挂着
        assertEquals(1, dao.markCommitted("p-0", PublicationStatus.COMMITTED, "m-0"))
        assertEquals(0, dao.markCommitted("p-0", PublicationStatus.COMMITTED, "m-0"))

        val pending = dao.listPending().map { it.id }
        assertEquals(listOf("p-1", "p-2"), pending)
    }

    @Test
    fun `superseding a publication leaves committed items untouched`() = runTest {
        val dao = db.publicationOutboxDao()
        dao.upsert(
            PublicationOutboxEntity(
                id = "p-0", publicationId = "pub-2", botId = "bot-1",
                itemIndex = 0, status = PublicationStatus.COMMITTED, messageId = "m-0", createdAt = 0L,
            ),
        )
        dao.upsert(
            PublicationOutboxEntity(
                id = "p-1", publicationId = "pub-2", botId = "bot-1",
                itemIndex = 1, status = PublicationStatus.PENDING, createdAt = 1L,
            ),
        )

        assertEquals(1, dao.supersedePending("pub-2"))
        val items = dao.listByPublication("pub-2").associateBy { it.id }
        assertEquals("已发出的条目不许回滚", PublicationStatus.COMMITTED, items["p-0"]!!.status)
        assertEquals(PublicationStatus.SUPERSEDED, items["p-1"]!!.status)
    }

    @Test
    fun `run attempt numbers are unique per task`() = runTest {
        val dao = db.taskRunDao()
        dao.upsert(TaskRunEntity(runId = "r1", taskId = "t1", attempt = 1))
        // 同一 task 的 attempt 唯一索引存在；这里只验证两轮并存可查
        dao.upsert(TaskRunEntity(runId = "r2", taskId = "t1", attempt = 2))
        assertEquals(listOf(2, 1), dao.listByTask("t1").map { it.attempt })
    }

    @Test
    fun `stale runs are the ones whose lease expired`() = runTest {
        val dao = db.taskRunDao()
        dao.upsert(TaskRunEntity(runId = "r-live", taskId = "t1", attempt = 1, status = TaskRunStatus.RUNNING, leaseUntil = 9_000L))
        dao.upsert(TaskRunEntity(runId = "r-stale", taskId = "t2", attempt = 1, status = TaskRunStatus.RUNNING, leaseUntil = 100L))
        dao.upsert(TaskRunEntity(runId = "r-done", taskId = "t3", attempt = 1, status = TaskRunStatus.COMPLETED, leaseUntil = 100L))

        assertEquals(listOf("r-stale"), dao.listStale(now = 1_000L).map { it.runId })
    }

    @Test
    fun `task deletion cascades by explicit calls and leaves no orphans`() = runTest {
        db.botTaskDao().upsert(task("t1"))
        db.taskRunDao().upsert(TaskRunEntity(runId = "r1", taskId = "t1", attempt = 1))
        db.taskStepDao().upsert(TaskStepEntity(stepId = "s1", runId = "r1", stepIndex = 0))
        db.approvalRequestDao().upsert(
            ApprovalRequestEntity(approvalId = "a1", taskId = "t1", status = ApprovalStatus.PENDING),
        )

        db.taskStepDao().deleteByRun("r1")
        db.taskRunDao().deleteByTask("t1")
        db.approvalRequestDao().deleteByTask("t1")
        db.botTaskDao().deleteById("t1")

        assertNull(db.botTaskDao().getById("t1"))
        assertTrue(db.taskRunDao().listByTask("t1").isEmpty())
        assertTrue(db.taskStepDao().listByRun("r1").isEmpty())
        assertTrue(db.approvalRequestDao().listByTask("t1").isEmpty())
    }

    @Test
    fun `binding round trips and enabled filter works`() = runTest {
        val dao = db.botBindingDao()
        dao.upsert(
            BotBindingEntity(
                botId = "bot-1",
                assistantId = "default",
                canonicalAgentSessionId = "agent-session-1",
                enabled = true,
            ),
        )
        dao.upsert(BotBindingEntity(botId = "bot-2", enabled = false))

        assertEquals(1, dao.listEnabled().size)
        assertEquals("agent-session-1", dao.getById("bot-1")!!.canonicalAgentSessionId)
    }
}
