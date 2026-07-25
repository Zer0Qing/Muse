package io.zer0.ai.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * kotlinx.serialization 序列化/反序列化测试。
 *
 * 覆盖带 @Serializable 注解的核心数据类:
 *  - UIMessage / ToolCall / MessageRole
 *  - ProviderConfig / ProviderType / Model
 *  - ProviderSpecificConfig sealed class(带 type 区分字段)
 *
 * ChatCompletion / ChatRequest / ToolDefinition / AbortSignal 未标注 @Serializable,
 * 故跳过对应序列化测试(见任务要求)。
 */
class SerializationTest {

    /** 与 common 模块 AppJson 行为对齐:宽松解析 + 编码默认值。 */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    // ---------- MessageRole ----------

    @Test
    fun `should round-trip MessageRole enum when serializing`() {
        for (role in MessageRole.values()) {
            val s = json.encodeToString(MessageRole.serializer(), role)
            val back = json.decodeFromString(MessageRole.serializer(), s)
            assertEquals(role, back)
        }
    }

    @Test
    fun `should serialize MessageRole as plain string when using enum serializer`() {
        val s = json.encodeToString(MessageRole.serializer(), MessageRole.ASSISTANT)
        assertEquals("\"ASSISTANT\"", s)
    }

    // ---------- ToolCall ----------

    @Test
    fun `should round-trip ToolCall when serializing`() {
        val tc = ToolCall(id = "call_1", name = "getWeather", arguments = "{\"city\":\"北京\"}")
        val s = json.encodeToString(ToolCall.serializer(), tc)
        val back = json.decodeFromString(ToolCall.serializer(), s)
        assertEquals(tc, back)
    }

    @Test
    fun `should produce JSON with required fields when serializing ToolCall`() {
        val tc = ToolCall(id = "call_1", name = "getWeather", arguments = "{}")
        val s = json.encodeToString(ToolCall.serializer(), tc)
        val obj = json.parseToJsonElement(s).jsonObject
        assertEquals("call_1", obj["id"]!!.jsonPrimitive.content)
        assertEquals("getWeather", obj["name"]!!.jsonPrimitive.content)
        assertEquals("{}", obj["arguments"]!!.jsonPrimitive.content)
    }

    // ---------- UIMessage ----------

    @Test
    fun `should round-trip UIMessage when serializing with minimal fields`() {
        val msg = UIMessage(role = MessageRole.USER, content = "hi")
        val s = json.encodeToString(UIMessage.serializer(), msg)
        val back = json.decodeFromString(UIMessage.serializer(), s)
        assertEquals(msg.role, back.role)
        assertEquals(msg.content, back.content)
        assertEquals(msg.id, back.id)
    }

