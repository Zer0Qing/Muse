package io.zer0.memory.compile

/**
 * 编译摘要里命中的一行。
 *
 * @param sectionKey 命中所在的段(facts / today / week / longterm)
 * @param text 命中行的原文(已去掉首尾空白,过长时截断)
 */
data class CompiledSectionHit(
    val sectionKey: String,
    val text: String,
)

/** 单条命中最多带多少字:一段散文命中不该把整段搬进工具结果。 */
internal const val MAX_COMPILED_HIT_CHARS = 200

/**
 * 把查询串拆成用于子串扫描的词。
 *
 * 只按“字母/数字连续段”切,不做分词:拆出来的词直接拿去 contains,
 * 切得比检索词更碎反而会放大误命中。重复词只留一个。
 */
internal fun compiledSearchTerms(query: String): List<String> {
    if (query.isBlank()) return emptyList()
    val terms = mutableListOf<String>()
    val buffer = StringBuilder()
    for (ch in query) {
        if (ch.isLetterOrDigit()) {
            buffer.append(ch)
        } else if (buffer.isNotEmpty()) {
            terms.add(buffer.toString())
            buffer.clear()
        }
    }
    if (buffer.isNotEmpty()) terms.add(buffer.toString())
    return terms.distinct()
}

/**
 * 在已编译摘要的分段文本里做子串扫描。
 *
 * 一行包含任一查询词即算候选,按命中词数降序排列(同数保持原文顺序),
 * 同一行文字只出现一次。这是 facts 表之外的补位:摘要段是 LLM 归纳出的散文,
 * 原文常常根本不在 facts 里,只能靠文本比对把它们捞回来。
 *
 * @param sections 段 key 与段全文,按注入顺序给
 * @param query 用户查询串
 * @param limit 最多返回几条
 */
internal fun scanCompiledSections(
    sections: List<Pair<String, String>>,
    query: String,
    limit: Int,
): List<CompiledSectionHit> {
    if (limit <= 0) return emptyList()
    val terms = compiledSearchTerms(query)
    if (terms.isEmpty()) return emptyList()
    return sections.asSequence()
        .filter { (_, content) -> content.isNotBlank() }
        .flatMap { (sectionKey, content) -> content.lineSequence().map { sectionKey to it } }
        .map { (sectionKey, rawLine) -> sectionKey to rawLine.trim().removePrefix("-").trim() }
        .filter { (_, line) -> line.isNotEmpty() }
        .distinctBy { (_, line) -> line }
        .mapNotNull { (sectionKey, line) ->
            val matched = terms.count { line.contains(it) }
            if (matched == 0) null else CompiledSectionHit(sectionKey, line.take(MAX_COMPILED_HIT_CHARS)) to matched
        }
        .toList()
        .sortedByDescending { it.second }
        .map { it.first }
        .take(limit)
}
