package io.zer0.muse.web

import io.zer0.common.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 联网搜索唯一编排入口：限制同一工具轮的预算、重复 query 和连续空结果。
 * provider 自己只负责请求；这里负责用户可感知的搜索策略。
 */
class WebSearchCoordinator(
    private val service: WebSearchService,
) {
    private data class TurnState(
        var used: Int = 0,
        var empty: Int = 0,
        val queries: MutableSet<String> = mutableSetOf(),
    )

    private val mutex = Mutex()
    private val turns = mutableMapOf<String, TurnState>()
    private val _lastResponse = MutableStateFlow<WebSearchResponse?>(null)
    val lastResponse: StateFlow<WebSearchResponse?> = _lastResponse.asStateFlow()

    suspend fun search(
        request: WebSearchRequest,
        turnKey: String,
        policy: WebSearchPolicy = WebSearchPolicy(),
    ): WebSearchResponse {
        val normalized = WebSearchQueryNormalizer.normalize(request.query)
        if (normalized.isBlank()) {
            return WebSearchResponse(request.query, normalized, status = WebSearchStatus.EMPTY)
        }
        val state = mutex.withLock { turns.getOrPut(turnKey) { TurnState() } }
        val normalizedKey = normalized.lowercase()
        val gate = mutex.withLock {
            when {
                state.used >= policy.maxSearchesPerTurn -> WebSearchStatus.BUDGET_EXCEEDED
                normalizedKey in state.queries -> WebSearchStatus.DUPLICATE_QUERY
                state.empty >= policy.stopAfterEmpty -> WebSearchStatus.EMPTY
                else -> {
                    state.used++
                    state.queries += normalizedKey
                    null
                }
            }
        }
        if (gate != null) {
            Logger.w("WebSearch", "search blocked: turn=$turnKey status=$gate query=$normalized")
            return WebSearchResponse(request.query, normalized, status = gate).also { _lastResponse.value = it }
        }

        val started = System.currentTimeMillis()
        return try {
            val results = service.searchWithOptions(normalized, request.maxResults, mapOfNotNull(request.dateRange?.let { "date_range" to it }))
            val status = if (results.isEmpty()) WebSearchStatus.EMPTY else WebSearchStatus.RESULTS
            mutex.withLock { if (results.isEmpty()) state.empty++ else state.empty = 0 }
            WebSearchResponse(
                request.query, normalized, service.name, results,
                listOf(WebSearchAttempt(service.name, if (results.isEmpty()) WebSearchAttemptStatus.EMPTY else WebSearchAttemptStatus.SUCCESS, results.size, System.currentTimeMillis() - started)),
                status,
            ).also {
                Logger.i(
                    "WebSearch",
                    "outcome provider=${service.name}, status=${it.status}, results=${it.results.size}, " +
                        "elapsedMs=${it.attempts.firstOrNull()?.elapsedMs ?: 0}, query=${normalized.take(80)}",
                )
                _lastResponse.value = it
            }
        } catch (e: Exception) {
            val status = if (e is SearchRateLimitException) WebSearchStatus.RATE_LIMITED else WebSearchStatus.FAILED
            mutex.withLock { state.empty++ }
            WebSearchResponse(request.query, normalized, service.name, emptyList(), listOf(WebSearchAttempt(service.name, if (e is SearchRateLimitException) WebSearchAttemptStatus.RATE_LIMITED else WebSearchAttemptStatus.FAILED, 0, System.currentTimeMillis() - started, e.message)), status).also {
                Logger.w(
                    "WebSearch",
                    "outcome provider=${service.name}, status=${it.status}, elapsedMs=${it.attempts.firstOrNull()?.elapsedMs ?: 0}, " +
                        "query=${normalized.take(80)}, error=${e.message?.take(160)}",
                )
                _lastResponse.value = it
            }
        }
    }

    suspend fun clear(turnKey: String) {
        mutex.withLock { turns.remove(turnKey) }
    }
}

private fun <K, V> mapOfNotNull(pair: Pair<K, V>?): Map<K, V> = if (pair == null) emptyMap() else mapOf(pair)
