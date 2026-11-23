package io.zer0.muse.data.agentbot

import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.serialization.Serializable

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
        /**
         * 有验收标准却没有可核验记录时，这些项必须如实上报。
         *
         * 结构化字段而不是让下游去 findings 里找关键字：审核结论要能被程序消费，
         * 靠字符串匹配迟早会因为改了一句文案而失效。
         */
        val unverifiedItems: List<String> = emptyList(),
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
        /** 空 resourceScope 的只读任务可以交付摘要，但摘要不伪装成文件产物。 */
        summaryOnly: Boolean = false,
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
        if (producedArtifacts.isEmpty() && !summaryOnly) {
            findings += "没有任何可核验的产物 —— 只返回一段文字不算完成任务"
            return decideReworkOrGiveUp(findings, attempt, "需要真正产出可打开的产物")
        }
        if (summaryOnly && claimedSummary.isBlank()) {
            findings += "只读任务没有可交付的摘要"
            return decideReworkOrGiveUp(findings, attempt, "需要提供可核对的摘要")
        }

        // 3) 有验收标准却没跑过任何校验：不能宣称"已验证"。
        if (criteria.isNotBlank() && verifiedChecks.isEmpty()) {
            findings += UNVERIFIED_CRITERIA_NOTE
            val unverified = listOf(UNVERIFIED_CRITERIA_NOTE)
            // 第一次给返工机会；第二次直接如实上报未验项。
            return if (attempt < MAX_AUTO_REWORK) {
                Decision(
                    disposition = ReviewDisposition.REWORK,
                    findings = findings,
                    retryBrief = "按验收标准补一次可核验的检查",
                    unverifiedItems = unverified,
                )
            } else {
                Decision(
                    disposition = ReviewDisposition.PARTIAL,
                    findings = findings + "已达自动返工上限，未验项如实上报",
                    unverifiedItems = unverified,
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

    /**
     * 审核结论 → 任务状态。
     *
     * 与 [transitionFor] 是同一张映射的两种表达：一个给状态机，一个给审核事实。
     * 不从事件反推是为了让"状态机改了映射"在这里编译期就暴露。
     */
    fun finalStateFor(disposition: String): String = when (disposition) {
        ReviewDisposition.ACCEPT -> BotTaskStatus.SUCCEEDED
        ReviewDisposition.PARTIAL -> BotTaskStatus.PARTIAL
        ReviewDisposition.REWORK -> BotTaskStatus.QUEUED
        ReviewDisposition.REJECT -> BotTaskStatus.FAILED
        ReviewDisposition.NEEDS_USER -> BotTaskStatus.NEEDS_USER
        ReviewDisposition.UNKNOWN -> BotTaskStatus.UNKNOWN
        else -> {
            Logger.w("BotReviewWorker", "未知审核结论，终态按 UNKNOWN 处理: $disposition")
            BotTaskStatus.UNKNOWN
        }
    }

    /**
     * 把一次审核判断组装成可落库、可发布的审核事实。
     *
     * ## 摘要为什么必须在这里生成
     *
     * 发布正文只能来自审核事实。把子 Agent 的自述拼进来，等于让"我以为我做完了"
     * 直接变成"已经做完" —— 那正是这个类要防的事。
     *
     * @param publicationAllowed 只有"审核接受且产物真的落地"才允许为 true
     */
    fun reviewDecision(
        decision: Decision,
        goal: String,
        verifiedChecks: List<String>,
        sideEffects: List<String>,
        candidateRefs: List<String>,
        publicationAllowed: Boolean,
        publicationKey: String,
        taskRevision: Int,
    ): ReviewDecision = ReviewDecision(
        disposition = decision.disposition,
        publicationAllowed = publicationAllowed,
        finalState = finalStateFor(decision.disposition),
        userVisibleSummary = summarize(decision, goal, verifiedChecks, candidateRefs, sideEffects),
        unverifiedItems = decision.unverifiedItems,
        sideEffects = sideEffects,
        candidateRefs = candidateRefs,
        publicationKey = publicationKey,
        taskRevision = taskRevision,
    )

    /** 用户可见摘要：只拼审核事实，不取子 Agent 自述。 */
    private fun summarize(
        decision: Decision,
        goal: String,
        verifiedChecks: List<String>,
        candidateRefs: List<String>,
        sideEffects: List<String>,
    ): String = buildString {
        append("「")
        append(goal.trim().take(MAX_GOAL_CHARS).ifBlank { "这件事" })
        append("」审核结论：")
        append(dispositionText(decision.disposition))
        if (candidateRefs.isNotEmpty()) {
            append("\n产物：")
            append(candidateRefs.joinToString("、"))
        }
        if (verifiedChecks.isNotEmpty()) {
            append("\n已核验：")
            append(verifiedChecks.joinToString("、"))
        }
        if (sideEffects.isNotEmpty()) {
            append("\n已发生且无法回滚：")
            append(sideEffects.joinToString("、"))
        }
        if (decision.unverifiedItems.isNotEmpty()) {
            append("\n未验项：")
            append(decision.unverifiedItems.joinToString("、"))
        }
    }

    private fun dispositionText(disposition: String): String = when (disposition) {
        ReviewDisposition.ACCEPT -> "已完成"
        ReviewDisposition.PARTIAL -> "只完成了一部分"
        ReviewDisposition.REWORK -> "还需要重做"
        ReviewDisposition.REJECT -> "未通过审核"
        ReviewDisposition.NEEDS_USER -> "需要你确认"
        ReviewDisposition.UNKNOWN -> "结果无法确认"
        else -> {
            Logger.w("BotReviewWorker", "未知审核结论，摘要按无法确认处理: $disposition")
            "结果无法确认"
        }
    }

    companion object {
        /** 自动返工上限。超过之后由主 Bot 说明阻塞，不再无限重试。 */
        const val MAX_AUTO_REWORK = 2

        /** 摘要里保留的目标长度。再长会挤掉真正重要的结论。 */
        private const val MAX_GOAL_CHARS = 60

        /** "有标准但没校验"的统一措辞。审核事实与 findings 共用它，避免两处漂移。 */
        private const val UNVERIFIED_CRITERIA_NOTE = "有验收标准，但没有任何校验记录（测试/构建/diff 核对）"
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

/**
 * 审核事实 —— 这次结果能不能对外说、说什么、为什么。
 *
 * ## 为什么它不是一段文字
 *
 * 发布必须能回答"凭什么允许发布"。把结论、终态、未验项、副作用、产物引用
 * 分开存，任何一项缺失都能被程序发现；糊成一句摘要就只能靠人读。
 *
 * ## 为什么它可以序列化进事件
 *
 * 事件表只留引用，正文不进事件。审核事实是"要不要发、发什么"的判据，
 * 跟着事件走才能保证消费方看到的是同一个版本，而不是重新推理一遍。
 */
@Serializable
data class ReviewDecision(
    /** 见 [ReviewDisposition]。 */
    val disposition: String,
    /** 明确允许发布才为 true。失败/REWORK/PARTIAL/UNKNOWN 默认 false。 */
    val publicationAllowed: Boolean,
    /** 审核判定的任务状态（[BotTaskStatus] 常量）。 */
    val finalState: String,
    /** 允许发布时给用户看的正文，全部由审核事实拼成。 */
    val userVisibleSummary: String,
    val unverifiedItems: List<String> = emptyList(),
    val sideEffects: List<String> = emptyList(),
    val candidateRefs: List<String> = emptyList(),
    /** 稳定发布键：同一条事实重复投递只对应一条对外消息。 */
    val publicationKey: String = "",
    val taskRevision: Int = 1,
) {

    fun encode(): String = AppJson.encodeToString(serializer(), this)

    companion object {
        /**
         * 解析失败返回 null。
         *
         * 宁可当作"没有可发布事实"，也不能猜一份内容发出去 —— 猜出来的正文
         * 会以审核的名义出现在用户面前。
         */
        fun decode(raw: String): ReviewDecision? = runCatching { AppJson.decodeFromString(serializer(), raw) }
            .onFailure { Logger.w("BotReviewWorker", "审核事实解析失败: ${it.message}") }
            .getOrNull()
    }
}
