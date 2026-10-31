package io.zer0.muse.tools

import io.zer0.memory.compile.CompiledSectionHit
import io.zer0.memory.fact.FactStore

/**
 * search_memory 工具。
 *
 * 让 LLM 主动检索长期记忆。支持:
 *  - 全文搜索(FTS4 + CJK 2-gram,两段式:精确优先 + 任一命中兜底)
 *  - 标签精确匹配(OR 逻辑,按匹配数降序)
 *  - 日期范围过滤(ISO 8601 字符串,可选)
 *  - 事实表查不到时回落到编译摘要(注入提示词的那几段散文)
 */
object SearchMemoryTool {

    /**
     * 编译摘要检索口。
     *
     * facts 表只装了结构化事实,而注入提示词的 TODAY/WEEK/LONGTERM 段是 LLM 归纳的散文,
     * 原文常常根本不在 facts 里。它们不能当作“编排好的记忆”用,但确实“用户看得见”,
     * 所以只有 facts 检索全空时才回落过去,并在结果里把来源标清楚。
     */
    fun interface CompiledSummarySearch {
        /**
         * @param query 用户查询串
         * @param scope 记忆作用域
         * @param spaceId 记忆空间
         * @param limit 最多返回几条
         */
        suspend fun search(query: String, scope: String, spaceId: String, limit: Int): List<CompiledSectionHit>
    }

    fun toolDef() = ToolRegistry.ToolDef(
        name = "search_memory",
        description = "在用户长期记忆里搜索关于他的事实。当答案可能取决于他的偏好、经历、身份、目标" +
            "或早前对话时使用。多条关键词用空格分开,会先按“全部命中”再按“任一命中”两轮检索;" +
            "每条结果都带 fact id,可直接传给 delete_memory。" +
            "部分早期记忆可能已按使用频率退出自动召回(不再自动注入上下文),显式检索仍能找回它们;" +
            "所以搜不到时不要直接断定记忆里没有。",
        parameters = mapOf(
            "query" to "Required. Keywords to search for in memory facts.",
            "tags" to "Optional. Comma-separated tag names (OR logic).",
            "from" to "Optional. ISO 8601 lower bound on the fact's time, inclusive.",
            "to" to "Optional. ISO 8601 upper bound on the fact's time, inclusive.",
            "limit" to "Optional. Max results, default 10.",
        ),
        required = setOf("query"),
        category = "built-in",
        parameterTypes = mapOf("limit" to "integer"),
        riskLevel = ToolRiskLevel.SAFE,
    )

    suspend fun execute(args: Map<String, String>, factStore: FactStore): String =
        executeInternal(args, factStore, null, null)

    suspend fun execute(args: Map<String, String>, factStore: FactStore, executionContext: ToolExecutionContext): String =
        executeInternal(args, factStore, executionContext, null)

    /**
     * 带编译摘要回落的执行入口。
     *
     * @param compiledSearch 事实表全空时的摘要检索口;为 null 时不做回落
     */
    suspend fun execute(
        args: Map<String, String>,
        factStore: FactStore,
        executionContext: ToolExecutionContext,
        compiledSearch: CompiledSummarySearch?,
    ): String = executeInternal(args, factStore, executionContext, compiledSearch)

    private suspend fun executeInternal(
        args: Map<String, String>,
        factStore: FactStore,
        executionContext: ToolExecutionContext?,
        compiledSearch: CompiledSummarySearch?,
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
        } else {
            null
        }
        val facts = searchFacts(factStore, query, tags, dateRange, limit, executionContext)

        if (facts.isEmpty()) {
            val hits = searchCompiledSummary(query, limit, executionContext, compiledSearch)
            return if (hits.isEmpty()) "No matching memories found." else renderCompiledHits(hits)
        }

        return buildString {
            appendLine("Found ${facts.size} memory fact(s):")
            appendLine()
            facts.forEachIndexed { index, fact ->
                appendLine("${index + 1}. ${fact.fact}")
                appendLine("   ID: ${fact.id}")
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

    /** 按有无执行上下文 / 标签分别走标签检索或两段式全文检索。 */
    private suspend fun searchFacts(
        factStore: FactStore,
        query: String,
        tags: List<String>,
        dateRange: FactStore.DateRange?,
        limit: Int,
        executionContext: ToolExecutionContext?,
    ): List<FactStore.Fact> = when {
        executionContext != null && tags.isNotEmpty() -> factStore.searchByTagsScoped(
            queryTags = tags,
            scope = executionContext.scope,
            spaceId = executionContext.spaceId,
            dateRange = dateRange,
            limit = limit,
        )
        executionContext != null -> factStore.searchFullTextScopedTwoStage(
            query = query,
            scope = executionContext.scope,
            spaceId = executionContext.spaceId,
            limit = limit,
        )
        tags.isNotEmpty() -> factStore.searchByTags(queryTags = tags, dateRange = dateRange, limit = limit)
        else -> factStore.searchFullTextTwoStage(query, limit)
    }

    /**
     * 事实表全空时查编译摘要。
     *
     * 没有执行上下文就没有作用域,不猜默认值:那种调用路径宁可不回落,
     * 也不要跨助手把别人的摘要读出来。
     */
    private suspend fun searchCompiledSummary(
        query: String,
        limit: Int,
        executionContext: ToolExecutionContext?,
        compiledSearch: CompiledSummarySearch?,
    ): List<CompiledSectionHit> {
        if (compiledSearch == null || executionContext == null) return emptyList()
        val hits = compiledSearch.search(query, executionContext.scope, executionContext.spaceId, limit)
        return hits.take(limit)
    }

    /** 摘要命中的渲染:逐条标出“编译摘要”与所在段,与事实行明确区分。 */
    private fun renderCompiledHits(hits: List<CompiledSectionHit>): String = buildString {
        appendLine("事实记忆里没有匹配项;以下内容摘自编译摘要(来源标注为“编译摘要·段名”,不是 facts 表里的条目,没有 fact id):")
        appendLine()
        hits.forEachIndexed { index, hit ->
            appendLine("${index + 1}. [编译摘要·${hit.sectionKey}] ${hit.text}")
            appendLine()
        }
    }.trimEnd()
}
