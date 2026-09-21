package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModelCatalogLoader] 与 [ProviderApiRegistry] 的单测。
 *
 * 覆盖:正常解析、字段缺省兜底、查不到返回 null、坏 JSON 降级为空库(不抛)、协议互转。
 */
class ModelAbilityCatalogTest {

    private val sample = """
        {
          "schemaVersion": 1,
          "publishedAt": "2026-09-17T15:59:09Z",
          "providers": {
            "anthropic": {
              "claude-fable-5-1": {
                "name": "Claude Fable 5.1",
                "context": 1000000,
                "maxOutput": 128000,
                "image": true,
                "reasoning": true,
                "toolUse": {"supportsTools": true, "dialect": "anthropic", "toolResultFormat": "tool-result"},
                "thinkingLevels": ["low", "high", "max"],
                "defaultThinkingLevel": "high",
                "compat": {"thinkingFormat": "anthropic", "reasoningProfile": "anthropic-adaptive-only"}
              }
            },
            "deepseek": {
              "deepseek-v4-flash": {"name": "DeepSeek V4 Flash", "context": 1048576}
            }
          }
        }
    """.trimIndent()

    @Test
    fun `loads catalog and reads entry by provider and model`() {
        val catalog = ModelCatalogLoader.loadOrEmpty(sample)
        val e = catalog.entryOf("anthropic", "claude-fable-5-1")
        assertEquals("Claude Fable 5.1", e!!.name)
        assertEquals(1000000L, e.context)
        assertTrue(e.image)
        assertEquals("anthropic", e.toolUse?.dialect)
        assertEquals("tool-result", e.toolUse?.toolResultFormat)
        assertEquals(listOf("low", "high", "max"), e.thinkingLevels)
        assertEquals("anthropic-adaptive-only", e.compat?.reasoningProfile)
    }

    @Test
    fun `omitted fields fall back to safe defaults`() {
        val e = ModelCatalogLoader.loadOrEmpty(sample).entryOf("deepseek", "deepseek-v4-flash")
        assertEquals("DeepSeek V4 Flash", e!!.name)
        assertEquals(false, e.image)
        assertEquals(false, e.reasoning)
        assertNull(e.toolUse)
        assertTrue(e.thinkingLevels.isEmpty())
        assertNull(e.compat)
    }

    @Test
    fun `unknown provider or model returns null`() {
        val catalog = ModelCatalogLoader.loadOrEmpty(sample)
        assertNull(catalog.entryOf("nope", "x"))
        assertNull(catalog.entryOf("anthropic", "nope"))
        assertTrue(catalog.modelsOf("nope").isEmpty())
        assertEquals(1, catalog.modelsOf("anthropic").size)
    }

    @Test
    fun `malformed or blank json yields empty catalog without throwing`() {
        assertTrue(ModelCatalogLoader.loadOrEmpty(null).providers.isEmpty())
        assertTrue(ModelCatalogLoader.loadOrEmpty("").providers.isEmpty())
        assertTrue(ModelCatalogLoader.loadOrEmpty("{ not json").providers.isEmpty())
    }

    @Test
    fun `provider api registry round trips and normalizes input`() {
        ProviderType.values().forEach { t ->
            assertEquals(t, ProviderApiRegistry.typeOf(ProviderApiRegistry.apiOf(t)))
        }
        assertEquals(ProviderType.OPENAI, ProviderApiRegistry.typeOf("  OpenAI-Completions "))
        assertNull(ProviderApiRegistry.typeOf("unknown-api"))
        assertEquals(4, ProviderApiRegistry.known.size)
    }
}
