package io.zer0.muse.web

import io.zer0.common.AppJson
import io.zer0.muse.data.assistant.AssistantEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** `assistants.detail` 的载荷投影测试。 */
class HostAssistantDetailCommandsTest {

    private val workDir = Files.createTempDirectory("host-assistant-detail").toFile()
    private val imageDir = File(workDir, "muse_images").apply { mkdirs() }
    private val voiceDir = File(workDir, "voice_messages").apply { mkdirs() }
    private val avatarDir = File(workDir, "avatar").apply { mkdirs() }

    private fun mediaLinks() = HostMediaLinks(HostMediaStore(imageDir, voiceDir, avatarDir))

    private fun assistant(
        personaDefinition: String = "冷静、话少的写作搭档。",
        systemPrompt: String = "你叫 Muse。".repeat(12),
    ) = AssistantEntity(
        id = "default",
        name = "Muse",
        enabled = false,
        modelId = "gpt-4o",
        providerId = "preset_openai",
        temperature = 0.7f,
        topP = 0.9f,
        maxTokens = 4096,
        contextMessageSize = 32,
        reasoningLevel = "HIGH",
        systemPrompt = systemPrompt,
        personaDefinition = personaDefinition,
        toolIdsJson = """["read_file","web_search","memory_search"]""",
        summary = "灵感伙伴",
        avatarImageUrl = "file://${File(avatarDir, "muse.png").apply { writeBytes(PNG_BYTES) }.absolutePath}",
    )

    @Test
    fun `detail carries every display field and rewrites the avatar`() {
        val store = HostMediaStore(imageDir, voiceDir, avatarDir)

        val event = HostReadCommands.assistantDetailEvent(
            assistant = assistant(),
            requestId = "req-detail",
            mediaLinks = HostMediaLinks(store),
        )

        assertEquals(HostReadCommands.TYPE_ASSISTANT_DETAIL, event.type)
        assertEquals("req-detail", event.requestId)
        assertNull(event.error)
        val detail = requireNotNull(event.assistantDetail)
        assertEquals("default", detail.id)
        assertEquals("Muse", detail.name)
        assertEquals("灵感伙伴", detail.description)
        assertTrue("头像应换成媒体路由: ${detail.avatarUri}", detail.avatarUri!!.startsWith(HostMediaLinks.MEDIA_PATH_PREFIX))
        assertEquals("gpt-4o", detail.modelId)
        assertEquals("preset_openai", detail.providerId)
        assertEquals(0.7f, detail.temperature!!, 0.0001f)
        assertEquals(0.9f, detail.topP!!, 0.0001f)
        assertEquals(4096, detail.maxTokens)
        assertEquals(32, detail.contextMessageSize)
        assertEquals("HIGH", detail.reasoningLevel)
        assertEquals(3, detail.toolCount)
        assertEquals("冷静、话少的写作搭档。", detail.personaSummary)
        assertFalse(detail.personaTruncated)
        assertEquals("你叫 Muse。".repeat(12).length, detail.systemPromptLength)
        assertFalse(detail.enabled)
    }

    @Test
    fun `detail never ships the system prompt body`() {
        val prompt = "只属于提示词正文的句子。"

        val event = HostReadCommands.assistantDetailEvent(
            assistant = assistant(systemPrompt = prompt),
            requestId = "req-body",
            mediaLinks = mediaLinks(),
        )

        val wire = AppJson.encodeToString(event)
        assertFalse("提示词正文不该出现在回执里: $wire", wire.contains(prompt))
        assertEquals(prompt.length, event.assistantDetail!!.systemPromptLength)
    }

    @Test
    fun `detail for a missing assistant returns assistant_not_found`() {
        val event = HostReadCommands.assistantDetailEvent(
            assistant = null,
            requestId = "req-missing",
            mediaLinks = mediaLinks(),
        )

        assertEquals(HostReadCommands.TYPE_ASSISTANT_DETAIL, event.type)
        assertEquals("req-missing", event.requestId)
        assertEquals(HostReadCommands.ERROR_ASSISTANT_NOT_FOUND, event.error)
        assertNull(event.assistantDetail)
    }

    @Test
    fun `an over long persona is truncated to the summary limit`() {
        val persona = "人设第一句。".repeat(200)

        val event = HostReadCommands.assistantDetailEvent(
            assistant = assistant(personaDefinition = persona),
            requestId = "req-long",
            mediaLinks = mediaLinks(),
        )

        val detail = requireNotNull(event.assistantDetail)
        assertTrue("人设摘要应被截断到 500 字以内,实际 ${detail.personaSummary.length}", detail.personaSummary.length <= 500)
        assertTrue("截断后应保留开头", persona.startsWith(detail.personaSummary))
        assertTrue(detail.personaTruncated)
    }

    @Test
    fun `an assistant without a persona block reports an empty summary`() {
        val event = HostReadCommands.assistantDetailEvent(
            assistant = assistant(personaDefinition = "   "),
            requestId = "req-blank",
            mediaLinks = mediaLinks(),
        )

        assertEquals("", event.assistantDetail!!.personaSummary)
        assertFalse(event.assistantDetail!!.personaTruncated)
    }

    @Test
    fun `detail survives the wire round trip`() {
        val event = HostReadCommands.assistantDetailEvent(assistant(), "req-wire", mediaLinks())

        val decoded = AppJson.decodeFromString<HostEvent>(AppJson.encodeToString(event))

        assertEquals(event, decoded)
        assertEquals("default", decoded.assistantDetail?.id)
    }

    private companion object {
        val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(24)
    }
}
