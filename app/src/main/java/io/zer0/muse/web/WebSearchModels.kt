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

    /** 召回到了结果但无一与查询主题相关，按产品取向返回空而非噪声。 */
    RECALL_IRRELEVANT,
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
    /** 调试与可观测信息：实际引擎、构造后 query、各阶段结果数、失败原因。 */
    val debug: WebSearchDebug? = null,
)

/**
 * 一次搜索的可观测信息。日志层早有 provider/attempts，但响应层只显示「Auto」，
 * 线上定位问题只能靠用户反馈；这里把关键链路信息随结果带出。
 */
data class WebSearchDebug(
    val outboundQuery: String,
    val effectiveProvider: String?,
    val appliedDateRange: String? = null,
    val inferredDateRange: Boolean = false,
    val constraints: WebSearchConstraints = WebSearchConstraints(),
    val unsupportedOperators: List<String> = emptyList(),
    val recalledCount: Int = 0,
    val afterFilterCount: Int = 0,
    val afterDedupCount: Int = 0,
    val finalCount: Int = 0,
    /** 召回失败/为空的具体原因，交给上层如实转述，不凭记忆编造。 */
    val reason: String? = null,
)

data class WebSearchPolicy(
    val maxSearchesPerTurn: Int = 5,
    val maxResults: Int = 5,
    val maxConcurrent: Int = 1,
    val stopAfterEmpty: Int = 2,
)
