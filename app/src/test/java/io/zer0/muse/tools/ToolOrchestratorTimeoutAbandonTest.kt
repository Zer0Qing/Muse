package io.zer0.muse.tools

import android.content.Context
import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.ChatSessionState
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.chat.ChatStateAccessor
import io.zer0.muse.ui.chat.ChatTaskCardCoordinator
import io.zer0.muse.ui.taskcard.TaskStepStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * P2-33: 阻塞型工具超时后必须切断回写。
 *
 * 场景:工具执行体忽略线程中断并延迟返回(WebView / 阻塞 IO 的真实行为)。
 * 旧实现只做 `future.cancel(true)`,结果「无人读取」但仍可达;修复后结果经
 * [ToolResultGate] 发布,超时先行作废,迟到结果被丢弃,TaskCard / 消息 / toolCallHistory
 * 都不得留下迟到结果的痕迹,状态保持 TIMED_OUT 不被迟到完成覆盖。
 */
class ToolOrchestratorTimeoutAbandonTest {

    private lateinit var registry: ToolRegistry
    private lateinit var skillRepository: SkillRepository
    private lateinit var skillExecutor: SkillExecutor
    private lateinit var assistantRepository: AssistantRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        registry = mockk(relaxed = true)
        skillRepository = mockk(relaxed = true)
        skillExecutor = mockk(relaxed = true)
        assistantRepository = mockk(relaxed = true)
        sessionRepository = mockk(relaxed = true)
        context = mockk(relaxed = true)
    }

    /** 真实 ChatUiState 支撑的 accessor:工具卡 / toolCallHistory / 消息可读可断言。 */
    private class RecordingAccessor : ChatStateAccessor {
        @Volatile
        private var state: ChatUiState = ChatUiState(
            sessionState = ChatSessionState(currentSessionId = "session-1"),
        )

        @Volatile
        var messages: List<UIMessage> = emptyList()
            private set

        override val snapshot: ChatUiState get() = state

        override fun update(transform: (ChatUiState) -> ChatUiState) {
            synchronized(this) { state = transform(state) }
        }

        override val messagesSnapshot: List<UIMessage> get() = messages

        override fun updateMessages(transform: (List<UIMessage>) -> List<UIMessage>) {
            synchronized(this) { messages = transform(messages) }
        }

        override val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)
    }

    private class FakeToolLoopHost(private val results: ArrayDeque<StreamRoundResult>) : ToolLoopHost {
        override suspend fun streamRound(params: StreamRoundParams): StreamRoundResult = results.removeFirst()

        override suspend fun requestToolApproval(
            toolName: String,
            toolCallId: String,
            argsPreview: String,
            args: Map<String, Any?>,
        ): ToolApprovalState = ToolApprovalState.Approved()

        override fun onToolLoopError(type: ChatErrorType, message: String, recoverable: Boolean) = Unit
    }

    private fun orchestrator(timeoutMs: Long) = ToolOrchestrator(
        toolRegistry = registry,
        skillRepository = skillRepository,
        skillExecutor = skillExecutor,
        assistantRepository = assistantRepository,
        sessionRepository = sessionRepository,
        context = context,
        toolTimeoutMs = timeoutMs,
    )

    private fun params() = ToolLoopParams(
        sessionId = "session-1",
        // turnId 非空 → 工具轮结构化记录(toolCallRecord / ToolRoundEntity)才会落库
        turnId = "turn-1",
        initialAssistantId = kotlin.uuid.Uuid.random(),
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

    private fun toolRound(toolCalls: List<ToolCall>): StreamRoundResult.Success = StreamRoundResult.Success(
        assistantMessage = UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = toolCalls),
        hasToolCalls = toolCalls.isNotEmpty(),
        contentLength = 0,
        firstTokenTime = 0L,
    )

    private fun finalRound(): StreamRoundResult.Success = StreamRoundResult.Success(
        assistantMessage = UIMessage(role = MessageRole.ASSISTANT, content = "done"),
        hasToolCalls = false,
        contentLength = 0,
        firstTokenTime = 0L,
    )

    @Test
    fun `late result of interrupt-ignoring tool is dropped and never pollutes state`() = runBlocking {
        val latePayload = "LATE_PAYLOAD_MUST_NOT_LAND"
        val toolCallId = "t-late-1"
        val returned = CountDownLatch(1)

        // 忽略中断、延迟返回的执行体:模拟 WebView 等不可中断的阻塞调用
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers {
            val deadline = System.currentTimeMillis() + 600
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    // 关键:吞掉中断,继续执行 —— cancel(true) 对它无效
                }
            }
            returned.countDown()
            latePayload
        }

        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(listOf(ToolCall(toolCallId, "calculator", """{"expression":"1"}"""))),
                    finalRound(),
                ),
            ),
        )
        val accessor = RecordingAccessor()
        val coordinator = ChatTaskCardCoordinator(accessor, registry)
        val orchestrator = orchestrator(timeoutMs = 60L)
        // 工具轮结构化记录(ToolRoundEntity)落库捕获 → 断言 toolCallRecord 不被迟到结果污染
        val persistedRounds = java.util.Collections.synchronizedList(
            mutableListOf<io.zer0.muse.data.session.ToolRoundEntity>(),
        )
        coEvery { sessionRepository.upsertToolRound(any()) } answers { persistedRounds.add(firstArg()) }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator.runLoop(params(), history, host, accessor, coordinator)

        // 1) 超时语义保持:给 LLM/UI 的是 [超时] 终态
        val toolMessage = history.first { it.role == MessageRole.TOOL }
        assertTrue(toolMessage.content.startsWith("[超时]"))
        assertEquals(ToolOrchestrator.ToolExecStatus.TIMED_OUT.name, result.toolRounds.single().status)
        val cardAtTimeout = accessor.snapshot.taskCards.values.single()
        assertEquals(TaskStepStatus.TIMED_OUT, cardAtTimeout.steps.single().status)

        // 2) 迟到执行确实跑完了(忽略中断),结果被闸门丢弃
        assertTrue("阻塞工具应在超时后仍然跑完", returned.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) {
            while (orchestrator.droppedLateResultCount() == 0) delay(10)
        }
        assertEquals(1, orchestrator.droppedLateResultCount())
        assertTrue("超时后应登记该 toolCallId", orchestrator.isToolCallAbandoned(toolCallId))

        // 3) 迟到结果不得写入任何状态:历史 / 消息 / toolCallHistory / TaskCard
        assertFalse("迟到结果不得进入对话历史", history.any { it.content.contains(latePayload) })
        assertFalse(
            "迟到结果不得进入 UI 消息",
            accessor.messages.any { msg ->
                msg.content.contains(latePayload) || msg.toolCallInfo?.result?.contains(latePayload) == true
            },
        )
        assertFalse(
            "迟到结果不得写入 toolCallRecord",
            accessor.snapshot.toolCallHistory.any { it.result.contains(latePayload) },
        )
        assertTrue("超时轮次应落库", persistedRounds.isNotEmpty())
        assertFalse(
            "迟到结果不得写入工具轮结构化记录",
            persistedRounds.any { it.resultJson?.contains(latePayload) == true },
        )
        assertTrue(
            "落库终态应为 TIMED_OUT",
            persistedRounds.any { it.status == ToolOrchestrator.ToolExecStatus.TIMED_OUT.name },
        )
        val cardAfterLate = accessor.snapshot.taskCards.values.single()
        assertEquals("迟到完成不得覆盖 TIMED_OUT 终态", TaskStepStatus.TIMED_OUT, cardAfterLate.steps.single().status)
        assertFalse(cardAfterLate.steps.single().result.contains(latePayload))
        assertTrue(cardAfterLate.steps.single().result.contains("超时"))
    }

    @Test
    fun `timeout before completion still returns the gate value when tool finishes in time`() = runBlocking {
        // 反向对照:未超时(足够的超时窗口)时结果照常发布,闸门不误杀
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers { "3" }

        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(listOf(ToolCall("t-ok-1", "calculator", """{"expression":"1+2"}"""))),
                    finalRound(),
                ),
            ),
        )
        val accessor = RecordingAccessor()
        val coordinator = ChatTaskCardCoordinator(accessor, registry)
        val orchestrator = orchestrator(timeoutMs = 5_000L)

        val history = mutableListOf<UIMessage>()
        val result = orchestrator.runLoop(params(), history, host, accessor, coordinator)

        assertTrue(result.success)
        assertEquals("3", history.first { it.role == MessageRole.TOOL }.content)
        assertEquals(ToolOrchestrator.ToolExecStatus.SUCCESS.name, result.toolRounds.single().status)
        assertEquals(0, orchestrator.droppedLateResultCount())
        assertFalse(orchestrator.isToolCallAbandoned("t-ok-1"))
    }


}
