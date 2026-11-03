package io.zer0.muse.conversation.core

data class CommandFingerprint(val value: String) {
    init {
        require(value.isNotBlank()) { "command fingerprint must not be blank" }
    }
}

sealed interface CommandResult {
    data class Accepted(
        val messageId: String,
        val turnId: String?,
    ) : CommandResult

    data class ObjectValue(val value: String) : CommandResult
}

data class CommandReceipt(
    val commandId: String,
    val fingerprint: CommandFingerprint,
    val acceptedAt: Long,
    val result: CommandResult,
) {
    init {
        require(commandId.isNotBlank()) { "command id must not be blank" }
        require(acceptedAt >= 0) { "accepted time must not be negative" }
    }
}

sealed interface IdempotencyDecision {
    data class New(val receipt: CommandReceipt) : IdempotencyDecision

    data class Replay(val receipt: CommandReceipt) : IdempotencyDecision

    data class Conflict(val existing: CommandReceipt) : IdempotencyDecision
}
