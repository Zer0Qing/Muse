package io.zer0.muse.intent

/**
 * AUD-20261011-TASKER-ABUSE: Tasker 广播入口的三道成本防线(纯判定,可单测)。
 *
 * 背景:TaskerTriggerReceiver 为 exported=true 且无 permission 的广播入口,
 * 任意应用可循环触发计费 LLM 补全。已验证的缓解:completeText 不带 tools,
 * 外部广播无法间接触发工具执行、无用户数据外泄面 —— 因此缺口是**成本滥用**
 * 与通知垃圾,而非权限提升。
 *
 * 三道防线:
 *  1) 总开关(默认关闭):用户没在设置里启用过就不响应;
 *  2) 长度上限:超长 prompt 直通计费模型,必须拒绝;
 *  3) 限频:同一广播在最小间隔内重复到达即节流。
 */
internal object TaskerTriggerGuard {

    /** 判定结果。 */
    enum class Decision {
        ACCEPTED,
        REJECTED_DISABLED,
        REJECTED_BLANK,
        REJECTED_TOO_LONG,
        REJECTED_THROTTLED,
    }

    /** prompt 最大字符数(超出直接拒绝,避免巨型文本直通计费)。 */
    const val MAX_PROMPT_LENGTH = 4_000

    /** 最小触发间隔(毫秒):期间内的重复广播一律节流。 */
    const val MIN_INTERVAL_MS = 5_000L
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongParameterList")
    fun evaluate(
        enabled: Boolean,
        promptLength: Int,
        maxPromptLength: Int = MAX_PROMPT_LENGTH,
        nowMs: Long,
        lastAcceptedAtMs: Long?,
        minIntervalMs: Long = MIN_INTERVAL_MS,
    ): Decision {
        if (!enabled) return Decision.REJECTED_DISABLED
        if (promptLength <= 0) return Decision.REJECTED_BLANK
        if (promptLength > maxPromptLength) return Decision.REJECTED_TOO_LONG
        val last = lastAcceptedAtMs
        // 时间回拨(now < last)不得造成永久阻塞:视为间隔已过。
        if (last != null && nowMs >= last && nowMs - last < minIntervalMs) {
            return Decision.REJECTED_THROTTLED
        }
        return Decision.ACCEPTED
    }
}
