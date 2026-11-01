package io.zer0.muse.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定时任务巡检的等待时长。
 *
 * 旧实现是固定 60s 空转：没有到期任务也每分钟醒一次，每次还要 GlobalContext 查找 +
 * DataStore 读 + 两条 DB 查询。改成「按最近到期时间睡，但**只允许比 60s 短**」之后，
 * 这里把边界钉死 —— 尤其"不能睡得比 60s 更久"这条：新建一个 5 秒后执行的任务时，
 * 固定轮询本来最多也就等 60s，睡更久就是行为回退。
 */
class ScheduledTaskPollDelayTest {

    private val now = 1_000_000L
    private val poll = 60_000L
    private val minDelay = 1_000L

    private fun delay(nextDueAt: Long?) = ScheduledTaskRunner.computePollDelay(
        now = now,
        nextDueAt = nextDueAt,
        pollInterval = poll,
        minDelay = minDelay,
    )

    @Test
    fun `no_task_keeps_the_minute_heartbeat`() {
        assertEquals(poll, delay(null))
    }

    @Test
    fun `already_due_task_waits_only_the_minimum`() {
        assertEquals(minDelay, delay(now - 5_000))
        assertEquals(minDelay, delay(now))
    }

    @Test
    fun `near_due_task_sleeps_exactly_until_then`() {
        assertEquals(10_000L, delay(now + 10_000))
        // 到期时间只差 1ms：仍要尊重下限，不能 0 延迟空转
        assertEquals(minDelay, delay(now + 1))
    }

    @Test
    fun `far_due_task_is_capped_at_the_poll_interval`() {
        assertEquals(poll, delay(now + 5 * 60_000))
        assertEquals(poll, delay(now + poll))
    }

    @Test
    fun `delay_never_exceeds_poll_interval_nor_drops_below_minimum`() {
        val probes = listOf(-100_000L, 0L, 1L, 500L, 1_000L, 59_999L, 60_000L, 3_600_000L)
        probes.forEach { offset ->
            val value = delay(now + offset)
            assertTrue("延迟必须 ≥ 下限：offset=$offset value=$value", value >= minDelay)
            assertTrue("延迟必须 ≤ 轮询上限：offset=$offset value=$value", value <= poll)
        }
    }
}
