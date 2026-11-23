package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
 * 页面投影的范围必须是「当前 assistant + canonical 会话」。
 *
 * 此前任务按 botId 查，审批与候选却是全局列表：页面会显示别的 Bot 的
 * 审批卡与候选，用户点下去改的是另一条任务。这里逐条钉住三个来源的过滤。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentBotProjectionScopeTest {

    private lateinit var graph: AgentBotFlowTestGraph

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        graph = AgentBotFlowTestGraph(context)
    }

    @After
    fun tearDown() {
        graph.close()
    }

    private suspend fun seed() {
        graph.db.botTaskDao().upsert(scopedTask("task-a", "assistant-a", "session-1", BotTaskStatus.RUNNING))
        graph.db.botTaskDao().upsert(scopedTask("task-b", "assistant-b", "session-2", BotTaskStatus.RUNNING))
        // 同一个 assistant 的另一个会话也不是当前页面的一部分。
        graph.db.botTaskDao().upsert(scopedTask("task-a2", "assistant-a", "session-2", BotTaskStatus.QUEUED))

        graph.db.approvalRequestDao().upsert(scopedApproval("appr-a", "assistant-a", "session-1", "task-a"))
        graph.db.approvalRequestDao().upsert(scopedApproval("appr-b", "assistant-b", "session-2", "task-b"))
        graph.db.approvalRequestDao().upsert(scopedApproval("appr-a2", "assistant-a", "session-2", "task-a2"))
    }

    @Test
    fun `scope only contains the requested assistant and canonical session`() = runTest {
        seed()

        val scope = graph.repository.observeScope("assistant-a", "session-1").first()

        assertEquals(listOf("task-a"), scope.tasks.map { it.taskId })
        assertEquals(listOf("appr-a"), scope.approvals.map { it.approvalId })
        assertEquals("assistant-a", scope.assistantId)
        assertEquals("session-1", scope.sessionId)
    }

    @Test
    fun `same assistant with another session is a different scope`() = runTest {
        seed()

        val otherSession = graph.repository.observeScope("assistant-a", "session-2").first()

        assertEquals(listOf("task-a2"), otherSession.tasks.map { it.taskId })
        assertEquals(listOf("appr-a2"), otherSession.approvals.map { it.approvalId })
    }

    @Test
    fun `pending approvals exclude settled rows`() = runTest {
        seed()
        graph.db.approvalRequestDao().upsert(
            scopedApproval("appr-settled", "assistant-a", "session-1", "task-a", ApprovalStatus.APPROVED),
        )

        val scope = graph.repository.observeScope("assistant-a", "session-1").first()

        assertEquals(listOf("appr-a"), scope.pendingApprovals.map { it.approvalId })
        assertTrue(
            "已结算审批仍要留在 approvals 里，NEEDS_USER 的来源判断需要它",
            scope.approvals.any { it.approvalId == "appr-settled" },
        )
    }

    @Test
    fun `candidate ids come only from events in the current scope`() = runTest {
        graph.repository.appendEvent(
            botId = "assistant-a",
            originSessionId = "session-1",
            taskId = "task-a",
            runId = "",
            kind = BotEventKind.TASK_RESULT_READY,
            dedupeKey = "result-a",
            payloadRef = "做完了\n[staged=cand-101]",
        )
        graph.repository.appendEvent(
            botId = "assistant-b",
            originSessionId = "session-2",
            taskId = "task-b",
            runId = "",
            kind = BotEventKind.TASK_RESULT_READY,
            dedupeKey = "result-b",
            payloadRef = "做完了\n[staged=cand-202]",
        )

        val scope = graph.repository.observeScope("assistant-a", "session-1").first()

        assertEquals(setOf("cand-101"), scope.candidateIds)
    }

    @Test
    fun `candidate marker inside the summary is not mistaken for the real id`() = runTest {
        graph.repository.appendEvent(
            botId = "assistant-a",
            originSessionId = "session-1",
            taskId = "task-a",
            runId = "",
            kind = BotEventKind.TASK_RESULT_READY,
            dedupeKey = "result-a",
            payloadRef = "摘要里提到 [staged=cand-999] 这个说法\n[staged=cand-303]",
        )

        val scope = graph.repository.observeScope("assistant-a", "session-1").first()

        assertEquals("只有末尾的标记是真实 id", setOf("cand-303"), scope.candidateIds)
    }

    @Test
    fun `one result event can project multiple staged candidates`() = runTest {
        graph.repository.appendEvent(
            botId = "assistant-a",
            originSessionId = "session-1",
            taskId = "task-a",
            runId = "run-a",
            kind = BotEventKind.TASK_RESULT_READY,
            dedupeKey = "result-multi",
            payloadRef = "两份产物\n[staged=cand-401,cand-402]",
        )

        val scope = graph.repository.observeScope("assistant-a", "session-1").first()

        assertEquals(setOf("cand-401", "cand-402"), scope.candidateIds)
    }

    @Test
    fun `legacy global queries still work for scheduler paths`() = runTest {
        seed()

        assertEquals("调度器按 botId 领任务，不按页面会话", 2, graph.repository.listTasks("assistant-a").size)
        assertEquals("全局待审批仍供巡检使用", 3, graph.repository.pendingApprovals().size)
    }
}
