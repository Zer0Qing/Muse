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
 * 两轮匹配：先按整词，一无所获时再按 [relaxedTerms] 兜一次。
 * 中文不打空格是常态 —— 一整句连写会被当成一个词去 contains，
 * 措辞稍有不同就什么都捞不到，用户以为"它不记得了"，其实摘要里就写着。
 * 兜底只在整词搜不到时启用，不会把已有的精确命中面搅浑。
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
    val exact = if (terms.isEmpty()) emptyList() else scanWith(sections, terms, limit)
    val relaxed = terms.flatMap { term -> relaxedTerms(term) }.distinct()
    return when {
        exact.isNotEmpty() -> exact
        terms.isEmpty() || relaxed == terms -> emptyList()
        else -> scanWith(sections, relaxed, limit)
    }
}

/**
 * 长串中文的兜底切分：相邻两字组成的词。
 *
 * 只处理"够长的中文连写"：英文本来就被空格切开；两三个字也不切
 * （"预算""同步"本身就是完整词，切成单字会到处乱匹配）。
 */
private fun relaxedTerms(term: String): List<String> {
    val chars = term.toList()
    if (chars.size < MIN_RELAXED_TERM_CHARS) return listOf(term)
    if (chars.none { ch -> ch in CJK_RANGE }) return listOf(term)
    return chars.windowed(GRAM_SIZE) { gram -> gram.joinToString("") }.distinct()
}

private fun scanWith(
    sections: List<Pair<String, String>>,
    terms: List<String>,
    limit: Int,
): List<CompiledSectionHit> =
    sections.asSequence()
        .filter { (_, content) -> content.isNotBlank() }
        .flatMap { (sectionKey, content) -> content.lineSequence().map { line -> sectionKey to line } }
        .map { (sectionKey, rawLine) -> sectionKey to rawLine.trim().removePrefix("-").trim() }
        .filter { (_, line) -> line.isNotEmpty() }
        .distinctBy { (_, line) -> line }
        .mapNotNull { (sectionKey, line) ->
            val matched = terms.count { term -> line.contains(term) }
            if (matched == 0) null else CompiledSectionHit(sectionKey, line.take(MAX_COMPILED_HIT_CHARS)) to matched
        }
        .toList()
        .sortedByDescending { (_, matched) -> matched }
        .map { (hit, _) -> hit }
        .take(limit)

/** 参与兜底切分的最短查询词，与 [relaxedTerms] 的注释一起看。 */
private const val MIN_RELAXED_TERM_CHARS: Int = 4

/** 兜底切分的窗口：两字，正好是中文最常见的词长。 */
private const val GRAM_SIZE: Int = 2

/** 基本汉字区，用来判断这个查询词要不要做二次切分。 */
private val CJK_RANGE: CharRange = '\u4e00'..'\u9fff'
