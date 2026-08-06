package io.zer0.muse.importer

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-TEST-20: ConfigImporter 第三方配置导入真实路径测试。
 */
class ConfigImporterTest {

    private lateinit var settings: SettingsRepository
    private lateinit var importer: ConfigImporter

    @Before
    fun setUp() {
        settings = mockk(relaxed = true)
        every { settings.providersFlow } returns flowOf(emptyList())
        coEvery { settings.addProvider(any()) } answers { }
        importer = ConfigImporter(settings)
    }

    @Test
    fun `imports cherry studio provider array`() = runBlocking {
        val json = """
            {
              "providers": [
                {
                  "id": "openai-custom",
                  "name": "OpenAI",
                  "apiHost": "https://api.example.com",
                  "apiKey": "sk-1",
                  "models": ["gpt-4o", {"id": "gpt-4o-mini", "name": "Mini"}]
                }
              ]
            }
        """.trimIndent()

        val result = importer.importFromJson(json)

        assertEquals(1, result.imported)
        assertEquals(0, result.skipped)
        assertEquals(listOf("OpenAI"), result.providers)
    }

    @Test
    fun `imports chatbox settings map with provider id injected`() = runBlocking {
        val json = """
            {
              "settings": {
                "github": {
                  "name": "GitHub",
                  "apiHost": "https://api.github.com",
                  "apiKey": "ghp-test"
                }
              }
            }
        """.trimIndent()

        val result = importer.importFromJson(json)

        assertEquals(1, result.imported)
        assertEquals(listOf("GitHub"), result.providers)
    }

    @Test
    fun `skips duplicate provider id`() = runBlocking {
        val existing = ProviderConfig(
            id = "dup",
            displayName = "Existing",
            type = ProviderType.OPENAI,
            baseUrl = "",
            apiKey = "",
            models = emptyList(),
        )
        every { settings.providersFlow } returns flowOf(listOf(existing))
        val json = """
            {
              "providers": [
                {"id": "dup", "name": "Duplicate", "apiHost": "https://example.com", "apiKey": "k"}
              ]
            }
        """.trimIndent()

        val result = importer.importFromJson(json)

        assertEquals(0, result.imported)
        assertEquals(1, result.skipped)
        assertEquals(listOf("Duplicate"), result.skippedProviders)
    }

    @Test
    fun `invalid json returns empty result`() = runBlocking {
        val result = importer.importFromJson("not json")
        assertEquals(0, result.imported)
        assertEquals(0, result.skipped)
        assertTrue(result.providers.isEmpty())
    }

    @Test
    fun `infers anthropic provider type from host`() = runBlocking {
        val captured = mutableListOf<ProviderConfig>()
        coEvery { settings.addProvider(any()) } answers {
            captured.add(firstArg())
        }
        val json = """
            {
              "providers": [
                {"id": "claude", "name": "Claude", "apiHost": "https://api.anthropic.com", "apiKey": "sk-ant"}
              ]
            }
        """.trimIndent()

        importer.importFromJson(json)

        assertEquals(1, captured.size)
        assertEquals(ProviderType.ANTHROPIC, captured.single().type)
    }
}
