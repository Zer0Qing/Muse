package io.zer0.muse.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGenerationControllerSessionScopeTest {

    @Test
    fun `agent mode stop scope prefers agent session`() {
        val state = ChatUiState(
            sessionState = ChatSessionState(currentSessionId = "task-1"),
            agentState = ChatAgentState(isAgentMode = true, agentSessionId = "agent-1"),
        )

        assertEquals("agent-1", effectiveGenerationSessionId(state))
    }

    @Test
    fun `optimistic rollback follows the effective agent session`() {
        val state = ChatUiState(
            sessionState = ChatSessionState(currentSessionId = "task-1"),
            agentState = ChatAgentState(isAgentMode = true, agentSessionId = "agent-1"),
        )

        assertTrue(shouldRollbackOptimisticSend(state, "agent-1"))
        assertFalse(shouldRollbackOptimisticSend(state, "task-1"))
    }
}
