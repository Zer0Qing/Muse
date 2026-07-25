package io.zer0.muse.ui.taskcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.56: AgentPlan 数据类单元测试。
 *
 * 验证计算属性:completedSteps / failedSteps / progress / isAllDone。
 */
class AgentPlanTest {

    @Test
    fun `空步骤 progress 为 0 且 isAllDone 为 false`() {
        val plan = AgentPlan(id = "p1", title = "test", steps = emptyList())
        assertEquals(0, plan.totalSteps)
        assertEquals(0, plan.completedSteps)
        assertEquals(0f, plan.progress, 0.001f)
        assertFalse(plan.isAllDone)
    }

    @Test
    fun `全部 PENDING progress 为 0`() {
        val plan = AgentPlan(
            id = "p1",
            title = "test",
            steps = listOf(
                AgentPlanStep(id = "s0", title = "步骤1", status = AgentPlanStepStatus.PENDING),
                AgentPlanStep(id = "s1", title = "步骤2", status = AgentPlanStepStatus.PENDING),
                AgentPlanStep(id = "s2", title = "步骤3", status = AgentPlanStepStatus.PENDING),
            ),
        )
        assertEquals(3, plan.totalSteps)
        assertEquals(0, plan.completedSteps)
        assertEquals(0f, plan.progress, 0.001f)
        assertFalse(plan.isAllDone)
    }

    @Test
    fun `部分完成 progress 正确`() {
        val plan = AgentPlan(
            id = "p1",
            title = "test",
            steps = listOf(
                AgentPlanStep(id = "s0", title = "步骤1", status = AgentPlanStepStatus.DONE),
                AgentPlanStep(id = "s1", title = "步骤2", status = AgentPlanStepStatus.IN_PROGRESS),
                AgentPlanStep(id = "s2", title = "步骤3", status = AgentPlanStepStatus.PENDING),
            ),
        )
        assertEquals(1, plan.completedSteps)
        assertEquals(1, plan.inProgressSteps)
        assertEquals(0.333f, plan.progress, 0.01f)
        assertFalse(plan.isAllDone)
    }

    @Test
    fun `全部完成 isAllDone 为 true`() {
        val plan = AgentPlan(
            id = "p1",
            title = "test",
            steps = listOf(
                AgentPlanStep(id = "s0", title = "步骤1", status = AgentPlanStepStatus.DONE),
                AgentPlanStep(id = "s1", title = "步骤2", status = AgentPlanStepStatus.DONE),
            ),
        )
        assertEquals(2, plan.completedSteps)
        assertEquals(1f, plan.progress, 0.001f)
        assertTrue(plan.isAllDone)
        assertEquals(0, plan.failedSteps)
    }

    @Test
    fun `有失败步骤 isAllDone 仍为 true`() {
        val plan = AgentPlan(
            id = "p1",
            title = "test",
            steps = listOf(
                AgentPlanStep(id = "s0", title = "步骤1", status = AgentPlanStepStatus.DONE),
                AgentPlanStep(id = "s1", title = "步骤2", status = AgentPlanStepStatus.FAILED),
            ),
        )
        assertTrue(plan.isAllDone)
        assertEquals(1, plan.failedSteps)
        assertEquals(1, plan.completedSteps)
        assertTrue(plan.failedSteps > 0)
    }

    @Test
    fun `SKIPPED 不算完成也不算失败`() {
        val plan = AgentPlan(
            id = "p1",
            title = "test",
            steps = listOf(
                AgentPlanStep(id = "s0", title = "步骤1", status = AgentPlanStepStatus.DONE),
                AgentPlanStep(id = "s1", title = "步骤2", status = AgentPlanStepStatus.SKIPPED),
            ),
        )
        assertEquals(1, plan.completedSteps)
        assertEquals(0, plan.failedSteps)
        // SKIPPED 不算 SUCCESS 也不算 FAILED,所以 isAllDone 应为 true(DONE 或 FAILED)
        assertTrue(plan.isAllDone)
    }
}
