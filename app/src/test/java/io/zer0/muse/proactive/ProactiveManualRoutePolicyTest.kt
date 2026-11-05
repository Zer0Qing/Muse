package io.zer0.muse.proactive

import io.zer0.muse.data.ProactiveMessageConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「测试主动消息」的落点策略。
 *
 * 关键口径：测试要与真实巡检一致，否则用户在设置里选了「发到普通聊天框」，
 * 点测试却永远报「没有可用会话」—— 那不是测试，那是误导。
 */
class ProactiveManualRoutePolicyTest {

    private val plainPick =
        ProactiveContextLoader.SessionPick(
            "plain",
            ProactiveContextLoader.SessionSource.RECENT_ACTIVE,
            1,
        )

    @Test
    fun `Agent 关且未允许普通对话时没有落点`() = runBlocking {
        val route =
            ProactiveManualTestRoute.resolve(
                config =
                ProactiveMessageConfig(
                    agentSessionEnabled = false,
                    allowPlainChatSession = false,
                ),
                existingAgentSession = { error("must not query Agent") },
                createAgentSession = { error("must not create Agent") },
                plainSession = { error("must not query plain") },
            )

        assertNull(route)
    }

    @Test
    fun `Agent 关但允许普通对话时走普通会话`() = runBlocking {
        val route =
            ProactiveManualTestRoute.resolve(
                config =
                ProactiveMessageConfig(
                    agentSessionEnabled = false,
                    allowPlainChatSession = true,
                ),
                existingAgentSession = { error("must not query Agent") },
                createAgentSession = { error("must not create Agent") },
                plainSession = { plainPick },
            )

        assertEquals("plain", route?.sessionId)
    }

    @Test
    fun `允许普通对话但没有可用会话时仍为空`() = runBlocking {
        val route =
            ProactiveManualTestRoute.resolve(
                config =
                ProactiveMessageConfig(
                    agentSessionEnabled = false,
                    allowPlainChatSession = true,
                ),
                existingAgentSession = { error("must not query Agent") },
                createAgentSession = { error("must not create Agent") },
                plainSession = { null },
            )

        assertNull(route)
    }

    @Test
    fun `Agent 开着时普通对话再开放也不抢落点`() = runBlocking {
        val route =
            ProactiveManualTestRoute.resolve(
                config =
                ProactiveMessageConfig(
                    agentSessionEnabled = true,
                    allowPlainChatSession = true,
                ),
                existingAgentSession = {
                    ProactiveContextLoader.SessionPick(
                        "agent",
                        ProactiveContextLoader.SessionSource.EXISTING_AGENT,
                        1,
                    )
                },
                createAgentSession = { error("must reuse the existing Agent session") },
                plainSession = { error("must not query plain") },
            )

        assertEquals("agent", route?.sessionId)
    }
}
