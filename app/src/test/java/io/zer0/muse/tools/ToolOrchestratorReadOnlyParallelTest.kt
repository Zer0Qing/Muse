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
 * Phase 3: 只读工具有限并发测试。
 *
 * 覆盖:
 *  - 开关默认关闭 → 与既有 B-38 串行行为一致;
 *  - 开关开启 → 只读工具按上限并发执行,结果按原始 tool-call 顺序回填;
 *  - 混合轮(含写入/副作用工具)→ 整轮串行;
 *  - 工具执行类别白名单保守(未知工具一律串行)。
 *
 * 运行: ./gradlew :app:testDebugUnitTest --tests "*ToolOrchestratorReadOnlyParallelTest*"
 */
class ToolOrchestratorReadOnlyParallelTest {

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

    private fun orchestrator(parallelReadOnlyTools: Boolean) = ToolOrchestrator(
        toolRegistry = registry,
        skillRepository = skillRepository,
        skillExecutor = skillExecutor,
        assistantRepository = assistantRepository,
        sessionRepository = sessionRepository,
        context = context,
        parallelReadOnlyToolsEnabled = parallelReadOnlyTools,
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

    private fun trackConcurrency(): Pair<AtomicInteger, AtomicInteger> {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        coEvery { registry.executeFromJson(any(), any()) } coAnswers {
            val current = active.incrementAndGet()
            maxActive.accumulateAndGet(current, ::max)
            delay(120)
            active.decrementAndGet()
            "ok"
        }
        return active to maxActive
    }

    @Test
    fun `read-only tools stay sequential when flag is disabled`() = runBlocking {
        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(
                        listOf(
                            ToolCall("r1", "get_current_time", "{}"),
                            ToolCall("r2", "web_search", """{"q":"kotlin"}"""),
                            ToolCall("r3", "read_file", """{"path":"a.txt"}"""),
                        ),
                    ),
                    finalRound(),
                ),
            ),
        )
        val (_, maxActive) = trackConcurrency()

        val history = mutableListOf<UIMessage>()
        val result = orchestrator(parallelReadOnlyTools = false).runLoop(params(), history, host, accessor, coordinator)

        assertTrue(result.success)
        assertEquals(3, result.totalToolCallCount)
        assertEquals("开关默认关闭时必须保持串行,实际最大并发=${maxActive.get()}", 1, maxActive.get())
    }

    @Test
    fun `read-only tools run with bounded parallelism when flag is enabled`() = runBlocking {
        val calls = (1..4).map { ToolCall("p$it", "get_current_time", """{"i":$it}""") }
        val host = FakeToolLoopHost(ArrayDeque(listOf(toolRound(calls), finalRound())))
        val (_, maxActive) = trackConcurrency()

        val history = mutableListOf<UIMessage>()
        val result = orchestrator(parallelReadOnlyTools = true).runLoop(params(), history, host, accessor, coordinator)

        assertTrue(result.success)
        assertEquals(4, result.totalToolCallCount)
        assertEquals(4, history.count { it.role == MessageRole.TOOL })
        assertTrue("只读工具应并发执行(最大并发=${maxActive.get()})", maxActive.get() >= 2)
        assertTrue(
            "并发必须受 READ_ONLY_TOOL_MAX_PARALLELISM=$READ_ONLY_TOOL_MAX_PARALLELISM 限制," +
                "实际最大并发=${maxActive.get()}",
            maxActive.get() <= READ_ONLY_TOOL_MAX_PARALLELISM,
        )
    }

    @Test
    fun `results are backfilled in original tool-call order despite completion order`() = runBlocking {
        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(
                        listOf(
                            ToolCall("s1", "get_current_time", "{}"),
                            ToolCall("s2", "web_fetch", """{"url":"https://example.com"}"""),
                            ToolCall("s3", "read_file", """{"path":"b.txt"}"""),
                        ),
                    ),
                    finalRound(),
                ),
            ),
        )
        // 第一个调用最慢,完成顺序与回填顺序相反
        coEvery { registry.executeFromJson("get_current_time", any()) } coAnswers { delay(250); "slow" }
        coEvery { registry.executeFromJson("web_fetch", any()) } coAnswers { delay(100); "fast" }
        coEvery { registry.executeFromJson("read_file", any()) } coAnswers { delay(20); "quick" }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator(parallelReadOnlyTools = true).runLoop(params(), history, host, accessor, coordinator)

        assertTrue(result.success)
        assertEquals(
            listOf("slow", "fast", "quick"),
            history.filter { it.role == MessageRole.TOOL }.map { it.content },
        )
        assertEquals(
            listOf("s1", "s2", "s3"),
            history.filter { it.role == MessageRole.TOOL }.map { it.toolCallId },
        )
    }

    @Test
    fun `round containing a mutating tool stays sequential`() = runBlocking {
        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(
                        listOf(
                            ToolCall("m1", "get_current_time", "{}"),
                            ToolCall("m2", "send_sms", """{"to":"10086"}"""),
                            ToolCall("m3", "read_file", """{"path":"c.txt"}"""),
                        ),
                    ),
                    finalRound(),
                ),
            ),
        )
        val (_, maxActive) = trackConcurrency()

        val history = mutableListOf<UIMessage>()
        val result = orchestrator(parallelReadOnlyTools = true).runLoop(params(), history, host, accessor, coordinator)

        assertTrue(result.success)
        assertEquals(3, result.totalToolCallCount)
        assertEquals("混合轮含写入工具时必须整轮串行,实际最大并发=${maxActive.get()}", 1, maxActive.get())
    }

    @Test
    fun `skill routed read-only names stay sequential`() = runBlocking {
        // read_file 同名 skill:白名单命中但路由到 skill,必须串行(未知实现不并发)
        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(
                        listOf(
                            ToolCall("k1", "read_file", "{}"),
                            ToolCall("k2", "read_file", """{"path":"d.txt"}"""),
                        ),
                    ),
                    finalRound(),
                ),
            ),
        )
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        coEvery {
            skillExecutor.execute(
                skill = any(),
                argumentsJson = any(),
                onProgress = any(),
                turnKey = any(),
                sessionId = any(),
            )
        } coAnswers {
            val current = active.incrementAndGet()
            maxActive.accumulateAndGet(current, ::max)
            delay(120)
            active.decrementAndGet()
            "ok"
        }

        val skillParams = params().copy(
            skillMap = mapOf("read_file" to mockk<io.zer0.muse.data.skill.SkillEntity>(relaxed = true)),
        )
        val history = mutableListOf<UIMessage>()
        val result = orchestrator(parallelReadOnlyTools = true)
            .runLoop(skillParams, history, host, accessor, coordinator)

        assertTrue(result.success)
        assertEquals(2, result.totalToolCallCount)
        assertEquals("skill 路由的调用必须串行,实际最大并发=${maxActive.get()}", 1, maxActive.get())
    }

    @Test
    fun `classification is conservative for unknown and mutating tools`() {
        assertEquals(ToolExecCategory.READ_ONLY, classifyToolExecution("read_file"))
        assertEquals(ToolExecCategory.READ_ONLY, classifyToolExecution("web_search"))
        assertEquals(ToolExecCategory.READ_ONLY, classifyToolExecution("get_current_time"))
        assertEquals(ToolExecCategory.SERIAL, classifyToolExecution("send_sms"))
        assertEquals(ToolExecCategory.SERIAL, classifyToolExecution("todo_write"))
        assertEquals(ToolExecCategory.SERIAL, classifyToolExecution("browser_navigate"))
        assertEquals(ToolExecCategory.SERIAL, classifyToolExecution("delegate_agent"))
        assertEquals(ToolExecCategory.SERIAL, classifyToolExecution("brand_new_unknown_tool"))
        assertEquals(ToolExecCategory.SERIAL, classifyToolExecution("ui_click"))
        assertEquals(ToolExecCategory.SERIAL, classifyToolExecution("ui_screenshot"))
    }
}
