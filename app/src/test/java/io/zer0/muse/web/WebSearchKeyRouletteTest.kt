package io.zer0.muse.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Key 池解析与 429 换 key：复用 ai 模块轮换器，web 侧只负责 List ↔ 分隔串。 */
class WebSearchKeyRouletteTest {

    @Test
    fun `批量粘贴 逗号换行中文标点都分隔 去重保序`() {
        assertEquals(listOf("a", "b", "c"), WebSearchKeyRoulette.parse("a,b\nc；d".replace("d", "a")))
        assertEquals(listOf("a", "b"), WebSearchKeyRoulette.parse(" a , \n b ，a\n"))
    }

    @Test
    fun `空池与全空白返回空`() {
        assertEquals("", WebSearchKeyRoulette.pick("p", emptyList()))
        assertEquals("", WebSearchKeyRoulette.pick("p", listOf("", "  ")))
        assertTrue(WebSearchKeyRoulette.parse(" , ; \n").isEmpty())
    }

    @Test
    fun `首把来自 Key 池`() {
        val keys = listOf("k1", "k2", "k3")
        assertTrue(WebSearchKeyRoulette.pick("provider-a", keys) in keys)
    }

    @Test
    fun `429 时切到另一把 单把时保持原 key`() {
        val keys = listOf("k1", "k2", "k3")
        val first = WebSearchKeyRoulette.pick("provider-b", keys)
        val next = WebSearchKeyRoulette.pickNext("provider-b", keys, first)
        assertTrue("多把时应切到不同的 key: first=$first next=$next", next != first || next.isEmpty())
        assertEquals("k1", WebSearchKeyRoulette.pickNext("single-provider", listOf("k1"), "k1"))
    }

    @Test
    fun `join 过滤空项且可被 parse 还原`() {
        val joined = WebSearchKeyRoulette.join(listOf("a", "", "  ", "b", "a"))
        assertEquals(listOf("a", "b"), WebSearchKeyRoulette.parse(joined))
    }

    @Test
    fun `429 换 key 后 resolveProviderKey 拿到另一把`() {
        // 用独立 id 避免与同文件其它用例共享轮换器状态。
        val id = "rotation-e2e-provider"
        val cfg = WebSearchConfig(
            providerEntries = listOf(
                WebSearchProviderEntry(id = id, type = "Tavily", apiKeys = listOf("k1", "k2", "k3")),
            ),
        )
        val first = resolveProviderKey(cfg, "Tavily")
        val pool = cfg.providerEntries.single().apiKeys
        WebSearchKeyRoulette.pickNext(id, pool, first)
        val after = resolveProviderKey(cfg, "Tavily")
        assertTrue("429 后应换到另一把: first=$first after=$after", after != first)
        assertTrue(after in pool)
    }

    @Test
    fun `resolveProviderKey 从 Key 池首把读 旧字段兼容回退`() {
        // 轮换器是进程内单例：上面 `429 换 key` 那条用例已经用 id="Tavily" 轮换过，
        // 这里若继续用同一个 id，就会读到被推进过的下标（本用例曾因此偶发失败）。
        // 换成独有 id，把「首把读取」这条语义钉在与轮换状态无关的输入上。
        val poolId = "first-read-isolation"
        val v3 = WebSearchConfig(
            providerEntries = listOf(WebSearchProviderEntry(id = poolId, type = "Tavily", apiKeys = listOf("first", "second"))),
        )
        assertEquals("first", resolveProviderKey(v3, "Tavily"))
        val legacy = WebSearchConfig(apiKeys = mapOf("Brave" to "legacy"))
        assertEquals("legacy", resolveProviderKey(legacy, "Brave"))
        val single = WebSearchConfig(providerName = "Exa", apiKey = "single")
        assertEquals("single", resolveProviderKey(single, "Exa"))
        assertEquals("旧单 key 不误发别家", "", resolveProviderKey(single, "Tavily"))
    }
}
