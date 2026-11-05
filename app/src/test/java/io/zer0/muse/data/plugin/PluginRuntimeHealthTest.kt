package io.zer0.muse.data.plugin

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.23(PL1): 插件运行期熔断路径回归。
 *
 * 钉住四条:
 *  1. 阈值前不短路(前两笔失败要真实执行完再计账);
 *  2. 冷却期内 blockReason 有人话与剩余秒数,不是空错误;
 *  3. 到期半开:放行一条真实探测,再失败按翻倍退避重新开闸;
 *  4. 成功即清零 —— 包括半开探测成功。
 */
class PluginRuntimeHealthTest {

    private var now = 1_000_000L
    private val health = PluginRuntimeHealth(nowMillis = { now })

    private fun failThreeTimes(id: String) {
        repeat(PluginRuntimeHealth.FAILURE_THRESHOLD) { health.recordFailure(id) }
    }

    @Test
    fun `阈值之前不短路`() {
        health.recordFailure("p")
        health.recordFailure("p")
        assertNull(health.blockReason("p"))
        assertNotNull(health.stateOf("p"))
    }

    @Test
    fun `达到阈值后熔断并给出剩余时间`() {
        failThreeTimes("p")
        val reason = health.blockReason("p")
        assertNotNull("应处于熔断", reason)
        assertTrue("理由要带剩余秒数: $reason", reason!!.contains("60"))
    }

    @Test
    fun `冷却到期转半开放行一条探测`() {
        failThreeTimes("p")
        now += PluginRuntimeHealth.baseCooldownMs + 1

        assertNull("到期应放行探测", health.blockReason("p"))
        assertTrue("进入半开", health.stateOf("p")?.probing == true)
    }

    @Test
    fun `探测失败退避翻倍`() {
        failThreeTimes("p")
        now += PluginRuntimeHealth.baseCooldownMs + 1
        health.blockReason("p") // 转半开

        health.recordFailure("p")
        val reason = health.blockReason("p")
        assertNotNull(reason)
        assertTrue("第二轮冷却应翻倍(120s): $reason", reason!!.contains("120"))
    }

    @Test
    fun `成功关闭熔断`() {
        failThreeTimes("p")
        now += PluginRuntimeHealth.baseCooldownMs + 1
        health.blockReason("p")

        health.recordSuccess("p")
        assertNull(health.stateOf("p"))
        assertNull(health.blockReason("p"))
    }

    @Test
    fun `退避有上限`() {
        failThreeTimes("p")
        repeat(10) { i ->
            now += PluginRuntimeHealth.MAX_COOLDOWN_MS + 1
            health.blockReason("p") // 到期转半开
            health.recordFailure("p") // 探测失败,翻倍
        }
        val reason = health.blockReason("p") ?: fail("应仍在熔断")
        assertTrue("封顶 300s: $reason", reason.contains("300"))
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)
}
