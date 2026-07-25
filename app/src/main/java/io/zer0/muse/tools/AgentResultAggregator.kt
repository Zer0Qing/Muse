package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.common.resultOf

/**
 * v1.200: 多 Agent 结果聚合与冲突消解。
 *
 * 当多个 Agent 对同一任务给出不同结果时,通过以下策略统一输出:
 * - Merge: 按来源拼接,保留所有视角
 * - Vote: 对分类/简短答案进行多数投票
 * - ExpertReview: 按长度/置信度选出最详尽的单一结果
 * - ConfidenceWeighted: 按置信度加权合并(需结果带 confidence)
 * - Consensus: 先尝试找共识,无共识时降级为 Merge 并标注分歧
 * - LlmReview: 调用独立 LLM 综合评审所有候选,产出融合输出
 *               llmReviewer 缺失或调用失败时降级为 ExpertReview
 */
object AgentResultAggregator {

    /**
     * 可聚合的最小单元。
     *
     * @param source 来源标识(助手名/团队节点 id)
     * @param content 结果文本
     * @param confidence 可选置信度 0..1
     */
    data class Candidate(
        val source: String,
        val content: String,
        val confidence: Float? = null,
    )

    /**
     * 聚合结果。
     *
     * @param output 最终输出文本
     * @param strategy 使用的策略
     * @param conflicts 检测到的冲突点(无冲突为空)
     * @param candidates 原始候选列表
     */
    data class Aggregation(
        val output: String,
        val strategy: Strategy,
        val conflicts: List<String> = emptyList(),
        val candidates: List<Candidate> = emptyList(),
    ) {
        val hasConflict: Boolean get() = conflicts.isNotEmpty()
    }

    enum class Strategy {
        MERGE,
        VOTE,
        EXPERT_REVIEW,
        CONFIDENCE_WEIGHTED,
        CONSENSUS,
        LLM_REVIEW,
    }

    /**
     * 聚合候选结果。
     *
     * @param candidates 候选结果列表
     * @param strategy 聚合策略
     * @param question 原始问题/任务(用于投票时判断答案相似性 / LLM 评审上下文,可选)
     * @param llmReviewer LLM 评审回调,仅 [Strategy.LLM_REVIEW] 使用。
     *        传入候选列表与原始问题,返回综合融合后的文本;返回 null 视为评审失败,降级为 EXPERT_REVIEW。
     *        为 null 时记录 Logger.w 并降级为 EXPERT_REVIEW。
     */
    suspend fun aggregate(
        candidates: List<Candidate>,
        strategy: Strategy = Strategy.MERGE,
        question: String? = null,
        llmReviewer: (suspend (List<Candidate>, String?) -> String?)? = null,
    ): Aggregation {
        val valid = candidates.filter { it.content.isNotBlank() }
        if (valid.isEmpty()) {
            return Aggregation(output = "", strategy = strategy, candidates = candidates)
        }
        if (valid.size == 1) {
            return Aggregation(output = valid.first().content, strategy = strategy, candidates = candidates)
        }

        return when (strategy) {
            Strategy.MERGE -> merge(valid)
            Strategy.VOTE -> vote(valid, question)
            Strategy.EXPERT_REVIEW -> expertReview(valid)
            Strategy.CONFIDENCE_WEIGHTED -> confidenceWeighted(valid)
            Strategy.CONSENSUS -> consensus(valid)
            Strategy.LLM_REVIEW -> llmReview(valid, question, llmReviewer)
        }
    }

    /**
     * LLM 综合评审策略。
     *
     * - llmReviewer 为 null:记录 Logger.w 后降级为 EXPERT_REVIEW
     * - llmReviewer 调用返回 null/空文本:记录 Logger.w 后降级为 EXPERT_REVIEW
     * - 正常返回时,输出 LLM 融合文本,并在 conflicts 中标注 "由 LLM 综合评审"
     */
    private suspend fun llmReview(
        candidates: List<Candidate>,
        question: String?,
        llmReviewer: (suspend (List<Candidate>, String?) -> String?)?,
    ): Aggregation {
        if (llmReviewer == null) {
            Logger.w("AgentResultAggregator", "llm_review 缺少 llmReviewer,降级为 expert_review")
            return expertReview(candidates)
        }
        // H-LLM1: 用 resultOf{} 包裹 suspend 调用,正确重抛 CancellationException
        val reviewed = resultOf { llmReviewer(candidates, question) }
            .onError { msg, t ->
                Logger.w("AgentResultAggregator", "llm_review 调用异常,降级为 expert_review: $msg", t)
            }
            .getOrNull()
        if (reviewed.isNullOrBlank()) {
            Logger.w("AgentResultAggregator", "llm_review 返回空结果,降级为 expert_review")
            return expertReview(candidates)
        }
        return Aggregation(
            output = reviewed.trim(),
            strategy = Strategy.LLM_REVIEW,
            conflicts = listOf("由 LLM 综合评审(候选 ${candidates.size} 个)"),
            candidates = candidates,
        )
    }

    /** 拼接所有候选,标注来源。 */
    private fun merge(candidates: List<Candidate>): Aggregation {
        val output = buildString {
            appendLine("多 Agent 结果汇总:")
            candidates.forEachIndexed { idx, c ->
                appendLine()
                appendLine("--- 来源 ${idx + 1}: ${c.source} ${c.confidence?.let { "(置信度 ${"%.2f".format(it)})" } ?: ""}---")
                appendLine(c.content)
            }
        }.trimEnd()
        return Aggregation(output = output, strategy = Strategy.MERGE, candidates = candidates)
    }

