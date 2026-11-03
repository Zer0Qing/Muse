package io.zer0.muse.conversation.core

enum class PartKind {
    Text,
    Reasoning,
    Tool,
    Image,
    Attachment,
    Card,
    Mood,
    Reflection,
    Citation,
}

data class MessagePart(
    val partId: String,
    val kind: PartKind,
    val revision: Long,
    val text: String,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(partId.isNotBlank()) { "part id must not be blank" }
        require(revision >= 0) { "part revision must not be negative" }
    }
}

data class DraftSnapshot(
    val identity: RunIdentity,
    val revision: Long,
    val parts: List<MessagePart>,
    val finished: Boolean,
) {
    init {
        require(revision >= 0) { "draft revision must not be negative" }
        require(parts.map { it.partId }.distinct().size == parts.size) { "part ids must be unique" }
    }
}

enum class TerminalState {
    Completed,
    Interrupted,
    Failed,
    Cancelled,
}

sealed interface DraftEvent {
    val sequence: Long
    val identity: RunIdentity

    data class Start(
        override val sequence: Long,
        override val identity: RunIdentity,
        val startedAt: Long,
    ) : DraftEvent

    data class AppendText(
        override val sequence: Long,
        override val identity: RunIdentity,
        val partId: String,
        val delta: String,
    ) : DraftEvent

    data class ReplaceText(
        override val sequence: Long,
        override val identity: RunIdentity,
        val partId: String,
        val text: String,
    ) : DraftEvent

    data class StartReasoning(
        override val sequence: Long,
        override val identity: RunIdentity,
        val partId: String,
    ) : DraftEvent

    data class AppendReasoning(
        override val sequence: Long,
        override val identity: RunIdentity,
        val partId: String,
        val delta: String,
    ) : DraftEvent

    data class SetMetadataPart(
        override val sequence: Long,
        override val identity: RunIdentity,
        val partId: String,
        val kind: PartKind,
        val text: String,
        val metadata: Map<String, String> = emptyMap(),
    ) : DraftEvent

    data class Finish(
        override val sequence: Long,
        override val identity: RunIdentity,
        val state: TerminalState,
    ) : DraftEvent
}

sealed interface DraftApplyResult {
    data class Applied(val snapshot: DraftSnapshot) : DraftApplyResult

    data class Duplicate(val snapshot: DraftSnapshot) : DraftApplyResult

    data class IgnoredStale(val snapshot: DraftSnapshot) : DraftApplyResult

    data class Rejected(val reason: RejectionReason) : DraftApplyResult
}

enum class RejectionReason {
    NotStarted,
    IdentityMismatch,
    Finished,
    InvalidSequence,
    InvalidPart,
    EmptyAppend,
}

