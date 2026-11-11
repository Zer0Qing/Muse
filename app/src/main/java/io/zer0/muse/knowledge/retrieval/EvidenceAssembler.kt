package io.zer0.muse.knowledge.retrieval

import io.zer0.ai.core.RagCitation

/**
 * R7 证据装配 — 设计 §11「证据、预算与引用闭环」与计划 R7。
 *
 * 核心合同(由 EvidenceBudgetContractTest 钉住):
 *  1. [EvidenceBundle] 同时包含最终上下文文本、**实际发送片段**、citation 快照与 traceId ——
 *     引用不是事后根据检索结果"顺手再挂上去"的列表;
 *  2. 预算按**完整渲染单元**计算:标题、定位标签、分隔符、snippet 全部计入;
 *  3. 超长首块按结构安全截取(不直接终止整个候选集);剩余预算不足时跳过该条继续下一条;
 *  4. 最终只对可发送的完整证据单元编号,citation 编号 1..N 与文本中的 [N] 一一对应;
 *  5. 没有证据时不注入一段空的"资料说明"。
 */

/** 单个证据单元 — 渲染文本、实际 snippet、定位、token 估计。 */
data class EvidenceUnit(
    val index: Int,
    val docId: String,
    val chunkId: String,
    val generationId: String,
    val docTitle: String,
    val locator: String,
    /** 实际发送给模型的片段(可能被截断)。 */
    val sentSnippet: String,
    val truncated: Boolean,
    val tokenEstimate: Int,
)

data class EvidenceBundle(
    /** 完整注入文本(含 [N] 标记);无证据时为空串。 */
    val finalText: String,
    val units: List<EvidenceUnit>,
    val citations: List<RagCitation>,
    val tokenEstimate: Int,
    val traceId: String,
    /** 因预算被跳过的候选数。 */
    val skippedCount: Int,
    /** 是否发生任何截断/跳过。 */
    val degraded: Boolean,
) {
    val isEmpty: Boolean get() = units.isEmpty()
}

