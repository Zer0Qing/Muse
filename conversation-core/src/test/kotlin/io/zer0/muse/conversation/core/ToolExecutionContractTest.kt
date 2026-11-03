package io.zer0.muse.conversation.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolExecutionContractTest {
    @Test
    fun `denied approval never reaches execution`() = runTest {
        val tool = RecordingToolExecutor()
        val coordinator = ToolExecutionCoordinator(approval = { ApprovalDecision.Denied })

        val result = coordinator.execute(toolCall("write-file"), tool)

        assertEquals(ToolOutcome.Denied, result)
        assertEquals(0, tool.executions)
    }

    @Test
    fun `unknown side effect is recorded once and not retried`() = runTest {
        val tool = RecordingToolExecutor(outcome = ToolOutcome.Unknown)
        val coordinator = ToolExecutionCoordinator(approval = { ApprovalDecision.Approved })

        val first = coordinator.execute(toolCall("send-message"), tool)
        val second = coordinator.execute(toolCall("send-message"), tool)

        assertEquals(ToolOutcome.Unknown, first)
        assertEquals(ToolOutcome.AlreadyAttempted, second)
        assertEquals(1, tool.executions)
    }

    @Test
    fun `a2a policy remains text-only`() {
        val tools = listOf(ToolDescriptor("device_shell", ToolRisk.High))

        assertEquals(emptyList<ToolDescriptor>(), EntryPolicy.A2a.allowedTools(tools))
    }

    private fun toolCall(toolCallId: String) = ToolInvocation(
        toolCallId = toolCallId,
        toolName = "test",
        argumentsJson = "{}",
        sideEffectUnknown = false,
    )

    private class RecordingToolExecutor(
        private val outcome: ToolOutcome = ToolOutcome.Succeeded("ok"),
    ) : ToolExecutor {
        var executions = 0
            private set

        override suspend fun execute(invocation: ToolInvocation): ToolOutcome {
            executions++
            return outcome
        }
    }
}
