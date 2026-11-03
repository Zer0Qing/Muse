package io.zer0.muse.conversation.core

data class RunIdentity(
    val sessionId: String,
    val incarnation: Long,
    val turnId: String,
    val generationId: String,
    val streamId: String,
    val selectionSetId: String,
) {
    init {
        require(sessionId.isNotBlank()) { "session id must not be blank" }
        require(incarnation >= 0) { "incarnation must not be negative" }
        require(turnId.isNotBlank()) { "turn id must not be blank" }
        require(generationId.isNotBlank()) { "generation id must not be blank" }
        require(streamId.isNotBlank()) { "stream id must not be blank" }
        require(selectionSetId.isNotBlank()) { "selection set id must not be blank" }
    }
}
