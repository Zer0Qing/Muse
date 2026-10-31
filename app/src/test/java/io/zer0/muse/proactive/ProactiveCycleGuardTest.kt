package io.zer0.muse.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主动消息单飞闸的单测。
 *
 * 这里只测「同一时刻能不能有两轮巡检」以及「尝试记录何时该留、何时该撤」，
 * 判定时机、生成、落库都不在这个类的职责里，混进来会让失败原因难以定位。
 *
 * 这套约束的失败模式是「用户收到两条几乎一样的消息」，
 * 不会抛异常、不会打错误日志，所以必须由测试盯着。
 */
class ProactiveCycleGuardTest {

    @Test
    fun `同一时刻只允许一轮进入`() {
        val guard = ProactiveCycleGuard()
        val first = guard.tryEnter(now = 1_000L)

        assertNotNull("第一次进入应当拿到凭据", first)
        assertNull("上一轮还在跑时第二次进入必须被拒", guard.tryEnter(now = 1_100L))
        assertTrue(guard.isInFlight)
    }

    @Test
    fun `退出之后下一轮可以进入`() {
        val guard = ProactiveCycleGuard()
        val entry = guard.tryEnter(now = 1_000L)!!
        guard.leave(entry, sent = false)

        assertFalse(guard.isInFlight)
        assertNotNull("上一轮结束后应当放行", guard.tryEnter(now = 2_000L))
    }

    @Test
    fun `凭据里带出上一轮的尝试时间`() {
        val guard = ProactiveCycleGuard()
        val first = guard.tryEnter(now = 1_000L)!!
        guard.markAttempt(1_500L)
        guard.leave(first, sent = true)

        val second = guard.tryEnter(now = 2_000L)
        assertEquals("本轮自己的进入时间", 2_000L, second?.enteredAt)
        assertEquals("判定要问的是上一轮什么时候试的", 1_500L, second?.previousAttemptAt)
    }

    @Test
    fun `首轮的上一轮尝试时间是零`() {
        val guard = ProactiveCycleGuard()
        assertEquals(0L, guard.tryEnter(now = 1_000L)?.previousAttemptAt)
    }

    @Test
    fun `发出了消息的那一轮留下尝试痕迹`() {
        // 「这一轮还在跑」和「这一轮跑过」是两件事。
        // 发出去之后若把尝试时间清掉，刚发过的那一轮在判定层就等于没发生过，
        // 下一轮立刻又会通过时机闸 —— 这正是重复发送的成因。
        val guard = ProactiveCycleGuard()
        val entry = guard.tryEnter(now = 1_000L)!!
        guard.markAttempt(1_200L)
        guard.leave(entry, sent = true)

        assertEquals(1_200L, guard.tryEnter(now = 5_000L)?.previousAttemptAt)
    }

    @Test
    fun `没发出去的那一轮回滚尝试痕迹`() {
        // 模型说没什么可接的、生成失败、落库失败 —— 这些都没有产生消息，
        // 留着尝试记录只会白白压住下一次复查。
        val guard = ProactiveCycleGuard()
        val first = guard.tryEnter(now = 1_000L)!!
        guard.markAttempt(1_100L)
        guard.leave(first, sent = true)

        val second = guard.tryEnter(now = 2_000L)!!
        guard.markAttempt(2_100L)
        guard.leave(second, sent = false)

        assertEquals(
            "回滚到上一轮成功发送的那次，而不是清成 0",
            1_100L,
            guard.tryEnter(now = 9_000L)?.previousAttemptAt,
        )
    }

    @Test
    fun `被拒的触发不推进尝试时间`() {
        val guard = ProactiveCycleGuard()
        val entry = guard.tryEnter(now = 1_000L)!!
        guard.markAttempt(1_200L)

        assertNull(guard.tryEnter(now = 5_000L))
        guard.leave(entry, sent = true)

        // 被拒的那次没有真的跑，不能算作「上一轮」。
        assertEquals(1_200L, guard.tryEnter(now = 9_000L)?.previousAttemptAt)
    }

    @Test
    fun `未登记的轮次不会伪造尝试时间`() {
        // 在免打扰 / 额度不足处就被拦下的轮次不会走到生成，也就不会 markAttempt。
        val guard = ProactiveCycleGuard()
        val entry = guard.tryEnter(now = 1_000L)!!
        guard.leave(entry, sent = false)

        assertEquals(0L, guard.tryEnter(now = 2_000L)?.previousAttemptAt)
    }
}
