package io.zer0.muse.conversation.core

enum class ToolRisk {
    Safe,
    Normal,
    High,
}

data class ToolDescriptor(
    val name: String,
    val risk: ToolRisk,
)

enum class EntryPolicy {
    MainChat,
    Agent,
    Channel,
    A2a,
    Scheduled,
    Proactive,
    ;

    fun allowedTools(tools: List<ToolDescriptor>): List<ToolDescriptor> = when (this) {
        A2a -> emptyList()
        Channel, Scheduled -> tools.filter { it.risk != ToolRisk.High }
        MainChat, Agent, Proactive -> tools
    }
}

data class ToolInvocation(
    val toolCallId: String,
    val toolName: String,
    val argumentsJson: String,
    val sideEffectUnknown: Boolean,
)

sealed interface ToolOutcome {
    data class Succeeded(val output: String) : ToolOutcome

    data class Failed(val message: String) : ToolOutcome

    data object Denied : ToolOutcome

    data object Unknown : ToolOutcome

    data object AlreadyAttempted : ToolOutcome
}

fun interface ToolExecutor {
    suspend fun execute(invocation: ToolInvocation): ToolOutcome
}

enum class ApprovalDecision {
    Approved,
    Denied,
}

fun interface ApprovalPort {
    suspend fun decide(invocation: ToolInvocation): ApprovalDecision
}

/**
 * Coordinates a single tool call while respecting approval and side-effect
 * uncertainty. It has no dependency on a UI or a session-wide mutable state.
 */
class ToolExecutionCoordinator(
    private val approval: ApprovalPort,
) {
    private val attemptedCalls = mutableSetOf<String>()
    private val lock = Any()

    suspend fun execute(invocation: ToolInvocation, executor: ToolExecutor): ToolOutcome {
        if (invocation.sideEffectUnknown) {
            return ToolOutcome.AlreadyAttempted
        }
        val decision = approval.decide(invocation)
        if (decision == ApprovalDecision.Denied) return ToolOutcome.Denied
        val firstAttempt =
            synchronized(lock) {
                if (!attemptedCalls.add(invocation.toolCallId)) false else true
            }
        if (!firstAttempt) return ToolOutcome.AlreadyAttempted
        return executor.execute(invocation)
    }
}
