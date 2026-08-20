package io.zer0.muse.data.chat.rewrite

import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MessagePartEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageProjectionTest {

    @Test
    fun legacyMessageProjectsReasoningTextAndToolMetadata() {
        val message = MessageEntity(
            id = "m1",
            sessionId = "s1",
            role = "ASSISTANT",
            content = "answer",
            reasoning = "thought",
            toolCallInfoJson = "{\"name\":\"search\"}",
            seq = 2,
            createdAt = 20,
        )

        val projected = MessageProjector.project(message, emptyList())

        assertTrue(projected.isLegacyProjection)
        assertEquals("answer", projected.content)
        assertEquals("thought", projected.reasoning)
        assertEquals(listOf("reasoning", "text", "tool"), projected.parts.map { it.kind })
    }

    @Test
    fun storedPartsTakePrecedenceAndDeletedMessagesAreExcludedFromOrdering() {
        val legacy = MessageEntity("m1", "s1", "USER", "old", seq = 1, createdAt = 1)
        val structured = MessageEntity("m2", "s1", "ASSISTANT", "old", seq = 2, commitSeq = 10, createdAt = 2)
        val deleted = MessageEntity("m3", "s1", "USER", "deleted", seq = 3, commitSeq = 11, createdAt = 3, deletedAt = 4)
        val parts = listOf(MessagePartEntity("m2", 0, "text", "new", createdAt = 2))

        val projected = MessageProjector.project(structured, parts)
        assertFalse(projected.isLegacyProjection)
        assertEquals("new", projected.content)
        assertEquals(listOf("m1", "m2"), MessageProjector.order(listOf(deleted, structured, legacy), true).map { it.id })
    }
}
