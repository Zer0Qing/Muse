package io.zer0.ai.plugin

import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ProviderCategory
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-TEST-20: Provider 插件 DTO 与转换纯逻辑测试。
 */
class ProviderPluginRegistryTest {

    private val samplePlugin = ProviderPlugin(
        id = "relay",
        displayName = "Relay",
        description = "test",
        baseUrl = "https://relay.example.com/v1",
        apiKeyPattern = "^sk-",
        models = listOf(
            PluginModel(
                id = "m1",
                displayName = "M1",
                contextWindow = 8192,
                supportsVision = true,
                supportsTools = true,
            ),
        ),
        headers = mapOf("X-Test" to "1"),
        requestTemplate = "{\"model\":\"{{model}}\"}",
        responsePath = "$.output",
        streamResponsePath = "$.delta",
    )

    @Test
    fun `toProviderConfig maps plugin fields`() {
        val config = ProviderPluginRegistry().toProviderConfig(samplePlugin)

        assertEquals("plugin-relay", config.id)
        assertEquals(ProviderType.OPENAI, config.type)
        assertEquals(ProviderCategory.CUSTOM, config.category)
        assertEquals("https://relay.example.com/v1", config.baseUrl)
        assertEquals("", config.apiKey)
        assertEquals(1, config.models.size)
        assertEquals("m1", config.models[0].id)
        assertTrue(config.models[0].supportsVision)
        assertTrue(ModelAbility.TOOL in config.models[0].abilities)

        val custom = config.specific as ProviderSpecificConfig.Custom
        assertEquals(mapOf("X-Test" to "1"), custom.customHeaders)
        assertEquals("{\"model\":\"{{model}}\"}", custom.requestTemplate)
        assertEquals("$.output", custom.responsePath)
        assertEquals("$.delta", custom.streamResponsePath)
    }

    @Test
    fun `plugin serialization round trips`() {
        val json = AppJson.encodeToString(ProviderPlugin.serializer(), samplePlugin)
        val decoded = AppJson.decodeFromString(ProviderPlugin.serializer(), json)
        assertEquals(samplePlugin, decoded)
    }
}
