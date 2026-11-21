package io.zer0.muse.data.agentbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台任务的配额判定。
 *
 * 核心断言：**配额满时是"排队"，触到小时上限时是"拒绝"** ——
 * 两者对用户是不同的话，混成一句会让"等一下"听起来像"不行"。
 */
class BotResourceGovernorTest {

    private var clock = 1_000L

    private fun governor() = BotResourceGovernor(now = { clock })

    @Test
    fun `under the limit dispatches immediately`() {
        val g = governor()
        assertTrue(g.admit(runningCount = 0) is BotResourceGovernor.Verdict.Go)
        assertTrue(g.admit(runningCount = 1) is BotResourceGovernor.Verdict.Go)
    }

    @Test
    fun `at concurrency limit the task queues instead of being rejected`() {
        val g = governor()
        val verdict = g.admit(runningCount = BotResourceGovernor.DEFAULT_MAX_CONCURRENT)
        assertTrue(
            "并发满只是等一会儿，不是不能做",
            verdict is BotResourceGovernor.Verdict.Queue,
        )
    }

    @Test
    fun `hourly cap rejects rather than queues`() {
        val g = governor()
        repeat(BotResourceGovernor.DEFAULT_MAX_TASKS_PER_HOUR) {
            g.recordDispatch()
            clock += 100
        }
        val verdict = g.admit(runningCount = 0)
        assertTrue(
            "触到小时上限是一小时内不该再派，不是等一会儿",
            verdict is BotResourceGovernor.Verdict.Reject,
        )
    }

    @Test
    fun `dispatches older than an hour stop counting`() {
        val g = governor()
        repeat(BotResourceGovernor.DEFAULT_MAX_TASKS_PER_HOUR) {
            g.recordDispatch()
        }
        assertTrue(g.admit(0) is BotResourceGovernor.Verdict.Reject)

        // 一小时后窗口滑过去，配额恢复。
        clock += 3600_001L
        assertTrue(g.admit(0) is BotResourceGovernor.Verdict.Go)
    }

    @Test
    fun `overdue task is detectable`() {
        val g = governor()
        val startedAt = clock
        assertFalse(g.isOverdue(startedAt))

        clock += BotResourceGovernor.DEFAULT_MAX_TASK_DURATION_MS + 1
        assertTrue("超过单任务上限要能被发现", g.isOverdue(startedAt))
    }

    @Test
    fun `zero start time is never overdue`() {
        // 还没开始的 run 不该被判超时。
        assertFalse(governor().isOverdue(0L))
    }

    @Test
    fun `snapshot reflects running and dispatched counts`() {
        val g = governor()
        g.recordDispatch()
        g.recordDispatch()
        val snap = g.snapshot(runningCount = 1)

        assertEquals(1, snap.running)
        assertEquals(2, snap.dispatchedLastHour)
        assertEquals(BotResourceGovernor.DEFAULT_MAX_CONCURRENT, snap.maxConcurrent)
    }
}
