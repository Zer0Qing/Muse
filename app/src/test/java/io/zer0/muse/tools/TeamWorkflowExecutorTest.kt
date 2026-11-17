package io.zer0.muse.tools

import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatCompletion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamWorkflowExecutorTest {

    @Test
    fun conditionalDecisionFailureDoesNotExecuteTheSideEffectingNode() = runBlocking {
        val calls = mutableListOf<String>()
        val chatService = mockk<ChatService>()
        coEvery {
            // 用 any() 匹配可选参数：实际调用还带 reasoningLevel 等，
            // 写死具体参数会让 mock 在实现调整后静默失配（表现为「条件判断恒失败」）。
            chatService.completeText(
                messages = any(),
                temperature = any(),
                maxTokens = any(),
                reasoningLevel = any(),
            )
        } throws IllegalStateException("provider unavailable")

        val executor = TeamWorkflowExecutor(
            delegate = { request ->
                calls += request.requestId
                DelegationContract.DelegationResult(
                    requestId = request.requestId,
                    success = true,
                    resultText = "completed",
                )
            },
            chatService = chatService,
            concurrencyLimiter = AgentConcurrencyLimiter(),
        )

        val result = executor.execute(
            workflow = DelegationContract.TeamWorkflow(
                nodes = listOf(
                    node("prepare", "assistant-a"),
                    node(
                        id = "send",
                        assistantId = "assistant-b",
                        dependsOn = listOf("prepare"),
                        mode = DelegationContract.TeamWorkflowNode.Mode.CONDITIONAL,
                    ),
                ),
            ),
            teamTask = "run guarded action",
            parentRequestId = "team-1",
            teamMembers = emptyList(),
        )

        assertFalse(result.success)
        assertEquals(listOf("team-1/prepare"), calls)
        assertTrue(result.error.orEmpty().contains("条件"))
    }

    @Test
    fun conditionalNoSkipsDependentNodesInsteadOfExecutingThem() = runBlocking {
        val calls = mutableListOf<String>()
        val chatService = mockk<ChatService>()
        coEvery {
            // 用 any() 匹配可选参数：实际调用还带 reasoningLevel 等，
            // 写死具体参数会让 mock 在实现调整后静默失配（表现为「条件判断恒失败」）。
            chatService.completeText(
                messages = any(),
                temperature = any(),
                maxTokens = any(),
                reasoningLevel = any(),
            )
        } returns ChatCompletion(text = "NO")

        val executor = TeamWorkflowExecutor(
            delegate = { request ->
                calls += request.requestId
                DelegationContract.DelegationResult(
                    requestId = request.requestId,
                    success = true,
                    resultText = "completed",
                )
            },
            chatService = chatService,
            concurrencyLimiter = AgentConcurrencyLimiter(),
        )

        val result = executor.execute(
            workflow = DelegationContract.TeamWorkflow(
                nodes = listOf(
                    node("prepare", "assistant-a"),
                    node(
                        id = "guard",
                        assistantId = "assistant-b",
                        dependsOn = listOf("prepare"),
                        mode = DelegationContract.TeamWorkflowNode.Mode.CONDITIONAL,
                    ),
                    node(
                        id = "send",
                        assistantId = "assistant-c",
                        dependsOn = listOf("guard"),
                    ),
                ),
            ),
            teamTask = "run guarded action",
            parentRequestId = "team-2",
            teamMembers = emptyList(),
        )

        assertTrue(result.success)
        assertEquals(listOf("team-2/prepare"), calls)
        assertEquals(3, result.subResults.size)
        assertTrue(result.subResultTree.orEmpty().any { it.requestId == "team-2/guard" && it.status == "skipped" })
        assertTrue(result.subResultTree.orEmpty().any { it.requestId == "team-2/send" && it.status == "skipped" })
    }

    private fun node(
        id: String,
        assistantId: String,
        dependsOn: List<String> = emptyList(),
        mode: DelegationContract.TeamWorkflowNode.Mode = DelegationContract.TeamWorkflowNode.Mode.SEQUENTIAL,
    ): DelegationContract.TeamWorkflowNode = DelegationContract.TeamWorkflowNode(
        id = id,
        assistantId = assistantId,
        name = id,
        taskTemplate = id,
        dependsOn = dependsOn,
        mode = mode,
    )
}
