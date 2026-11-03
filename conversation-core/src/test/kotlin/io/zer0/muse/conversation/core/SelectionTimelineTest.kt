package io.zer0.muse.conversation.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SelectionTimelineTest {
    @Test
    fun `editing an earlier variant keeps its logical position`() {
        val slots =
            listOf(
                user("u1", 1, "u1a"),
                reply("a1", 2, "u1", "a1a"),
                user("u2", 3, "u2a"),
                reply("a2", 4, "u2", "a2a"),
            )
        val selection =
            ConversationSelection(
                user = mapOf("u1" to "u1b", "u2" to "u2a"),
                assistant = mapOf("a1" to "a1a", "a2" to "a2a"),
            )

        val projected = TimelineProjection.select(slots, selection)

        assertEquals(listOf("u1", "a1", "u2", "a2"), projected.map { it.id })
        assertEquals("u1b", projected.first().selectedMessageId)
    }

    @Test
    fun `selection snapshots are immutable values`() {
        val first =
            SelectionSet(
                id = SelectionSetId("selection-1"),
                revision = 1,
                selection = ConversationSelection(user = mapOf("u1" to "u1a")),
            )
        val second = first.copy(revision = 2)

        assertNotEquals(first.revision, second.revision)
        assertEquals("u1a", second.selection.user.getValue("u1"))
    }

    @Test
    fun `projection rejects duplicate and out of order logical slots`() {
        val duplicate =
            listOf(
                user("u1", 1, "u1a"),
                user("u2", 1, "u2a"),
            )
        val invalid = runCatching { TimelineProjection.select(duplicate, ConversationSelection()) }

        assertEquals(true, invalid.isFailure)
        assertEquals(IllegalArgumentException::class.java, invalid.exceptionOrNull()?.javaClass)
    }

    private fun user(id: String, position: Long, messageId: String) = DisplaySlot(
        id = id,
        kind = SlotKind.User,
        parentSlotId = null,
        logicalPosition = position,
        selectedMessageId = messageId,
    )

    private fun reply(id: String, position: Long, parent: String, messageId: String) = DisplaySlot(
        id = id,
        kind = SlotKind.Assistant,
        parentSlotId = parent,
        logicalPosition = position,
        selectedMessageId = messageId,
    )
}
