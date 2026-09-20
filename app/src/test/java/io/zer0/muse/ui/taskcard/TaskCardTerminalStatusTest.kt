package io.zer0.muse.ui.taskcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3: 任务卡新增终态(CANCELLED / TIMED_OUT)的定向测试。
 *
 * 锁定:
 *  - 新状态是终态,能让任务卡结束;
 *  - 超时可重试、取消不主动提示重试;
 *  - 进度仍只统计 SUCCESS(既有语义不变)。
 */
class TaskCardTerminalStatusTest {

    private fun step(id: String, status: TaskStepStatus) =
        TaskStep(id = id, title = id, status = status)

    @Test
    fun `TIMED_OUT 与 CANCELLED 都是终态`() {
        assertTrue(TaskStepStatus.SUCCESS.isTerminal)
        assertTrue(TaskStepStatus.FAILED.isTerminal)
        assertTrue(TaskStepStatus.TIMED_OUT.isTerminal)
        assertTrue(TaskStepStatus.CANCELLED.isTerminal)
        assertFalse(TaskStepStatus.PENDING.isTerminal)
        assertFalse(TaskStepStatus.RUNNING.isTerminal)
    }

    @Test
    fun `TIMED_OUT 可重试而 CANCELLED 不主动提示重试`() {
        assertTrue(TaskStepStatus.FAILED.isRetryable)
        assertTrue(TaskStepStatus.TIMED_OUT.isRetryable)
        assertFalse(TaskStepStatus.CANCELLED.isRetryable)
        assertFalse(TaskStepStatus.SUCCESS.isRetryable)
        assertFalse(TaskStepStatus.PENDING.isRetryable)
    }

    @Test
    fun `含超时步骤的任务卡到达终态,超时不计入进度`() {
        val card = TaskCardData(
            id = "c1",
            title = "t",
            steps = listOf(
                step("s0", TaskStepStatus.SUCCESS),
                step("s1", TaskStepStatus.TIMED_OUT),
            ),
        )
        assertTrue(card.isAllDone)
        assertTrue(card.hasFailedSteps)
        assertEquals(1, card.timedOutSteps)
        assertEquals(0, card.cancelledSteps)
        assertEquals(0.5f, card.progress, 0.001f)
    }

    @Test
    fun `含取消步骤的任务卡到达终态且无可重试步骤`() {
        val card = TaskCardData(
            id = "c2",
            title = "t",
            steps = listOf(
                step("s0", TaskStepStatus.CANCELLED),
                step("s1", TaskStepStatus.CANCELLED),
            ),
        )
        assertTrue(card.isAllDone)
        assertFalse(card.hasFailedSteps)
        assertEquals(2, card.cancelledSteps)
        assertEquals(0, card.timedOutSteps)
        assertEquals(0f, card.progress, 0.001f)
    }

    @Test
    fun `运行中步骤不算终态`() {
        val card = TaskCardData(
            id = "c3",
            title = "t",
            steps = listOf(
                step("s0", TaskStepStatus.SUCCESS),
                step("s1", TaskStepStatus.RUNNING),
            ),
        )
        assertFalse(card.isAllDone)
    }

    @Test
    fun `枚举追加新值不改变既有值的序号`() {
        // 持久化/兼容性护栏:既有枚举值顺序必须保持不变,新值只能追加。
        assertEquals(0, TaskStepStatus.PENDING.ordinal)
        assertEquals(1, TaskStepStatus.RUNNING.ordinal)
        assertEquals(2, TaskStepStatus.SUCCESS.ordinal)
        assertEquals(3, TaskStepStatus.FAILED.ordinal)
        assertEquals(4, TaskStepStatus.CANCELLED.ordinal)
        assertEquals(5, TaskStepStatus.TIMED_OUT.ordinal)
    }
}
