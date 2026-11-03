package io.zer0.muse.conversation.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowReaderTest {
    @Test
    fun `window reader does not load whole conversation`() = runTest {
        val source = RecordingWindowSource()
        val reader = ConversationWindowReader(source)

        val result = reader.load(address(), selectionSetId(), around = "slot-5", limit = 20)

        assertEquals(20, result.slots.size)
        assertEquals(1, source.windowReads)
        assertEquals(1, source.partBatches)
    }

    @Test
    fun `empty selection window performs no body reads`() = runTest {
        val source = RecordingWindowSource(empty = true)
        val reader = ConversationWindowReader(source)

        val result = reader.load(address(), selectionSetId(), around = null, limit = 20)

        assertEquals(emptyList<ConversationWindowSlot>(), result.slots)
        assertEquals(0, source.partBatches)
    }

    @Test
    fun `window preserves each slot role without inferring it from parts`() = runTest {
        val source = object : ConversationWindowSource {
            override suspend fun readSlots(address: ConversationAddress, selectionSetId: SelectionSetId, around: String?, limit: Int) =
                listOf(
                    WindowSlotReference("slot-user", "message-user", SlotKind.User),
                    WindowSlotReference("slot-assistant", "message-assistant", SlotKind.Assistant),
                )

            override suspend fun readParts(messageIds: List<String>) = emptyMap<String, List<MessagePart>>()
        }

        val window = ConversationWindowReader(source).load(
            address = address(),
            selectionSetId = selectionSetId(),
            around = null,
            limit = 10,
        )

        assertEquals(listOf(SlotKind.User, SlotKind.Assistant), window.slots.map { it.kind })
    }

    private fun address() = ConversationAddress("main", "session-1")

    private fun selectionSetId() = SelectionSetId("selection-1")

    private class RecordingWindowSource(
        private val empty: Boolean = false,
    ) : ConversationWindowSource {
        var windowReads = 0
        var partBatches = 0

        override suspend fun readSlots(
            address: ConversationAddress,
            selectionSetId: SelectionSetId,
            around: String?,
            limit: Int,
        ): List<WindowSlotReference> {
            windowReads++
            if (empty) return emptyList()
            return List(limit) { index ->
                WindowSlotReference(
                    slotId = "slot-$index",
                    messageId = "message-$index",
                    kind = if (index % 2 == 0) SlotKind.User else SlotKind.Assistant,
                )
            }
        }

        override suspend fun readParts(messageIds: List<String>): Map<String, List<MessagePart>> {
            partBatches++
            return messageIds.associateWith { listOf(MessagePart("part-$it", PartKind.Text, 1, it)) }
        }
    }
}
