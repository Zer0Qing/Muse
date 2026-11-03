package io.zer0.muse.conversation.core

/**
 * In-memory policy used by tests and callers that already loaded receipt state.
 * Persistent command handling applies the same decision in its transaction.
 */
class IdempotencyGuard {
    private val lock = Any()
    private val receipts = mutableMapOf<String, CommandReceipt>()

    fun accept(receipt: CommandReceipt): IdempotencyDecision = synchronized(lock) {
        val existing = receipts[receipt.commandId]
        when {
            existing == null -> {
                receipts[receipt.commandId] = receipt
                IdempotencyDecision.New(receipt)
            }
            existing.fingerprint == receipt.fingerprint -> IdempotencyDecision.Replay(existing)
            else -> IdempotencyDecision.Conflict(existing)
        }
    }
}
