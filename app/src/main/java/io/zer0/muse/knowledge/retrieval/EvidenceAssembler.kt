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
 *
 * 可见性合同(本层新增):
 *  6. 每条证据的可见性由 [EvidenceVisibility] 显式表达,引用上的 `isInternal` 由它推导,
 *     不再固定写 false;
 *  7. 已知内部资料只在 [includeInternal] 打开时注入;可见性**未知**的资料默认不注入
 *     (保守:宁可少注入,也不把不确定归属的内容送进上下文)。
 *     可见性来源是 [DocumentVisibilityIndex](装配层检索前刷新);未接索引又未传
 *     `visibilityOf` 时一律 UNKNOWN,不会因为“看起来像用户资料”而放行。
 */

/** 单条证据资料的可见性判定结果。 */
enum class EvidenceVisibility {
    /** 用户可读资料(用户库/会话附件):可注入,引用不标内部。 */
    USER_VISIBLE,

    /** 内部资料(内置帮助等):只在显式允许时注入,引用标记为内部。 */
    INTERNAL,

    /** 无法判定归属(文档已删、检索链路未携带归属):默认不注入。 */
    UNKNOWN,
}

/**
 * 文档可见性索引 — 装配层在检索前刷新,证据装配时按 docId 查。
 *
 * 不存在的 docId 一律返回 [EvidenceVisibility.UNKNOWN](即不注入),
 * 所以「忘了刷新」的后果是少给上下文,而不是把内部资料送进提示词。
 */
class DocumentVisibilityIndex {
    private val entries = java.util.concurrent.ConcurrentHashMap<String, EvidenceVisibility>()

    fun visibilityOf(docId: String): EvidenceVisibility =
        entries[docId] ?: EvidenceVisibility.UNKNOWN

    /** 整体替换索引(刷新语义:已删文档不残留旧判定)。 */
    fun replaceAll(values: Map<String, EvidenceVisibility>) {
        entries.clear()
        entries.putAll(values)
    }
}

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
    /** 该资料在装配时的可见性判定;引用上的 isInternal 由它推导。 */
    val visibility: EvidenceVisibility = EvidenceVisibility.UNKNOWN,
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
    /** 是否发生任何截断/跳过/可见性抑制。 */
    val degraded: Boolean,
    /** 因判定为内部资料且未允许注入而被抑制的候选数。 */
    val suppressedInternalCount: Int = 0,
    /** 因可见性未知而被保守抑制的候选数。 */
    val suppressedUnknownCount: Int = 0,
) {
    val isEmpty: Boolean get() = units.isEmpty()
}

class EvidenceAssembler(
    private val estimator: (String) -> Int = { text -> (text.length + 3) / 4 },
    /**
     * docId → 可见性的显式覆盖。为 null 时读 [visibilityIndex]。两者都判定不了就是
     * [EvidenceVisibility.UNKNOWN],装配层按保守规则不注入。
     */
    private val visibilityOf: ((String) -> EvidenceVisibility)? = null,
    /** 是否允许注入已知内部资料(内置帮助入口需要显式打开)。 */
    private val includeInternal: Boolean = false,
    /** 装配层刷新的可见性索引(未传 lambda 时作为判定来源)。 */
    val visibilityIndex: DocumentVisibilityIndex = DocumentVisibilityIndex(),
) {

    /** 可见性判定:显式 lambda 优先,否则读索引,都没命中就是 UNKNOWN。 */
    private fun resolveVisibility(docId: String): EvidenceVisibility =
        visibilityOf?.invoke(docId) ?: visibilityIndex.visibilityOf(docId)

    /**
     * 按预算装配证据。
     *
     * @param outcome 检索结果;只有 Found / Partial 会产出证据。
     * @param budgetTokens 本轮可用 token 预算;<=0 视为无预算,返回空 bundle(不伪造)。
     * @param docTitles docId → 标题(用于引用展示)。
     * @param allowInternal 本次是否允许内部资料;null = 沿用构造参数(false)。
     */
    fun assemble(
        outcome: RetrievalOutcome,
        budgetTokens: Int,
        docTitles: Map<String, String> = emptyMap(),
        traceId: String = "",
        allowInternal: Boolean? = null,
    ): EvidenceBundle {
        val includeInternalResolved = allowInternal ?: includeInternal
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
        var suppressedInternal = 0
        var suppressedUnknown = 0
        var degraded = false
        val units = mutableListOf<EvidenceUnit>()

        for (candidate in candidates) {
            val visibility = resolveVisibility(candidate.docId)
            // 可见性先于预算:被抑制的资料不占编号、不进正文。
            when (visibility) {
                EvidenceVisibility.INTERNAL -> if (!includeInternalResolved) {
                    suppressedInternal++
                    degraded = true
                    continue
                }
                EvidenceVisibility.UNKNOWN -> {
                    suppressedUnknown++
                    degraded = true
                    continue
                }
                EvidenceVisibility.USER_VISIBLE -> Unit
            }
            val rendered = renderUnit(candidate, units.size + 1, budgetTokens - used, docTitles)
            if (rendered == null) {
                skipped++
                degraded = true
                continue
            }
            if (rendered.truncated) degraded = true
            used += estimator(rendered.renderedText)
            units.add(rendered.unit.copy(visibility = visibility))
        }

        if (units.isEmpty()) {
            return emptyBundle(traceId).copy(
                skippedCount = skipped,
                degraded = degraded,
                suppressedInternalCount = suppressedInternal,
                suppressedUnknownCount = suppressedUnknown,
            )
        }

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
                // 引用可见性由装配时的判定推导,不写死 false。
                isInternal = unit.visibility == EvidenceVisibility.INTERNAL,
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
            suppressedInternalCount = suppressedInternal,
            suppressedUnknownCount = suppressedUnknown,
        )
    }

    /** 单条候选的渲染结果 — 完整渲染文本(含标题/分隔符)+ 单元本体。 */
    private data class RenderedUnit(
        val renderedText: String,
        val unit: EvidenceUnit,
        val truncated: Boolean,
    )

    /**
     * 渲染单条候选;预算不足以承载一个完整单元时返回 null(调用方计入 skipped)。
     *
     * @param index 本候选的编号(1-based,只数已接纳的单元)
     * @param budgetRemaining 剩余预算(含本条的标题/分隔符开销)
     */
    private fun renderUnit(
        candidate: EvidenceCandidate,
        index: Int,
        budgetRemaining: Int,
        docTitles: Map<String, String>,
    ): RenderedUnit? {
        val title = docTitles[candidate.docId]?.takeIf { it.isNotBlank() } ?: candidate.docId
        val locatorLabel = candidate.locator.ifBlank { "?" }
        val header = "【资料 $index】$title（$locatorLabel）\n"
        val separator = if (index == 1) "" else "\n\n"
        val fixedTokens = estimator(header) + estimator(separator) + estimator("[$index]")
        val remaining = budgetRemaining - fixedTokens
        if (remaining <= MIN_SNIPPET_TOKENS) return null

        val snippet = if (estimator(candidate.text) <= remaining) {
            candidate.text
        } else {
            // 结构安全截取:按字素边界截到预算内,不切断代理对。
            truncateToTokens(candidate.text, remaining)
        }
        if (snippet.isBlank() || estimator(snippet) <= 0) return null

        val truncated = snippet.length < candidate.text.length
        return RenderedUnit(
            renderedText = "$separator$header$snippet",
            unit = EvidenceUnit(
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
            truncated = truncated,
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
