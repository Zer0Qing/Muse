package io.zer0.muse.knowledge.integration

import io.zer0.common.Logger

/**
 * R9 读模式切换台账 — 设计 §15.3「回退指同一新 schema 下的兼容读取/功能开关回退，
 * 不是承诺旧 APK 能读取升级后的数据库」。
 *
 * 职责:
 *  - 记录每次模式变更(时间、从哪个模式到哪个模式、原因、是否降级);
 *  - 提供「当前是否处于回退状态」判定(曾经 ACTIVE 过又回到 LEGACY);
 *  - 不持久化到磁盘 —— 由调用方决定写设置;本类保证**内存内的可追溯性**与判定语义。
 *
 * 不变式(由 KnowledgeReadModeRollbackTest 钉住):
 *  - 降级(ACTIVE→LEGACY)必须带原因;
 *  - 回退后 `wasRolledBack` 为真,便于 UI/诊断提示;
 *  - 台账不因重复设置同一模式而膨胀(相同模式不追加)。
 */
data class ReadModeChange(
    val from: KnowledgeReadMode,
    val to: KnowledgeReadMode,
    val reason: String,
    val atMs: Long,
    /** 是否属于降级(目标比来源更保守)。 */
    val downgrade: Boolean,
)

class KnowledgeReadModeLedger(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = 50,
) {
    private val entries = mutableListOf<ReadModeChange>()

    @Volatile
    var current: KnowledgeReadMode = KnowledgeReadMode.LEGACY
        private set

    /** 是否曾启用过 V2_ACTIVE(用于判定"回退"是否成立)。 */
    private var everActivated = false

    /** 是否发生过从 ACTIVE 降级。 */
    val wasRolledBack: Boolean
        get() = everActivated && current != KnowledgeReadMode.V2_ACTIVE

    val history: List<ReadModeChange> get() = entries.toList()

    /**
     * 切换模式。相同模式不追加记录。
     * @return 实际生效的模式(可能因降级而非请求值)。
     */
    fun switchTo(
        requested: KnowledgeReadMode,
        v2Available: Boolean,
        cutoverApproved: Boolean,
        requestedReason: String = "",
    ): KnowledgeReadMode {
        val decision = KnowledgeReadModeResolver.decide(requested, v2Available, cutoverApproved)
        val target = decision.servingEngine
        if (target == current) {
            // 请求值与生效值一致时不重复记账;但请求被拒(降级)需要留痕。
            if (decision.fallbackReason != null) {
                record(
                    to = target,
                    reason = decision.fallbackReason,
                    downgrade = false,
                )
            }
            return target
        }
        val downgrade = isDowngrade(current, target)
        val reason = decision.fallbackReason
            ?: requestedReason.ifBlank { "切换至 ${target.name}" }
        record(to = target, reason = reason, downgrade = downgrade)
        if (target == KnowledgeReadMode.V2_ACTIVE) everActivated = true
        if (downgrade) {
            Logger.w("KnowledgeReadModeLedger", "读模式降级: ${current.name} -> ${target.name}($reason)")
        }
        return target
    }

    /** 主动回退到 LEGACY(必须带原因)。 */
    fun rollbackToLegacy(reason: String): KnowledgeReadMode {
        require(reason.isNotBlank()) { "回退必须说明原因" }
        if (current == KnowledgeReadMode.LEGACY) return current
        record(to = KnowledgeReadMode.LEGACY, reason = reason, downgrade = true)
        Logger.w("KnowledgeReadModeLedger", "主动回退到 LEGACY: $reason")
        return KnowledgeReadMode.LEGACY
    }

    private fun record(to: KnowledgeReadMode, reason: String, downgrade: Boolean) {
        val change = ReadModeChange(
            from = current,
            to = to,
            reason = reason,
            atMs = clock(),
            downgrade = downgrade,
        )
        entries.add(change)
        while (entries.size > maxEntries) entries.removeAt(0)
        current = to
    }

    /** 目标模式是否比来源更保守(LEGACY < SHADOW < ACTIVE)。 */
    private fun isDowngrade(from: KnowledgeReadMode, to: KnowledgeReadMode): Boolean =
        rank(to) < rank(from)

    private fun rank(mode: KnowledgeReadMode): Int = when (mode) {
        KnowledgeReadMode.LEGACY -> 0
        KnowledgeReadMode.V2_SHADOW -> 1
        KnowledgeReadMode.V2_ACTIVE -> 2
    }
}
