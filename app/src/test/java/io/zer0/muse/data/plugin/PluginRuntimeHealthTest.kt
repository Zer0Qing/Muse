package io.zer0.muse.data.plugin

import org.junit.Assert.assertEquals
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

    // ── PL1 收尾:插件页的展示视图 ────────────────────────────

    @Test
    fun `展示视图在冷却期给出剩余秒数且与理由同源`() {
        failThreeTimes("p")
        val view = health.circuitView("p")
        assertEquals(PluginRuntimeHealth.CircuitStatus.CoolingDown, view.status)
        assertEquals(60, view.remainingSeconds)
        // 理由与视图必须同一套算法:两处各算一次,早晚会在页面上差一秒
        assertTrue(health.blockReason("p")!!.contains(view.remainingSeconds.toString()))
    }

    @Test
    fun `剩余不足一秒显示一秒而不是零`() {
        failThreeTimes("p")
        now += PluginRuntimeHealth.baseCooldownMs - 1
        val view = health.circuitView("p")
        assertEquals(PluginRuntimeHealth.CircuitStatus.CoolingDown, view.status)
        assertEquals("还剩 1ms 不许显示「剩余 0 秒」——那读起来像已经解冻了", 1, view.remainingSeconds)
    }

    @Test
    fun `到期转半开时视图给探测中且剩余归零`() {
        failThreeTimes("p")
        now += PluginRuntimeHealth.baseCooldownMs + 1
        val view = health.circuitView("p")
        assertEquals(PluginRuntimeHealth.CircuitStatus.Probing, view.status)
        assertEquals(0, view.remainingSeconds)
    }

    @Test
    fun `读视图不许消耗掉半开探测的机会`() {
        failThreeTimes("p")
        now += PluginRuntimeHealth.baseCooldownMs + 1
        repeat(3) { health.circuitView("p") }
        // blockReason 在到期时会把状态翻成 probing(那是放行的记账),UI 只是看一眼,不能触发它
        assertEquals(
            "打开插件页就等于替插件吃掉一次探测,是把 Bug 藏进观察行为里",
            false,
            health.stateOf("p")?.probing,
        )
    }

    @Test
    fun `未熔断与阈值前都是 None`() {
        assertEquals(PluginRuntimeHealth.CircuitStatus.None, health.circuitView("fresh").status)
        health.recordFailure("p")
        health.recordFailure("p")
        val view = health.circuitView("p")
        assertEquals(PluginRuntimeHealth.CircuitStatus.None, view.status)
        assertEquals("阈值前的失败次数仍要能对上账", 2, view.consecutiveFailures)
    }

    @Test
    fun `解冻后视图回到 None`() {
        failThreeTimes("p")
        now += PluginRuntimeHealth.baseCooldownMs + 1
        assertEquals(PluginRuntimeHealth.CircuitStatus.Probing, health.circuitView("p").status)
        health.recordSuccess("p")
        assertEquals(PluginRuntimeHealth.CircuitStatus.None, health.circuitView("p").status)
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)
}
