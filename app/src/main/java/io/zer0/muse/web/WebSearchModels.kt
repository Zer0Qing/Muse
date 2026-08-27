package io.zer0.muse.web

/** 搜索请求终态。 */
enum class WebSearchStatus {
    RESULTS,
    EMPTY,
    BLOCKED,
    RATE_LIMITED,
    FAILED,
    BUDGET_EXCEEDED,
    DUPLICATE_QUERY,
}

enum class WebSearchAttemptStatus {
    SUCCESS,
    EMPTY,
    BLOCKED,
    RATE_LIMITED,
    FAILED,
}

data class WebSearchRequest(
    val query: String,
    val maxResults: Int = 5,
    val dateRange: String? = null,
)

data class WebSearchAttempt(
    val provider: String,
    val status: WebSearchAttemptStatus,
    val resultCount: Int = 0,
    val elapsedMs: Long = 0L,
    val message: String? = null,
)

data class WebSearchResponse(
    val query: String,
    val normalizedQuery: String,
    val provider: String? = null,
    val results: List<WebSearchResult> = emptyList(),
    val attempts: List<WebSearchAttempt> = emptyList(),
    val status: WebSearchStatus,
)

data class WebSearchPolicy(
    val maxSearchesPerTurn: Int = 5,
    val maxResults: Int = 5,
    val maxConcurrent: Int = 1,
    val stopAfterEmpty: Int = 2,
)
