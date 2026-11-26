package io.zer0.memory.runtime

import io.zer0.memory.budget.LlmBudget
import io.zer0.memory.compile.MemoryCompiler
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.pin.PinnedMemoryStore
import io.zer0.memory.summary.SessionSummaryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 默认记忆读取实现。
 *
 * 从 FactStore、CompiledSection 和 PinnedMemoryStore 读取同一 scope/space，
 * 输出结构化 [MemoryReadResult]。预算在结构化结果生成前应用，避免列表和 prompt
 * 看到不同的记忆集合。
 *
 * 预算按 pinned → facts → compiled → summary 的顺序分配：置顶记忆是用户显式要求
 * 「始终记住」的内容，超预算时最后被裁；compiled 只是 facts 的投影、摘要又是
 * compiled 的上游，后两者先让位。[MemoryReadRequest.tokenBudget] <= 0 表示不限制。
 */
class DefaultMemoryRuntime(
    private val factDbProvider: FactDbProvider,
    private val compiler: MemoryCompiler,
    private val summaryManager: SessionSummaryManager,
    private val pinnedMemoryStore: PinnedMemoryStore,
) : MemoryRuntime {

    override suspend fun read(request: MemoryReadRequest): MemoryReadResult = withContext(Dispatchers.IO) {
        val scope = request.scope.normalized() as? MemoryScopeResult.Valid
            ?: error("MemoryReadRequest scope must be valid: ${request.scope}")
        val normalized = scope.value
        val sources = mutableListOf<MemorySourceRevision>()

        val compiled = loadCompiled(request, normalized, sources)

        val store = factDbProvider.getFactStore(normalized.assistantId)
        val pinned = if (request.includePinned) {
            readPinned(store, normalized, request.includeGlobalPinned).also { entries ->
                entries.forEach { entry ->
                    sources += MemorySourceRevision(MemorySourceKind.PINNED, entry.id, 0, "")
                }
            }
        } else {
            emptyList()
        }
        val pinnedContents = pinned.mapTo(hashSetOf()) { it.content.trim() }
        val facts = if (request.includeFacts) {
            store.getByScopeAndSpace(
                normalized.scope,
                normalized.spaceId,
                includeSuppressed = request.includeSuppressed,
            ).let { all ->
                request.query?.takeIf { it.isNotBlank() }?.let { query ->
                    all.filter { fact -> fact.fact.contains(query, ignoreCase = true) }
                } ?: all
            }.filterNot { it.fact.trim() in pinnedContents }.also { list ->
                list.forEach { fact ->
                    sources += MemorySourceRevision(
                        kind = MemorySourceKind.FACT,
                        key = fact.id.toString(),
                        revision = 0,
                        fingerprint = "",
                    )
                }
            }
        } else {
            emptyList()
        }

        val sessionSummary = if (request.includeSessionSummary) {
            request.sessionId?.takeIf { it.isNotBlank() }
                ?.let { summaryManager.getSummary(it) }
                ?.takeIf { summary ->
                    summary.assistantId.ifBlank { "default" } == normalized.assistantId &&
                        summary.spaceId.ifBlank { "default" } == normalized.spaceId
                }
                ?.also {
                    sources += MemorySourceRevision(
                        kind = MemorySourceKind.SESSION_SUMMARY,
                        key = request.sessionId,
                        revision = it.summaryRevision,
                        fingerprint = it.sourceFingerprint,
                    )
                }
        } else {
            null
        }

        val fullTokens = estimateTokens(pinned, facts, compiled, sessionSummary)
        val budgeted = applyBudget(
            tokenBudget = request.tokenBudget,
            pinned = pinned,
            facts = facts,
            compiled = compiled,
            sessionSummary = sessionSummary,
        )
        val estimatedTokens = estimateTokens(
            budgeted.pinned,
            budgeted.facts,
            budgeted.compiled,
            budgeted.sessionSummary,
        )
        // 溯源只能指向实际进了结果的条目，否则列表、UI 和备份会把一条从未
        // 生效的来源当成当前依据。
        val visibleSources = retainedSources(sources, budgeted)
        MemoryReadResult(
            pinned = budgeted.pinned,
            facts = budgeted.facts,
            compiled = budgeted.compiled,
            sessionSummary = budgeted.sessionSummary,
            sources = visibleSources,
            usedTokens = estimatedTokens,
            truncated = request.tokenBudget > 0 && fullTokens > estimatedTokens,
        )
    }

    private suspend fun readPinned(
        store: FactStore,
        scope: MemoryScopeKey,
        includeGlobal: Boolean,
    ): List<PinnedMemoryStore.PinnedEntry> {
        val factPins = store.getPinned(scope.scope, scope.spaceId).mapNotNull { fact ->
            val pinnedAt = fact.pinnedAt ?: return@mapNotNull null
            PinnedMemoryStore.PinnedEntry(
                id = "fact:${scope.assistantId}:${fact.id}",
                content = fact.fact,
                createdAt = pinnedAt,
                updatedAt = pinnedAt,
                assistantId = scope.assistantId,
            )
        }
        val standalone = pinnedMemoryStore.getForAssistant(scope.assistantId, includeGlobal)
        val scopedPins = if (standalone.any { it.assistantId == scope.assistantId }) {
            // 旧版事实副本只有助手标记。与其他空间的置顶事实同文时按事实的空间隔离。
            val otherSpaceContents = store.getPinnedContentsOutside(scope.scope, scope.spaceId)
            standalone.filterNot { it.assistantId == scope.assistantId && it.content.trim() in otherSpaceContents }
        } else {
            standalone
        }
        return (factPins + scopedPins).distinctBy { it.content.trim() }
    }

    /**
     * 读取当前 scope/space 的编译段落，并把每段的来源追向 [sources]。
     *
     * 走行级读取而不是 readSection：只有拿到 status/fingerprint 才能判断
     * 「这段是不是失败的投影」。默认只暴露 READY —— 失败/过期的内容一旦进入
     * prompt，模型会把它当成当前事实，而用户毫不知情。
     */
    private suspend fun loadCompiled(
        request: MemoryReadRequest,
        scope: MemoryScopeKey,
        sources: MutableList<MemorySourceRevision>,
    ): CompiledMemorySnapshot? {
        if (!request.includeCompiled) return null
        val rows = compiler.readScopedSectionRows(scope.scope, scope.spaceId)
        val sections = MemoryCompiledProjection.visibleSections(
            rows,
            includeNonReady = request.includeNonReadyCompiled,
        )
        val revisions = rows
            .filter { row -> sections.containsKey(row.sectionKey) }
            .map { row ->
                MemorySourceRevision(
                    kind = MemorySourceKind.COMPILED_SECTION,
                    key = row.sectionKey,
                    // revision 用内容指纹的长度没有意义；这里给 0，真实判等靠 fingerprint。
                    revision = 0,
                    fingerprint = MemoryCompiledProjection.provenanceOf(row).contentFingerprint,
                )
            }
        val compiled = CompiledMemorySnapshot(
            sections = sections,
            sourceRevisions = revisions,
            provenance = rows.associate { row ->
                row.sectionKey to MemoryCompiledProjection.provenanceOf(row)
            },
        )
        sources += compiled.sourceRevisions
        return compiled
    }

    private data class BudgetedMemory(
        val pinned: List<PinnedMemoryStore.PinnedEntry>,
        val facts: List<FactStore.Fact>,
        val compiled: CompiledMemorySnapshot?,
        val sessionSummary: SessionSummaryManager.SummaryData?,
    )

    /**
     * 过滤出指向实际保留项的溯源。
     *
     * 被预算整条裁掉的条目不能留下溯源：否则备份与 UI 会把一条从未进入 prompt 的内容
     * 当成当前依据。类目与预算层次一一对应，新增来源类型必须显式决定它的归属。
     */
    private fun retainedSources(
        sources: List<MemorySourceRevision>,
        budgeted: BudgetedMemory,
    ): List<MemorySourceRevision> {
        val factKeys = budgeted.facts.mapTo(mutableSetOf()) { fact -> fact.id.toString() }
        val pinnedKeys = budgeted.pinned.mapTo(mutableSetOf()) { entry -> entry.id }
        val sectionKeys = budgeted.compiled?.sections?.keys.orEmpty()
        val summaryRetained = budgeted.sessionSummary != null
        return sources.filter { source ->
            when (source.kind) {
                MemorySourceKind.COMPILED_SECTION -> source.key in sectionKeys
                MemorySourceKind.FACT -> source.key in factKeys
                MemorySourceKind.PINNED -> source.key in pinnedKeys
                MemorySourceKind.SESSION_SUMMARY -> summaryRetained
                // 本运行时只读 fact / compiled / pinned / summary，不产出经验类来源；
                // 真要出现也不能冒充已注入。
                MemorySourceKind.EXPERIENCE -> false
            }
        }
    }

    /**
     * 按优先级分配 [tokenBudget]：pinned → facts → compiled → summary。
     *
     * 每条用 [LlmBudget.countTokens] 计实际计入的 token（含截断标记）并从剩余额度里扣掉，
     * 不用字符数/4 —— 中文按 4 字符折 1 token 会低估约 3 倍，预算看起来充足、实际溢出。
     * 连截断标记都放不下的条目直接丢弃，不留半截正文。[tokenBudget] <= 0 表示不限制。
     */
    private fun applyBudget(
        tokenBudget: Int,
        pinned: List<PinnedMemoryStore.PinnedEntry>,
        facts: List<FactStore.Fact>,
        compiled: CompiledMemorySnapshot?,
        sessionSummary: SessionSummaryManager.SummaryData?,
    ): BudgetedMemory {
        if (tokenBudget <= 0) return BudgetedMemory(pinned, facts, compiled, sessionSummary)
        var remaining = tokenBudget

        fun take(text: String): String? {
            if (remaining <= 0 || text.isBlank()) return null
            val kept = LlmBudget.truncateToTokenBudgetStrict(text, remaining)
            if (kept.isBlank()) return null
            remaining -= LlmBudget.countTokens(kept)
            return kept
        }

        val clippedPinned = pinned.mapNotNull { entry ->
            take(entry.content)?.let { entry.copy(content = it) }
        }
        val clippedFacts = facts.mapNotNull { fact ->
            take(fact.fact)?.let { fact.copy(fact = it) }
        }
        val clippedSections = compiled?.sections.orEmpty().mapNotNull { (key, content) ->
            take(content)?.let { key to it }
        }.toMap()
        // 被整段裁掉的 section 不再留在 sourceRevisions 里；provenance 仍保留全部行，
        // 因为它是给 UI 看「这张投影是什么状态」的诊断信息，与「是否已注入」是两件事。
        val clippedCompiled = compiled?.copy(
            sections = clippedSections,
            sourceRevisions = compiled.sourceRevisions.filter { it.key in clippedSections },
        )
        val clippedSummary = sessionSummary?.let { summary ->
            take(summary.summary)?.let { summary.copy(summary = it) }
        }
        return BudgetedMemory(clippedPinned, clippedFacts, clippedCompiled, clippedSummary)
    }

    /** 各条实际内容的 token 之和：与预算扣减同一把尺子，避免 usedTokens 与实际注入不一致。 */
    private fun estimateTokens(
        pinned: List<PinnedMemoryStore.PinnedEntry>,
        facts: List<FactStore.Fact>,
        compiled: CompiledMemorySnapshot?,
        sessionSummary: SessionSummaryManager.SummaryData?,
    ): Int {
        var tokens = 0
        pinned.forEach { tokens += LlmBudget.countTokens(it.content) }
        facts.forEach { tokens += LlmBudget.countTokens(it.fact) }
        compiled?.sections?.values.orEmpty().forEach { tokens += LlmBudget.countTokens(it) }
        sessionSummary?.summary?.let { tokens += LlmBudget.countTokens(it) }
        return tokens
    }
}
