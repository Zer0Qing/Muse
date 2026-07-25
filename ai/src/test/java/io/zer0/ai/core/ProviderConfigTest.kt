package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProviderConfig 单元测试。
 *
 * 覆盖构造、默认值、resolvedBaseUrl() 按 type 取默认、
 * resolvedSpecific() 兜底返回对应 ProviderSpecificConfig 子类等关键行为。
 */
class ProviderConfigTest {

    @Test
    fun `should default type to OPENAI when not provided`() {
        val cfg = ProviderConfig(id = "p1", displayName = "P1")
        assertEquals(ProviderType.DEFAULT, cfg.type)
        assertEquals(ProviderType.OPENAI, cfg.type)
    }

    @Test
    fun `should default baseUrl to blank when not provided`() {
        val cfg = ProviderConfig(id = "p1", displayName = "P1")
        assertEquals("", cfg.baseUrl)
    }

    @Test
    fun `should default apiKey to blank when not provided`() {
        val cfg = ProviderConfig(id = "p1", displayName = "P1")
        assertEquals("", cfg.apiKey)
    }

    @Test
    fun `should default enabled to true when not provided`() {
        val cfg = ProviderConfig(id = "p1", displayName = "P1")
        assertTrue(cfg.enabled)
    }

    @Test
    fun `should default builtIn to false when not provided`() {
        val cfg = ProviderConfig(id = "p1", displayName = "P1")
        assertFalse(cfg.builtIn)
    }

    @Test
    fun `should default balanceApiPath to blank when not provided`() {
        val cfg = ProviderConfig(id = "p1", displayName = "P1")
        assertEquals("", cfg.balanceApiPath)
    }

    @Test
    fun `should default balanceResultPath to blank when not provided`() {
        val cfg = ProviderConfig(id = "p1", displayName = "P1")
        assertEquals("", cfg.balanceResultPath)
    }

    @Test
    fun `should default models to empty list when not provided`() {
        val cfg = ProviderConfig(id = "p1", displayName = "P1")
        assertTrue(cfg.models.isEmpty())
    }

    @Test
    fun `should default specific to null when not provided`() {
        val cfg = ProviderConfig(id = "p1", displayName = "P1")
        assertNull(cfg.specific)
    }

    @Test
    fun `should preserve id and displayName when provided`() {
        val cfg = ProviderConfig(id = "p1", displayName = "My Provider")
        assertEquals("p1", cfg.id)
        assertEquals("My Provider", cfg.displayName)
    }

    @Test
    fun `should return OpenAI default url when resolving baseUrl for OPENAI type with blank baseUrl`() {
        val cfg = ProviderConfig(id = "p1", displayName = "P1", type = ProviderType.OPENAI)
        assertEquals(ProviderConfig.DEFAULT_OPENAI_BASE_URL, cfg.resolvedBaseUrl())
    }

    @Test
    fun `should return Anthropic default url when resolving baseUrl for ANTHROPIC type with blank baseUrl`() {
        val cfg = ProviderConfig(id = "p1", displayName = "P1", type = ProviderType.ANTHROPIC)
        assertEquals(ProviderConfig.DEFAULT_ANTHROPIC_BASE_URL, cfg.resolvedBaseUrl())
    }

    @Test
    fun `should return Gemini default url when resolving baseUrl for GEMINI type with blank baseUrl`() {
        val cfg = ProviderConfig(id = "p1", displayName = "P1", type = ProviderType.GEMINI)
        assertEquals(ProviderConfig.DEFAULT_GEMINI_BASE_URL, cfg.resolvedBaseUrl())
    }

    @Test
    fun `should strip trailing slash when resolving baseUrl with trailing slash`() {
        val cfg = ProviderConfig(
            id = "p1",
            displayName = "P1",
            type = ProviderType.OPENAI,
            baseUrl = "https://custom.example.com/v1/",
        )
        assertEquals("https://custom.example.com/v1", cfg.resolvedBaseUrl())
    }

    @Test
    fun `should return explicit baseUrl when set and ignore default`() {
        val cfg = ProviderConfig(
            id = "p1",
            displayName = "P1",
            type = ProviderType.OPENAI,
            baseUrl = "https://custom.example.com/v1",
        )
        assertEquals("https://custom.example.com/v1", cfg.resolvedBaseUrl())
    }

    @Test
    fun `should return OpenAI specific config when resolving for OPENAI type with null specific`() {
        val cfg = ProviderConfig(id = "p1", displayName = "P1", type = ProviderType.OPENAI)
        val resolved = cfg.resolvedSpecific()
        assertNotNull(resolved)
        assertTrue(resolved is ProviderSpecificConfig.OpenAI)
    }

    @Test
    fun `should return Anthropic specific config when resolving for ANTHROPIC type with null specific`() {
        val cfg = ProviderConfig(id = "p1", displayName = "P1", type = ProviderType.ANTHROPIC)
        val resolved = cfg.resolvedSpecific()
        assertTrue(resolved is ProviderSpecificConfig.Anthropic)
    }

    @Test
    fun `should return Gemini specific config when resolving for GEMINI type with null specific`() {
        val cfg = ProviderConfig(id = "p1", displayName = "P1", type = ProviderType.GEMINI)
        val resolved = cfg.resolvedSpecific()
        assertTrue(resolved is ProviderSpecificConfig.Gemini)
    }

    @Test
    fun `should return provided specific when not null instead of default`() {
        val custom = ProviderSpecificConfig.OpenAI(useResponseApi = true)
        val cfg = ProviderConfig(
            id = "p1",
            displayName = "P1",
            type = ProviderType.OPENAI,
            specific = custom,
        )
        val resolved = cfg.resolvedSpecific()
        assertTrue(resolved is ProviderSpecificConfig.OpenAI)
        assertTrue((resolved as ProviderSpecificConfig.OpenAI).useResponseApi)
    }

    @Test
    fun `should expose default url constants on companion`() {
        assertEquals("https://api.openai.com/v1", ProviderConfig.DEFAULT_OPENAI_BASE_URL)
        assertEquals("https://api.anthropic.com/v1", ProviderConfig.DEFAULT_ANTHROPIC_BASE_URL)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            ProviderConfig.DEFAULT_GEMINI_BASE_URL,
        )
    }
}