class DraftStore(
    private val parts: MutableList<MessagePart> = mutableListOf(),
    private var identity: RunIdentity? = null,
    private var lastAcceptedSequence: Long = 0,
    private var appliedRevision: Long = 0,
    private var finished: Boolean = false,
    private val acceptedSequences: MutableSet<Long> = mutableSetOf(),
) {
    fun snapshot(): DraftSnapshot = DraftSnapshot(
        identity = identity ?: error("draft has not started"),
        revision = appliedRevision,
        parts = parts.toList(),
        finished = finished,
    )

    fun apply(event: DraftEvent): DraftApplyResult {
        val currentIdentity = identity
        return if (event is DraftEvent.Start) {
            applyStart(event, currentIdentity)
        } else {
            applyAfterStart(event, currentIdentity)
        }
    }

    private fun applyStart(event: DraftEvent.Start, currentIdentity: RunIdentity?): DraftApplyResult {
        val rejection =
            when {
                currentIdentity != null && currentIdentity == event.identity && appliedRevision == 0L ->
                    DraftApplyResult.Duplicate(snapshot())
                currentIdentity != null -> DraftApplyResult.Rejected(RejectionReason.IdentityMismatch)
                event.sequence <= 0 -> DraftApplyResult.Rejected(RejectionReason.InvalidSequence)
                else -> null
            }
        if (rejection != null) return rejection
        identity = event.identity
        lastAcceptedSequence = event.sequence
        acceptedSequences += event.sequence
        appliedRevision = 1
        return DraftApplyResult.Applied(snapshot())
    }

    private fun applyAfterStart(event: DraftEvent, currentIdentity: RunIdentity?): DraftApplyResult {
        val rejection =
            when {
                currentIdentity == null -> DraftApplyResult.Rejected(RejectionReason.NotStarted)
                finished -> DraftApplyResult.Rejected(RejectionReason.Finished)
                currentIdentity != event.identity -> DraftApplyResult.Rejected(RejectionReason.IdentityMismatch)
                event.sequence <= 0 -> DraftApplyResult.Rejected(RejectionReason.InvalidSequence)
                event.sequence < lastAcceptedSequence -> DraftApplyResult.IgnoredStale(snapshot())
                event.sequence in acceptedSequences -> DraftApplyResult.Duplicate(snapshot())
                else -> null
            }
        if (rejection != null) return rejection
        val result = reduce(event)
        if (result is DraftApplyResult.Applied) {
            lastAcceptedSequence = event.sequence
            acceptedSequences += event.sequence
        }
        return result
    }

    private fun reduce(event: DraftEvent): DraftApplyResult = when (event) {
        is DraftEvent.AppendText -> appendText(event)
        is DraftEvent.ReplaceText -> replaceText(event)
        is DraftEvent.StartReasoning -> startReasoning(event)
        is DraftEvent.AppendReasoning -> appendReasoning(event)
        is DraftEvent.SetMetadataPart -> setMetadataPart(event)
        is DraftEvent.Finish -> finish()
        is DraftEvent.Start -> error("start was handled by applyStart")
    }

    private fun appendText(event: DraftEvent.AppendText): DraftApplyResult {
        if (event.delta.isEmpty()) return DraftApplyResult.Rejected(RejectionReason.EmptyAppend)
        val index = parts.indexOfFirst { it.partId == event.partId }
        if (index < 0) {
            parts += MessagePart(event.partId, PartKind.Text, appliedRevision + 1, event.delta)
        } else {
            val part = parts[index]
            if (part.kind != PartKind.Text) return DraftApplyResult.Rejected(RejectionReason.InvalidPart)
            parts[index] = part.copy(revision = appliedRevision + 1, text = part.text + event.delta)
        }
        appliedRevision++
        return DraftApplyResult.Applied(snapshot())
    }

    private fun replaceText(event: DraftEvent.ReplaceText): DraftApplyResult {
        val index = parts.indexOfFirst { it.partId == event.partId }
        if (index < 0) return DraftApplyResult.Rejected(RejectionReason.InvalidPart)
        val part = parts[index]
        if (part.kind != PartKind.Text) return DraftApplyResult.Rejected(RejectionReason.InvalidPart)
        parts[index] = part.copy(revision = appliedRevision + 1, text = event.text)
        appliedRevision++
        return DraftApplyResult.Applied(snapshot())
    }

    private fun startReasoning(event: DraftEvent.StartReasoning): DraftApplyResult {
        if (parts.any { it.partId == event.partId }) return DraftApplyResult.Duplicate(snapshot())
        parts += MessagePart(event.partId, PartKind.Reasoning, appliedRevision + 1, "")
        appliedRevision++
        return DraftApplyResult.Applied(snapshot())
    }

    private fun appendReasoning(event: DraftEvent.AppendReasoning): DraftApplyResult {
        if (event.delta.isEmpty()) return DraftApplyResult.Rejected(RejectionReason.EmptyAppend)
        val index = parts.indexOfFirst { it.partId == event.partId }
        if (index < 0) {
            parts += MessagePart(event.partId, PartKind.Reasoning, appliedRevision + 1, event.delta)
        } else {
            val part = parts[index]
            if (part.kind != PartKind.Reasoning) return DraftApplyResult.Rejected(RejectionReason.InvalidPart)
            parts[index] = part.copy(revision = appliedRevision + 1, text = part.text + event.delta)
        }
        appliedRevision++
        return DraftApplyResult.Applied(snapshot())
    }

    private fun setMetadataPart(event: DraftEvent.SetMetadataPart): DraftApplyResult {
        require(event.kind in METADATA_PART_KINDS) { "unsupported metadata part kind" }
        val index = parts.indexOfFirst { it.partId == event.partId }
        if (index < 0) {
            parts += MessagePart(
                partId = event.partId,
                kind = event.kind,
                revision = appliedRevision + 1,
                text = event.text,
                metadata = event.metadata,
            )
        } else {
            parts[index] = parts[index].copy(
                revision = appliedRevision + 1,
                text = event.text,
                metadata = event.metadata,
            )
        }
        appliedRevision++
        return DraftApplyResult.Applied(snapshot())
    }

    private fun finish(): DraftApplyResult {
        finished = true
        appliedRevision++
        return DraftApplyResult.Applied(snapshot())
    }

    private companion object {
        val METADATA_PART_KINDS =
            setOf(
                PartKind.Image,
                PartKind.Attachment,
                PartKind.Card,
                PartKind.Mood,
                PartKind.Reflection,
                PartKind.Citation,
                PartKind.Tool,
            )
    }
}
