package io.zer0.muse.knowledge.integration

/**
 * R9 读切换模式 — 设计 §15.3「先建立新数据与只读影子查询，再切换读入口」。
 *
 * 三种模式:
 *  - [LEGACY]:只读旧链路(默认,兼容与回退位);
 *  - [V2_SHADOW]:旧链路返回结果,同时执行一次 V2 只读查询做对比记录,不改变用户可见结果;
 *  - [V2_ACTIVE]:V2 引擎作为读真源,需显式切换批准且 V2 可用。
 *
 * 不变式(由 KnowledgeMigrationCutoverTest 钉住):
 *  - 默认 LEGACY;
 *  - V2_ACTIVE 在未获切换批准或 V2 不可用时**拒绝启用**,降级回 LEGACY 并给出原因;
 *  - SHADOW 只用于比对,不把 V2 结果返回给用户。
 */
enum class KnowledgeReadMode {
    LEGACY,
    V2_SHADOW,
    V2_ACTIVE,
}

data class KnowledgeReadDecision(
    /** 实际返回给用户的引擎。 */
    val servingEngine: KnowledgeReadMode,
    /** 是否需要额外执行影子查询。 */
    val runShadowRead: Boolean,
    /** 若请求的模式不可用,说明降级原因;null = 按请求模式执行。 */
    val fallbackReason: String? = null,
)

object KnowledgeReadModeResolver {

    fun decide(
        requested: KnowledgeReadMode,
        v2Available: Boolean,
        cutoverApproved: Boolean,
    ): KnowledgeReadDecision = when (requested) {
        KnowledgeReadMode.LEGACY -> KnowledgeReadDecision(
            servingEngine = KnowledgeReadMode.LEGACY,
            runShadowRead = false,
        )
        KnowledgeReadMode.V2_SHADOW -> {
            if (!v2Available) {
                KnowledgeReadDecision(
                    servingEngine = KnowledgeReadMode.LEGACY,
                    runShadowRead = false,
                    fallbackReason = "V2 不可用,影子读跳过",
                )
            } else {
                KnowledgeReadDecision(
                    servingEngine = KnowledgeReadMode.LEGACY,
                    runShadowRead = true,
                )
            }
        }
        KnowledgeReadMode.V2_ACTIVE -> {
            when {
                !cutoverApproved -> KnowledgeReadDecision(
                    servingEngine = KnowledgeReadMode.LEGACY,
                    runShadowRead = false,
                    fallbackReason = "未获读切换批准,V2_ACTIVE 拒绝启用",
                )
                !v2Available -> KnowledgeReadDecision(
                    servingEngine = KnowledgeReadMode.LEGACY,
                    runShadowRead = false,
                    fallbackReason = "V2 不可用,V2_ACTIVE 降级回 LEGACY",
                )
                else -> KnowledgeReadDecision(
                    servingEngine = KnowledgeReadMode.V2_ACTIVE,
                    runShadowRead = false,
                )
            }
        }
    }
}
