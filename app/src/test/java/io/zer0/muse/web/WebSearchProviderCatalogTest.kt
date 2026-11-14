package io.zer0.muse.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 供应商目录：一份清单、一份默认端点，消除历史两份清单与端点双写漂移。 */
class WebSearchProviderCatalogTest {

    @Test
    fun `下拉清单与链顺序同源 Auto 只在下拉里`() {
        assertEquals("Auto", WebSearchProviderCatalog.selectableTypes.first())
        assertFalse("Auto 是编排模式，不参与链构造", WebSearchProviderCatalog.chainKeys.contains("Auto"))
        assertEquals(WebSearchProviderCatalog.chainKeys, AUTO_CHAIN_PROVIDER_KEYS)
        assertEquals(WebSearchProviderCatalog.selectableTypes, WebSearchConfig.SUPPORTED_PROVIDERS)
    }

    @Test
    fun `链顺序与旧版一致 不产生 providerOrder 幽灵键`() {
        val expected = listOf(
            "Tavily", "Perplexity", "Brave", "Serper", "Zhipu", "Bocha", "Metaso", "Exa",
            "Firecrawl", "Jina", "DeepSeek", "SearXNG", "Custom API", "Bing", "Baidu",
        )
        assertEquals(expected, WebSearchProviderCatalog.chainKeys)
    }

    @Test
    fun `每家默认端点与 keyFor 需要的元数据自洽`() {
        WebSearchProviderCatalog.chainKeys.forEach { key ->
            val meta = WebSearchProviderCatalog.meta(key)
            assertEquals(key, meta.key)
            if (meta.needsKey) assertTrue("$key 需要 key", WebSearchProviderCatalog.needsKey(key))
        }
        assertEquals("https://api.tavily.com", WebSearchProviderCatalog.defaultEndpoint("Tavily"))
        assertEquals("https://searx.be", WebSearchProviderCatalog.defaultEndpoint("SearXNG"))
        assertTrue(WebSearchProviderCatalog.needsInstanceUrl("SearXNG"))
        assertTrue(WebSearchProviderCatalog.supportsCustomUrl("SearXNG"))
        assertFalse(WebSearchProviderCatalog.supportsCustomUrl("Tavily"))
        assertTrue(WebSearchProviderCatalog.supportsCustomUrl("Custom API"))
        assertFalse(WebSearchProviderCatalog.needsKey("Bing"))
    }

    @Test
    fun `需要 API Key 的集合包含两种自定义历史写法`() {
        val set = WebSearchConfig.PROVIDERS_NEEDING_API_KEY
        assertTrue(set.contains(WebSearchConfig.CUSTOM_PROVIDER_NAME))
        assertTrue(set.contains(WebSearchConfig.CUSTOM_PROVIDER_NAME_EN))
        assertTrue(set.contains("Tavily"))
        assertTrue(set.contains("Perplexity"))
    }

    @Test
    fun `旧中文自定义 API 键归一化为目录键`() {
        assertEquals(
            WebSearchConfig.CUSTOM_PROVIDER_NAME_EN,
            WebSearchProviderCatalog.canonicalKey(WebSearchConfig.CUSTOM_PROVIDER_NAME),
        )
        assertEquals(
            WebSearchConfig.CUSTOM_PROVIDER_NAME_EN,
            WebSearchProviderCatalog.canonicalKey(WebSearchConfig.CUSTOM_PROVIDER_NAME_EN),
        )
    }

    @Test
    fun `未知类型返回空 Meta 不抛异常`() {
        val meta = WebSearchProviderCatalog.meta("完全不存在的引擎")
        assertEquals("完全不存在的引擎", meta.key)
        assertFalse(meta.needsKey)
        assertEquals(null, WebSearchProviderCatalog.defaultEndpoint("完全不存在的引擎"))
    }

    @Test
    fun `buildSearchChain 仍能按 providerEntries 的 Key 池首把建链`() {
        val client = okhttp3.OkHttpClient()
        val cfg = WebSearchConfig(
            providerName = "Auto",
            providerEntries = listOf(
                WebSearchProviderEntry(id = "Tavily", type = "Tavily", apiKeys = listOf("first", "second")),
            ),
        )
        val names = buildSearchChain(client, cfg).map { it.name }
        assertTrue("配了 Key 的 Tavily 必须进链", names.contains("Tavily"))
        assertTrue("免费抓取始终兜底", names.contains("Bing") && names.contains("Baidu"))
    }

    @Test
    fun `providerEntries 为空时保持旧 apiKeys 行为 存量零回归`() {
        val client = okhttp3.OkHttpClient()
        val cfg = WebSearchConfig(providerName = "Auto", apiKeys = mapOf("Brave" to "b-key"))
        val names = buildSearchChain(client, cfg).map { it.name }
        assertTrue(names.contains("Brave"))
        assertFalse("没配 Key 的 Tavily 不该空转", names.contains("Tavily"))
    }

    @Test
    fun `provider list distinguishes ready key and instance setup`() {
        val empty = WebSearchConfig()
        assertEquals(WebSearchProviderCatalog.ProviderSetupState.READY, WebSearchProviderCatalog.setupState(empty, "Auto"))
        assertEquals(WebSearchProviderCatalog.ProviderSetupState.READY, WebSearchProviderCatalog.setupState(empty, "Bing"))
        assertEquals(WebSearchProviderCatalog.ProviderSetupState.NEEDS_KEY, WebSearchProviderCatalog.setupState(empty, "Tavily"))
        assertEquals(WebSearchProviderCatalog.ProviderSetupState.NEEDS_URL, WebSearchProviderCatalog.setupState(empty, "SearXNG"))

        val tavily = empty.copy(
            providerEntries = listOf(
                WebSearchProviderEntry(id = "Tavily", type = "Tavily", apiKeys = listOf("key")),
            ),
        )
        assertEquals(WebSearchProviderCatalog.ProviderSetupState.READY, WebSearchProviderCatalog.setupState(tavily, "Tavily"))

        val searx = empty.copy(
            providerEntries = listOf(
                WebSearchProviderEntry(id = "SearXNG", type = "SearXNG", customUrl = "https://search.example"),
            ),
        )
        assertEquals(WebSearchProviderCatalog.ProviderSetupState.READY, WebSearchProviderCatalog.setupState(searx, "SearXNG"))
    }
}
