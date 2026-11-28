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
import io.zer0.muse.ui.chat.ChatErrorType
import io.zer0.muse.ui.chat.ChatStateAccessor
import io.zer0.muse.ui.chat.ChatTaskCardCoordinator
import io.zer0.muse.ui.chat.ChatUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class ToolOrchestratorPreflightTest {

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
        every { snapshot.currentSessionId } returns "session-preflight"
        every { snapshot.agentSessionId } returns null
        accessor = mockk(relaxed = true)
        every { accessor.snapshot } returns snapshot

        coordinator = mockk(relaxed = true)
        every { coordinator.markTaskStepRunningIfNotPaused(any(), any(), any(), any()) } returns true
    }

    @Test
    fun `channel preflight block short circuits before requesting user approval`() = runBlocking {
        val orchestrator = ToolOrchestrator(
            toolRegistry = registry,
            skillRepository = skillRepository,
            skillExecutor = skillExecutor,
            assistantRepository = assistantRepository,
            sessionRepository = sessionRepository,
            context = context,
        )
        var approvalCalls = 0
        var executeCalls = 0
        coEvery { registry.executeFromJson(any(), any()) } coAnswers {
            executeCalls++
            "{}"
        }

        val rounds = ArrayDeque(
            listOf(
                StreamRoundResult.Success(
                    assistantMessage = UIMessage(
                        role = MessageRole.ASSISTANT,
                        content = "",
                        toolCalls = listOf(ToolCall("c1", "screen_tap", """{"x":10,"y":20}""")),
                    ),
                    hasToolCalls = true,
                    contentLength = 0,
                    firstTokenTime = 0L,
                ),
                StreamRoundResult.Success(
                    assistantMessage = UIMessage(role = MessageRole.ASSISTANT, content = "已告知用户"),
                    hasToolCalls = false,
                    contentLength = 0,
                    firstTokenTime = 0L,
                ),
            ),
        )
        val host = object : ToolLoopHost {
            override suspend fun streamRound(params: StreamRoundParams): StreamRoundResult = rounds.removeFirst()

            override suspend fun requestToolApproval(
                toolName: String,
                toolCallId: String,
                argsPreview: String,
                args: Map<String, Any?>,
            ): ToolApprovalState {
                approvalCalls++
                return ToolApprovalState.Approved()
            }

            override suspend fun availableDeviceChannels(): Set<ToolRequirements.Channel>? = emptySet()

            override suspend fun automationEnabled(): Boolean = false

            override fun onToolLoopError(type: ChatErrorType, message: String, recoverable: Boolean) = Unit
        }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator.runLoop(
            params = ToolLoopParams(
                sessionId = "session-preflight",
                initialAssistantId = Uuid.random(),
                baseHistorySize = 0,
                maxRounds = 4,
                tools = emptyList(),
                skillMap = emptyMap(),
                model = null,
                providerConfig = null,
                temperature = null,
                maxTokens = null,
                reasoningLevel = ReasoningLevel.OFF,
            ),
            conversationHistory = history,
            host = host,
            accessor = accessor,
            taskCardCoordinator = coordinator,
        )

        assertTrue(result.success)
        assertEquals("通道预检拦截后不得触发用户审批", 0, approvalCalls)
        assertEquals("通道预检拦截后不得执行工具", 0, executeCalls)
        val toolResult = history.single { it.role == MessageRole.TOOL }
        assertTrue(toolResult.content.contains("自动化") || toolResult.content.contains("无障碍"))
    }
}
