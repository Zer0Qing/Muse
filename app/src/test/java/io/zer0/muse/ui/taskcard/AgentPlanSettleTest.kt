package io.zer0.muse.ui.taskcard

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCallInfo
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.4: 计划卡终态收尾 —— 未结束步骤必须落成 CANCELLED，否则卡片永远转圈。
 */
class AgentPlanSettleTest {

    private fun planOf(vararg statuses: AgentPlanStepStatus): AgentPlan =
        AgentPlan(
            id = "plan-1",
            title = "测试计划",
            steps = statuses.mapIndexed { index, status ->
                AgentPlanStep(id = "step-$index", title = "步骤 $index", status = status)
            },
        )

    @Test
    fun settle_marks_pending_and_in_progress_as_cancelled() {
        val settled = planOf(
            AgentPlanStepStatus.DONE,
            AgentPlanStepStatus.IN_PROGRESS,
            AgentPlanStepStatus.PENDING,
            AgentPlanStepStatus.FAILED,
        ).settleAsCancelled(now = 1_000L)

        assertEquals(AgentPlanStepStatus.DONE, settled.steps[0].status)
        assertEquals(AgentPlanStepStatus.CANCELLED, settled.steps[1].status)
        assertEquals(AgentPlanStepStatus.CANCELLED, settled.steps[2].status)
        assertEquals(AgentPlanStepStatus.FAILED, settled.steps[3].status)
        assertTrue("收尾后必须已全部终结", settled.isAllSettled)
    }

    @Test
    fun settle_stamps_finishedAt_only_when_unset() {
        val settled = AgentPlan(
            id = "plan-1",
            title = "t",
            steps = listOf(
                AgentPlanStep(id = "a", title = "a", status = AgentPlanStepStatus.IN_PROGRESS),
                AgentPlanStep(id = "b", title = "b", status = AgentPlanStepStatus.PENDING, finishedAt = 555L),
            ),
        ).settleAsCancelled(now = 1_000L)

        assertEquals(1_000L, settled.steps[0].finishedAt)
        assertEquals(555L, settled.steps[1].finishedAt)
    }

    @Test
    fun settle_is_a_noop_when_all_steps_already_settled() {
        val original = planOf(AgentPlanStepStatus.DONE, AgentPlanStepStatus.SKIPPED)
        assertTrue(original.isAllSettled)
        assertEquals(original, original.settleAsCancelled())
    }

    @Test
    fun history_replay_settles_unfinished_steps() {
        val planMessage = toolMessage(
            at = 100L,
            name = "task_plan",
            arguments = """{"title":"历史计划","steps":[{"title":"第一步"},{"title":"第二步"}]}""",
            result = "计划已创建。planId: plan-h1",
        )
        val updateMessage = toolMessage(
            at = 200L,
            name = "update_plan_step",
            arguments = """{"planId":"plan-h1","stepIndex":0,"status":"done"}""",
            result = "步骤 0 已更新",
        )

        val plan = restoreAgentPlansFromHistory(listOf(planMessage, updateMessage)).getValue("plan-h1")
        // 第一步已完成保留，第二步（从未更新，仍是 PENDING）被收尾为 CANCELLED
        assertEquals(AgentPlanStepStatus.DONE, plan.steps[0].status)
        assertEquals(AgentPlanStepStatus.CANCELLED, plan.steps[1].status)
        assertTrue(plan.isAllSettled)
    }

    @Test
    fun history_replay_keeps_unfinished_steps_when_settle_disabled() {
        val planMessage = toolMessage(
            at = 100L,
            name = "task_plan",
            arguments = """{"title":"历史计划","steps":[{"title":"第一步"},{"title":"第二步"}]}""",
            result = "计划已创建。planId: plan-h2",
        )

        val plan = restoreAgentPlansFromHistory(listOf(planMessage), settleUnfinished = false).getValue("plan-h2")
        assertEquals(AgentPlanStepStatus.PENDING, plan.steps[0].status)
        assertEquals(AgentPlanStepStatus.PENDING, plan.steps[1].status)
    }

    private fun toolMessage(at: Long, name: String, arguments: String, result: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        content = "",
        createdAt = at,
        toolCallInfo = ToolCallInfo(
            toolName = name,
            arguments = arguments,
            result = result,
            isSuccess = true,
        ),
    )
}
