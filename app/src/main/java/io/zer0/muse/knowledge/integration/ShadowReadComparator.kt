package io.zer0.muse.knowledge.integration

import io.zer0.common.Logger
import io.zer0.muse.knowledge.retrieval.EvidenceCandidate
import io.zer0.muse.knowledge.retrieval.RetrievalOutcome

/**
 * R9 影子读比对器 — 设计 §15.3「先建立新数据与只读影子查询，再切换读入口」。
 *
 * 职责:在 [KnowledgeReadMode.V2_SHADOW] 下把旧链路结果与 V2 结果做**可量化比对**,
 * 产出一致性报告供切换决策;不改变用户可见结果,也不写任何索引。
 *
 * 比对口径:
 *  - 命中集合重叠率(Jaccard):两路文档 id 集合的交并比;
 *  - 顺序一致率:V2 前 K 与旧链路前 K 的顺序一致比例;
 *  - 仅旧/仅新计数:用于人工核查差异来源;
 *  - 是否任一路降级/失败。
 *
 * 注意:两路算法不同(旧链路 FTS/HNSW/rerank vs V2 词法),**不要求完全一致**;
 * 报告用于发现"V2 明显漏召回"或"V2 引入越界",而非追求数值相同。
 */
data class ShadowComparisonReport(
    val query: String,
    val legacyDocIds: List<String>,
    val v2DocIds: List<String>,
    /** 交集 / 并集;两路皆空时为 1.0(都无命中)。 */
    val jaccard: Double,
    /** 前 K 顺序一致比例(按位置比较)。 */
    val topKAgreement: Double,
    val onlyInLegacy: List<String>,
    val onlyInV2: List<String>,
    val legacyDegraded: Boolean,
    val v2Degraded: Boolean,
    /** 是否触发人工复核门槛(见 [needsReview])。 */
    val needsReview: Boolean,
    val reviewReasons: List<String>,
) {
    fun summary(): String = buildString {
        append("shadow query='")
        append(query.take(40))
        append("' legacy=").append(legacyDocIds.size)
        append(" v2=").append(v2DocIds.size)
        append(" jaccard=").append("%.2f".format(jaccard))
        append(" topK=").append("%.2f".format(topKAgreement))
        if (onlyInLegacy.isNotEmpty()) append(" onlyLegacy=").append(onlyInLegacy.size)
        if (onlyInV2.isNotEmpty()) append(" onlyV2=").append(onlyInV2.size)
        if (needsReview) append(" REVIEW=").append(reviewReasons.joinToString(";"))
    }
}

object ShadowReadComparator {

    /** 命中集合 Jaccard 低于此值时要求人工复核。 */
    const val MIN_JACCARD_FOR_REVIEW = 0.30

    /**
     * 比对两路检索结果。
     *
     * @param topK 参与顺序一致率比较的前 K 条(默认 10)。
     */
    fun compare(
        query: String,
        legacy: RetrievalOutcome,
        v2: RetrievalOutcome,
        topK: Int = 10,
    ): ShadowComparisonReport {
        val legacyCandidates = candidatesOf(legacy)
        val v2Candidates = candidatesOf(v2)
        val legacyIds = legacyCandidates.map { it.docId }.distinct()
        val v2Ids = v2Candidates.map { it.docId }.distinct()

        val legacySet = legacyIds.toSet()
        val v2Set = v2Ids.toSet()
        val union = legacySet + v2Set
        val jaccard = if (union.isEmpty()) 1.0 else (legacySet intersect v2Set).size.toDouble() / union.size

        val legacyTop = legacyIds.take(topK)
        val v2Top = v2Ids.take(topK)
        val positions = minOf(legacyTop.size, v2Top.size)
        val agreement = if (positions == 0) 1.0 else
            (0 until positions).count { legacyTop[it] == v2Top[it] }.toDouble() / positions

        val reasons = mutableListOf<String>()
        if (jaccard < MIN_JACCARD_FOR_REVIEW) {
            reasons.add("命中集合重叠率过低(${"%.2f".format(jaccard)})")
        }
        if (legacyIds.isNotEmpty() && v2Ids.isEmpty()) {
            reasons.add("V2 零命中而旧链路有结果")
        }
        if (isDegraded(legacy)) reasons.add("旧链路降级")
        if (isDegraded(v2)) reasons.add("V2 降级")

        val report = ShadowComparisonReport(
            query = query,
            legacyDocIds = legacyIds,
            v2DocIds = v2Ids,
            jaccard = jaccard,
            topKAgreement = agreement,
            onlyInLegacy = (legacySet - v2Set).toList(),
            onlyInV2 = (v2Set - legacySet).toList(),
            legacyDegraded = isDegraded(legacy),
            v2Degraded = isDegraded(v2),
            needsReview = reasons.isNotEmpty(),
            reviewReasons = reasons,
        )
        if (report.needsReview) {
            Logger.w("ShadowReadComparator", report.summary())
        }
        return report
    }

    /** 聚合多轮比对:用于切换前的一次演练报告。 */
    fun aggregate(reports: List<ShadowComparisonReport>): ShadowAggregate {
        if (reports.isEmpty()) {
            return ShadowAggregate(0, 0.0, 0.0, 0, 0, 0)
        }
        val reviewCount = reports.count { it.needsReview }
        val avgJaccard = reports.map { it.jaccard }.average()
        val avgTopK = reports.map { it.topKAgreement }.average()
        val onlyLegacy = reports.sumOf { it.onlyInLegacy.size }
        val onlyV2 = reports.sumOf { it.onlyInV2.size }
        val degraded = reports.count { it.legacyDegraded || it.v2Degraded }
        return ShadowAggregate(
            queryCount = reports.size,
            avgJaccard = avgJaccard,
            avgTopKAgreement = avgTopK,
            reviewCount = reviewCount,
            onlyInLegacyTotal = onlyLegacy,
            onlyInV2Total = onlyV2,
            degradedCount = degraded,
        )
    }

    private fun candidatesOf(outcome: RetrievalOutcome): List<EvidenceCandidate> = when (outcome) {
        is RetrievalOutcome.Found -> outcome.candidates
        is RetrievalOutcome.Partial -> outcome.candidates
        else -> emptyList()
    }

    private fun isDegraded(outcome: RetrievalOutcome): Boolean = when (outcome) {
        is RetrievalOutcome.Partial -> true
        is RetrievalOutcome.Unavailable -> true
        is RetrievalOutcome.Failed -> true
        else -> false
    }
}

/** 多轮影子读聚合结果 — 切换决策依据。 */
data class ShadowAggregate(
    val queryCount: Int,
    val avgJaccard: Double,
    val avgTopKAgreement: Double,
    val reviewCount: Int,
    val onlyInLegacyTotal: Int,
    val onlyInV2Total: Int,
    val degradedCount: Int = 0,
) {
    /** 是否可进入下一步小范围切换(全部通过才建议)。 */
    val switchable: Boolean
        get() = queryCount > 0 && reviewCount == 0 && degradedCount == 0

    fun summary(): String =
        "shadow aggregate queries=$queryCount avgJaccard=${"%.2f".format(avgJaccard)} " +
            "avgTopK=${"%.2f".format(avgTopKAgreement)} review=$reviewCount " +
            "onlyLegacy=$onlyInLegacyTotal onlyV2=$onlyInV2Total degraded=$degradedCount " +
            "switchable=$switchable"
}
