package io.zer0.muse.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子 agent 并发闸门的**行为**（协作语义显式化 · P4 收尾）。
 *
 * 之前的测试只是"造一个 limiter 塞给被测对象让它放行"（`AgentConcurrencyLimiter()`、
 * 或者 mockk 直通），**闸门本身从没被考验过**。而它兜的是两件很难看的事：
 *
 *  - 不生效 → 本地模型同时被十几个子 agent 压住，整机卡死；
 *  - 少释放 → 槽位越漏越少，跑到第 N 次委派起**永久排队**，用户只看到"子 agent 不返回"。
 *     这类泄漏不会因为重启应用而恢复（进程内计数），所以必须测。
 */
class AgentConcurrencyLimiterTest {

    /** 轮询等待用的是真实时间，所以断言前给足一个上限，超时即失败。 */
    private suspend fun awaitUntil(predicate: () -> Boolean): Boolean =
        withTimeoutOrNull(3_000L) {
            while (!predicate()) {
                kotlinx.coroutines.delay(20)
            }
            true
        } == true

    @Test
    fun `超出并发上限的那一个必须排队而不是挤进去`() = runBlocking {
        val limiter = AgentConcurrencyLimiter(maxConcurrent = 2, maxTotal = 100)
        val release = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()

        // 两个都得**占着不放**：上一版让 b 立刻跑完，b 归还了槽位，
        // c 自然就进来了 —— 那不是"挤进来"，是我把测试写成了合法场景（诊断印出 started=[a, b, c]）。
        val holders = listOf(
            launch { limiter.run { started += "a"; release.await() } },
            launch { limiter.run { started += "b"; release.await() } },
        )
        val waiter = launch { limiter.run { started += "c" } }

        assertTrue("前两个应该拿到槽", awaitUntil { started.size == 2 })
        delay(300)
        assertEquals("第三个不能挤进来", listOf("a", "b"), started.toList())
        assertEquals(2, limiter.activeCount)

        release.complete(Unit)
        holders.forEach { it.join() }
        assertTrue("释放后第三个应被放行", awaitUntil { started.contains("c") })
        waiter.join()
        assertEquals("三个都跑完后不该漏槽位", 0, limiter.activeCount)
    }

    @Test
    fun `任务抛异常也必须归还槽位`() = runBlocking {
        val limiter = AgentConcurrencyLimiter(maxConcurrent = 1, maxTotal = 100)

        val failed = runCatching { limiter.run<Int> { throw IllegalStateException("子任务炸了") } }

        assertTrue("异常要原样传出去", failed.isFailure)
        assertEquals("炸掉不能漏槽位", 0, limiter.activeCount)
        // 漏了的话这里就会永久卡住：用超时来判定，而不是靠读内部计数
        val next = withTimeoutOrNull(2_000L) { limiter.run { "跑通了" } }
        assertEquals("上一个失败不该影响下一个", "跑通了", next)
    }

    @Test
    fun `累计派发上限是防失控的兜底并且拒绝原因可读`() = runBlocking {
        val limiter = AgentConcurrencyLimiter(maxConcurrent = 4, maxTotal = 3)

        repeat(3) { limiter.run { it } }
        val error = runCatching { limiter.run { "第 4 次" } }.exceptionOrNull()

        assertNotNull("超过 maxTotal 必须拒绝", error)
        assertTrue(
            "拒绝原因要能让人看懂是防失控兜底：${error?.message}",
            error is IllegalStateException && error.message?.contains("上限") == true,
        )
    }

    @Test
    fun `计数在正常使用后回到零且不会变负`() = runBlocking {
        val limiter = AgentConcurrencyLimiter(maxConcurrent = 3, maxTotal = 100)

        repeat(5) { limiter.run { } }

        assertEquals(0, limiter.activeCount)
        // 再跑几轮：如果哪一步把计数减成负数，activeCount 就再也回不到 0 之上
        limiter.run { }
        assertEquals(0, limiter.activeCount)
    }

    @Test
    fun `默认配额就是文档里那两个数`() {
        // 并发 8 / 累计 200：手机端本地模型并发能力有限，改这两个数等于改全体委派的资源上限
        assertEquals(8, AgentConcurrencyLimiter.DEFAULT_MAX_CONCURRENT)
        assertEquals(200, AgentConcurrencyLimiter.DEFAULT_MAX_TOTAL)
    }
}
