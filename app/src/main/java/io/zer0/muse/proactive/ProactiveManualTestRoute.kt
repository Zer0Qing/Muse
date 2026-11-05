package io.zer0.muse.proactive

import io.zer0.muse.data.ProactiveMessageConfig

internal fun proactiveConfigOnReadFailure(): ProactiveMessageConfig = ProactiveMessageConfig(
    enabled = false,
    agentSessionEnabled = true,
    allowPlainChatSession = false,
)

/** 测试发送的落点：与真实巡检一致，但不去新建普通会话。 */
object ProactiveManualTestRoute {
    suspend fun resolve(
        config: ProactiveMessageConfig,
        existingAgentSession: suspend () -> ProactiveContextLoader.SessionPick?,
        createAgentSession: suspend () -> ProactiveContextLoader.SessionPick,
        plainSession: suspend () -> ProactiveContextLoader.SessionPick? = { null },
    ): ProactiveContextLoader.SessionPick? {
        if (config.agentSessionEnabled) {
            return existingAgentSession() ?: createAgentSession()
        }
        // Agent 会话关掉时，测试必须复现真实落点：只要允许普通对话，就走普通会话。
        // 旧写法在这里直接返回 null，「测试主动消息」在「只想发到聊天框」的配置下
        // 永远报「没有可用会话」，测试与真实行为对不上，用户无法验证自己的设置。
        return if (config.allowPlainChatSession) plainSession() else null
    }
}