    /**
     * 投票策略。
     *
     * 对短答案(<=50 字)进行规范化后分组,返回最高票答案。
     * 若最高票占比低于 0.5,视为存在冲突,输出中标注分歧。
     */
    private fun vote(candidates: List<Candidate>, question: String?): Aggregation {
        val shortCandidates = candidates.filter { it.content.length <= 150 }
        val target = if (shortCandidates.size >= 2) shortCandidates else candidates

        val groups = target.groupBy { normalizeForVote(it.content) }
        val ranked = groups.entries.sortedByDescending { it.value.size }
        val winner = ranked.first()
        val total = target.size
        val winnerRatio = winner.value.size.toFloat() / total

        val conflicts = if (winnerRatio < 0.5f) {
            listOf("投票未形成明显多数:最高票 ${winner.value.size}/$total (${"%.0f".format(winnerRatio * 100)}%)")
        } else emptyList()

        val output = buildString {
            appendLine("投票结果(共 $total 票):")
            ranked.forEachIndexed { idx, (answer, votes) ->
                appendLine("${idx + 1}. [${votes.size} 票] $answer")
            }
            if (conflicts.isNotEmpty()) {
                appendLine()
                appendLine("注意: ${conflicts.first()}")
            }
            appendLine()
            appendLine("最终采用: ${winner.key}")
        }.trimEnd()

        return Aggregation(output = output, strategy = Strategy.VOTE, conflicts = conflicts, candidates = candidates)
    }

    /** 专家评审:选择内容最长或置信度最高的结果作为权威答案。 */
    private fun expertReview(candidates: List<Candidate>): Aggregation {
        val best = candidates.maxWithOrNull(compareBy<Candidate> {
            it.confidence ?: 0f
        }.thenBy { it.content.length }) ?: candidates.first()

        val output = buildString {
            appendLine("专家评审结果(选自 ${best.source}):")
            appendLine()
            appendLine(best.content)
            if (candidates.size > 1) {
                appendLine()
                appendLine("参考来源: ${candidates.map { it.source }.distinct().joinToString(", ")}")
            }
        }.trimEnd()

        return Aggregation(output = output, strategy = Strategy.EXPERT_REVIEW, candidates = candidates)
    }

    /** 按置信度加权:把带 confidence 的结果按权重排序,无置信度时退化为 Merge。 */
    private fun confidenceWeighted(candidates: List<Candidate>): Aggregation {
        val withConfidence = candidates.filter { it.confidence != null }
        if (withConfidence.size < 2) {
            Logger.w("AgentResultAggregator", "confidence_weighted 候选缺少置信度,退化为 merge")
            return merge(candidates)
        }
        val sorted = withConfidence.sortedByDescending { it.confidence }
        val totalConfidence = sorted.sumOf { (it.confidence ?: 0f).toDouble() }.toFloat().coerceAtLeast(0.0001f)

        val output = buildString {
            appendLine("置信度加权汇总(总权重 ${"%.2f".format(totalConfidence)}):")
            sorted.forEach { c ->
                val weight = ((c.confidence ?: 0f) / totalConfidence * 100).toInt()
                appendLine("- [${c.source}] 权重 $weight%: ${c.content.take(200)}")
            }
            appendLine()
            appendLine("最高置信度结果(${sorted.first().confidence?.let { "%.2f".format(it) } ?: "-"}):")
            appendLine(sorted.first().content)
        }.trimEnd()

        return Aggregation(output = output, strategy = Strategy.CONFIDENCE_WEIGHTED, candidates = candidates)
    }

    /**
     * 共识策略。
     *
     * 先尝试找完全一致的答案;若找不到,检测差异点并降级为 Merge。
     */
    private fun consensus(candidates: List<Candidate>): Aggregation {
        val normalized = candidates.map { normalizeForVote(it.content) }
        val groups = candidates.groupBy { normalizeForVote(it.content) }
        val majority = groups.maxByOrNull { it.value.size }

        return if (majority != null && majority.value.size.toFloat() / candidates.size >= 0.5f) {
            Aggregation(
                output = majority.key,
                strategy = Strategy.CONSENSUS,
                candidates = candidates,
            )
        } else {
            val conflicts = detectConflicts(candidates)
            val merged = merge(candidates)
            val output = buildString {
                appendLine("未达成一致,已降级为汇总输出。检测到以下分歧:")
                conflicts.forEach { appendLine("- $it") }
                appendLine()
                append(merged.output)
            }.trimEnd()
            Aggregation(output = output, strategy = Strategy.CONSENSUS, conflicts = conflicts, candidates = candidates)
        }
    }

    /** 规范化文本用于投票比较(去空白、去标点、小写)。 */
    private fun normalizeForVote(text: String): String {
        return text.trim()
            .lowercase()
            .replace(Regex("[\\s\\n\\r\\t]+", RegexOption.MULTILINE), " ")
            .replace(Regex("[，。！？.,!?;:；；\"'“”]"), "")
            .trim()
    }

    /** 简单冲突检测:返回候选结果之间的差异摘要。 */
    private fun detectConflicts(candidates: List<Candidate>): List<String> {
        val summaries = candidates.mapIndexed { idx, c ->
            "来源 ${idx + 1}(${c.source}): ${c.content.take(80)}${if (c.content.length > 80) "..." else ""}"
        }
        return if (summaries.size <= 1) emptyList() else listOf(summaries.joinToString("\n"))
    }
}
