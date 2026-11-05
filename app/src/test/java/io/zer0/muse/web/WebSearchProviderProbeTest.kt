package io.zer0.muse.web

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐引擎「验证」（整改计划 第 1 条 ④）。
 *
 * 这里只测**不需要联网**的部分：配置叠加与"引擎不存在时不去打网络"。
 * 真实可达性判定要发一次检索，属设备/网络验收；但"未配置的引擎不该被探测"
 * 是纯逻辑，且必须钉住 —— 否则设置页一打开就可能对外打出十几次请求。
 */
class WebSearchProviderProbeTest {

    private val client = OkHttpClient()

    @Test
    fun `草稿为空时用已保存的 Key，不覆盖成空串`() {
        val cfg = WebSearchConfig(enabled = true, apiKeys = mapOf("Tavily" to "saved-key"))
        assertSame("空草稿必须原样返回同一个配置", cfg, verifyConfigFor(cfg, "Tavily", ""))
        assertSame("纯空白同样算没输入", cfg, verifyConfigFor(cfg, "Tavily", "   "))
    }

    @Test
    fun `草稿只覆盖这一家并去掉首尾空白`() {
        val cfg = WebSearchConfig(
            enabled = true,
            apiKeys = mapOf("Tavily" to "old", "Brave" to "brave-key"),
        )
        val merged = verifyConfigFor(cfg, "Tavily", "  new-key  ")
        assertEquals("new-key", merged.apiKeys["Tavily"])
        assertEquals("别家的 Key 不该被顺手改掉", "brave-key", merged.apiKeys["Brave"])
        assertEquals("只多不少", 2, merged.apiKeys.size)
        assertEquals("原配置不能被改坏", "old", cfg.apiKeys["Tavily"])
    }

    @Test
    fun `未配置的引擎直接判不可用，不发请求`() = runTest {
        val cfg = WebSearchConfig(enabled = true)
        val outcome = probeSearchProvider(client, cfg, "不存在的引擎", "")
        assertFalse("不存在就该判失败", outcome.ok)
        assertEquals(0, outcome.results)
        assertEquals("没有发请求的时间开销", 0L, outcome.elapsedMs)
        assertEquals("原因必须是未配置/不存在", WebSearchProbeReason.NOT_USABLE, outcome.reason)
    }

    @Test
    fun `没配 Key 的内置引擎也不该被打一次网络`() = runTest {
        val cfg = WebSearchConfig(enabled = true)
        val outcome = probeSearchProvider(client, cfg, "Tavily", "")
        assertFalse("Tavily 没配 Key 时不该真的去请求", outcome.ok)
        assertEquals(0L, outcome.elapsedMs)
    }

    @Test
    fun `自定义引擎按名字找，找不到就失败`() = runTest {
        val cfg = WebSearchConfig(enabled = true)
        val outcome = probeSearchProvider(client, cfg, customSearchOrderKey("查无此引擎"), "")
        assertFalse(outcome.ok)
        assertEquals(customSearchOrderKey("查无此引擎"), outcome.providerKey)
        assertEquals(0L, outcome.elapsedMs)
    }

    @Test
    fun `探测只取一条结果，不把对方打满`() {
        assertEquals(1, PROBE_MAX_RESULTS)
        assertTrue("探测词不该是空串（空查询常被服务端直接拒绝，会把好端端的 Key 判成坏）",
            PROBE_QUERY.isNotBlank())
    }
}
