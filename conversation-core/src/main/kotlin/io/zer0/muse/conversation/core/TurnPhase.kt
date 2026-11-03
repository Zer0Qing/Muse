package io.zer0.muse.conversation.core

enum class TurnPhase {
    Queued,
    Preparing,
    Streaming,
    WaitingTool,
    WaitingApproval,
    Finalizing,
    Completed,
    Cancelled,
    Failed,
    Interrupted,
    Recoverable,
    ;

    val isTerminal: Boolean
        get() = this in TERMINAL

    fun transitionTo(target: TurnPhase): TurnPhase {
        check(!isTerminal) { "terminal turn cannot transition to $target" }
        require(target in ALLOWED_TRANSITIONS.getValue(this)) {
            "illegal turn transition from $this to $target"
        }
        return target
    }

    companion object {
        private val TERMINAL = setOf(Completed, Cancelled, Failed, Interrupted)

        private val ALLOWED_TRANSITIONS: Map<TurnPhase, Set<TurnPhase>> =
            mapOf(
                Queued to setOf(Preparing, Cancelled, Failed),
                Preparing to setOf(Streaming, WaitingApproval, Finalizing, Cancelled, Failed, Interrupted, Recoverable),
                Streaming to setOf(WaitingTool, WaitingApproval, Finalizing, Cancelled, Failed, Interrupted),
                WaitingTool to setOf(Streaming, WaitingApproval, Finalizing, Cancelled, Failed, Interrupted),
                WaitingApproval to setOf(Streaming, WaitingTool, Cancelled, Failed, Interrupted),
                Finalizing to setOf(Completed, Cancelled, Failed, Interrupted, Recoverable),
                Completed to emptySet(),
                Cancelled to emptySet(),
                Failed to emptySet(),
                Interrupted to emptySet(),
                Recoverable to setOf(Preparing, Streaming, WaitingTool, WaitingApproval, Cancelled, Failed, Interrupted),
            )
    }
}
