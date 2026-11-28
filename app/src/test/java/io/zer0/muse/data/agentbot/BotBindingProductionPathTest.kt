package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.ui.agentbot.AgentBotViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 绑定必须走生产路径落库。
 *
 * 页面此前只在内存里记着 botId / sessionId：进程一死，第二天打开页面
 * 既不知道这个助手上次用哪个 canonical 会话，后台消费也没有可用的绑定。
 *
 * 这里从 `AgentBotViewModel.bind` 这个真实入口出发，而不是直接调仓储 ——
 * "仓储有方法但页面不调用"正是最容易被单测漏掉的那类缺陷。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BotBindingProductionPathTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var graph: AgentBotFlowTestGraph
    private val viewModels = mutableListOf<AgentBotViewModel>()

    /** 没有 bind 过时页面用的 bot id，与 ViewModel 的默认值同源。 */
    private val botId = AgentBotViewModel.DEFAULT_BOT_ID

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
    fun `binding writes exactly one bot binding row`() = runTest(dispatcher) {
        val viewModel = vm()

        viewModel.bind(botId = "assistant-a", originSessionId = "session-1")
        viewModel.awaitBoundSession()

        val rows = graph.db.botBindingDao().getAll()
        assertEquals("bind 只能留下一行绑定", 1, rows.size)
        val row = rows.single()
        assertEquals("assistant-a", row.botId)
        assertEquals("assistant-a", row.assistantId)
        assertEquals("session-1", row.canonicalAgentSessionId)
        assertTrue("生产绑定必须是启用状态，否则后台消费者看不到它", row.enabled)
        assertEquals("session-1", viewModel.boundSessionId.value)
    }

    @Test
    fun `ensureBinding is idempotent for the same assistant and session`() = runTest(dispatcher) {
        val first = graph.repository.ensureBinding("assistant-a", "session-1")
        val second = graph.repository.ensureBinding("assistant-a", "session-1")

        assertEquals("重复调用必须返回同一份绑定", first, second)
        assertEquals(1, graph.db.botBindingDao().getAll().size)
        assertEquals(first.createdAt, second.createdAt)
        assertEquals(first.updatedAt, second.updatedAt)
    }

    @Test
    fun `repeated bind keeps the same binding row`() = runTest(dispatcher) {
        val viewModel = vm()
        viewModel.bind(botId = "assistant-a", originSessionId = "session-1")
        viewModel.awaitBoundSession()
        val first = graph.db.botBindingDao().getAll().single()

        // 中间真的换一次作用域再切回来。
        //
        // 直接连续两次 bind 同样参数时 ScopeRequest 相等，MutableStateFlow 会去重，
        // 第二次根本不进管线 —— 那样的断言只是空跑，钉不住任何东西。
        viewModel.bind(botId = "assistant-b", originSessionId = "session-2")
        viewModel.boundSessionId.first { it == "session-2" }
        viewModel.bind(botId = "assistant-a", originSessionId = "session-1")
        viewModel.boundSessionId.first { it == "session-1" }

        val second = graph.db.botBindingDao().getAll().first { it.botId == "assistant-a" }
        assertEquals("同一绑定再进页面不得改写既有行", first, second)
    }

    @Test
    fun `reopening the page restores the canonical session from the binding`() = runTest(dispatcher) {
        val first = vm()
        first.bind(botId = "assistant-a", originSessionId = "session-1")
        first.awaitBoundSession()
        first.viewModelScope.cancel()

        // 新进程里宿主只拿得到 assistantId，会话 id 为空。
        val reopened = vm()
        reopened.bind(botId = "assistant-a", originSessionId = "")

        assertEquals("session-1", reopened.awaitBoundSession())
        assertEquals(1, graph.db.botBindingDao().getAll().size)
    }

    @Test
    fun `another assistant approvals and candidates never reach this page`() = runTest(dispatcher) {
        graph.db.botTaskDao().upsert(scopedTask("task-a", "assistant-a", "session-1", BotTaskStatus.RUNNING))
        graph.db.botTaskDao().upsert(scopedTask("task-b", "assistant-b", "session-2", BotTaskStatus.RUNNING))
        graph.db.approvalRequestDao().upsert(scopedApproval("appr-a", "assistant-a", "session-1", "task-a"))
        graph.db.approvalRequestDao().upsert(scopedApproval("appr-b", "assistant-b", "session-2", "task-b"))

        val ownCandidate = graph.staging.stage(stagedCandidate("notes-a.md"))
        val otherCandidate = graph.staging.stage(stagedCandidate("notes-b.md"))
        graph.repository.appendEvent(
            botId = "assistant-a",
            originSessionId = "session-1",
            taskId = "task-a",
            runId = "",
            kind = BotEventKind.TASK_RESULT_READY,
            dedupeKey = "result-a",
            payloadRef = "做完了\n[staged=$ownCandidate]",
        )
        graph.repository.appendEvent(
            botId = "assistant-b",
            originSessionId = "session-2",
            taskId = "task-b",
            runId = "",
            kind = BotEventKind.TASK_RESULT_READY,
            dedupeKey = "result-b",
            payloadRef = "做完了\n[staged=$otherCandidate]",
        )

        val viewModel = vm()
        viewModel.bind(botId = "assistant-a", originSessionId = "session-1")
        val state = viewModel.awaitScopeSettled()

        assertEquals(
            "看板只能有当前 assistant + session 的任务",
            listOf("task-a"),
            viewModel.taskBoard.value.tasks.map { it.taskId },
        )
        assertEquals(
            "审批只能有当前 assistant + session 的",
            listOf("appr-a"),
            viewModel.taskBoard.value.approvals.map { it.approvalId },
        )
        assertEquals(
            "候选只能有当前 assistant + session 的",
            listOf(ownCandidate),
            viewModel.pendingCandidates.value.map { it.stagedId },
        )

        assertNotNull(state)
        assertTrue("有数据时必须是 Content，实际 $state", state is AgentBotScopeState.Content)
        val projection = (state as AgentBotScopeState.Content).projection
        assertEquals(setOf("task-a"), projection.taskRows.map { it.taskId }.toSet())
        assertEquals(listOf("appr-a"), projection.pendingApprovals.map { it.approvalId })
        assertEquals(listOf(ownCandidate), projection.candidates.map { it.stagedId })
        assertTrue(
            "另一个 assistant 的候选不得出现在投影里",
            projection.candidates.none { it.stagedId == otherCandidate },
        )
    }

    @Test
    fun `a binding read failure aborts send instead of rewriting the binding`() = runTest(dispatcher) {
        // 旧绑定还在库里：读失败时它必须原样保留。
        graph.repository.ensureBinding(botId, "old-session")
        val brokenDao = UnreadableBindingDao(graph.db.botBindingDao())
        val brokenRepository = graph.repositoryWith(bindingDao = brokenDao)

        val viewModel = graph.viewModel(repository = brokenRepository)
        viewModels += viewModel
        viewModel.onComposerTextChange("帮我把稿子改完")
        viewModel.send()

        val busy = viewModel.busy.first { it.error != null }
        assertEquals(
            "读绑定失败必须是可区分的错误，不能混成 NO_SESSION",
            AgentBotError.BINDING_UNREADABLE,
            busy.error,
        )
        assertFalse("发送必须中止，不能停在 sending", busy.sending)
        assertEquals("读失败不得新建 canonical 会话", 0, graph.db.sessionDao().observeAll().first().size)
        assertEquals("读失败不得写入绑定", 0, brokenDao.writeCalls)
        assertEquals(
            "旧绑定必须保持原值，否则用户历史看起来会消失",
            "old-session",
            graph.db.botBindingDao().getById(botId)?.canonicalAgentSessionId,
        )
    }
}
