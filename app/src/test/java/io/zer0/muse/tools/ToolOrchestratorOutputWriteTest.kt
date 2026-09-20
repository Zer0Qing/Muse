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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.uuid.Uuid

/**
 * Phase 3: 工具输出落盘超时/降级测试。
 *
 * 覆盖:
 *  - 超长输出正常落盘(路径 + read_file 引用出现在回填结果中);
 *  - 落盘超时 → 降级为内存截断(带明确提示),工具循环不被磁盘 IO 阻塞;
 *  - 落盘异常 → 降级为内存截断;
 *  - 未超长输出完全不落盘(默认行为不变)。
 *
 * 运行: ./gradlew :app:testDebugUnitTest --tests "*ToolOrchestratorOutputWriteTest*"
 */
class ToolOrchestratorOutputWriteTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var registry: ToolRegistry
    private lateinit var skillRepository: SkillRepository
    private lateinit var skillExecutor: SkillExecutor
    private lateinit var assistantRepository: AssistantRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var context: Context
    private lateinit var accessor: ChatStateAccessor
    private lateinit var coordinator: ChatTaskCardCoordinator

    private val bigOutput = "x".repeat(MAX_TOOL_RESULT_CHARS + 8_000)

    @Before
    fun setUp() {
        registry = mockk(relaxed = true)
        skillRepository = mockk(relaxed = true)
        skillExecutor = mockk(relaxed = true)
        every { skillExecutor.getActivePlans() } returns emptyMap()
        assistantRepository = mockk(relaxed = true)
        sessionRepository = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.filesDir } returns tempFolder.root

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

    private fun orchestrator(
        toolOutputWriteTimeoutMs: Long = TOOL_OUTPUT_WRITE_TIMEOUT_MS,
        writer: (suspend (File, String) -> Unit)? = null,
    ) = if (writer == null) {
        ToolOrchestrator(
            toolRegistry = registry,
            skillRepository = skillRepository,
            skillExecutor = skillExecutor,
            assistantRepository = assistantRepository,
            sessionRepository = sessionRepository,
            context = context,
            toolOutputWriteTimeoutMs = toolOutputWriteTimeoutMs,
        )
    } else {
        ToolOrchestrator(
            toolRegistry = registry,
            skillRepository = skillRepository,
            skillExecutor = skillExecutor,
            assistantRepository = assistantRepository,
            sessionRepository = sessionRepository,
            context = context,
            toolOutputWriteTimeoutMs = toolOutputWriteTimeoutMs,
            toolOutputWriter = writer,
        )
    }

    private fun params() = ToolLoopParams(
        sessionId = "session-1",
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

    private fun toolRound(toolCall: ToolCall): StreamRoundResult.Success =
        StreamRoundResult.Success(
            assistantMessage = UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(toolCall)),
            hasToolCalls = true,
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
        override suspend fun streamRound(params: StreamRoundParams): StreamRoundResult = results.removeFirst()

        override suspend fun requestToolApproval(
            toolName: String,
            toolCallId: String,
            argsPreview: String,
            args: Map<String, Any?>,
        ): ToolApprovalState = ToolApprovalState.Approved()

        override fun onToolLoopError(type: ChatErrorType, message: String, recoverable: Boolean) = Unit
    }

    private suspend fun runWithOutput(toolName: String, output: String, orchestrator: ToolOrchestrator): String {
        val host = FakeToolLoopHost(
            ArrayDeque(listOf(toolRound(ToolCall("o1", toolName, "{}")), finalRound())),
        )
        coEvery { registry.executeFromJson(toolName, any()) } coAnswers { output }
        val history = mutableListOf<UIMessage>()
        val result = orchestrator.runLoop(params(), history, host, accessor, coordinator)
        assertTrue(result.success)
        return history.single { it.role == MessageRole.TOOL }.content
    }

    @Test
    fun `oversized output is written to disk and referenced in result`() = runBlocking {
        val content = runWithOutput("read_file", bigOutput, orchestrator())

        assertTrue(content.contains("[工具输出已截断"))
        assertTrue(content.contains("[完整输出已保存到:"))
        assertTrue(content.contains("read_file 工具读取"))
        val files = File(tempFolder.root, TOOL_OUTPUTS_DIR).listFiles()
        assertEquals(1, files?.size)
        assertEquals(bigOutput, files!!.single().readText())
    }

    @Test
    fun `disk write timeout degrades to in-memory truncation`() = runBlocking {
        val orchestrator = orchestrator(
            toolOutputWriteTimeoutMs = 20L,
            writer = { _, _ -> delay(60_000) },
        )
        val startedAt = System.currentTimeMillis()

        val content = runWithOutput("read_file", bigOutput, orchestrator)

        assertTrue("超时应降级并给出提示,实际=${content.take(80)}", content.contains("落盘超时"))
        assertFalse(content.contains("[完整输出已保存到:"))
        // 工具循环不被 60s 慢写阻塞(超时 20ms 后立即降级)
        assertTrue(
            "落盘超时应快速降级,实际耗时=${System.currentTimeMillis() - startedAt}ms",
            System.currentTimeMillis() - startedAt < 5_000,
        )
    }

    @Test
    fun `disk write failure degrades to in-memory truncation`() = runBlocking {
        val orchestrator = orchestrator(
            writer = { _, _ -> throw java.io.IOException("disk full") },
        )

        val content = runWithOutput("read_file", bigOutput, orchestrator)

        assertTrue(content.contains("落盘失败"))
        assertTrue(content.contains("disk full"))
        assertFalse(content.contains("[完整输出已保存到:"))
    }

    @Test
    fun `small output never touches disk`() = runBlocking {
        val orchestrator = orchestrator(
            writer = { _, _ -> throw AssertionError("小输出不应写盘") },
        )

        val content = runWithOutput("echo", "small", orchestrator)

        assertEquals("small", content)
        assertFalse(File(tempFolder.root, TOOL_OUTPUTS_DIR).exists())
    }
}
