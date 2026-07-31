package io.zer0.ai.util

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-3: [SlidingWindowRateLimiter] 单元测试。
 *
 * 用 `runTest` 的虚拟时间 + 注入 `clock = { testScheduler.currentTime }` 让限流器的窗口判断
 * 与 `delay` 的虚拟时间推进保持一致,可确定性验证"超限等待 → 窗口滑动 → 放行"。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SlidingWindowRateLimiterTest {

    @Test
    fun `acquire should allow up to maxRequests without delay`() = runTest {
        val limiter = SlidingWindowRateLimiter(maxRequests = 3, windowMs = 1_000, clock = { testScheduler.currentTime })
        limiter.acquire()
        limiter.acquire()
        limiter.acquire()
        assertEquals(3, limiter.currentCount())
        // 3 次立即获取,虚拟时间未推进
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `acquire should wait until window slides when full`() = runTest {
        val limiter = SlidingWindowRateLimiter(maxRequests = 2, windowMs = 1_000, clock = { testScheduler.currentTime })
        limiter.acquire() // t=0
        limiter.acquire() // t=0
        assertEquals(0L, testScheduler.currentTime)

        // 第 3 次:窗口已满(t=0 时有 2 条),需等到 t=1000 首条滑出窗口
        limiter.acquire()
        // 虚拟时间应推进到 1000(首条时间戳 0 + windowMs 1000)
        assertEquals(1_000L, testScheduler.currentTime)
        // t=1000 时:cutoff = 1000-1000 = 0,t=0 的两条都 <= 0 被清理,新增 t=1000 一条 → size=1
        assertEquals(1, limiter.currentCount())
    }

    @Test
    fun `acquire should reject non-positive maxRequests`() {
        assertThrows(IllegalArgumentException::class.java) {
            SlidingWindowRateLimiter(maxRequests = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SlidingWindowRateLimiter(maxRequests = -1)
        }
    }

    @Test
    fun `acquire should reject non-positive windowMs`() {
        assertThrows(IllegalArgumentException::class.java) {
            SlidingWindowRateLimiter(maxRequests = 1, windowMs = 0)
        }
    }

    @Test
    fun `currentCount should clean expired timestamps`() = runTest {
        val limiter = SlidingWindowRateLimiter(maxRequests = 10, windowMs = 500, clock = { testScheduler.currentTime })
        limiter.acquire() // t=0
        limiter.acquire() // t=0
        assertEquals(2, limiter.currentCount())

        delay(600) // 推进到 t=600,两条 t=0 都过期(cutoff=100)
        assertEquals(0, limiter.currentCount())

        // 过期后可再次立即获取
        limiter.acquire()
        assertEquals(1, limiter.currentCount())
    }

    @Test
    fun `acquire should serialize concurrent acquires without over-allocating`() = runTest {
        // 5 并发 acquire,但窗口只允许 2 个 → 多余的等待,最终全部完成但耗时 ≥ windowMs
        val limiter = SlidingWindowRateLimiter(maxRequests = 2, windowMs = 1_000, clock = { testScheduler.currentTime })
        val results = (1..5).map {
            async { limiter.acquire() }
        }
        results.awaitAll()
        // 5 个请求分批:2 个 t=0,2 个 t=1000,1 个 t=2000 → 最终虚拟时间 ≥ 2000
        val now = testScheduler.currentTime
        assertTrue("expected virtual time >= 2000, got $now", now >= 2_000L)
        // 全部完成
        assertEquals(5, results.size)
    }
}
