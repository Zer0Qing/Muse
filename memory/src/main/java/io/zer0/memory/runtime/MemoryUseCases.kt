package io.zer0.memory.runtime

import io.zer0.common.Logger
import io.zer0.memory.fact.FactStore
import io.zer0.memory.pin.PinnedMemoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

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

/**
 * 单条事实的置顶切换结果。
 *
 * 与 [PinMemoryResult] 是两件事:[PinMemoryResult] 是"一段用户写的文字"的独立置顶,
 * 本类型是"某条已存事实"的手动置顶 —— 后者的唯一事实源是 `facts.pinned_at`。
 */
sealed interface FactPinResult {
    /** 切换已生效,[pinned] 是切换后的状态。 */
    data class Changed(val pinned: Boolean) : FactPinResult

    /** 该事实不存在，或已不在调用方给定的 scope/space 内。 */
    data object Missing : FactPinResult

    /** 请求被拒(作用域非法、跨 Space、存储错误)。 */
    data class Rejected(val reason: String) : FactPinResult
}

interface MemoryUseCases {
    suspend fun search(request: MemorySearchRequest): MemorySearchResult
    suspend fun save(request: SaveMemoryRequest): SaveMemoryResult
    suspend fun delete(request: DeleteMemoryRequest): DeleteMemoryResult
    suspend fun pin(request: PinMemoryRequest): PinMemoryResult
    suspend fun unpin(request: UnpinMemoryRequest): UnpinMemoryResult
    suspend fun dedupPinned(request: DedupPinnedRequest): DedupPinnedResult

    /**
     * 切换一条事实的手动置顶。
     *
     * 唯一事实源是 `facts.pinned_at`:不往固定记忆文件里写副本,
     * 否则两份存储之间一旦半步失败就会出现"取消不掉、删不掉"的幽灵条目。
     * 取消置顶时会精确清理旧版本写进固定记忆文件的残留。
     */
    suspend fun toggleFactPinned(scope: MemoryScopeKey, factId: Long): FactPinResult
}

class DefaultMemoryUseCases(
    private val factStoreProvider: (assistantId: String) -> FactStore,
    private val pinnedMemoryStore: PinnedMemoryStore,
) : MemoryUseCases {

    /**
     * 同一 (assistantId, scope, spaceId) 的读写串行化。
     *
     * 置顶/删除碰的是两条不同存储(事实库与固定记忆文件),没有跨库事务可用;
     * 同一个键上的"切换置顶"与"删除"若并发,可能重新创建刚被清掉的残留条目。
     * 锁按作用域分桶而不是全局一把 —— 不同 Space 的操作互不排队。
     */
    private val scopeLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(scope: MemoryScopeKey): Mutex = scopeLocks.computeIfAbsent(
        "${scope.assistantId}\u0000${scope.scope}\u0000${scope.spaceId}",
    ) { Mutex() }

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
        return lockFor(normalized).withLock {
            val fact = store.getById(request.factId) ?: return@withLock DeleteMemoryResult.Missing
            if (fact.scope != normalized.scope || fact.spaceId != normalized.spaceId) {
                return@withLock DeleteMemoryResult.Rejected("memory belongs to another space")
            }
            val removed = try {
                store.delete(request.factId, normalized.assistantId, normalized.scope, normalized.spaceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return@withLock DeleteMemoryResult.Rejected(error.message ?: "storage error")
            }
            if (!removed) return@withLock DeleteMemoryResult.Missing
            // 删掉的若是一条旧路径置顶,固定记忆文件里的副本会变成“删了还在 prompt 里”的幽灵。
            // 只在它确实带置顶标记时清理,避免无条件按内容匹配。
            if (fact.pinnedAt != null) clearLegacyPinnedResidue(fact.fact, normalized.assistantId)
            DeleteMemoryResult.Deleted(request.factId)
        }
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

    /**
     * 切换事实置顶。
     *
     * 写入顺序是先事实行、后文件残留清理:事实表是唯一事实源,
     * 文件只是旧版本留下的副本。反过来(先清文件再改行)一旦后者失败,
     * 就会变成"置顶标记还在但固定记忆段已消失"。而前一种顺序里,
     * 即使清理失败也只是多一条旧副本 —— 可见性仍由事实行决定。
     */
    override suspend fun toggleFactPinned(scope: MemoryScopeKey, factId: Long): FactPinResult {
        val normalized = normalizeScope(scope) ?: return FactPinResult.Rejected("invalid scope")
        val store = factStoreProvider(normalized.assistantId)
        return lockFor(normalized).withLock {
            val fact = store.getById(factId) ?: return@withLock FactPinResult.Missing
            // 裸 id 在分库/多 Space 下会撞号:行不归这个键管就不改,而不是改错行。
            if (fact.scope != normalized.scope || fact.spaceId != normalized.spaceId) {
                return@withLock FactPinResult.Rejected("memory belongs to another space")
            }
            val target = fact.pinnedAt == null
            val changed = try {
                store.setPinned(factId, target, normalized.scope, normalized.spaceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return@withLock FactPinResult.Rejected(error.message ?: "storage error")
            }
            if (!changed) return@withLock FactPinResult.Missing
            if (!target) clearLegacyPinnedResidue(fact.fact, normalized.assistantId)
            FactPinResult.Changed(target)
        }
    }

    /**
     * 清掉旧版本写进固定记忆文件的置顶副本。
     *
     * 老条目没有事实 id,只能靠原文认回;用**精确内容**匹配 —— 包含匹配会连带删掉
     * 内容里含着同一段文字的其它独立置顶。
     * 失败只记日志、不回滚事实行:回滚会把刚成功的置顶操作变成假失败。
     */
    private suspend fun clearLegacyPinnedResidue(content: String, assistantId: String) {
        try {
            pinnedMemoryStore.removeByContentExactForAssistant(
                content = content,
                assistantId = assistantId,
                includeGlobal = assistantId == DEFAULT_ASSISTANT_ID,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Logger.w(TAG, "legacy pinned residue cleanup failed: ${error.message}", error)
        }
    }

    private fun normalizeScope(scope: MemoryScopeKey): MemoryScopeKey? = (scope.normalized() as? MemoryScopeResult.Valid)?.value

    private companion object {
        private const val TAG = "MemoryUseCases"

        /** 主助手的 assistantId。旧版置顶写入时把主助手写成显式 id,只有真的全局条目才是 null。 */
        private const val DEFAULT_ASSISTANT_ID = "default"
    }

    private fun List<FactStore.Fact>.filterByDateRange(range: FactStore.DateRange?): List<FactStore.Fact> {
        if (range == null) return this
        return filter { fact ->
            val time = fact.time ?: return@filter false
            (range.from == null || time >= range.from) && (range.to == null || time <= range.to)
        }
    }
}
