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
        // v2.6.1: “显式不支持”由 Provider 能力矩阵终裁（deepseek-reasoner 不发 tools），
        // 而非“abilities 非空且无 TOOL”。
        val model = Model(
            id = "deepseek-reasoner",
            providerId = "deepseek",
            abilities = setOf(ModelAbility.REASONING),
        )
        val config = ProviderConfig(
            id = "deepseek",
            displayName = "DeepSeek",
            type = ProviderType.OPENAI,
            baseUrl = "https://api.deepseek.com/v1",
        )

        assertFalse(shouldSendTools(model, config, tools))
    }

    @Test
    fun partialCapabilityDeclarationStillSendsTools() {
        // v2.6.1 回归：云目录只写了 reasoning 而漏写 toolUse（如 deepseek-flash，
        // abilities=[REASONING]）时，不得把工具链整条切断 —— 未声明 ≠ 不支持。
        val model = Model(
            id = "deepseek-flash",
            providerId = "deepseek",
            abilities = setOf(ModelAbility.REASONING),
        )
        val config = ProviderConfig(
            id = "deepseek",
            displayName = "DeepSeek",
            type = ProviderType.OPENAI,
            baseUrl = "https://api.deepseek.com/v1",
        )

        assertTrue(shouldSendTools(model, config, tools))
    }

    @Test
    fun userEditedModelWithToolsAlwaysSends() {
        // v2.6.1: 用户显式编辑的配置优先级最高 —— 用户开了工具就发。
        val model = Model(
            id = "fictional-model-x",
            providerId = "relay",
            abilities = setOf(ModelAbility.TOOL),
            userEdited = true,
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
    fun userEditedModelWithoutToolsNeverSends() {
        // v2.6.1: 用户显式编辑为“仅推理”时，即便能力矩阵默认支持也别越权放行。
        val model = Model(
            id = "fictional-model-y",
            providerId = "relay",
            abilities = setOf(ModelAbility.REASONING),
            userEdited = true,
        )
        val config = ProviderConfig(
            id = "relay",
            displayName = "Relay",
            type = ProviderType.OPENAI,
            baseUrl = "https://relay.example.com/v1",
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
        // v2.6.1: “显式不支持”仍由能力矩阵终裁（deepseek-reasoner）。
        val model = Model(
            id = "deepseek-reasoner",
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
