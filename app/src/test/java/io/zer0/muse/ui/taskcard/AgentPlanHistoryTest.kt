package io.zer0.muse.ui.taskcard

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCallInfo
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanHistoryTest {

    @Test
    fun replay_restores_plan_and_applies_persisted_step_updates() {
        val created = toolMessage(
            at = 100L,
            name = "task_plan",
            arguments = """{"title":"发布版本","steps":[{"title":"实现修复","description":"完成代码"}]}""",
            result = "计划已创建, planId: plan-1",
        )
        val updated = toolMessage(
            at = 300L,
            name = "update_plan_step",
            arguments = """{"planId":"plan-1","stepIndex":0,"status":"done","result":"已完成"}""",
            result = "步骤已更新",
        )

        val plans = restoreAgentPlansFromHistory(listOf(created, updated))
        val plan = plans.getValue("plan-1")

        assertEquals("发布版本", plan.title)
        assertEquals(created.id.toString(), plan.messageId)
        assertEquals(AgentPlanStepStatus.DONE, plan.steps.single().status)
        assertEquals("已完成", plan.steps.single().result)
        assertEquals(300L, plan.steps.single().finishedAt)
    }

    @Test
    fun replay_ignores_empty_plan_records_instead_of_rendering_empty_card() {
        val emptyPlan = toolMessage(
            at = 100L,
            name = "task_plan",
            arguments = """{"title":"空计划","steps":[]}""",
            result = "planId: empty-plan",
        )

        val plans = restoreAgentPlansFromHistory(listOf(emptyPlan))

        assertTrue(plans.isEmpty())
    }

    private fun toolMessage(at: Long, name: String, arguments: String, result: String) =
        UIMessage(
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
