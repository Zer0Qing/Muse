package io.zer0.muse.tools

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.chat.ChatStateAccessor
import io.zer0.muse.ui.chat.ChatTaskCardCoordinator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.uuid.Uuid

/**
 * R-TEST-10: ToolOrchestrator 工具执行真实路径测试。
 *
 * 覆盖 calculator/web_search 执行回填、超时终止、连续失败 3 次熔断、并行执行。
 * 通过 mock ToolRegistry + fake ToolLoopHost 注入,不依赖 ChatViewModel。
 */
class ToolOrchestratorRunLoopTest {

    private lateinit var registry: ToolRegistry
    private lateinit var skillRepository: SkillRepository
    private lateinit var skillExecutor: SkillExecutor
    private lateinit var assistantRepository: AssistantRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var context: Context
    private lateinit var accessor: ChatStateAccessor
    private lateinit var coordinator: ChatTaskCardCoordinator

    @Before
    fun setUp() {
        registry = mockk(relaxed = true)
        skillRepository = mockk(relaxed = true)
        skillExecutor = mockk(relaxed = true)
        every { skillExecutor.getActivePlans() } returns emptyMap()
        assistantRepository = mockk(relaxed = true)
        sessionRepository = mockk(relaxed = true)
        context = mockk(relaxed = true)

        val snapshot = mockk<ChatUiState>(relaxed = true)
        every { snapshot.isAgentMode } returns false
        every { snapshot.currentSessionId } returns "session-1"
        every { snapshot.agentSessionId } returns null
        accessor = mockk(relaxed = true)
        every { accessor.snapshot } returns snapshot

        coordinator = mockk(relaxed = true)
        every { coordinator.isToolResultSuccess(any()) } answers {
            val result = firstArg<String>()
            !result.contains("error") && !result.startsWith("[超时]")
        }
    }

    private fun orchestrator(timeoutMs: Long = 120_000L) = ToolOrchestrator(
        toolRegistry = registry,
        skillRepository = skillRepository,
        skillExecutor = skillExecutor,
        assistantRepository = assistantRepository,
        sessionRepository = sessionRepository,
        context = context,
        toolTimeoutMs = timeoutMs,
    )

    private fun params(maxRounds: Int = 5) = ToolLoopParams(
        sessionId = "session-1",
        initialAssistantId = Uuid.random(),
        baseHistorySize = 0,
        maxRounds = maxRounds,
        tools = emptyList(),
        skillMap = emptyMap(),
        model = null,
        providerConfig = null,
        temperature = null,
        maxTokens = null,
        reasoningLevel = ReasoningLevel.OFF,
    )

    private fun toolRound(toolCalls: List<ToolCall>): StreamRoundResult.Success =
        StreamRoundResult.Success(
            assistantMessage = UIMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = toolCalls,
            ),
            hasToolCalls = toolCalls.isNotEmpty(),
            contentLength = 0,
            firstTokenTime = 0L,
        )

    private fun finalRound(): StreamRoundResult.Success =
        StreamRoundResult.Success(
            assistantMessage = UIMessage(role = MessageRole.ASSISTANT, content = "done"),
            hasToolCalls = false,
            contentLength = 0,
            firstTokenTime = 0L,
        )

    private class FakeToolLoopHost(
        private val results: ArrayDeque<StreamRoundResult>,
    ) : ToolLoopHost {
        override suspend fun streamRound(params: StreamRoundParams): StreamRoundResult =
            results.removeFirst()

        override suspend fun requestToolApproval(
            toolName: String,
            toolCallId: String,
            argsPreview: String,
            args: Map<String, Any?>,
        ): ToolApprovalState = ToolApprovalState.Approved()

        override fun onToolLoopError(type: ChatErrorType, message: String, recoverable: Boolean) = Unit
    }

    @Test
    fun `calculator result is appended to history and loop completes`() = runBlocking {
        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(listOf(ToolCall("c1", "calculator", """{"expression":"1+2"}"""))),
                    finalRound(),
                ),
            ),
        )
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers { "3" }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator().runLoop(params(), history, host, accessor, coordinator)

        assertTrue(result.success)
        assertEquals(1, result.totalToolCallCount)
        assertEquals(1, history.count { it.role == MessageRole.TOOL })
        assertTrue(history.any { it.role == MessageRole.TOOL && it.content == "3" })
    }

    @Test
    fun `web search result is collected as citation url`() = runBlocking {
        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(listOf(ToolCall("w1", "web_search", """{"q":"kotlin"}"""))),
                    finalRound(),
                ),
            ),
        )
        coEvery { registry.executeFromJson("web_search", any()) } coAnswers { "URL: https://example.com/result" }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator().runLoop(params(), history, host, accessor, coordinator)

        assertTrue(result.success)
        assertEquals(listOf("https://example.com/result"), result.citationUrls)
        assertTrue(history.any { it.role == MessageRole.TOOL })
    }

    @Test
    fun `tool timeout terminates execution with timeout message`() = runBlocking {
        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(listOf(ToolCall("t1", "calculator", """{"expression":"1"}"""))),
                    finalRound(),
                ),
            ),
        )
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers {
            delay(500)
            "too late"
        }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator(timeoutMs = 20L).runLoop(params(), history, host, accessor, coordinator)

        assertTrue(result.success)
        val toolMessage = history.first { it.role == MessageRole.TOOL }
        assertTrue(toolMessage.content.startsWith("[超时]"))
        assertTrue(toolMessage.content.contains("calculator"))
    }

    @Test
    fun `three consecutive tool failures abort the loop`() = runBlocking {
        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(listOf(ToolCall("f1", "calculator", """{"expression":"1"}"""))),
                    toolRound(listOf(ToolCall("f2", "calculator", """{"expression":"2"}"""))),
                    toolRound(listOf(ToolCall("f3", "calculator", """{"expression":"3"}"""))),
                ),
            ),
        )
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers { """{"error": "boom"}""" }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator().runLoop(params(), history, host, accessor, coordinator)

        assertTrue(result.success)
        assertEquals(3, result.round)
        assertEquals(3, result.totalToolCallCount)
        assertEquals(3, history.count { it.role == MessageRole.TOOL })
        assertTrue(history.filter { it.role == MessageRole.TOOL }.all { it.content.contains("error") })
    }

    @Test
    fun `parallel tool calls run concurrently`() = runBlocking {
        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(
                        listOf(
                            ToolCall("p1", "calculator", """{"expression":"1"}"""),
                            ToolCall("p2", "calculator", """{"expression":"2"}"""),
                        ),
                    ),
                    finalRound(),
                ),
            ),
        )
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers {
            val current = active.incrementAndGet()
            maxActive.accumulateAndGet(current, ::max)
            delay(100)
            active.decrementAndGet()
            "ok"
        }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator().runLoop(params(), history, host, accessor, coordinator)

        assertTrue(result.success)
        assertEquals(2, result.totalToolCallCount)
        assertEquals(2, history.count { it.role == MessageRole.TOOL })
        assertTrue("并行工具调用应同时活跃,实际最大并发=$maxActive", maxActive.get() >= 2)
    }
}
