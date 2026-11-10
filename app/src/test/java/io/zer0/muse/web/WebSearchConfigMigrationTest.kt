package io.zer0.muse.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 → v3 配置迁移：旧配置的 Key、端点、顺序、当前生效供应商**零丢失**，且幂等。
 * 这是整个改造里风险最高的一环：靠 AppJson 宽容解码会静默丢旧值，必须显式归并。
 */
class WebSearchConfigMigrationTest {

    @Test
    fun `已是 v3 直接返回原对象 幂等`() {
        val v3 = WebSearchConfig(
            policyVersion = 3,
            providerEntries = listOf(WebSearchProviderEntry(id = "Tavily", type = "Tavily", apiKeys = listOf("k"))),
        )
        assertEquals(v3, WebSearchConfig.migrateToV3(v3))
    }

    @Test
    fun `只有旧单 apiKey 时归并到 providerName 那家`() {
        val old = WebSearchConfig(providerName = "Tavily", apiKey = "tvly-old", policyVersion = 2)
        val out = WebSearchConfig.migrateToV3(old)
        assertEquals(3, out.policyVersion)
        val entry = out.providerEntries.single { it.type == "Tavily" }
        assertEquals(listOf("tvly-old"), entry.apiKeys)
        assertEquals("旧字段必须保留做兼容", "tvly-old", out.apiKey)
        assertEquals("Tavily", out.providerName)
    }

    @Test
    fun `apiKeys 映射每家一把各自成条目`() {
        val old = WebSearchConfig(
            apiKeys = mapOf("Tavily" to "t-key", "Brave" to "b-key"),
            policyVersion = 2,
        )
        val out = WebSearchConfig.migrateToV3(old)
        assertEquals("t-key", out.providerEntries.single { it.type == "Tavily" }.apiKeys.single())
        assertEquals("b-key", out.providerEntries.single { it.type == "Brave" }.apiKeys.single())
        assertEquals(2, out.providerEntries.size)
    }

    @Test
    fun `旧全局 endpoint 归到当前生效那家 Auto 不臆造归属`() {
        val owned = WebSearchConfig(providerName = "SearXNG", endpoint = "https://searx.example.com", policyVersion = 2)
        val outOwned = WebSearchConfig.migrateToV3(owned)
        // SearXNG 无需 key，旧配置里可能没有 apiKeys/apiKey —— 也要保留端点。
        val searx = outOwned.providerEntries.single { it.type == "SearXNG" }
        assertEquals("https://searx.example.com", searx.customUrl)

        val auto = WebSearchConfig(providerName = "Auto", endpoint = "https://legacy.example.com", policyVersion = 2)
        val outAuto = WebSearchConfig.migrateToV3(auto)
        assertTrue("Auto 时端点不属于任何一家，不能臆造归属", outAuto.providerEntries.none { it.customUrl.isNotBlank() })
    }

    @Test
    fun `customEngines 各自成条目并带端点与 key`() {
        val old = WebSearchConfig(
            customEngines = listOf(
                CustomSearchEngine(id = "c1", name = "我的引擎", endpoint = "https://my.example.com/search", apiKey = "my-key"),
            ),
            policyVersion = 2,
        )
        val out = WebSearchConfig.migrateToV3(old)
        val entry = out.providerEntries.single { it.id == "c1" }
        assertEquals("https://my.example.com/search", entry.customUrl)
        assertEquals(listOf("my-key"), entry.apiKeys)
        assertEquals("Custom API", entry.type)
    }

    @Test
    fun `customEngines 与 apiKeys 同名时以自定义引擎为准 不重复`() {
        val old = WebSearchConfig(
            apiKeys = mapOf("Custom API" to "legacy-key"),
            customEngines = listOf(CustomSearchEngine(id = "Custom API", name = "Custom API", endpoint = "https://x", apiKey = "new-key")),
            policyVersion = 2,
        )
        val out = WebSearchConfig.migrateToV3(old)
        assertEquals(1, out.providerEntries.count { it.id == "Custom API" })
        assertEquals("new-key", out.providerEntries.single { it.id == "Custom API" }.apiKeys.single())
    }

    @Test
    fun `重复迁移幂等 不重复追加条目`() {
        val old = WebSearchConfig(providerName = "Tavily", apiKey = "k", apiKeys = mapOf("Brave" to "b"), policyVersion = 2)
        val once = WebSearchConfig.migrateToV3(old)
        val twice = WebSearchConfig.migrateToV3(once)
        assertEquals(once, twice)
        assertEquals(2, once.providerEntries.size)
    }

    @Test
    fun `预算与 providerOrder fallbackEnabled 原样保留`() {
        val old = WebSearchConfig(
            maxSearchesPerTurn = 2,
            providerOrder = listOf("Brave", "Tavily"),
            fallbackEnabled = false,
            policyVersion = 1,
        )
        val out = WebSearchConfig.migrateToV3(old)
        assertEquals(50, out.maxSearchesPerTurn)
        assertEquals(listOf("Brave", "Tavily"), out.providerOrder)
        assertFalse(out.fallbackEnabled)
        assertEquals(3, out.policyVersion)
    }

    @Test
    fun `v3 之后旧字段里的新写入不被迁移覆盖`() {
        val current = WebSearchConfig(
            policyVersion = 3,
            apiKeys = mapOf("Tavily" to "old-compat"),
            providerEntries = listOf(WebSearchProviderEntry(id = "Tavily", type = "Tavily", apiKeys = listOf("new-key"))),
        )
        val out = WebSearchConfig.migrateToV3(current)
        assertEquals(listOf("new-key"), out.providerEntries.single().apiKeys)
        assertEquals("old-compat", out.apiKeys["Tavily"])
    }
}
