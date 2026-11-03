package io.zer0.muse.conversation.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

sealed interface StartResult {
    data class Started(val identity: RunIdentity) : StartResult

    data class ReplacedPrevious(val identity: RunIdentity) : StartResult

    data object IncarnationInvalid : StartResult
}

sealed interface EventAcceptance {
    data object Accepted : EventAcceptance

    data class Rejected(val reason: String) : EventAcceptance
}

/**
 * Owns one generation job for a conversation independent of page attachment.
 * UI detach changes observation only; it never cancels this job.
 */
class ConversationRuntime(
    val sessionId: String,
    private val incarnation: ConversationIncarnation,
    private val selectionSetId: SelectionSetId,
    dispatcher: CoroutineDispatcher,
    private val generation: suspend (generationId: String) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val activeIdentity = AtomicReference<RunIdentity?>(null)
    private val attached = AtomicReference(0)

    @Volatile
    var phase: TurnPhase = TurnPhase.Queued
        private set

    @Volatile
    var isFinished: Boolean = false
        private set

    val activeGenerationId: String?
        get() = activeIdentity.get()?.generationId

    fun attach() {
        attached.updateAndGet { it + 1 }
    }

    fun detach() {
        attached.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
    }

    fun start(turnId: String, generationId: String, streamId: String): StartResult {
        if (phase in TERMINAL_PHASES || phase == TurnPhase.Cancelled || phase == TurnPhase.Interrupted) {
            return StartResult.IncarnationInvalid
        }
        val identity = identityOf(turnId, generationId, streamId)
        val previous = activeIdentity.getAndSet(identity)
        phase = TurnPhase.Preparing
        scope.launch {
            try {
                generation(identity.generationId)
                if (activeIdentity.get() == identity) {
                    phase = TurnPhase.Completed
                    isFinished = true
                }
            } finally {
                if (activeIdentity.get() == identity && !isFinished) {
                    phase = TurnPhase.Interrupted
                }
            }
        }
        return if (previous == null) StartResult.Started(identity) else StartResult.ReplacedPrevious(identity)
    }

    fun accepts(identity: RunIdentity): EventAcceptance {
        val current = activeIdentity.get()
        val reason =
            when {
                current == null -> "no active run"
                identity.sessionId != sessionId -> "session mismatch"
                identity.incarnation != incarnation.value -> "incarnation mismatch"
                identity.selectionSetId != selectionSetId.value -> "selection mismatch"
                identity != current -> "generation mismatch"
                else -> null
            }
        return if (reason == null) EventAcceptance.Accepted else EventAcceptance.Rejected(reason)
    }

    fun invalidate() {
        activeIdentity.set(null)
        phase = TurnPhase.Cancelled
        isFinished = true
        scope.cancel()
    }

    private fun identityOf(turnId: String, generationId: String, streamId: String) = RunIdentity(
        sessionId = sessionId,
        incarnation = incarnation.value,
        turnId = turnId,
        generationId = generationId,
        streamId = streamId,
        selectionSetId = selectionSetId.value,
    )

    private companion object {
        val TERMINAL_PHASES = setOf(TurnPhase.Completed, TurnPhase.Failed)
    }
}
