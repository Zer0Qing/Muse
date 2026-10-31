package io.zer0.muse.web

/**
 * 记忆只读命令(`memory.list` / `memory.search`)的载荷投影。
 *
 * ## 这一批覆盖的记忆作用域
 *
 * 记忆按作用域分几层,这一批只做「会话 / 主记忆池」那一层 —— 也就是记忆页上直接看到的
 * 那份条目列表:
 *  - **会话记忆**:由对话抽取出来的条目(带来源会话),`source` 下发
 *    [HostMemorySourceKind.CONVERSATION];
 *  - **主记忆池**:当前助手作用域下的条目 —— 主助手是 `main`,子助手是自己的 assistantId,
 *    与记忆页、system prompt 注入共用同一套分库路由(见 [FactHostMemorySource])。
 *
 * ## 这一批没有覆盖
 *
 *  - **事实库的整理层**:实体图谱边、矛盾清单、取代(`supersedes`)/ 合并(`merged_into`)链、
 *    Deep 层原子化。这些是"怎么理解记忆"的派生结构,列表只读不展开;
 *  - **跨助手分库汇总**:一次请求只读一个作用域对应的库,"全部作用域"的并集留给下一批;
 *  - **其它记忆来源**:会话滚动摘要(`session_summaries`)、编译产物(`compiled_sections` /
 *    memory.md)、经验库、群聊记忆 —— 它们不在这份列表里。
 *
 * ## 分页口径
 *
 * 条目按 id 倒序下发(自增 id ≈ 入库顺序,越大越新),`cursor` 取上一页最后一条的 id,
 * 下一页只取更小的 id。这样分页不依赖偏移量:新条目插入只会出现在第一页之前,
 * 不会把后面几页挤得重复或跳条。置顶只作为 `pinned` 标记下发、不参与排序 ——
 * 一旦把置顶提到最前,keyset 分页就会在页边界漏条目。
 */
internal object HostMemoryReads {

    const val TYPE_LIST = "memory.list"
    const val TYPE_SEARCH = "memory.search"

    /** 单页条数默认值。 */
    const val DEFAULT_LIMIT = 30

    /** 单页条数上限:再大的页对网页端也没有意义,只会把一条回执推成几百 KB。 */
    const val MAX_LIMIT = 100

    /**
     * 列表里单条正文的字符上限。
     *
     * 列表页只渲染两三行,200 字足够判断"这条记的是什么";一条整理过的事实可能上千字,
     * 整段下发会让一页 30 条的回执膨胀到几百 KB,而网页端并不会把它们全画出来。
     * 要看全文就走 App 内的记忆详情。
     */
    const val CONTENT_LIMIT = 200

    /** 命中片段的字符上限。 */
    const val SNIPPET_LIMIT = 120

    /** 分页结果。 */
    data class MemoryPage(val entries: List<HostMemoryEntry>, val hasMore: Boolean)

    /** `memory.list` 回执。[cursor] 为空表示第一页。 */
    fun listEvent(
        records: List<HostMemoryRecord>,
        cursor: Long?,
        limit: Int,
        requestId: String?,
    ): HostEvent {
        val page = page(records, cursor, limit)
        return HostEvent(
            type = TYPE_LIST,
            requestId = requestId,
            memory = page.entries,
            hasMore = page.hasMore,
        )
    }

    /** `memory.search` 回执:条目结构与列表一致,额外带命中片段。 */
    fun searchEvent(
        query: String,
        records: List<HostMemoryRecord>,
        requestId: String?,
    ): HostEvent = HostEvent(
        type = TYPE_SEARCH,
        requestId = requestId,
        memoryHits = records.map { record ->
            HostMemoryHit(entry = record.toEntry(), snippet = snippetOf(record.content, query))
        },
    )

    /**
     * 取一页。
     *
     * [limit] 先夹到 `1..`[MAX_LIMIT];[cursor] 不在本批条目里(被删了/库被换过)时,
     * 退化成"取比它小的 id",不会因为找不到锚点而回第一页。
     */
    fun page(records: List<HostMemoryRecord>, cursor: Long?, limit: Int): MemoryPage {
        val capped = limit.coerceIn(1, MAX_LIMIT)
        val ordered = records.sortedByDescending { idOf(it.id) }
        val remaining = if (cursor == null) ordered else ordered.filter { idOf(it.id) < cursor }
        return MemoryPage(
            entries = remaining.take(capped).map { it.toEntry() },
            hasMore = remaining.size > capped,
        )
    }

    /** 条目 id 是十进制字符串;解析不了(理论上不会有)按 0 处理,排到最末。 */
    fun idOf(id: String): Long = id.toLongOrNull() ?: 0L

    /** 事实来源 → 协议里的来源类别。 */
    fun sourceKind(sessionId: String?, factSource: String): String = when {
        !sessionId.isNullOrBlank() -> HostMemorySourceKind.CONVERSATION
        factSource == FACT_SOURCE_USER_EXPLICIT -> HostMemorySourceKind.MANUAL
        else -> HostMemorySourceKind.AUTO
    }

    /** 命中位置附近的片段;查询串不在正文里(比如只命中了分词)时取开头一段。 */
    fun snippetOf(content: String, query: String): String {
        val text = content.trim()
        if (text.length <= SNIPPET_LIMIT) return text
        val hit = text.indexOf(query, ignoreCase = true).coerceAtLeast(0)
        val start = (hit - SNIPPET_LIMIT / 3).coerceIn(0, text.length - SNIPPET_LIMIT)
        val end = start + SNIPPET_LIMIT
        // 片段边界不能落在代理对中间,否则序列化出孤立代理字符。
        val from = if (start > 0 && Character.isLowSurrogate(text[start])) start + 1 else start
        val to = if (end < text.length && Character.isHighSurrogate(text[end - 1])) end - 1 else end
        return text.substring(from, to)
    }

    private fun HostMemoryRecord.toEntry(): HostMemoryEntry = HostMemoryEntry(
        id = id,
        content = content.prefixOf(CONTENT_LIMIT),
        createdAt = createdAt,
        source = source,
        pinned = pinned,
    )

    /** 头部截断,不切断代理对。 */
    private fun String.prefixOf(limit: Int): String {
        if (length <= limit) return this
        val end = if (Character.isHighSurrogate(this[limit - 1])) limit - 1 else limit
        return substring(0, end)
    }

    /** 事实表里"用户明确陈述"的来源值。 */
    private const val FACT_SOURCE_USER_EXPLICIT = "user_explicit"
}
