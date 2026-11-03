package io.zer0.muse.conversation.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConversationIdentityTest {
    @Test
    fun `addresses with different namespaces do not collide`() {
        val main = ConversationAddress("main", "session-1")
        val channel = ConversationAddress("telegram", "session-1")

        assertNotEquals(main, channel)
    }

    @Test
    fun `blank identities are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConversationAddress("main", " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelectionSetId("")
        }
    }

    @Test
    fun `negative sequence is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CommittedWatermark("turn-1", -1)
        }
    }

    @Test
    fun `run identity preserves complete generation identity`() {
        val identity = RunIdentity(
            sessionId = "session-1",
            incarnation = 2,
            turnId = "turn-1",
            generationId = "generation-1",
            streamId = "stream-1",
            selectionSetId = "selection-1",
        )

        assertEquals("turn-1", identity.turnId)
        assertEquals(2, identity.incarnation)
    }
}
