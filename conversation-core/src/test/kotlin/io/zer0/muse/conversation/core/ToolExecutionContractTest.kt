package io.zer0.muse.conversation.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `无人等待的入口一律拿不到高副作用工具`() {
        val tools = listOf(
            ToolDescriptor("get_time", ToolRisk.Safe),
            ToolDescriptor("web_search", ToolRisk.Normal),
            ToolDescriptor("screen_tap", ToolRisk.High),
        )

        // 这三个入口的共性是"没有人在输入框前面"：出事时没人能当场叫停
        listOf(EntryPolicy.Channel, EntryPolicy.Scheduled, EntryPolicy.Proactive).forEach { entry ->
            assertEquals(
                "$entry 应滤掉 High 风险工具",
                listOf("get_time", "web_search"),
                entry.allowedTools(tools).map { it.name },
            )
        }
        assertEquals(
            "Proactive 要与 Scheduled 同档（原先它和 MainChat 一样全放行）",
            EntryPolicy.Scheduled.allowedTools(tools),
            EntryPolicy.Proactive.allowedTools(tools),
        )
    }

    @Test
    fun `有人在场的入口全量给 远程入口一个都不给`() {
        val tools = listOf(
            ToolDescriptor("get_time", ToolRisk.Safe),
            ToolDescriptor("screen_tap", ToolRisk.High),
        )

        listOf(EntryPolicy.MainChat, EntryPolicy.Agent).forEach { entry ->
            assertEquals("$entry 应全量给（审批闸在下游）", 2, entry.allowedTools(tools).size)
        }
        assertTrue(EntryPolicy.A2a.allowedTools(tools).isEmpty())
    }

    @Test
    fun `一次失败的调用同样占住 toolCallId 重试要换新调用号`() = runTest {
        // 这不是"顺手实现成这样"，是明确取舍：工具报失败时副作用可能已经发生一半
        // （消息发出去一半、文件写了一半），自动重跑一次的代价通常比拒绝重试大。
        // 因此这里把语义钉住：真要重试必须由上层发一个新的 toolCallId。
        val tool = RecordingToolExecutor(outcome = ToolOutcome.Failed("网络断了"))
        val coordinator = ToolExecutionCoordinator(approval = { ApprovalDecision.Approved })

        val first = coordinator.execute(toolCall("call-1"), tool)
        val second = coordinator.execute(toolCall("call-1"), tool)
        val retryWithNewId = coordinator.execute(toolCall("call-2"), tool)

        assertEquals(ToolOutcome.Failed("网络断了"), first)
        assertEquals("同一个调用号不重跑", ToolOutcome.AlreadyAttempted, second)
        assertEquals("换调用号可以再试", ToolOutcome.Failed("网络断了"), retryWithNewId)
        assertEquals(2, tool.executions)
    }

    @Test
    fun `去重记录有上限 挤掉的最早调用号可以再跑而最近的仍被拦住`() = runTest {
        // 不用反射看内部集合：有界这件事从行为上就证得出来 ——
        // 最老的号被挤出去后允许再跑，最近的号仍然拦得住。
        val tool = RecordingToolExecutor()
        val coordinator = ToolExecutionCoordinator(approval = { ApprovalDecision.Approved })
        val limit = ToolExecutionCoordinator.MAX_ATTEMPTED_CALLS
        val total = limit + 50

        (1..total).forEach { index -> coordinator.execute(toolCall("call-$index"), tool) }
        assertEquals("每次都真跑了一遍", total, tool.executions)

        val newest = coordinator.execute(toolCall("call-$total"), tool)
        val oldest = coordinator.execute(toolCall("call-1"), tool)

        assertEquals("最近的号还在去重表里", ToolOutcome.AlreadyAttempted, newest)
        assertNotEquals("最老的号已被挤出（有意的取舍，见字段注释）", ToolOutcome.AlreadyAttempted, oldest)
        assertEquals(total + 1, tool.executions)
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
