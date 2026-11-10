package io.zer0.muse.proactive

import io.zer0.muse.data.ProactiveMessageConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProactiveManualTestRouteTest {
    @Test
    fun `manual test lands in agent even when plain chats are globally allowed`() = kotlinx.coroutines.runBlocking {
        val route =
            ProactiveManualTestRoute.resolve(
                config = ProactiveMessageConfig(allowPlainChatSession = true),
                existingAgentSession = {
                    ProactiveContextLoader.SessionPick(
                        "agent",
                        ProactiveContextLoader.SessionSource.EXISTING_AGENT,
                        1,
                    )
                },
                createAgentSession = { error("must reuse the existing Agent session") },
            )

        assertEquals("agent", route?.sessionId)
        assertEquals(ProactiveContextLoader.SessionSource.EXISTING_AGENT, route?.source)
    }

    @Test
    fun `manual test creates a new agent instead of borrowing a viewed task chat`() = kotlinx.coroutines.runBlocking {
        var created = 0
        val route =
            ProactiveManualTestRoute.resolve(
                config = ProactiveMessageConfig(allowPlainChatSession = true),
                existingAgentSession = { null },
                createAgentSession = {
                    created++
                    ProactiveContextLoader.SessionPick(
                        "new-agent",
                        ProactiveContextLoader.SessionSource.NEW_AGENT,
                        1,
                    )
                },
            )

        assertEquals(1, created)
        assertEquals("new-agent", route?.sessionId)
        assertEquals(ProactiveContextLoader.SessionSource.NEW_AGENT, route?.source)
    }

    @Test
    fun `manual test never falls back to plain tasks when agent is disabled`() = kotlinx.coroutines.runBlocking {
        val route =
            ProactiveManualTestRoute.resolve(
                config = ProactiveMessageConfig(agentSessionEnabled = false, allowPlainChatSession = true),
                existingAgentSession = { error("disabled Agent must not be queried") },
                createAgentSession = { error("disabled Agent must not be created") },
            )

        assertNull(route)
    }
}
