package io.zer0.muse.data.plugin.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.7.x(PL3): 市场版本建议的判定。
 *
 * 这一层存在的意义是把"能不能装、装了算升级还是降级"说清楚，
 * 而它最容易犯的错是**在不知道的时候装作知道** —— 所以下面专门钉了几条
 * "信息不全时必须落到 Unknown，而不是 CurrentIsLatest"。
 */
class PluginMarketVersionsTest {

    private fun view(
        catalog: String,
        installed: String? = null,
        retained: List<String> = emptyList(),
        app: String = "2.7.0",
        minApp: String? = null,
    ) = PluginMarketVersions.view(catalog, installed, retained, app, minApp)

    @Test
    fun `未安装时给出可装`() {
        val result = view("1.2.0")

        assertEquals(MarketVersionAdvice.InstallAvailable, result.advice)
        assertNull(result.reason)
    }

    @Test
    fun `目录更新时是升级并说清从到`() {
        val result = view("1.3.0", installed = "1.2.0")

        assertEquals(MarketVersionAdvice.UpgradeAvailable, result.advice)
        assertTrue(result.reason!!, result.reason!!.contains("1.2.0") && result.reason!!.contains("1.3.0"))
    }

    @Test
    fun `同版本就是最新`() {
        assertEquals(MarketVersionAdvice.CurrentIsLatest, view("1.2.0", installed = "1.2.0").advice)
    }

    @Test
    fun `目录比本机旧要说成降级而不是升级`() {
        val result = view("1.1.0", installed = "1.2.0")

        assertEquals(MarketVersionAdvice.DowngradeAvailable, result.advice)
        assertTrue("降级要明说: ${result.reason}", result.reason!!.contains("降级"))
    }

    @Test
    fun `已装副本要求更高的App版本时优先说这个`() {
        val result = view("1.3.0", installed = "1.2.0", app = "2.6.22", minApp = "2.7.0")

        assertEquals(MarketVersionAdvice.NeedsNewerApp, result.advice)
        assertTrue(result.reason!!.contains("2.7.0"))
        assertTrue(result.reason!!.contains("2.6.22"))
    }

    @Test
    fun `开发版哨兵不卡兼容门槛`() {
        // PluginVersionPolicy 的同一条规则:哨兵版本是开发者机器,不该被门槛挡住。
        val result = view("1.3.0", installed = "1.2.0", app = "0.0.0-dev", minApp = "9.9.9")

        assertEquals(MarketVersionAdvice.UpgradeAvailable, result.advice)
    }

    @Test
    fun `版本无法识别时落到 Unknown 而不是猜一个结论`() {
        assertEquals(MarketVersionAdvice.UnknownVersion, view("beta", app = "2.7.0").advice)
        assertEquals(MarketVersionAdvice.UnknownVersion, view("1.2.0", installed = "最新版", app = "2.7.0").advice)
        assertTrue(view("beta", app = "2.7.0").reason!!.contains("beta"))
    }

    @Test
    fun `minAppVersion 不知道时不编造兼容结论`() {
        val withoutMin = view("1.3.0", installed = "1.2.0", app = "2.7.0", minApp = null)

        assertEquals("没拿到要求值就按普通升级处理,不显示任何兼容结论", MarketVersionAdvice.UpgradeAvailable, withoutMin.advice)
        assertNull(withoutMin.reason?.takeIf { it.contains("要求") })
    }

    @Test
    fun `可切换版本排除当前版本并按新旧排`() {
        val switchable = PluginMarketVersions.switchableVersions(
            installedVersion = "1.2.0",
            retainedVersions = listOf("1.0.0", "1.2.0", "1.10.0", "1.2.0", ""),
        )

        assertEquals(listOf("1.10.0", "1.0.0"), switchable)
    }

    @Test
    fun `预发布版本按既有序比较而不另立规则`() {
        // 1.2.0-rc.1 < 1.2.0:如果这里出现两个不同的判定,说明市场层自己造了一套比较。
        assertEquals(MarketVersionAdvice.DowngradeAvailable, view("1.2.0-rc.1", installed = "1.2.0").advice)
        assertEquals(MarketVersionAdvice.UpgradeAvailable, view("1.2.0", installed = "1.2.0-rc.1").advice)
    }
}
