package io.zer0.muse.data.agentbot

import io.zer0.common.Logger

/**
 * 内部审核器 —— 决定子任务的结果能不能算"完成"。
 *
 * ## 为什么不能只读子 Agent 的"完成了"
 *
 * 子 Agent 的汇报是**材料**，不是结论。它会说自己做完了，但没打开过产物、
 * 没跑过校验、或者改超了范围 —— 这些只有审核能发现。直接把它的话转给用户，
 * 等于把"我以为我做完了"当成"已经做完"。
 *
 * ## 与主 Bot 权限的边界
 *
 * 审核能决定接受、补查、退回、重新分派（内部质量判断）；
 * 但**不能**代替用户批准自己或子 Agent 的权限申请。那是 [ApprovalStatus] 的事。
 */
class BotReviewWorker {

    /**
     * 审核结论。
     *
     * 与 [ReviewDecisionEntity.disposition] 的取值一致，但这里是结构化结果，
     * 不是自由文本 —— 下游按 disposition 分支，不解析字符串。
     */
    data class Decision(
        val disposition: String,
        /** 给主 Bot 看的具体发现（缺什么、哪里超范围）。 */
        val findings: List<String> = emptyList(),
        /** 返工时给下一轮的简要说明。 */
        val retryBrief: String = "",
    ) {
        val accepted: Boolean get() = disposition == ReviewDisposition.ACCEPT
    }

    /**
     * 审核一次执行结果。
     *
     * [expectedBaseHash] 与 [actualBaseHash] 不一致时判 stale：说明目标在任务跑
     * 的过程中被别人改过，这一轮的产物不能发布（否则会覆盖用户的修改）。
     *
     * @param criteria 验收标准（来自任务）。空表示没有明确标准，只做基本检查。
     * @param claimedSummary 子 Agent 自己说它做了什么。
     * @param producedArtifacts 实际产出的产物引用。空表示它什么都没留下。
     * @param verifiedChecks 实际跑过的校验（测试、构建、diff 核对）。
     */
    fun review(
        criteria: String,
        claimedSummary: String,
        producedArtifacts: List<String>,
        verifiedChecks: List<String>,
        expectedBaseHash: String = "",
        actualBaseHash: String = "",
        attempt: Int = 1,
        /**
         * 已经发生、无法回滚的副作用（V23）。
         *
         * 有些操作不能暂存：发了消息、调了外部接口、改了数据库。
         * 这类操作一旦发生，新约束只能前向生效 —— **不能假装撤销**，
         * 也不能对用户隐瞒"其实已经做了"。
         */
        irreversibleSideEffects: List<String> = emptyList(),
    ): Decision {
        val findings = mutableListOf<String>()

        // 0) V23：副作用已发生。必须先如实汇报，再谈别的。
        //
        // 顺序放在最前是刻意的：如果一件事已经做了一半，用户最需要知道的是
        // "哪些已经做了"，而不是"目标哈希对不对"。
        if (irreversibleSideEffects.isNotEmpty()) {
            findings += irreversibleSideEffects.map { "已发生且无法回滚: ${it}" }
            findings += "新约束只对后续生效；以上操作无法撤销，也不会被隐藏"
            // 有产物且没有其他硬伤时算 PARTIAL —— 做了但没全做完。
            val otherwiseClean = producedArtifacts.isNotEmpty()
            return Decision(
                disposition = if (otherwiseClean) ReviewDisposition.PARTIAL else ReviewDisposition.NEEDS_USER,
                findings = findings,
                retryBrief = if (otherwiseClean) "" else "需要用户确认已发生的操作怎么处理",
            )
        }

        // 1) 基线漂移：目标在跑的过程中被改过。
        if (expectedBaseHash.isNotBlank() && expectedBaseHash != actualBaseHash) {
            findings += "目标在任务执行期间被修改（基线 hash 不匹配），本轮产物不能发布"
            return Decision(
                disposition = ReviewDisposition.UNKNOWN,
                findings = findings,
                retryBrief = "目标已被改动，需要基于新版本重做",
            )
        }

        // 2) 什么都没产出：不能算完成。
        if (producedArtifacts.isEmpty()) {
            findings += "没有任何可核验的产物 —— 只返回一段文字不算完成任务"
            return decideReworkOrGiveUp(findings, attempt, "需要真正产出可打开的产物")
        }

        // 3) 有验收标准却没跑过任何校验：不能宣称"已验证"。
        if (criteria.isNotBlank() && verifiedChecks.isEmpty()) {
            findings += "有验收标准，但没有任何校验记录（测试/构建/diff 核对）"
            // 第一次给返工机会；第二次直接如实上报未验项。
            return if (attempt < MAX_AUTO_REWORK) {
                Decision(
                    disposition = ReviewDisposition.REWORK,
                    findings = findings,
                    retryBrief = "按验收标准补一次可核验的检查",
                )
            } else {
                Decision(
                    disposition = ReviewDisposition.PARTIAL,
                    findings = findings + "已达自动返工上限，未验项如实上报",
                )
            }
        }

        // 4) 子 Agent 的自述为空，但产物在：可接受，但要记一笔。
        if (claimedSummary.isBlank()) {
            findings += "子 Agent 未给出说明，仅凭产物判定"
        }

        return Decision(
            disposition = ReviewDisposition.ACCEPT,
            findings = findings,
        )
    }

