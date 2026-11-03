package io.zer0.muse.conversation.core

import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandReceiptTest {
    @Test
    fun `same command and payload replays original result`() {
        val guard = IdempotencyGuard()
        val receipt = receipt("cmd-1", "fingerprint-a")

        assertTrue(guard.accept(receipt) is IdempotencyDecision.New)
        val replay = guard.accept(receipt)

        assertTrue(replay is IdempotencyDecision.Replay)
        assertSame(receipt, (replay as IdempotencyDecision.Replay).receipt)
    }

    @Test
    fun `reused command id with different payload conflicts`() {
        val guard = IdempotencyGuard()
        val original = receipt("cmd-1", "fingerprint-a")
        guard.accept(original)

        val conflict = guard.accept(receipt("cmd-1", "fingerprint-b"))

        assertTrue(conflict is IdempotencyDecision.Conflict)
        assertSame(original, (conflict as IdempotencyDecision.Conflict).existing)
    }

    @Test
    fun `commands reject a blank command id`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConversationCommand.Create(
                commandId = "",
                sessionId = "session-1",
                assistantId = "assistant-1",
                createdAt = 1L,
            )
        }
    }

    private fun receipt(commandId: String, fingerprint: String) = CommandReceipt(
        commandId = commandId,
        fingerprint = CommandFingerprint(fingerprint),
        acceptedAt = 1L,
        result = CommandResult.Accepted(
            messageId = "message-1",
            turnId = "turn-1",
        ),
    )
}
