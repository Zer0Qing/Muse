package io.zer0.muse.ui.chat

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
import io.zer0.muse.tools.SkillExecutor
import io.zer0.muse.tools.StreamRoundParams
import io.zer0.muse.tools.StreamRoundResult
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.tools.ToolLoopHost
import io.zer0.muse.tools.ToolLoopParams
import io.zer0.muse.tools.ToolLoopResult
import io.zer0.muse.tools.ToolOrchestrator
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolResultJudge
import io.zer0.muse.ui.ChatErrorType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/**
 * 工具轮之间的插话通道（ToolLoopHost.drainSteeringMessages）。
 *
 * 钉住三件事：
 *  1. 有插话时，它作为一条独立的 USER 消息接在**全部 tool 结果之后**、下一轮请求之前；
 *  2. 无插话时（默认空实现），history 与插话通道不存在时逐字一致；
 *  3. 插话不打断正在执行的工具 —— 取件发生在工具执行完成之后，结果照旧回填。
 */
class ToolLoopSteeringTest {

    private lateinit var registry: ToolRegistry
    private lateinit var skillRepository: SkillRepository
    private lateinit var skillExecutor: SkillExecutor
    private lateinit var assistantRepository: AssistantRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var context: Context
    private lateinit var accessor: InMemoryChatStateAccessor
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
        accessor = InMemoryChatStateAccessor()
        coordinator = mockk(relaxed = true)
        every { coordinator.isToolResultSuccess(any()) } answers {
            ToolResultJudge.isSuccess(firstArg())
        }
        every { coordinator.markTaskStepRunningIfNotPaused(any(), any(), any(), any()) } returns true
    }

    private fun orchestrator() = ToolOrchestrator(
        toolRegistry = registry,
        skillRepository = skillRepository,
        skillExecutor = skillExecutor,
        assistantRepository = assistantRepository,
        sessionRepository = sessionRepository,
        context = context,
    )

    private fun params() = ToolLoopParams(
        sessionId = SESSION_ID,
        initialAssistantId = Uuid.random(),
        baseHistorySize = 0,
        maxRounds = 5,
        tools = emptyList(),
        skillMap = emptyMap(),
        model = null,
        providerConfig = null,
        temperature = null,
        maxTokens = null,
        reasoningLevel = ReasoningLevel.OFF,
    )

    private fun toolRound(vararg ids: String): StreamRoundResult.Success {
        val calls = ids.map { ToolCall(it, "calculator", """{"expression":"1+2"}""") }
        return StreamRoundResult.Success(
            assistantMessage = UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = calls),
            hasToolCalls = true,
            contentLength = 0,
            firstTokenTime = 0L,
        )
    }

    private fun finalRound(): StreamRoundResult.Success = StreamRoundResult.Success(
        assistantMessage = UIMessage(role = MessageRole.ASSISTANT, content = "done"),
        hasToolCalls = false,
        contentLength = 0,
        firstTokenTime = 0L,
    )

    /**
     * @param steering 每次 drain 依次返回的元素（List<String> 的列表），耗尽后返回空
     * @param overrideSteering false 时完全使用 ToolLoopHost 的默认实现（不覆盖插话通道）
     */
    private class SteeringHost(
        private val results: ArrayDeque<StreamRoundResult>,
        steering: List<List<String>> = emptyList(),
        private val overrideSteering: Boolean = true,
        private val onDrain: () -> Unit = {},
    ) : ToolLoopHost {
        val calls = mutableListOf<StreamRoundParams>()
        private val pendingSteering: ArrayDeque<List<String>> = ArrayDeque(steering)
        var drainCount = 0
            private set

        override suspend fun streamRound(params: StreamRoundParams): StreamRoundResult {
            calls += params
            return results.removeFirst()
        }

        override suspend fun requestToolApproval(
            toolName: String,
            toolCallId: String,
            argsPreview: String,
            args: Map<String, Any?>,
        ): ToolApprovalState = ToolApprovalState.Approved()

        override fun onToolLoopError(type: ChatErrorType, message: String, recoverable: Boolean) = Unit

        override suspend fun drainSteeringMessages(): List<String> {
            if (!overrideSteering) return super.drainSteeringMessages()
            drainCount++
            onDrain()
            return if (pendingSteering.isEmpty()) emptyList() else pendingSteering.removeFirst()
        }
    }

    private fun steerableProjection(history: List<UIMessage>): List<Triple<MessageRole, String, String?>> =
        history.map { Triple(it.role, it.content, it.toolCallId) }

    @Test
    fun `steering message enters next round as user message after tool results`() = runBlocking {
        val toolExecuted = AtomicInteger(0)
        // 取件时机：drain 被调用时，本轮工具必须已经执行完（插话不打断正在跑的工具）。
        var executedAtDrain = -1
        val host =
            SteeringHost(
                results = ArrayDeque(listOf(toolRound("c1"), finalRound())),
                steering = listOf(listOf("等等，别改那个文件")),
                onDrain = { executedAtDrain = toolExecuted.get() },
            )
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers {
            toolExecuted.incrementAndGet()
            "3"
        }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator().runLoop(params(), history, host, accessor, coordinator)

        assertTrue(result.success)
        assertEquals(1, host.drainCount)
        assertEquals(2, host.calls.size)

        val request = host.calls[1].history
        val lastToolIndex = request.indexOfLast { it.role == MessageRole.TOOL }
        val steeringIndex = request.indexOfLast { it.role == MessageRole.USER && it.content == "等等，别改那个文件" }
        assertTrue("插话必须出现在下一轮请求里", steeringIndex >= 0)
        assertTrue("插话必须排在全部 tool 结果之后", steeringIndex > lastToolIndex)
        assertEquals("插话必须落在请求末尾", request.size - 1, steeringIndex)
        // 插话不得拆散 assistant(tool_calls) 与它的 tool 结果
        assertEquals(1, request.count { it.role == MessageRole.TOOL && it.toolCallId == "c1" })
        assertTrue(request.any { it.role == MessageRole.TOOL && it.content == "3" })

        // 工具只执行了一次，且是在取插话之前完成 —— 插话没有打断它
        assertEquals(1, toolExecuted.get())
        assertEquals(1, executedAtDrain)

        // 落进本轮 history 的插话同样在 tool 结果之后
        val historyLastTool = history.indexOfLast { it.role == MessageRole.TOOL }
        val historySteering = history.indexOfLast { it.content == "等等，别改那个文件" }
        assertTrue(historySteering > historyLastTool)
    }

    @Test
    fun `without steering history is identical to the default empty channel`() = runBlocking {
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers { "3" }

        val defaultHistory = mutableListOf<UIMessage>()
        val defaultHost = SteeringHost(
            results = ArrayDeque(listOf(toolRound("c1"), finalRound())),
            overrideSteering = false,
        )
        orchestrator().runLoop(params(), defaultHistory, defaultHost, accessor, coordinator)

        val explicitEmptyHistory = mutableListOf<UIMessage>()
        val explicitEmptyHost = SteeringHost(
            results = ArrayDeque(listOf(toolRound("c1"), finalRound())),
            steering = listOf(emptyList()),
        )
        orchestrator().runLoop(params(), explicitEmptyHistory, explicitEmptyHost, accessor, coordinator)

        // 空实现不产生任何额外消息：角色/正文/toolCallId 序列逐条一致，且没有 USER 消息
        assertEquals(steerableProjection(defaultHistory), steerableProjection(explicitEmptyHistory))
        assertFalse(defaultHistory.any { it.role == MessageRole.USER })
        assertEquals(0, defaultHost.drainCount)
        // 空实现下下一轮请求与改动前同形：assistant(tool_calls) + TOOL
        val request = defaultHost.calls[1].history
        assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.TOOL), request.map { it.role })
    }

    @Test
    fun `multiple steering messages keep their order between rounds`() = runBlocking {
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers { "3" }
        val host = SteeringHost(
            results = ArrayDeque(listOf(toolRound("c1"), finalRound())),
            steering = listOf(listOf("先别动 utils.kt", "改成只读检查")),
        )

        val history = mutableListOf<UIMessage>()
        val result: ToolLoopResult = orchestrator().runLoop(params(), history, host, accessor, coordinator)

        assertTrue(result.success)
        val users = host.calls[1].history.filter { it.role == MessageRole.USER }.map { it.content }
        assertEquals(listOf("先别动 utils.kt", "改成只读检查"), users)
    }

    private companion object {
        const val SESSION_ID = "session-steering"
    }
}
