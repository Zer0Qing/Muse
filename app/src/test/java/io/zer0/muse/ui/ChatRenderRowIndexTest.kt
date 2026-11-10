package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRenderRowIndexTest {
    @Test
    fun `message index maps to the first lazy row of that message`() {
        val rows =
            listOf(
                row("a", messageIndex = 0),
                row("b0", messageIndex = 1),
                row("b1", messageIndex = 1),
                row("b2", messageIndex = 1),
                row("c", messageIndex = 2),
            )

        assertEquals(0, ChatRenderRowIndex.firstRowForMessage(rows, 0))
        assertEquals(1, ChatRenderRowIndex.firstRowForMessage(rows, 1))
        assertEquals(4, ChatRenderRowIndex.firstRowForMessage(rows, 2))
    }

    @Test
    fun `last message maps to its final rendered row`() {
        val rows =
            listOf(
                row("a", messageIndex = 0),
                row("b0", messageIndex = 1),
                row("b1", messageIndex = 1),
            )

        assertEquals(2, ChatRenderRowIndex.lastRowForMessage(rows, 1))
    }

    @Test
    fun `missing index falls back without leaving list bounds`() {
        val rows = listOf(row("a", messageIndex = 0), row("a1", messageIndex = 0))

        assertEquals(5, ChatRenderRowIndex.firstRowForMessage(rows, messageIndex = 5, fallback = 5))
        assertEquals(1, ChatRenderRowIndex.lastRowForMessage(rows, messageIndex = 5, fallback = 1))
    }

    @Test
    fun `lazy row index maps back to the owning message index`() {
        val rows =
            listOf(
                row("a", messageIndex = 0),
                row("b0", messageIndex = 1),
                row("b1", messageIndex = 1),
                row("c", messageIndex = 2),
            )

        assertEquals(0, ChatRenderRowIndex.messageAtRow(rows, rowIndex = 0))
        assertEquals(1, ChatRenderRowIndex.messageAtRow(rows, rowIndex = 2))
        assertEquals(2, ChatRenderRowIndex.messageAtRow(rows, rowIndex = 3))
        assertEquals(null, ChatRenderRowIndex.messageAtRow(rows, rowIndex = 4))
    }

    private fun row(key: String, messageIndex: Int) = ChatMessageRenderRow(
        key = key,
        message = null,
        chunk =
        ChatRenderChunk(
            key = key,
            text = key,
            index = 0,
            count = 1,
            sourceStart = 0,
            sourceEnd = key.length,
        ),
        messageIndex = messageIndex,
    )
}
