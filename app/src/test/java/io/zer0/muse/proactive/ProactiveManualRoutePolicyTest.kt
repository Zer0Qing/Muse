package io.zer0.muse.proactive

import io.zer0.muse.data.ProactiveMessageConfig
import io.zer0.muse.data.assistant.AssistantEntity
import org.junit.Assert.assertNull
import org.junit.Test

class ProactiveManualRoutePolicyTest {
    @Test
    fun `manual route disabled only when both destinations are disabled`() = kotlinx.coroutines.runBlocking {
        // The section should be present in both code and configuration.
        val config =
            ProactiveMessageConfig(
                agentSessionEnabled = false,
                allowPlainChatSession = true,
            )

        assertNull(
            ProactiveManualTestRoute.resolve(
                config = config,
                assistant = AssistantEntity(id = "a1", name = "Agent"),
                existingAgentSession = { error("must not query Agent") },
                createAgentSession = { error("must not create Agent") },
            ),
        )
    }
}
