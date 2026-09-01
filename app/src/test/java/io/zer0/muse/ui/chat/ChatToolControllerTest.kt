package io.zer0.muse.ui.chat

import io.mockk.mockk
import io.zer0.ai.core.UIMessage
import io.zer0.muse.tools.SessionPermissionStore
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.PendingToolApproval
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatToolControllerTest {

    private class ScopedAccessor(
        initial: ChatUiState,
        override val coroutineScope: CoroutineScope,
    ) : ChatStateAccessor {
        private val state = MutableStateFlow(initial)
        override val snapshot: ChatUiState get() = state.value
        override fun update(transform: (ChatUiState) -> ChatUiState) = state.update(transform)
        override val messagesSnapshot: List<UIMessage> get() = emptyList()
        override fun updateMessages(transform: (List<UIMessage>) -> List<UIMessage>) = Unit
    }

    private fun controller(
        scope: CoroutineScope,
        state: ChatUiState,
        results: ConcurrentHashMap<String, CompletableDeferred<ToolApprovalState>>,
    ): Pair<ChatToolController, ScopedAccessor> {
        val accessor = ScopedAccessor(state, scope)
        return ChatToolController(accessor, mockk<SessionPermissionStore>(relaxed = true), results, null) to accessor
    }

    @Test
    fun `approveToolCall completes deferred as approved`() = runTest {
        val pending = PendingToolApproval(toolCallId = "c1", toolName = "web_search", argumentsPreview = "{}")
        val state = ChatUiState().copy(pendingToolApprovals = listOf(pending))
        val deferred = CompletableDeferred<ToolApprovalState>()
        val results = ConcurrentHashMap<String, CompletableDeferred<ToolApprovalState>>()
        results["c1"] = deferred
        val (c, accessor) = controller(this, state, results)
        c.approveToolCall("c1")
        assertTrue(accessor.snapshot.pendingToolApprovals.isEmpty())
        val outcome = deferred.getCompleted()
        assertTrue(outcome is ToolApprovalState.Approved)
    }

    @Test
    fun `denyToolCall completes deferred as denied`() = runTest {
        val pending = PendingToolApproval(toolCallId = "c2", toolName = "shell", argumentsPreview = "{}")
        val state = ChatUiState().copy(pendingToolApprovals = listOf(pending))
        val deferred = CompletableDeferred<ToolApprovalState>()
        val results = ConcurrentHashMap<String, CompletableDeferred<ToolApprovalState>>()
        results["c2"] = deferred
        val (c, _) = controller(this, state, results)
        c.denyToolCall("c2", "user rejected")
        val outcome = deferred.getCompleted()
        assertEquals(ToolApprovalState.Denied("user rejected"), outcome)
    }
}
