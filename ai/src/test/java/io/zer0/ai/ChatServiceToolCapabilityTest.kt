package io.zer0.ai

import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ModelVerification
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.ToolDefinition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatServiceToolCapabilityTest {

    private val tools = listOf(
        ToolDefinition(
            name = "echo",
            description = "Echo text",
            parametersJsonSchema = """{"type":"object","properties":{}}""",
        ),
    )

    private val mcpTools = listOf(
        ToolDefinition(
            name = "mcp_feishu_remote__search-doc",
            description = "Search a Feishu document",
            parametersJsonSchema = """{"type":"object","properties":{}}""",
        ),
    )

    @Test
    fun unknownRelayModelKeepsToolsEnabled() {
        val model = Model(
            id = "custom-relay-model",
            providerId = "relay",
            verification = ModelVerification.UNVERIFIED,
        )
        val config = ProviderConfig(
            id = "relay",
            displayName = "Relay",
            type = ProviderType.OPENAI,
            baseUrl = "https://relay.example.com/v1",
        )

        assertTrue(shouldSendTools(model, config, tools))
    }

    @Test
    fun explicitlyNonToolModelDropsTools() {
        val model = Model(
            id = "reasoning-only",
            providerId = "openai",
            abilities = setOf(ModelAbility.REASONING),
        )
        val config = ProviderConfig(
            id = "openai",
            displayName = "OpenAI",
            type = ProviderType.OPENAI,
            baseUrl = "https://api.openai.com/v1",
        )

        assertFalse(shouldSendTools(model, config, tools))
    }

    @Test
    fun explicitlyBoundMcpToolsAreNotSilentlyDroppedByUnknownModelMetadata() {
        val model = Model(
            id = "custom-relay-model",
            providerId = "relay",
        )
        val config = ProviderConfig(
            id = "relay",
            displayName = "Relay",
            type = ProviderType.OPENAI,
            baseUrl = "https://relay.example.com/v1",
        )

        assertTrue(shouldSendTools(model, config, mcpTools))
    }

    @Test
    fun explicitlyNonToolModelStillDropsMcpTools() {
        val model = Model(
            id = "custom-reasoning-relay-model",
            providerId = "relay",
            abilities = setOf(ModelAbility.REASONING),
        )
        val config = ProviderConfig(
            id = "relay",
            displayName = "Relay",
            type = ProviderType.OPENAI,
            baseUrl = "https://relay.example.com/v1",
        )

        assertFalse(shouldSendTools(model, config, mcpTools))
    }

    @Test
    fun emptyToolListIsNotSent() {
        val model = Model(
            id = "custom-relay-model",
            providerId = "relay",
            verification = ModelVerification.UNVERIFIED,
        )
        val config = ProviderConfig(
            id = "relay",
            displayName = "Relay",
            type = ProviderType.OPENAI,
            baseUrl = "https://relay.example.com/v1",
        )

        assertFalse(shouldSendTools(model, config, emptyList()))
    }
}
