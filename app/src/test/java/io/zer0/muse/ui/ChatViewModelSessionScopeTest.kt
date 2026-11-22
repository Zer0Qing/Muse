package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import io.zer0.muse.ui.chat.ChatSessionState
import io.zer0.muse.ui.chat.ChatAgentState
import io.zer0.muse.ui.chat.ChatUiState
import io.zer0.muse.ui.chat.effectiveChatSessionId

class ChatViewModelSessionScopeTest {

    @Test
    fun `agent mode session scope prefers agent session`() {
        val state = ChatUiState(
            sessionState = ChatSessionState(currentSessionId = "task-1"),
            agentState = ChatAgentState(isAgentMode = true, agentSessionId = "agent-1"),
        )

        assertEquals("agent-1", effectiveChatSessionId(state))
    }
}