    /**
     * 返工还是放弃。
     *
     * 默认最多自动返工 2 次 —— 无限循环会烧掉用户预算，而且第二次之后
     * 再试通常也不会有新信息。超过就如实说明阻塞与选项。
     */
    private fun decideReworkOrGiveUp(
        findings: List<String>,
        attempt: Int,
        brief: String,
    ): Decision = if (attempt < MAX_AUTO_REWORK) {
        Decision(ReviewDisposition.REWORK, findings, brief)
    } else {
        Decision(
            disposition = ReviewDisposition.NEEDS_USER,
            findings = findings + "已达自动返工上限，需要用户决定是否继续",
        )
    }

    /**
     * 把审核结论落成任务状态转移。
     *
     * 只做映射，不做判断 —— 判断在 [review] 里，这里保证"每个 disposition
     * 都有对应的状态"，不会出现某个结论被静默丢弃。
     */
    fun transitionFor(decision: Decision): BotTaskStatusMachine.Event? = when (decision.disposition) {
        ReviewDisposition.ACCEPT -> BotTaskStatusMachine.Event.REVIEW_ACCEPT
        ReviewDisposition.PARTIAL -> BotTaskStatusMachine.Event.REVIEW_PARTIAL
        ReviewDisposition.REWORK -> BotTaskStatusMachine.Event.REVIEW_REWORK
        ReviewDisposition.REJECT -> BotTaskStatusMachine.Event.REVIEW_FAIL
        ReviewDisposition.NEEDS_USER -> BotTaskStatusMachine.Event.REVIEW_NEEDS_USER
        ReviewDisposition.UNKNOWN -> BotTaskStatusMachine.Event.MARK_UNKNOWN
        else -> {
            Logger.w("BotReviewWorker", "未知审核结论，按 UNKNOWN 处理: ${decision.disposition}")
            BotTaskStatusMachine.Event.MARK_UNKNOWN
        }
    }

    companion object {
        /** 自动返工上限。超过之后由主 Bot 说明阻塞，不再无限重试。 */
        const val MAX_AUTO_REWORK = 2
    }
}

/** 审核结论的取值集合。与实体字段同源，新增取值要同步 [BotReviewWorker.transitionFor]。 */
object ReviewDisposition {
    const val ACCEPT = "ACCEPT"
    const val REWORK = "REWORK"
    const val NEEDS_USER = "NEEDS_USER"
    const val PARTIAL = "PARTIAL"
    const val REJECT = "REJECT"
    const val UNKNOWN = "UNKNOWN"

    val all: Set<String> = setOf(ACCEPT, REWORK, NEEDS_USER, PARTIAL, REJECT, UNKNOWN)
}