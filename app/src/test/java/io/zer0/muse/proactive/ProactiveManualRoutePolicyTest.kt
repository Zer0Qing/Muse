package io.zer0.muse.proactive

import io.zer0.muse.data.ProactiveMessageConfig
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
                    existingAgentSession = { error("must not query Agent") },
                createAgentSession = { error("must not create Agent") },
            ),
        )
    }
}
