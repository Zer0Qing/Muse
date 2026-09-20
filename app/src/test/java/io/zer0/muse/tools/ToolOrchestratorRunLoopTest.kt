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
import io.zer0.muse.ui.chat.InMemoryChatStateAccessor
import io.zer0.muse.ui.taskcard.TaskStepStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        // P5-6: mock 必须复用生产判定器 ToolResultJudge.isSuccess(20+ 失败前缀),
        // 不能用「只认 error/[超时]」的弱替身,否则测试与生产判定漂移。
        every { coordinator.isToolResultSuccess(any()) } answers {
            ToolResultJudge.isSuccess(firstArg())
        }
    }

    private fun orchestrator(timeoutMs: Long = 120_000L, parallelReadOnlyToolsEnabled: Boolean = true) = ToolOrchestrator(
        toolRegistry = registry,
        skillRepository = skillRepository,
        skillExecutor = skillExecutor,
        assistantRepository = assistantRepository,
        sessionRepository = sessionRepository,
        context = context,
        toolTimeoutMs = timeoutMs,
        parallelReadOnlyToolsEnabled = parallelReadOnlyToolsEnabled,
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
        assertEquals(ToolLoopTerminationReason.COMPLETED, result.terminationReason)
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

        // Phase 3: 熔断退出不再被标成成功 — success 只表示正常完成最终答复。
        assertFalse(result.success)
        assertEquals(ToolLoopTerminationReason.CONSECUTIVE_FAILURES, result.terminationReason)
        assertNotNull(result.error)
        assertEquals(3, result.round)
        assertEquals(3, result.totalToolCallCount)
        assertEquals(3, history.count { it.role == MessageRole.TOOL })
        assertTrue(history.filter { it.role == MessageRole.TOOL }.all { it.content.contains("error") })
    }

    @Test
    fun `round limit exit reports ROUND_LIMIT failure`() = runBlocking {
        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(listOf(ToolCall("r1", "calculator", """{"expression":"1"}"""))),
                    toolRound(listOf(ToolCall("r2", "calculator", """{"expression":"2"}"""))),
                ),
            ),
        )
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers { "ok" }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator().runLoop(params(maxRounds = 2), history, host, accessor, coordinator)

        assertFalse(result.success)
        assertEquals(ToolLoopTerminationReason.ROUND_LIMIT, result.terminationReason)
        assertNotNull(result.error)
        assertEquals(2, result.round)
        assertEquals(2, result.totalToolCallCount)
        // 轮次耗尽仍注入用户可见收尾文案
        assertNotNull(result.finalAssistantMessage)
    }

    @Test
    fun `repeated identical tool calls abort with REPEATED_TOOL_CALLS`() = runBlocking {
        val repeated = listOf(
            toolRound(listOf(ToolCall("d1", "calculator", """{"expression":"1"}"""))),
            toolRound(listOf(ToolCall("d2", "calculator", """{"expression":"1"}"""))),
            // 第 3 轮签名重复达阈值,熔断发生在执行之前
            toolRound(listOf(ToolCall("d3", "calculator", """{"expression":"1"}"""))),
        )
        val host = FakeToolLoopHost(ArrayDeque(repeated))
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers { "ok" }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator().runLoop(params(), history, host, accessor, coordinator)

        assertFalse(result.success)
        assertEquals(ToolLoopTerminationReason.REPEATED_TOOL_CALLS, result.terminationReason)
        assertEquals(3, result.round)
        // 前两轮真实执行,第三轮被停滞检测拦截(计数在检测前记录,共 3 次"模型发出")
        assertEquals(3, result.totalToolCallCount)
        assertEquals(2, history.count { it.role == MessageRole.TOOL })
    }

    @Test
    fun `tool timeout is mapped to TIMED_OUT task step status`() = runBlocking {
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

        // 真实 coordinator + 内存 accessor:验证任务卡步骤终态(而非仅工具消息文本)
        val uiAccessor = InMemoryChatStateAccessor()
        val realCoordinator = ChatTaskCardCoordinator(uiAccessor, registry)
        val history = mutableListOf<UIMessage>()
        val result = orchestrator(timeoutMs = 20L).runLoop(params(), history, host, uiAccessor, realCoordinator)

        assertTrue(result.success)
        val card = uiAccessor.snapshot.taskCards.values.single()
        assertEquals(1, card.steps.size)
        assertEquals(TaskStepStatus.TIMED_OUT, card.steps.first().status)
        assertTrue(card.hasFailedSteps)
    }

    @Test
    fun `tool calls executed sequentially after B-38`() = runBlocking {
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
        // B-38 串行语义:显式关闭只读并行(生产默认开启,经「实验功能」可关)
        val result = orchestrator(parallelReadOnlyToolsEnabled = false)
            .runLoop(params(), history, host, accessor, coordinator)

        assertTrue(result.success)
        assertEquals(2, result.totalToolCallCount)
        assertEquals(2, history.count { it.role == MessageRole.TOOL })
        // B-38: 工具执行改为串行,消除 afterExecute/taskCard/计数竞态;断言不再并发
        assertEquals(
            "B-38 后工具应串行执行,最大并发应恒为 1,实际=$maxActive",
            1,
            maxActive.get(),
        )
    }
}
