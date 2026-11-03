package io.zer0.muse.data.conversation

import io.zer0.muse.data.session.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacySlotImporterTest {
    @Test
    fun `imports selected user and assistant order`() {
        val messages = listOf(
            message("u1", "USER", 1, group = "ug1", index = 0, count = 1),
            message("a1", "ASSISTANT", 2, group = "ag1", index = 0, count = 1, parent = "u1"),
            message("u2", "USER", 3, group = "ug2", index = 0, count = 1),
            message("a2", "ASSISTANT", 4, group = "ag2", index = 0, count = 1, parent = "u2"),
        )

        val slots = LegacySlotImporter.import(messages)

        assertEquals(listOf("slot-0", "slot-1", "slot-2", "slot-3"), slots.map { it.slotId })
        assertEquals(listOf("u1", "a1", "u2", "a2"), slots.map { it.messageId })
        assertEquals(listOf(1L, 2L, 3L, 4L), slots.map { it.logicalPosition })
    }

    @Test
    fun `uses the last variant as the current selection`() {
        val messages = listOf(
            message("u1a", "USER", 1, group = "ug1", index = 0, count = 2),
            message("u1b", "USER", 2, group = "ug1", index = 1, count = 2),
            message("a1a", "ASSISTANT", 3, group = "ag1", index = 0, count = 2, parent = "u1b"),
            message("a1b", "ASSISTANT", 4, group = "ag1", index = 1, count = 2, parent = "u1b"),
        )

        val slots = LegacySlotImporter.import(messages)

        assertEquals(listOf("u1b", "a1b"), slots.map { it.messageId })
    }

    private fun message(
        id: String,
        role: String,
        createdAt: Long,
        group: String?,
        index: Int,
        count: Int,
        parent: String? = null,
    ) = MessageEntity(
        id = id,
        sessionId = "session-1",
        role = role,
        content = id,
        createdAt = createdAt,
        parentMessageId = parent,
        variantGroupId = group,
        variantIndex = index,
        variantCount = count,
    )
}
