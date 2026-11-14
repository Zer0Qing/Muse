package io.zer0.memory.runtime

import io.zer0.memory.fact.FactStore
import io.zer0.memory.pin.PinnedMemoryStore
import kotlinx.coroutines.CancellationException

data class MemorySearchRequest(
    val scope: MemoryScopeKey,
    val query: String?,
    val tags: List<String> = emptyList(),
    val dateRange: FactStore.DateRange? = null,
    val limit: Int = 20,
    val includeSuppressed: Boolean = false,
)

data class SaveMemoryRequest(
    val scope: MemoryScopeKey,
    val fact: FactStore.Fact,
)

data class DeleteMemoryRequest(
    val scope: MemoryScopeKey,
    val factId: Long,
)

data class PinMemoryRequest(
    val scope: MemoryScopeKey,
    val content: String,
)

data class UnpinMemoryRequest(
    val scope: MemoryScopeKey,
    val id: String?,
    val keyword: String?,
)

data class DedupPinnedRequest(
    val scope: MemoryScopeKey,
    val includeGlobal: Boolean,
)

sealed interface MemorySearchResult {
    data class Found(val facts: List<FactStore.Fact>) : MemorySearchResult
    data object Empty : MemorySearchResult
}

sealed interface SaveMemoryResult {
    data class Saved(val id: Long) : SaveMemoryResult
    data class Rejected(val reason: String) : SaveMemoryResult
}

sealed interface DeleteMemoryResult {
    data class Deleted(val id: Long) : DeleteMemoryResult
    data object Missing : DeleteMemoryResult
    data class Rejected(val reason: String) : DeleteMemoryResult
}

sealed interface PinMemoryResult {
    data class Pinned(val id: String) : PinMemoryResult
    data class Rejected(val reason: String) : PinMemoryResult
}

sealed interface UnpinMemoryResult {
    data object Removed : UnpinMemoryResult
    data object Missing : UnpinMemoryResult
}

sealed interface DedupPinnedResult {
    data class Removed(val count: Int) : DedupPinnedResult
}

interface MemoryUseCases {
    suspend fun search(request: MemorySearchRequest): MemorySearchResult
    suspend fun save(request: SaveMemoryRequest): SaveMemoryResult
    suspend fun delete(request: DeleteMemoryRequest): DeleteMemoryResult
    suspend fun pin(request: PinMemoryRequest): PinMemoryResult
    suspend fun unpin(request: UnpinMemoryRequest): UnpinMemoryResult
    suspend fun dedupPinned(request: DedupPinnedRequest): DedupPinnedResult
}

class DefaultMemoryUseCases(
    private val factStoreProvider: (assistantId: String) -> FactStore,
    private val pinnedMemoryStore: PinnedMemoryStore,
) : MemoryUseCases {

    override suspend fun search(request: MemorySearchRequest): MemorySearchResult {
        val normalized = normalizeScope(request.scope) ?: return MemorySearchResult.Empty
        val store = factStoreProvider(normalized.assistantId)
        val limit = request.limit.coerceIn(1, 100)
        val facts = if (request.tags.isNotEmpty()) {
            store.searchByTagsScoped(
                queryTags = request.tags,
                scope = normalized.scope,
                spaceId = normalized.spaceId,
                dateRange = request.dateRange,
                limit = limit,
            )
        } else if (request.query.isNullOrBlank()) {
            store.getByScopeAndSpace(
                normalized.scope,
                normalized.spaceId,
                includeSuppressed = request.includeSuppressed,
            ).filterByDateRange(request.dateRange).take(limit)
        } else {
            store.searchFullTextScopedTwoStage(
                query = request.query,
                scope = normalized.scope,
                spaceId = normalized.spaceId,
                limit = limit,
            ).filterByDateRange(request.dateRange)
        }
        val visible = if (request.includeSuppressed) {
            facts
        } else {
            facts.filter { it.suppressedAt == null && it.mergedIntoId == null }
        }.take(limit)
        return if (visible.isEmpty()) MemorySearchResult.Empty else MemorySearchResult.Found(visible)
    }

    override suspend fun save(request: SaveMemoryRequest): SaveMemoryResult {
        val normalized = normalizeScope(request.scope) ?: return SaveMemoryResult.Rejected("invalid scope")
        return try {
            val id = factStoreProvider(normalized.assistantId).add(
                request.fact,
                scope = normalized.scope,
                spaceId = normalized.spaceId,
            )
            SaveMemoryResult.Saved(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SaveMemoryResult.Rejected(error.message ?: "storage error")
        }
    }

    override suspend fun delete(request: DeleteMemoryRequest): DeleteMemoryResult {
        val normalized = normalizeScope(request.scope) ?: return DeleteMemoryResult.Rejected("invalid scope")
        val store = factStoreProvider(normalized.assistantId)
        val fact = store.getById(request.factId) ?: return DeleteMemoryResult.Missing
        if (fact.scope != normalized.scope || fact.spaceId != normalized.spaceId) {
            return DeleteMemoryResult.Rejected("memory belongs to another space")
        }
        val removed = store.delete(request.factId, normalized.assistantId, normalized.scope, normalized.spaceId)
        return if (removed) DeleteMemoryResult.Deleted(request.factId) else DeleteMemoryResult.Missing
    }

    override suspend fun pin(request: PinMemoryRequest): PinMemoryResult {
        val normalized = normalizeScope(request.scope) ?: return PinMemoryResult.Rejected("invalid scope")
        val id = pinnedMemoryStore.add(request.content, assistantId = normalized.assistantId)
        return if (id.isEmpty()) PinMemoryResult.Rejected("failed to pin") else PinMemoryResult.Pinned(id)
    }

    override suspend fun unpin(request: UnpinMemoryRequest): UnpinMemoryResult {
        val normalized = normalizeScope(request.scope) ?: return UnpinMemoryResult.Missing
        val includeGlobal = normalized.assistantId == "default"
        val removed = when {
            !request.id.isNullOrBlank() -> {
                val all = pinnedMemoryStore.getForAssistant(normalized.assistantId, includeGlobal)
                val target = all.firstOrNull { it.id.startsWith(request.id) }
                if (target == null) {
                    false
                } else {
                    pinnedMemoryStore.removeByIdForAssistant(target.id, normalized.assistantId, includeGlobal)
                }
            }
            !request.keyword.isNullOrBlank() ->
                pinnedMemoryStore.removeByContentFlexibleForAssistant(request.keyword, normalized.assistantId, includeGlobal)
            else -> false
        }
        return if (removed) UnpinMemoryResult.Removed else UnpinMemoryResult.Missing
    }

    override suspend fun dedupPinned(request: DedupPinnedRequest): DedupPinnedResult = DedupPinnedResult.Removed(
        pinnedMemoryStore.dedupExactForAssistant(
            assistantId = normalizeScope(request.scope)?.assistantId,
            includeGlobal = request.includeGlobal,
        ),
    )

    private fun normalizeScope(scope: MemoryScopeKey): MemoryScopeKey? = (scope.normalized() as? MemoryScopeResult.Valid)?.value

    private fun List<FactStore.Fact>.filterByDateRange(range: FactStore.DateRange?): List<FactStore.Fact> {
        if (range == null) return this
        return filter { fact ->
            val time = fact.time ?: return@filter false
            (range.from == null || time >= range.from) && (range.to == null || time <= range.to)
        }
    }
}
