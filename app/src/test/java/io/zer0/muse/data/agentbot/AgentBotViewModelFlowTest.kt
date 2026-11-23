package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.ui.agentbot.AgentBotViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * 页面随 Room Flow 持续观察，而不是在 bind 时读死一份。
 *
 * 四态里最容易做错的是 Error：把一次查询失败降级成空列表，用户会看到
 * "刚才还在的任务突然没了"，而且没有任何可重试的入口。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentBotViewModelFlowTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var graph: AgentBotFlowTestGraph
    private val viewModels = mutableListOf<AgentBotViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context: Context = ApplicationProvider.getApplicationContext()
        graph = AgentBotFlowTestGraph(context)
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        graph.close()
        Dispatchers.resetMain()
    }

    private fun vm(restorer: TranscriptRestorer? = null): AgentBotViewModel {
        val created = if (restorer == null) graph.viewModel() else graph.viewModel(restorer)
        viewModels += created
        return created
    }

    @Test
    fun `projection buckets the scoped rows and carries the canonical transcript`() = runTest(dispatcher) {
        val sessionId = graph.sessions.createAgentSession("assistant-a")
        graph.sessions.appendMessage(
            sessionId,
            UIMessage(role = MessageRole.USER, content = "帮我看下这份稿子", createdAt = 10L),
        )
        graph.db.botTaskDao().upsert(scopedTask("task-run", "assistant-a", sessionId, BotTaskStatus.RUNNING))
        graph.db.botTaskDao().upsert(scopedTask("task-needs", "assistant-a", sessionId, BotTaskStatus.NEEDS_USER))
        graph.db.botTaskDao().upsert(scopedTask("task-done", "assistant-a", sessionId, BotTaskStatus.SUCCEEDED))
        graph.db.approvalRequestDao().upsert(scopedApproval("appr-1", "assistant-a", sessionId, "task-run"))
        val stagedId = graph.staging.stage(stagedCandidate("notes.md"))
        graph.repository.appendEvent(
            botId = "assistant-a",
            originSessionId = sessionId,
            taskId = "task-run",
            runId = "",
            kind = BotEventKind.TASK_RESULT_READY,
            dedupeKey = "result-1",
            payloadRef = "做完了\n[staged=$stagedId]",
        )

        val viewModel = vm()
        viewModel.refreshCandidates()
        val projection = viewModel.observeAgentScope("assistant-a", sessionId).first()

        assertEquals("assistant-a", projection.assistantId)
        assertEquals(sessionId, projection.sessionId)
        assertEquals(listOf("task-run"), projection.inProgress.map { it.taskId })
        assertEquals(listOf("task-needs"), projection.needsAction.map { it.taskId })
        assertEquals(listOf("task-done"), projection.recent.map { it.taskId })
        assertEquals(listOf("appr-1"), projection.pendingApprovals.map { it.approvalId })
        assertEquals(listOf(stagedId), projection.candidates.map { it.stagedId })
        assertEquals(listOf("帮我看下这份稿子"), projection.transcript.map { it.text })
    }

    @Test
    fun `a consumed approval no longer offers continue`() = runTest(dispatcher) {
        val sessionId = graph.sessions.createAgentSession("assistant-a")
        graph.db.botTaskDao().upsert(scopedTask("task-needs", "assistant-a", sessionId, BotTaskStatus.NEEDS_USER))
        graph.db.approvalRequestDao().upsert(
            scopedApproval("appr-used", "assistant-a", sessionId, "task-needs", ApprovalStatus.APPROVED)
                .copy(decisionReason = "consumed_for_resume"),
        )

        val viewModel = vm()
        viewModel.bind(botId = "assistant-a", originSessionId = sessionId)
        viewModel.awaitScopeSettled()

        val task = viewModel.taskBoard.value.tasks.single()
        assertTrue(task.actions.contains(AgentBotUiState.TaskAction.CANCEL))
        assertFalse("一次性批准已消费后不能继续重复执行", task.actions.contains(AgentBotUiState.TaskAction.CONTINUE))
        assertEquals(AgentBotPrompt.NEEDS_DECISION, task.needsUser)
    }

    @Test
    fun `older transcript page is prepended and the current tail remains visible`() = runTest(dispatcher) {
        val sessionId = graph.sessions.createAgentSession("assistant-a")
        repeat(75) { index ->
            graph.sessions.appendMessage(
                sessionId,
                UIMessage(
                    role = MessageRole.USER,
                    content = "message-$index",
                    createdAt = 1_000L + index,
                ),
            )
        }

        val viewModel = vm()
        viewModel.bind(botId = "assistant-a", originSessionId = sessionId)
        viewModel.awaitScopeSettled()
        viewModel.hasOlderTranscript.first { it }
        assertEquals("message-25", viewModel.transcript.value.first().text)

        viewModel.loadOlderTranscript()
        val messages = viewModel.transcript.first { it.firstOrNull()?.text == "message-0" }

        assertEquals(75, messages.size)
        assertEquals("message-0", messages.first().text)
        assertEquals("message-74", messages.last().text)
        assertFalse(viewModel.hasOlderTranscript.value)
    }

    @Test
    fun `bind drives content state and keeps the legacy flows in sync`() = runTest(dispatcher) {
        val sessionId = graph.sessions.createAgentSession("assistant-a")
        graph.sessions.appendMessage(
            sessionId,
            UIMessage(role = MessageRole.ASSISTANT, content = "收到，开始改", createdAt = 20L),
        )
        graph.db.botTaskDao().upsert(scopedTask("task-run", "assistant-a", sessionId, BotTaskStatus.RUNNING))

        val viewModel = vm()
        viewModel.bind(botId = "assistant-a", originSessionId = sessionId)

        val state = viewModel.awaitScopeSettled()
        assertTrue("有任务与消息时必须是 Content，实际 $state", state is AgentBotScopeState.Content)
        assertEquals(listOf("task-run"), viewModel.taskBoard.value.tasks.map { it.taskId })
        assertEquals(listOf("收到，开始改"), viewModel.transcript.value.map { it.text })
        assertEquals(sessionId, viewModel.boundSessionId.value)
    }

    @Test
    fun `an empty scope is Empty not a stale board`() = runTest(dispatcher) {
        val sessionId = graph.sessions.createAgentSession("assistant-a")

        val viewModel = vm()
        viewModel.bind(botId = "assistant-a", originSessionId = sessionId)

        assertEquals(AgentBotScopeState.Empty, viewModel.awaitScopeSettled())
        assertTrue(viewModel.taskBoard.value.tasks.isEmpty())
        assertTrue(viewModel.transcript.value.isEmpty())
    }

    @Test
    fun `read failure becomes Error and retry advances the token`() = runTest(dispatcher) {
        val sessionId = graph.sessions.createAgentSession("assistant-a")

        // 没有会话仓储时 transcript 无法观察：这是一次真实的读取失败。
        val viewModel = vm(TranscriptRestorer(null))
        viewModel.bind(botId = "assistant-a", originSessionId = sessionId)

        val first = viewModel.awaitScopeSettled()
        assertTrue("读取失败必须是 Error，不能伪装成空列表，实际 $first", first is AgentBotScopeState.Error)
        first as AgentBotScopeState.Error
        assertTrue("错误必须带可显示的原因", first.message.isNotBlank())
        assertEquals(0L, first.retryToken)
        assertNull("还没有成功内容时没有可保留的投影", first.lastContent)

        viewModel.retryScope()
        val retried = viewModel.scopeState.first { it is AgentBotScopeState.Error && it.retryToken == 1L }

        assertTrue("重试失败仍是 Error", retried is AgentBotScopeState.Error)
        assertEquals(
            "每次重试都要换 token，界面才能区分这是新一轮失败",
            1L,
            (retried as AgentBotScopeState.Error).retryToken,
        )
    }

    @Test
    fun `a staged read failure becomes Error and keeps the last projection`() = runTest(dispatcher) {
        val sessionId = graph.sessions.createAgentSession("assistant-a")
        graph.db.botTaskDao().upsert(scopedTask("task-run", "assistant-a", sessionId, BotTaskStatus.RUNNING))
        val stagedId = graph.staging.stage(stagedCandidate("notes.md"))
        graph.repository.appendEvent(
            botId = "assistant-a",
            originSessionId = sessionId,
            taskId = "task-run",
            runId = "",
            kind = BotEventKind.TASK_RESULT_READY,
            dedupeKey = "result-1",
            payloadRef = "做完了\n[staged=$stagedId]",
        )

        val viewModel = vm()
        viewModel.bind(botId = "assistant-a", originSessionId = sessionId)
        val content = viewModel.awaitScopeSettled()
        assertTrue("读得到候选时必须是 Content，实际 $content", content is AgentBotScopeState.Content)
        assertEquals(listOf(stagedId), viewModel.pendingCandidates.value.map { it.stagedId })

        // 暂存区路径被一个普通文件占住：目录列表读取必然失败。
        graph.stagingDir.deleteRecursively()
        graph.stagingDir.writeText("not a directory")
        viewModel.refreshCandidates()

        val error = viewModel.scopeState.first { it is AgentBotScopeState.Error } as AgentBotScopeState.Error
        assertTrue("读取失败必须带可显示的原因", error.message.isNotBlank())
        assertEquals(
            "读取失败必须保留上一次成功投影，不能清成空列表",
            (content as AgentBotScopeState.Content).projection,
            error.lastContent,
        )
        assertEquals(
            "界面上已显示的任务不能被清空",
            listOf("task-run"),
            viewModel.taskBoard.value.tasks.map { it.taskId },
        )
        assertEquals(
            "界面上已显示的候选不能被清空",
            listOf(stagedId),
            viewModel.pendingCandidates.value.map { it.stagedId },
        )
    }

    @Test
    fun `an error keeps the last successful projection`() = runTest {
        val row = AgentBotUiState.TaskRow(
            taskId = "task-run",
            goal = "把稿子改完",
            status = BotTaskStatus.RUNNING,
        )
        val lastGood = AgentBotProjection(
            assistantId = "assistant-a",
            sessionId = "session-1",
            inProgress = listOf(row),
        )

        val states = flow {
            emit(lastGood)
            throw IOException("room read failed")
        }.asScopeStates(retryToken = 7L).toList()

        assertEquals(3, states.size)
        assertTrue(states[0] is AgentBotScopeState.Loading)
        assertEquals(lastGood, (states[1] as AgentBotScopeState.Content).projection)
        val error = states[2] as AgentBotScopeState.Error
        assertEquals("room read failed", error.message)
        assertEquals(7L, error.retryToken)
        assertEquals("失败时上一次成功内容必须保留，不能清空", lastGood, error.lastContent)
    }

    @Test
    fun `error state also receives the previous projection across a retry`() = runTest {
        val lastGood = AgentBotProjection(assistantId = "assistant-a", sessionId = "session-1")

        val states = flow<AgentBotProjection> {
            throw IllegalStateException("transport closed")
        }.asScopeStates(retryToken = 3L, previous = lastGood).toList()

        assertEquals(2, states.size)
        assertTrue(states[0] is AgentBotScopeState.Loading)
        assertEquals(lastGood, (states[1] as AgentBotScopeState.Error).lastContent)
    }
}
