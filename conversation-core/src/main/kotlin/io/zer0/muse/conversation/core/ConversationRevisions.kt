package io.zer0.muse.conversation.core

data class ConversationIncarnation(val value: Long) {
    init {
        require(value >= 0) { "incarnation must not be negative" }
    }
}

data class SessionRevision(val value: Long) {
    init {
        require(value >= 0) { "session revision must not be negative" }
    }
}

data class DraftRevision(val value: Long) {
    init {
        require(value >= 0) { "draft revision must not be negative" }
    }
}

data class CommittedWatermark(
    val turnId: String,
    val sequenceInStream: Long,
) {
    init {
        require(turnId.isNotBlank()) { "turn id must not be blank" }
        require(sequenceInStream >= 0) { "committed sequence must not be negative" }
    }
}

data class SelectionSetId(val value: String) {
    init {
        require(value.isNotBlank()) { "selection set id must not be blank" }
    }
}
