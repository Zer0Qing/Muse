package io.zer0.muse.conversation.render

import io.zer0.ai.core.AttachmentRef
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCallInfo
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LegacyConversationProjectionTest {
    @Test
    fun `legacy message keeps rich payloads beside markdown render slots`() {
        val artifactIds = listOf("artifact-1", "artifact-2")
        val attachments = listOf(
            AttachmentRef(
                id = "attachment-1",
                name = "report.pdf",
                mimeType = "application/pdf",
                size = 42,
                workspacePath = "attachments/report.pdf",
            ),
        )
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "hello **world**",
            reasoning = "thinking",
            mood = "calm",
            imageUrls = listOf("file:///image.png"),
            voiceUri = "file:///voice.m4a",
            voiceDurationMs = 1200,
            attachments = attachments,
            artifactIds = artifactIds,
            toolCallInfo = ToolCallInfo("show_card", "{}", "ok", isSuccess = true),
        )

        val projection = LegacyConversationProjector.project(
            rowId = "slot-1",
            message = message,
            complete = true,
        )

        assertEquals(message.id.toString(), projection.messageId)
        assertSame(message, projection.source)
        assertEquals("hello **world**", projection.markdown.single().text)
        assertEquals("thinking", projection.reasoning)
        assertEquals("calm", projection.mood)
        assertEquals(listOf("file:///image.png"), projection.imageUrls)
        assertEquals("file:///voice.m4a", projection.voiceUri)
        assertEquals(1200L, projection.voiceDurationMs)
        assertEquals(attachments, projection.attachments)
        assertEquals(artifactIds, projection.artifactIds)
        assertEquals("show_card", projection.toolCall?.toolName)
    }

    @Test
    fun `multiple rich parts keep deterministic slot order`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "content",
            reasoning = "reasoning",
            mood = "mood",
            reflection = "reflection",
            imageUrls = listOf("file:///one.png"),
            attachments = listOf(AttachmentRef("a1", "one.txt")),
            translations = mapOf("English" to "translated body"),
        )

        val projection = LegacyConversationProjector.project(
            rowId = "slot-2",
            message = message,
            complete = false,
        )

        assertEquals(
            listOf(
                LegacyRenderSlotKind.Reasoning,
                LegacyRenderSlotKind.Mood,
                LegacyRenderSlotKind.Body,
                LegacyRenderSlotKind.Reflection,
                LegacyRenderSlotKind.Images,
                LegacyRenderSlotKind.Attachments,
                LegacyRenderSlotKind.Translations,
            ),
            projection.slots.map { it.kind },
        )
        assertEquals(
            projection.slots.map { it.slotId }.distinct().size,
            projection.slots.size,
        )
    }

    @Test
    fun `quote translation and variant metadata survive projection`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "translated body",
            quotedContent = "quoted original",
            translationSourceId = "source-message",
            translations = mapOf("English" to "translated body"),
            variantGroupId = "variant-group",
            variantIndex = 2,
            variantCount = 4,
            parentGroupId = "parent-group",
        )

        val projection = LegacyConversationProjector.project("slot-3", message, complete = true)

        assertEquals("quoted original", projection.quotedContent)
        assertEquals("source-message", projection.translationSourceId)
        assertEquals(mapOf("English" to "translated body"), projection.translations)
        assertEquals("variant-group", projection.variantGroupId)
        assertEquals(2, projection.variantIndex)
        assertEquals(4, projection.variantCount)
        assertEquals("parent-group", projection.parentGroupId)
    }
}
