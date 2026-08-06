package io.zer0.muse.data.assistant

import io.zer0.common.AppJson
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * R-TEST-16: 助手全字段序列化往返与坏 JSON 样本。
 */
class AssistantEntitySerializationTest {

    @Test
    fun `full assistant round trips through json`() {
        val entity = fullAssistant()
        val json = AppJson.encodeToString(AssistantEntity.serializer(), entity)
        val decoded = AppJson.decodeFromString(AssistantEntity.serializer(), json)
        assertEquals(entity, decoded)
    }

    @Test
    fun `malformed json fails parsing`() {
        assertThrows(SerializationException::class.java) {
            AppJson.decodeFromString(AssistantEntity.serializer(), "{broken")
        }
    }

    @Test
    fun `missing required id fails parsing`() {
        val json = """{"name":"缺 id 助手"}"""
        assertThrows(SerializationException::class.java) {
            AppJson.decodeFromString(AssistantEntity.serializer(), json)
        }
    }

    @Test
    fun `extra unknown fields are ignored`() {
        val json = """{"id":"a1","name":"助手","futureField":123}"""
        val decoded = AppJson.decodeFromString(AssistantEntity.serializer(), json)
        assertEquals("a1", decoded.id)
        assertEquals("助手", decoded.name)
    }

    private fun fullAssistant() = AssistantEntity(
        id = "orig-id",
        name = "全字段助手",
        sortIndex = 3,
        createdAt = 111L,
        updatedAt = 222L,
        modelId = "gpt-test",
        providerId = "openai",
        temperature = 0.7f,
        topP = 0.9f,
        maxTokens = 2048,
        contextMessageSize = 32,
        reasoningLevel = "HIGH",
        systemPrompt = "你是测试助手",
        messageTemplate = "{{content}}",
        presetMessagesJson = """[{"role":"system","content":"hello"}]""",
        toolIdsJson = """["web_search","calculator"]""",
        mcpServerIdsJson = """["server-1"]""",
        streamOutput = false,
        memoryEnabled = true,
        useGlobalMemory = false,
        enableRecentChatsReference = false,
        enableTimeReminder = false,
        avatarEmoji = "🤖",
        avatarImageUrl = "",
        backgroundUrl = "https://example.com/bg.png",
        backgroundOpacity = 0.8f,
        useGradientBackground = true,
        tagsJson = """["测试","中文"]""",
        capabilitiesJson = """["code","write"]""",
        quickMessageIdsJson = """["q1"]""",
        lorebookIdsJson = """["lb1"]""",
        modeInjectionIdsJson = """["m1"]""",
        skillIdsJson = """["s1"]""",
        toolModelId = "tool-model",
        customHeadersJson = """{"Authorization":"Bearer x"}""",
        customBodiesJson = """{"temperature":0.2}""",
        regexRulesJson = """[{"pattern":"a","replacement":"b"}]""",
        knowledgeBaseIdsJson = """["kb1"]""",
        ragConfigOverride = """{"topK":5}""",
        messageCount = 12,
        lastUsedAt = 333L,
        summary = "一句话简介",
        useAssistantName = true,
        allowGroupChat = false,
    )
}
