package io.zer0.muse.proactive

import io.zer0.muse.data.ProactiveMessageConfig
import io.zer0.muse.data.assistant.AssistantEntity

internal fun proactiveConfigOnReadFailure(): ProactiveMessageConfig =
    ProactiveMessageConfig(
        enabled = false,
        agentSessionEnabled = true,
        allowPlainChatSession = false,
    )

/** Manual proactive tests can only use the Agent destination. */
object ProactiveManualTestRoute {
    suspend fun resolve(
        config: ProactiveMessageConfig,
        assistant: AssistantEntity,
        existingAgentSession: suspend () -> ProactiveContextLoader.SessionPick?,
        createAgentSession: suspend () -> ProactiveContextLoader.SessionPick,
    ): ProactiveContextLoader.SessionPick? {
        if (!config.agentSessionEnabled) return null
        return existingAgentSession() ?: createAgentSession()
    }
}