    @Test
    fun `should round-trip UIMessage when serializing with all fields populated`() {
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "answer",
            reasoning = "thoughts",
            modelId = "gpt-4o",
            imageUrls = listOf("https://img.example.com/1.png"),
            toolCalls = listOf(ToolCall(id = "tc1", name = "search", arguments = "{}")),
            toolCallId = "call_abc",
            favorite = true,
            citationUrls = listOf("https://example.com/cite"),
            imageBase64List = listOf("base64data"),
        )
        val s = json.encodeToString(UIMessage.serializer(), msg)
        val back = json.decodeFromString(UIMessage.serializer(), s)
        assertEquals(msg.role, back.role)
        assertEquals(msg.content, back.content)
        assertEquals(msg.reasoning, back.reasoning)
        assertEquals(msg.modelId, back.modelId)
        assertEquals(msg.imageUrls, back.imageUrls)
        assertEquals(msg.toolCalls, back.toolCalls)
        assertEquals(msg.toolCallId, back.toolCallId)
        assertEquals(msg.favorite, back.favorite)
        assertEquals(msg.citationUrls, back.citationUrls)
        assertEquals(msg.imageBase64List, back.imageBase64List)
        assertEquals(msg.id, back.id)
    }

    @Test
    fun `should preserve role as enum string when serializing UIMessage`() {
        val msg = UIMessage(role = MessageRole.SYSTEM, content = "system prompt")
        val s = json.encodeToString(UIMessage.serializer(), msg)
        val obj = json.parseToJsonElement(s).jsonObject
        assertEquals("SYSTEM", obj["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `should tolerate unknown fields when deserializing UIMessage`() {
        val msg = UIMessage(role = MessageRole.USER, content = "hi")
        val s = json.encodeToString(UIMessage.serializer(), msg)
        val withUnknown = s.removeSuffix("}") + ",\"unknownField\":123}"
        val back = json.decodeFromString(UIMessage.serializer(), withUnknown)
        assertEquals(msg.role, back.role)
        assertEquals(msg.content, back.content)
    }

    // ---------- ProviderType ----------

    @Test
    fun `should round-trip ProviderType enum when serializing`() {
        for (t in ProviderType.values()) {
            val s = json.encodeToString(ProviderType.serializer(), t)
            val back = json.decodeFromString(ProviderType.serializer(), s)
            assertEquals(t, back)
        }
    }

    // ---------- Model ----------

    @Test
    fun `should round-trip Model when serializing with defaults`() {
        val m = Model(id = "gpt-4o", providerId = "p1")
        val s = json.encodeToString(Model.serializer(), m)
        val back = json.decodeFromString(Model.serializer(), s)
        assertEquals(m.id, back.id)
        assertEquals(m.providerId, back.providerId)
        assertEquals(m.name, back.name)
    }

    @Test
    fun `should preserve abilities and tools when serializing Model`() {
        val m = Model(
            id = "gpt-5",
            providerId = "p1",
            abilities = setOf(ModelAbility.TOOL, ModelAbility.REASONING),
            tools = setOf(BuiltInTool.SEARCH),
        )
        val s = json.encodeToString(Model.serializer(), m)
        val back = json.decodeFromString(Model.serializer(), s)
        assertEquals(m.abilities, back.abilities)
        assertEquals(m.tools, back.tools)
    }

    // ---------- ProviderConfig ----------

    @Test
    fun `should round-trip ProviderConfig when serializing with minimal fields`() {
        val cfg = ProviderConfig(id = "p1", displayName = "P1")
        val s = json.encodeToString(ProviderConfig.serializer(), cfg)
        val back = json.decodeFromString(ProviderConfig.serializer(), s)
        assertEquals(cfg.id, back.id)
        assertEquals(cfg.displayName, back.displayName)
        assertEquals(cfg.type, back.type)
        assertEquals(cfg.enabled, back.enabled)
    }

    @Test
    fun `should round-trip ProviderConfig when serializing with models list`() {
        val cfg = ProviderConfig(
            id = "p1",
            displayName = "P1",
            type = ProviderType.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-xxx",
            models = listOf(
                Model(id = "gpt-4o", providerId = "p1"),
                Model(id = "gpt-4o-mini", providerId = "p1"),
            ),
        )
        val s = json.encodeToString(ProviderConfig.serializer(), cfg)
        val back = json.decodeFromString(ProviderConfig.serializer(), s)
        assertEquals(cfg.id, back.id)
        assertEquals(cfg.type, back.type)
        assertEquals(cfg.apiKey, back.apiKey)
        assertEquals(2, back.models.size)
        assertEquals("gpt-4o", back.models[0].id)
        assertEquals("gpt-4o-mini", back.models[1].id)
    }

    @Test
    fun `should preserve balance fields when serializing ProviderConfig`() {
        val cfg = ProviderConfig(
            id = "p1",
            displayName = "P1",
            balanceApiPath = "/dashboard/billing/usage",
            balanceResultPath = "\$.data.total_usage",
        )
        val s = json.encodeToString(ProviderConfig.serializer(), cfg)
        val back = json.decodeFromString(ProviderConfig.serializer(), s)
        assertEquals(cfg.balanceApiPath, back.balanceApiPath)
        assertEquals(cfg.balanceResultPath, back.balanceResultPath)
    }

    // ---------- ProviderSpecificConfig (sealed class) ----------

    @Test
    fun `should attach type discriminator when serializing ProviderSpecificConfig OpenAI`() {
        val cfg = ProviderConfig(
            id = "p1",
            displayName = "P1",
            type = ProviderType.OPENAI,
            specific = ProviderSpecificConfig.OpenAI(useResponseApi = true),
        )
        val s = json.encodeToString(ProviderConfig.serializer(), cfg)
        val obj = json.parseToJsonElement(s).jsonObject
        assertNotNull(obj["specific"])
        val specific = obj["specific"]!!.jsonObject
        assertEquals("OpenAI", specific["type"]!!.jsonPrimitive.content)
        assertEquals(true, specific["useResponseApi"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `should attach type discriminator when serializing ProviderSpecificConfig Anthropic`() {
        val cfg = ProviderConfig(
            id = "p1",
            displayName = "P1",
            type = ProviderType.ANTHROPIC,
            specific = ProviderSpecificConfig.Anthropic(promptCaching = true),
        )
        val s = json.encodeToString(ProviderConfig.serializer(), cfg)
        val obj = json.parseToJsonElement(s).jsonObject
        val specific = obj["specific"]!!.jsonObject
        assertEquals("Anthropic", specific["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `should attach type discriminator when serializing ProviderSpecificConfig Gemini`() {
        val cfg = ProviderConfig(
            id = "p1",
            displayName = "P1",
            type = ProviderType.GEMINI,
            specific = ProviderSpecificConfig.Gemini(useVertexAI = true),
        )
        val s = json.encodeToString(ProviderConfig.serializer(), cfg)
        val obj = json.parseToJsonElement(s).jsonObject
        val specific = obj["specific"]!!.jsonObject
        assertEquals("Gemini", specific["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `should deserialize correct ProviderSpecificConfig subtype based on type field`() {
        val jsonStr = """
            {
              "id": "p1",
              "displayName": "P1",
              "type": "GEMINI",
              "specific": {
                "type": "Gemini",
                "useVertexAI": true,
                "projectId": "my-gcp-project"
              }
            }
        """.trimIndent()
        val back = json.decodeFromString(ProviderConfig.serializer(), jsonStr)
        val specific = back.specific
        assertNotNull(specific)
        assertTrue(specific is ProviderSpecificConfig.Gemini)
        assertEquals(true, (specific as ProviderSpecificConfig.Gemini).useVertexAI)
        assertEquals("my-gcp-project", specific.projectId)
    }

    @Test
    fun `should preserve default specific null when ProviderConfig has no specific field`() {
        val jsonStr = """
            {
              "id": "p1",
              "displayName": "P1",
              "type": "OPENAI"
            }
        """.trimIndent()
        val back = json.decodeFromString(ProviderConfig.serializer(), jsonStr)
        // explicitNulls=false + 缺省字段 → 反序列化得到 null
        assertEquals(null, back.specific)
        // resolvedSpecific 仍可兜底为 OpenAI 默认配置
        assertTrue(back.resolvedSpecific() is ProviderSpecificConfig.OpenAI)
    }
}
