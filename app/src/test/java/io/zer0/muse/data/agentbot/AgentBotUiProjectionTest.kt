package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.ui.agentbot.AgentBotViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentBotUiProjectionTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var graph: AgentBotFlowTestGraph
    private val viewModels = mutableListOf<AgentBotViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        graph = AgentBotFlowTestGraph(ApplicationProvider.getApplicationContext<Context>())
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        graph.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `task sections keep actionable rows separate from running and recent rows`() = runTest(dispatcher) {
        val sessionId = graph.sessions.createAgentSession("assistant-a")
        graph.db.botTaskDao().upsert(scopedTask("running", "assistant-a", sessionId, BotTaskStatus.RUNNING))
        graph.db.botTaskDao().upsert(scopedTask("approval", "assistant-a", sessionId, BotTaskStatus.WAITING_APPROVAL))
        graph.db.botTaskDao().upsert(scopedTask("unknown", "assistant-a", sessionId, BotTaskStatus.UNKNOWN))
        graph.db.botTaskDao().upsert(scopedTask("failed", "assistant-a", sessionId, BotTaskStatus.FAILED))
        graph.db.botTaskDao().upsert(scopedTask("complete", "assistant-a", sessionId, BotTaskStatus.SUCCEEDED))
        graph.db.approvalRequestDao().upsert(scopedApproval("approval-row", "assistant-a", sessionId, "approval"))

        val viewModel = graph.viewModel().also(viewModels::add)
        viewModel.bind("assistant-a", sessionId)
        val state = viewModel.awaitScopeSettled() as AgentBotScopeState.Content
        val projection = state.projection

        assertEquals(setOf("running"), projection.inProgress.map { it.taskId }.toSet())
        assertEquals(setOf("approval", "unknown"), projection.needsAction.map { it.taskId }.toSet())
        assertEquals(setOf("failed", "complete"), projection.recent.map { it.taskId }.toSet())
        assertEquals(1, projection.pendingApprovals.size)

        val rows = projection.taskRows.associateBy { it.taskId }
        assertTrue(
            "approval remains cancellable while its card offers approve and deny",
            rows.getValue("approval").actions.contains(AgentBotUiState.TaskAction.CANCEL),
        )
        assertTrue(
            "unknown results need a verification action",
            rows.getValue("unknown").actions.contains(AgentBotUiState.TaskAction.VERIFY),
        )
        assertEquals(AgentBotPrompt.VERIFY_RESULT, rows.getValue("unknown").needsUser)
        assertTrue("failed tasks expose an explicit retry", rows.getValue("failed").actions.contains(AgentBotUiState.TaskAction.RETRY))
        assertFalse("terminal tasks never appear in the running section", projection.inProgress.any { it.status in BotTaskStatus.TERMINAL })
        assertEquals(projection.taskRows.size, projection.taskRows.map { it.taskId }.distinct().size)
    }
}
