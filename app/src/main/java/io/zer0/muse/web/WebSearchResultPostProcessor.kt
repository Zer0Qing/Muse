package io.zer0.muse.web

import java.util.Locale

/**
 * 联网搜索结果的统一后处理。
 *
 * 原来 [AutoWebSearchService] 的 normalizeResults 只做 URL 级去重与等权 OR 排序，
 * 缺 host 级去重、导航页剔除、约束过滤、时敏软过滤和召回门禁，导致噪声结果
 * 直接进 LLM 上下文。这里把这几步集中成纯函数，便于独立单测，链路只调一次。
 *
 * 顺序：约束/导航页/时敏过滤 → URL 规范化去重 → host 去重 → 加权排序 → 门禁。
 */
object WebSearchResultPostProcessor {

    /** 同域名最多保留条数，避免省政府站点几条变体占满 top5。 */
    private const val MAX_PER_HOST = 2

    /** 导航页判定：路径深度 ≤1 且标题/摘要与主题词零交集。 */
    private val INDEX_FILES = setOf("index.shtml", "index.html", "index.htm", "index.php", "default.aspx")

    private val NAV_DOMAINS = setOf(
        "baike.baidu.com",
        "baike.sogou.com",
        "baike.so.com",
        "huangli.com",
        "laohuangli.net",
        "rili.com.cn",
        "calendar.com",
    )

    private val DICTIONARY_HINTS = listOf("词典", "字典", "汉典", "基本解释", "汉语意思", "释义", "definition of", "dictionary")

    data class Outcome(
        val results: List<WebSearchResult>,
        val recalledCount: Int,
        val afterFilterCount: Int,
        val afterDedupCount: Int,
        val finalCount: Int,
        val recallOk: Boolean,
        val reason: String?,
    )

    fun process(
        input: List<WebSearchResult>,
        plan: WebSearchQueryPlan,
        dateRange: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): Outcome {
        val recalled = input.size
        val earliest = WebSearchDateParser.earliestMillis(dateRange, nowMillis)

        // 1. 约束 / 导航页 / 低质量 / 时敏软过滤。
        val filtered = input.filter { result ->
            val title = result.title
            val snippet = result.snippet
            if (!plan.constraints.matches(result.url, title, snippet)) return@filter false
            if (isNavigationPage(result, plan)) return@filter false
            if (isLowQualityNoise(result, plan)) return@filter false
            // 软过滤：解析到日期且明显超范围才剔除；解析不到日期降权、不剔除。
            val published = WebSearchDateParser.parse("$title $snippet", nowMillis)
            if (earliest != null && published != null && published < earliest) return@filter false
            true
        }
        val afterFilter = filtered.size

        // 2. URL 规范化去重（去 query/fragment、index 后缀、末尾斜杠）。
        val seenUrl = mutableSetOf<String>()
        val urlDeduped = filtered.filter { seenUrl.add(normalizeUrl(it.url)) }
        // 3. host 级去重：同域名最多 MAX_PER_HOST 条。
        val hostCount = mutableMapOf<String, Int>()
        val deduped = urlDeduped.filter { r ->
            val host = hostOf(r.url)
            val n = hostCount.getOrDefault(host, 0)
            if (n >= MAX_PER_HOST) {
                false
            } else {
                hostCount[host] = n + 1
                true
            }
        }
        val afterDedup = deduped.size

        // 4. 加权排序：主题词权重 + 日期新鲜度 + 无日期降权 + 低质量降权。
        val ranked = deduped.sortedByDescending { score(plan, it, earliest, nowMillis) }
        val finalCount = ranked.size

        // 5. 召回门禁：有结果但 top5 全不相关 → 判失败，由调用方返回空+原因。
        val recallOk = when {
            ranked.isEmpty() -> false
            plan.topics.isEmpty() -> true
            else -> ranked.take(5).any { plan.hasTopicHit(it.title, it.snippet) }
        }
        val reason = if (!recallOk && recalled > 0) {
            "召回到了 $recalled 条结果，但没有一条与查询主题相关（已剔除导航页/低质量/超范围结果）"
        } else {
            null
        }

        return Outcome(
            results = ranked,
            recalledCount = recalled,
            afterFilterCount = afterFilter,
            afterDedupCount = afterDedup,
            finalCount = finalCount,
            recallOk = recallOk,
            reason = reason,
        )
    }

    private fun score(plan: WebSearchQueryPlan, r: WebSearchResult, earliest: Long?, nowMillis: Long): Double {
        var s = plan.relevanceScore(r.title, r.snippet)
        if (earliest != null) {
            val published = WebSearchDateParser.parse("${r.title} ${r.snippet}", nowMillis)
            when {
                published == null -> s -= 1.5 // 无法解析日期：降权，不剔除
                published >= earliest -> s += 1.0
            }
        }
        if (DICTIONARY_HINTS.any { (r.title + r.snippet).contains(it, ignoreCase = true) }) s -= 8.0
        return s
    }

    /** path 深度 ≤1 且与主题零交集的站点首页/百科词条，属于导航页而非内容页。 */
    private fun isNavigationPage(r: WebSearchResult, plan: WebSearchQueryPlan): Boolean {
        val host = hostOf(r.url)
        if (NAV_DOMAINS.any { host == it || host.endsWith(".$it") }) return true
        val path = runCatching { java.net.URI(r.url).path?.trim('/') ?: "" }.getOrDefault("")
            .removeSuffix("/").let { p -> INDEX_FILES.fold(p) { acc, f -> acc.removeSuffix(f).trimEnd('/') } }
        if (path.isNotEmpty()) return false
        // path 为空 = 站点首页；此时只有当标题与主题词毫无交集才判为导航页。
        return !plan.hasTopicHit(r.title, r.snippet) || plan.topics.isEmpty()
    }

    /** 与查询明显无关的词典/日期计算器类噪声；保留原有「降权不剔除」思路，这里只在明确无关时剔除。 */
    private fun isLowQualityNoise(r: WebSearchResult, plan: WebSearchQueryPlan): Boolean {
        val hay = (r.title + " " + r.snippet).lowercase(Locale.ROOT)
        val dictHit = DICTIONARY_HINTS.any { hay.contains(it.lowercase(Locale.ROOT)) }
        val dateCalcHit = hay.contains("老黄历") || hay.contains("万年历") || hay.contains("日历网") ||
            hay.contains("date calculator") || hay.contains("days calculator")
        if (!dictHit && !dateCalcHit) return false
        // 用户明确在查词/查日期时不能剔除；其余情况这类页面与主题无关。
        return !plan.hasTopicHit(r.title, r.snippet)
    }

    /** URL 规范化：去 query/fragment、index 类文件名、末尾斜杠，统一小写 host。 */
    internal fun normalizeUrl(raw: String): String = runCatching {
        val uri = java.net.URI(raw)
        val host = (uri.host ?: "").lowercase(Locale.ROOT)
        var path = (uri.path ?: "").trimEnd('/')
        INDEX_FILES.forEach { f -> if (path.endsWith("/$f")) path = path.removeSuffix("/$f") }
        "$host$path".trimEnd('/')
    }.getOrDefault(raw.lowercase(Locale.ROOT).substringBefore('?').trimEnd('/'))

    private fun hostOf(url: String): String = runCatching {
        java.net.URI(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: ""
    }.getOrDefault("")
}
