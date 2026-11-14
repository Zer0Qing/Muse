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

        val compiled = if (request.includeCompiled) {
            val sections = MemoryCompiler.Section.ALL.associate { section ->
                section.key to compiler.readSection(section, normalized.scope, normalized.spaceId)
            }
            val compiled = CompiledMemorySnapshot(
                sections = sections,
                sourceRevisions = MemoryCompiler.Section.ALL.map { section ->
                    MemorySourceRevision(
                        kind = MemorySourceKind.COMPILED_SECTION,
                        key = section.key,
                        revision = 0,
                        fingerprint = "",
                    )
                },
            )
            sources += compiled.sourceRevisions
            compiled
        } else {
            null
        }

        val store = factDbProvider.getFactStore(normalized.assistantId)
        val facts = if (request.includeFacts) {
            store.getByScopeAndSpace(
                normalized.scope,
                normalized.spaceId,
                includeSuppressed = request.includeSuppressed,
            ).let { all ->
                request.query?.takeIf { it.isNotBlank() }?.let { query ->
                    all.filter { fact -> fact.fact.contains(query, ignoreCase = true) }
                } ?: all
            }.also { list ->
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

        val pinned = if (request.includePinned) {
            pinnedMemoryStore.getForAssistant(
                normalized.assistantId,
                includeGlobal = request.includeGlobalPinned,
            ).also { list ->
                list.forEach { entry ->
                    sources += MemorySourceRevision(
                        kind = MemorySourceKind.PINNED,
                        key = entry.id,
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

        val fullTokens = estimateTokens(pinned, facts, compiled)
        val budgeted = applyBudget(request.tokenBudget, pinned, facts, compiled)
        val estimatedTokens = estimateTokens(budgeted.pinned, budgeted.facts, budgeted.compiled)
        val budgetedSourceKeys = buildSet {
            addAll(budgeted.facts.map { it.id.toString() })
            addAll(budgeted.pinned.map { it.id })
        }
        val visibleSources = sources.filter { source ->
            source.kind == MemorySourceKind.COMPILED_SECTION || source.key in budgetedSourceKeys
        }
        MemoryReadResult(
            pinned = budgeted.pinned,
            facts = budgeted.facts,
            compiled = budgeted.compiled,
            sessionSummary = sessionSummary,
            sources = visibleSources,
            usedTokens = estimatedTokens,
            truncated = request.tokenBudget > 0 && fullTokens > estimatedTokens,
        )
    }

    private data class BudgetedMemory(
        val pinned: List<PinnedMemoryStore.PinnedEntry>,
        val facts: List<FactStore.Fact>,
        val compiled: CompiledMemorySnapshot?,
    )

    private fun applyBudget(
        tokenBudget: Int,
        pinned: List<PinnedMemoryStore.PinnedEntry>,
        facts: List<FactStore.Fact>,
        compiled: CompiledMemorySnapshot?,
    ): BudgetedMemory {
        if (tokenBudget <= 0) return BudgetedMemory(pinned, facts, compiled)
        var remaining = tokenBudget
        fun takeText(text: String): String {
            if (remaining <= 0 || text.isBlank()) return ""
            val clipped = LlmBudget.truncateToTokenBudget(text, remaining)
            remaining -= characterEstimateToTokens(clipped.length)
            return clipped
        }
        val clippedSections = compiled?.sections.orEmpty().mapValues { (_, value) -> takeText(value) }
        val clippedCompiled = compiled?.copy(sections = clippedSections)
        val clippedFacts = facts.mapNotNull { fact ->
            takeText(fact.fact).takeIf { it.isNotBlank() }?.let { fact.copy(fact = it) }
        }
        val clippedPinned = pinned.mapNotNull { entry ->
            takeText(entry.content).takeIf { it.isNotBlank() }?.let { entry.copy(content = it) }
        }
        return BudgetedMemory(clippedPinned, clippedFacts, clippedCompiled)
    }

    private fun estimateTokens(
        pinned: List<PinnedMemoryStore.PinnedEntry>,
        facts: List<FactStore.Fact>,
        compiled: CompiledMemorySnapshot?,
    ): Int {
        var chars = pinned.sumOf { it.content.length } + facts.sumOf { it.fact.length }
        chars += compiled?.sections?.values.orEmpty().sumOf { it.length } ?: 0
        return characterEstimateToTokens(chars)
    }

    private fun characterEstimateToTokens(chars: Int): Int = maxOf(1, chars / 4)
}
