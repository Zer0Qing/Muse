package io.zer0.muse.tools

/**
 * M3.2: 统一工具执行预算/停止策略。
 *
 * 把散落在 ToolOrchestrator 各处的预算判断(轮次上限、连续失败早停、无进展
 * 签名检测)收口为一个可注入、可测试的策略对象;并补齐此前缺失的预算维度:
 * 总调用数、总耗时、单条输出大小、连续重复调用指纹。
 *
 * M3.1 状态机对应关系:
 * ```
 * REQUEST(模型发出 tool_call)
 *   -> APPROVAL(host.requestToolApproval,现有链路)
 *   -> EXECUTE(beforeExecute 放行后执行;预算命中则短路为 STOP)
 *   -> PERSIST(persistToolRoundIncrementally,现有链路)
 *   -> CONTINUE(下一轮 LLM 续接)/ STOP(任一预算命中,携带终止原因)
 * ```
 *
 * 线程安全:单 turn 内顺序使用(工具循环为顺序/受控并行),不加锁;
 * 多 turn 场景每 turn 新建一个策略实例。
 */
@Suppress("TooManyFunctions")
class ToolExecutionPolicy(
    private val limits: ToolExecutionLimits = ToolExecutionLimits(),
    /** 初始最大轮次;与 ToolOrchestrator.DEFAULT_MAX_TOOL_ROUNDS(10)对齐。 */
    initialMaxRounds: Int = 10,
) {

    /** 动态最大轮次(task_plan 产生后可扩容)。运行期由 [updateMaxRounds] 维护。 */
    var maxRounds: Int = initialMaxRounds
        private set

    fun updateMaxRounds(newMax: Int) {
        maxRounds = newMax
    }

    /** M3.2: 预算命中后的终止原因(错误文案与诊断日志使用)。 */
    enum class StopReason {
        /** 达到最大轮数(原 maxRounds 语义,兜底防线)。 */
        MAX_ROUNDS,
        /** 单 turn 累计工具调用次数超限。 */
        MAX_TOTAL_CALLS,
        /** 连续失败次数超限(与既有早停一致)。 */
        CONSECUTIVE_FAILURES,
        /** turn 总耗时超限。 */
        TIME_BUDGET_EXHAUSTED,
        /** 同一 (工具名+参数) 连续重复调用超限(调用风暴指纹)。 */
        REPEATED_IDENTICAL_CALL,
    }

    /** 单次调用放行决策。 */
    data class Decision(
        val allowed: Boolean,
        val reason: StopReason? = null,
        /** M3.3: 命中预算时的结构化审计摘要(诊断日志用)。 */
        val detail: String = "",
    )

    private var totalCalls = 0
    private var consecutiveFailures = 0
    /** turn 起始时间戳(测试据此构造相对 nowMs)。 */
    val startedAtMs = System.currentTimeMillis()
    private var lastFingerprint: String? = null
    private var lastFingerprintRepeatCount = 0

    /** 当前累计调用次数(观测用)。 */
    val executedCalls: Int get() = totalCalls

    // M3.3: turn 级结果统计(非预算,供 ToolLoopResult 快照取值)。由 ToolOrchestrator 记录,
    // 避免 Orchestrator 再维护 totalToolCallCount/totalCharCount 两套可变计数。
    private var emittedToolCalls = 0
    private var streamedChars = 0

    /** 模型发出(经 Sanitizer 清洗后)的工具调用总数。 */
    val emittedToolCallCount: Int get() = emittedToolCalls

    /** 流式输出累计字符数(含非工具轮的最终回复)。 */
    val streamedCharCount: Int get() = streamedChars

    fun recordEmittedToolCalls(count: Int) {
        emittedToolCalls += count
    }

    fun recordStreamedChars(count: Int) {
        streamedChars += count
    }

    /** 连续失败计数(供用户可见终止原因文案使用)。 */
    val consecutiveFailuresCount: Int get() = consecutiveFailures

    /** 连续失败是否已达早停阈值(替代散落在 ToolOrchestrator 的 shouldAbortToolLoop)。 */
    fun shouldAbortOnConsecutiveFailures(): Boolean =
        consecutiveFailures >= limits.maxConsecutiveFailures

    // 轮级无进展检测(替代 ToolOrchestrator.noProgressRounds + previousToolCallSignature):
    // 连续 maxNoProgressRounds 轮 LLM 返回相同 tool_call 签名时判定卡死。
    private var lastRoundSignature: String? = null
    private var noProgressRounds = 0

    /** 轮级无进展计数(供用户可见终止原因文案使用)。 */
    val noProgressRoundsCount: Int get() = noProgressRounds

    fun checkRoundProgress(roundSignature: String): Decision {
        if (roundSignature.isEmpty()) {
            lastRoundSignature = ""
        }
        val isRepeat = roundSignature.isNotEmpty() && roundSignature == lastRoundSignature
        if (!isRepeat) {
            lastRoundSignature = roundSignature
        }
        noProgressRounds = if (isRepeat) noProgressRounds + 1 else 0
        return if (noProgressRounds >= limits.maxNoProgressRounds) {
            blocked(
                StopReason.REPEATED_IDENTICAL_CALL,
                "consecutive identical tool-call rounds=$noProgressRounds",
            )
        } else {
            Decision(allowed = true)
        }
    }

    /**
     * 单次工具调用前的放行检查。命中任一预算即拒绝执行
     * (调用方把拒绝原因作为合成 tool 结果回给模型,不执行真实工具)。
     *
     * @param toolName 工具名
     * @param argumentsJson 工具参数原文(指纹原料,不做解析)
     */
    fun beforeExecute(toolName: String, argumentsJson: String): Decision {
        val fingerprint = fingerprint(toolName, argumentsJson)
        val repeatCount = if (fingerprint == lastFingerprint) lastFingerprintRepeatCount else 0
        val elapsedMs = System.currentTimeMillis() - startedAtMs
        val budgetMs = limits.totalBudgetMs
        val violation = listOf(
            blocked(StopReason.MAX_TOTAL_CALLS, "totalCalls=$totalCalls max=${limits.maxTotalCalls}")
                .takeIf { totalCalls >= limits.maxTotalCalls },
            blocked(StopReason.CONSECUTIVE_FAILURES, "consecutiveFailures=$consecutiveFailures")
                .takeIf { consecutiveFailures >= limits.maxConsecutiveFailures },
            blocked(
                StopReason.REPEATED_IDENTICAL_CALL,
                // M3.3: 指纹只入日志,不回显完整参数(避免大参数/敏感参数刷屏)
                "tool=$toolName fingerprint=$fingerprint repeats=$repeatCount",
            ).takeIf { repeatCount + 1 > limits.maxConsecutiveIdenticalCalls },
            blocked(StopReason.TIME_BUDGET_EXHAUSTED, "elapsedMs=$elapsedMs budget=$budgetMs")
                .takeIf { budgetMs != null && elapsedMs > budgetMs },
        ).firstOrNull { it != null }
        return violation ?: Decision(allowed = true)
    }

    /** 预算命中决策的便捷构造。 */
    private fun blocked(reason: StopReason, detail: String): Decision =
        Decision(allowed = false, reason = reason, detail = detail)

    /**
     * 调用落账:更新计数、失败连击与重复指纹。
     *
     * @param toolName 工具名
     * @param argumentsJson 参数原文(与 beforeExecute 一致)
     * @param success 工具是否执行成功(审批拒绝/预算拦截不算失败,不计入)
     */
    fun afterExecute(toolName: String, argumentsJson: String, success: Boolean) {
        totalCalls++
        if (success) {
            consecutiveFailures = 0
        } else {
            consecutiveFailures++
        }
        val fingerprint = fingerprint(toolName, argumentsJson)
        if (fingerprint == lastFingerprint) {
            lastFingerprintRepeatCount++
        } else {
            lastFingerprint = fingerprint
            lastFingerprintRepeatCount = 1
        }
    }

    /**
     * turn 级耗时预算检查(每轮循环开头调用)。
     * null 表示未配置时间预算(默认关闭,兼容既有长任务)。
     */
    fun isTimeBudgetExhausted(nowMs: Long = System.currentTimeMillis()): Boolean {
        val budget = limits.totalBudgetMs ?: return false
        return nowMs - startedAtMs > budget
    }

    /**
     * M3.2: 输出大小上限。超限截断并附注说明,防止单个工具结果
     * 撑爆下一轮 LLM 上下文(读大文件/网页抓取场景)。
     *
     * @return Pair(截断后文本, 是否被截断)
     */
    fun clampOutput(output: String): Pair<String, Boolean> {
        if (output.length <= limits.maxOutputChars) return output to false
        val head = output.take(limits.maxOutputChars)
        return head + "\n\n[输出已截断: 原始 ${output.length} 字符, 上限 ${limits.maxOutputChars}]" to true
    }

    /**
     * 重复调用指纹:工具名 + 参数原文的 SHA-256。
     * 参数原文不解析 —— 指纹稳定性由"同一模型重复发出相同调用"保证,
     * 键序差异视为不同调用(保守,不误伤合法重试)。
     */
    internal fun fingerprint(toolName: String, argumentsJson: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest((toolName + "\u0000" + argumentsJson).toByteArray())
        // 每个字节按无符号处理:%02x 直接格式化 Byte 会把负字节符号扩展成
        // "ffffffXX"(8 字符),再被 take(16) 截断后指纹熵塌缩到 ~8bit,导致
        // 不同工具调用被误判为"重复相同调用"。先 toInt() and 0xff 再格式化。
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(16)
    }
}

/**
 * M3.2: 预算上限配置。默认值以"不改变既有行为"为原则:
 * 轮次上限仍由 ToolOrchestrator 的 computeMaxRounds 管理(本类不重复限制轮数),
 * 总调用数/重复指纹/输出截断取宽松默认,时间预算默认关闭。
 */
data class ToolExecutionLimits(
    /** 单 turn 累计工具调用上限(含成功与失败,不含审批拒绝)。 */
    val maxTotalCalls: Int = 60,
    /** 连续失败早停阈值(与 ToolOrchestrator.MAX_CONSECUTIVE_TOOL_FAILURES 对齐)。 */
    val maxConsecutiveFailures: Int = 3,
    /** 同一 (工具名+参数) 连续重复调用上限;第 N+1 次被拦截。 */
    val maxConsecutiveIdenticalCalls: Int = 3,
    /** 轮级无进展上限:连续 N 轮 LLM 返回相同 tool_call 签名即判定卡死。 */
    val maxNoProgressRounds: Int = 2,
    /** turn 总耗时预算(毫秒);null 关闭。 */
    val totalBudgetMs: Long? = null,
    /** 单条工具结果输出上限(字符);超出截断。 */
    val maxOutputChars: Int = 200_000,
)
