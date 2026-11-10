package io.zer0.muse.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动态表单的字段判定（按类型决定显示哪些项、必填校验）。
 * 渲染本身另由设置页 Robolectric 覆盖；这里钉住「类型 → 字段集」这条纯规则。
 */
class WebSearchProviderFormStateTest {

    private fun fieldsFor(type: String): Set<String> {
        val meta = WebSearchProviderCatalog.meta(type)
        return buildSet {
            if (meta.needsKey) add("keys")
            if (meta.supportsCustomUrl) add("url")
            if (meta.needsModel) add("model")
            addAll(meta.extraFields)
        }
    }

    @Test
    fun `商用 API 只要 Key 与可选 URL`() {
        val f = fieldsFor("Tavily")
        assertTrue("keys" in f)
        assertTrue("url" in f)
        assertFalse("model" in f)
    }

    @Test
    fun `免费抓取不需要 Key`() {
        val f = fieldsFor("Bing")
        assertFalse("keys" in f)
        assertFalse("url" in f)
    }

    @Test
    fun `自托管 SearXNG 要实例 URL 与扩展项`() {
        val f = fieldsFor("SearXNG")
        assertTrue("url" in f)
        assertFalse("keys" in f)
        assertTrue("engines" in f)
        assertTrue("language" in f)
    }

    @Test
    fun `Perplexity 需要模型名`() {
        assertTrue("model" in fieldsFor("Perplexity"))
    }

    @Test
    fun `实例 URL 必填校验 留空不通过 自托管才要求`() {
        assertTrue(WebSearchProviderCatalog.needsInstanceUrl("SearXNG"))
        assertFalse(WebSearchProviderCatalog.needsInstanceUrl("Tavily"))
        // 表单保存前对留空的拦截条件
        fun canSave(type: String, url: String): Boolean = !WebSearchProviderCatalog.needsInstanceUrl(type) || url.isNotBlank()
        assertFalse(canSave("SearXNG", ""))
        assertTrue(canSave("SearXNG", "https://searx.example.com"))
        assertTrue(canSave("Tavily", ""))
    }

    @Test
    fun `URL 留空时用默认端点 不为空则不覆盖`() {
        fun effectiveUrl(type: String, custom: String): String = custom.ifBlank { WebSearchProviderCatalog.defaultEndpoint(type).orEmpty() }
        assertEquals("https://api.tavily.com", effectiveUrl("Tavily", ""))
        assertEquals("https://my.example.com", effectiveUrl("Tavily", "https://my.example.com"))
    }
}
