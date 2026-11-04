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

    /**
     * 这轮对话里允许出现哪些工具。
     *
     * 规矩按"有没有人在这轮前面看着"分档，而不是按入口名字：
     *
     *  - [A2a]：远程调用，纯文本，一个工具都不给；
     *  - [Channel] / [Scheduled] / [Proactive]：**没有人在输入框前面**。
     *    高副作用工具（读屏、点击、发送、删除一类）不该由一次没人等待的自动运行触发 ——
     *    出事时没人能当场叫停。`Proactive` 原先和 `MainChat` 一样全放行，
     *    与 `Scheduled` 只差一个入口名字，没有理由：主动消息同样是应用自己起的轮次
     *    （应用现状里后台单轮也是"不授权工具"，这里把合同对齐到实现）。
     *  - [MainChat] / [Agent]：用户正在这条会话里，全量给（审批闸仍在下游）。
     */
    fun allowedTools(tools: List<ToolDescriptor>): List<ToolDescriptor> = when (this) {
        A2a -> emptyList()
        Channel, Scheduled, Proactive -> tools.filter { it.risk != ToolRisk.High }
        MainChat, Agent -> tools
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
    /**
     * 已尝试过的 toolCallId。**必须按插入顺序**（`LinkedHashSet`），
     * 因为超出上限时要丢最早的那批 —— 用普通 `HashSet` 会丢在谁身上不确定。
     *
     * 不设上限就等于让一个长命协调器无限攒字符串：每轮每个工具调用都进账、永不出账。
     * 上限与淘汰的理由同 `:memory` 的墓碑表：宁可放过一次极旧的重复，也不让内存只涨不落。
     */
    private val attemptedCalls = LinkedHashSet<String>()
    private val lock = Any()

    suspend fun execute(invocation: ToolInvocation, executor: ToolExecutor): ToolOutcome {
        if (invocation.sideEffectUnknown) {
            return ToolOutcome.AlreadyAttempted
        }
        val decision = approval.decide(invocation)
        if (decision == ApprovalDecision.Denied) return ToolOutcome.Denied
        val firstAttempt = synchronized(lock) {
            if (!attemptedCalls.add(invocation.toolCallId)) {
                false
            } else {
                if (attemptedCalls.size > MAX_ATTEMPTED_CALLS) {
                    val oldest = attemptedCalls.iterator()
                    while (attemptedCalls.size > MAX_ATTEMPTED_CALLS && oldest.hasNext()) {
                        oldest.next()
                        oldest.remove()
                    }
                }
                true
            }
        }
        if (!firstAttempt) return ToolOutcome.AlreadyAttempted
        return executor.execute(invocation)
    }

    internal companion object {
        /**
         * 去重记录的上限：一次长会话里的工具调用总数可以远大于这个数。
         *
         * `internal` 而不是私有的唯一理由：测试要引用同一个数。
         * 测试里另抄一份字面量，就等于让上限可以单方面改动而无人察觉 —— 那正是
         * 本会话一直在拆的"两处真相"。
         */
        const val MAX_ATTEMPTED_CALLS: Int = 2_000
    }
}