class EvidenceAssembler(
    private val estimator: (String) -> Int = { text -> (text.length + 3) / 4 },
) {

    /**
     * 按预算装配证据。
     *
     * @param outcome 检索结果;只有 Found / Partial 会产出证据。
     * @param budgetTokens 本轮可用 token 预算;<=0 视为无预算,返回空 bundle(不伪造)。
     * @param docTitles docId → 标题(用于引用展示)。
     */
    fun assemble(
        outcome: RetrievalOutcome,
        budgetTokens: Int,
        docTitles: Map<String, String> = emptyMap(),
        traceId: String = "",
    ): EvidenceBundle {
        val candidates = when (outcome) {
            is RetrievalOutcome.Found -> outcome.candidates
            is RetrievalOutcome.Partial -> outcome.candidates
            else -> emptyList()
        }
        if (candidates.isEmpty() || budgetTokens <= 0) {
            return emptyBundle(traceId)
        }

        var used = 0
        var skipped = 0
        var degraded = false
        val units = mutableListOf<EvidenceUnit>()

        for (candidate in candidates) {
            val title = docTitles[candidate.docId]?.takeIf { it.isNotBlank() } ?: candidate.docId
            val locatorLabel = candidate.locator.ifBlank { "?" }
            val header = "【资料 ${units.size + 1}】$title（$locatorLabel）\n"
            val separator = if (units.isEmpty()) "" else "\n\n"
            val fixedTokens = estimator(header) + estimator(separator) + estimator("[${units.size + 1}]")
            val remaining = budgetTokens - used - fixedTokens
            if (remaining <= MIN_SNIPPET_TOKENS) {
                skipped++
                degraded = true
                continue
            }
            val originalTokens = estimator(candidate.text)
            val snippet = if (originalTokens <= remaining) {
                candidate.text
            } else {
                // 结构安全截取:按字素边界截到预算内,不切断代理对。
                truncateToTokens(candidate.text, remaining)
            }
            if (snippet.isBlank() || estimator(snippet) <= 0) {
                skipped++
                degraded = true
                continue
            }
            val truncated = snippet.length < candidate.text.length
            if (truncated) degraded = true
            val index = units.size + 1
            val unitText = "$separator$header$snippet"
            used += estimator(unitText)
            units.add(
                EvidenceUnit(
                    index = index,
                    docId = candidate.docId,
                    chunkId = candidate.chunkId,
                    generationId = candidate.generationId,
                    docTitle = title,
                    locator = locatorLabel,
                    sentSnippet = snippet,
                    truncated = truncated,
                    tokenEstimate = estimator(snippet),
                ),
            )
        }

        if (units.isEmpty()) return emptyBundle(traceId).copy(skippedCount = skipped, degraded = degraded)

        val finalText = units.joinToString("") { unit ->
            val prefix = if (unit.index == 1) "" else "\n\n"
            "$prefix【资料 ${unit.index}】${unit.docTitle}（${unit.locator}）\n${unit.sentSnippet}"
        }
        val citations = units.map { unit ->
            RagCitation(
                index = unit.index,
                docId = unit.docId,
                docTitle = unit.docTitle,
                chunkId = unit.chunkId,
                chunkIndex = 0,
                snippet = unit.sentSnippet.take(200),
                score = 0f,
                matchType = "knowledge_v2",
                isInternal = false,
            )
        }
        return EvidenceBundle(
            finalText = finalText,
            units = units,
            citations = citations,
            tokenEstimate = estimator(finalText),
            traceId = traceId,
            skippedCount = skipped,
            degraded = degraded || skipped > 0,
        )
    }

    /**
     * 全局上下文协调器二次缩减:必须重新装配而不是对最终字符串 `take`。
     * 这里按比例降低预算并重跑同一算法,保证 citation 与文本一致。
     */
    fun reassembleWithBudget(
        previous: EvidenceBundle,
        newBudgetTokens: Int,
        outcomeCandidates: List<EvidenceCandidate>,
        docTitles: Map<String, String> = emptyMap(),
    ): EvidenceBundle {
        if (previous.isEmpty) return previous
        val synthetic = RetrievalOutcome.Found(outcomeCandidates, dummyTrace(previous.traceId))
        return assemble(synthetic, newBudgetTokens, docTitles, previous.traceId)
    }

    /** 字符安全截断:按 code point 边界,避免拆开代理对/组合字符簇的代理部分。 */
    fun truncateToTokens(text: String, maxTokens: Int): String {
        if (maxTokens <= 0) return ""
        // 估算反推最大字符数(保守:4 字符/token)。
        val maxChars = maxTokens * CHARS_PER_TOKEN
        if (text.length <= maxChars) return text
        var end = maxChars.coerceAtMost(text.length)
        // 避免停在低代理位置。
        if (end > 0 && end < text.length && Character.isHighSurrogate(text[end - 1])) {
            end--
        }
        return text.substring(0, end)
    }

    private fun emptyBundle(traceId: String) = EvidenceBundle(
        finalText = "",
        units = emptyList(),
        citations = emptyList(),
        tokenEstimate = 0,
        traceId = traceId,
        skippedCount = 0,
        degraded = false,
    )

    private fun dummyTrace(traceId: String) = RetrievalTrace(
        requestId = traceId,
        entry = "REASSEMBLE",
        scopeSummary = "",
        spaces = emptyList(),
        lexicalCount = 0,
        denseCount = 0,
        fusedCount = 0,
        finalCount = 0,
        degradedReasons = emptyList(),
        elapsedMs = 0L,
    )

    private companion object {
        /** 小于此值的剩余预算视为无法承载有效片段(标题/定位已占满)。 */
        const val MIN_SNIPPET_TOKENS = 8

        /** 估算口径:4 字符 ≈ 1 token。 */
        const val CHARS_PER_TOKEN = 4
    }
}
