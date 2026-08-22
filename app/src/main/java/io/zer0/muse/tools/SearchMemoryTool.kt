package io.zer0.muse.tools

import io.zer0.memory.fact.FactStore

/**
 * search_memory tool (既有实现 memory-search.ts port)。
 *
 * 让 LLM 主动检索长期记忆。支持:
 *  - 全文搜索(FTS4 + CJK 2-gram,单字回退 LIKE)
 *  - 标签精确匹配(OR 逻辑,按匹配数降序)
 *  - 日期范围过滤(ISO 8601 字符串,可选)
 */
object SearchMemoryTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "search_memory",
        description = "Search the long-term memory store for facts about the user. " +
            "Use this tool when the user asks something that might depend on their preferences, " +
            "history, identity, goals, or previously discussed topics. " +
            "For casual chat or general knowledge, this tool is not needed.",
        parameters = mapOf(
            "query" to "Required. Keywords to search for in memory facts.",
            "tags" to "Optional. Comma-separated tag names to filter by (OR logic).",
            "from" to "Optional. ISO 8601 date lower bound (inclusive) for the fact's time field.",
            "to" to "Optional. ISO 8601 date upper bound (inclusive) for the fact's time field.",
            "limit" to "Optional. Maximum number of results, default 10.",
        ),
        required = setOf("query"),
        category = "built-in",
        parameterTypes = mapOf("limit" to "integer"),
        riskLevel = ToolRiskLevel.SAFE,
    )

    suspend fun execute(args: Map<String, String>, factStore: FactStore): String =
        executeInternal(args, factStore, null)

    suspend fun execute(
        args: Map<String, String>,
        factStore: FactStore,
        executionContext: ToolExecutionContext,
    ): String = executeInternal(args, factStore, executionContext)

    private suspend fun executeInternal(
        args: Map<String, String>,
        factStore: FactStore,
        executionContext: ToolExecutionContext?,
    ): String {
        val query = args["query"]?.trim().orEmpty()
        if (query.isEmpty()) return "Error: query parameter is required."

        val tags = args["tags"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        val from = args["from"]?.trim()?.takeIf { it.isNotEmpty() }
        val to = args["to"]?.trim()?.takeIf { it.isNotEmpty() }
        val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 10

        val dateRange = if (from != null || to != null) {
            FactStore.DateRange(from = from, to = to)
        } else null
        val facts = if (executionContext != null && tags.isNotEmpty()) {
            factStore.searchByTagsScoped(
                queryTags = tags,
                scope = executionContext.scope,
                spaceId = executionContext.spaceId,
                dateRange = dateRange,
                limit = limit,
            )
        } else if (executionContext != null) {
            factStore.searchFullTextScoped(
                query = query,
                scope = executionContext.scope,
                spaceId = executionContext.spaceId,
                limit = limit,
            )
        } else if (tags.isNotEmpty()) {
            factStore.searchByTags(queryTags = tags, dateRange = dateRange, limit = limit)
        } else {
            factStore.searchFullText(query, limit)
        }

        if (facts.isEmpty()) return "No matching memories found."

        return buildString {
            appendLine("Found ${facts.size} memory fact(s):")
            appendLine()
            facts.forEachIndexed { index, fact ->
                appendLine("${index + 1}. ${fact.fact}")
                if (fact.tags.isNotEmpty()) {
                    appendLine("   Tags: ${fact.tags.joinToString(", ")}")
                }
                if (fact.category.isNotBlank() && fact.category != "general") {
                    appendLine("   Category: ${fact.category}")
                }
                if (fact.confidence < 1.0f) {
                    appendLine("   Confidence: ${fact.confidence}")
                }
                fact.time?.let { appendLine("   Time: $it") }
                appendLine()
            }
        }.trimEnd()
    }
}
