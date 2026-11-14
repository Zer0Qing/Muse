package io.zer0.muse.web

import java.util.Locale

/**
 * 联网搜索查询分析。
 *
 * 职责边界（重要）：本对象**不改写发给搜索引擎的 query**。[analyze] 产出的
 * [WebSearchQueryPlan.outboundQuery] 仍是与用户输入等价的整句（保留 site 约束，
 * 剥离不稳定的 filetype/intitle 及未知操作符），原样交给各 provider。原因是
 * Tavily / Exa / Perplexity 这类语义引擎以整句为输入，
 * 把自然语言压成词袋会让召回变差；历史上也从未做过「只取第一个词」的改写。
 *
 * 分析产物只服务检索**之后**的环节：
 * - [WebSearchQueryPlan.topics] 及其权重 → 结果排序、召回质量门禁；
 * - [WebSearchQueryPlan.constraints] → 结果层域名/文件类型/标题过滤；
 * - [WebSearchQueryPlan.effectiveDateRange] → 时敏参数与结果级软过滤。
 *
 * 中文不做分词：引入分词库会把「专升本」这类专名切碎，误删风险高于收益。
 * 主题词按空白/标点切成连续片段后**整段保留**，命中判定用子串包含——
 * 「四川省人民政府」不含「四川专升本」，正好能把门户首页挡在门禁外。
 */
object WebSearchQueryAnalyzer {

    /** 英文虚词：出现在 query 里但不该作为主题词参与结果相关性判定。 */
    private val EN_STOP_WORDS = setOf(
        "a", "an", "the", "and", "or", "of", "to", "in", "on", "at", "for", "with", "by", "from",
        "is", "are", "was", "were", "be", "been", "being", "do", "does", "did", "done",
        "have", "has", "had", "what", "which", "who", "whom", "whose", "when", "where", "why", "how",
        "this", "that", "these", "those", "it", "its", "i", "you", "he", "she", "they", "we",
        "can", "could", "would", "should", "will", "shall", "may", "might", "must",
        "please", "tell", "me", "about", "most", "more", "some", "any", "all",
        "there", "here", "also", "just", "than", "into", "out", "up", "down",
    )

    /**
     * 中文虚词/提问套话：整词从 query 中剔除后再看剩下什么。
     * 时效词（今天/最近…）不在这里丢弃——它们仍留在 outboundQuery 里给语义引擎，
     * 只额外产生 [inferredDateRange] 信号。
     */
    private val ZH_STOP_WORDS = listOf(
        "请问", "请帮我", "帮我", "麻烦", "麻烦你", "能不能", "可不可以", "我想知道", "我想了解",
        "你知道", "查一下", "查查", "搜索一下", "搜一下", "有没有", "有哪些", "什么是", "怎么样",
        "有什么", "值得", "关注", "哪些", "大概", "多少", "怎么", "如何", "为什么", "是不是",
        "谢谢", "谢谢你", "多谢", "请问一下",
    )

    /**
     * 中文里语义很弱、单拎出来会污染主题词的词。
     * 注意「新闻/消息/资讯/政策/时间」是**有效内容词**，不能列进来，
     * 否则「今天国内科技新闻」这类查询会被削到只剩空泛虚词。
     */
    private val ZH_WEAK_WORDS = setOf(
        "今天", "今日", "昨天", "明日", "最近", "近期", "最新", "现在", "当前", "目前",
        "新消息", "相关", "一下", "一些", "方面", "什么", "大概",
        "吗", "呢", "吧", "啊", "呀", "的", "了", "和", "与", "及", "或", "在", "有", "是",
    )

    /** 时效词 → date_range。顺序重要：长词先匹配，避免「最近」抢掉「最近一周」。 */
    private val TIME_SIGNALS: List<Pair<List<String>, String>> = listOf(
        listOf("past_day", "今天", "今日", "昨日", "昨天", "24小时", "today", "past day") to "past_day",
        listOf(
            "past_week", "最近一周", "这一周", "本周", "近一周", "这一礼拜",
            "recently", "latest", "last week", "this week", "past week", "最近", "近期", "最新",
        ) to "past_week",
        listOf("past_month", "本月", "这个月", "近一个月", "this month", "past month", "last month") to "past_month",
        listOf("past_year", "今年", "本年度", "近一年", "this year", "past year", "last year") to "past_year",
    )

    private val VALID_DATE_RANGES = setOf("past_day", "past_week", "past_month", "past_year")

    /** 单独一个 token 形如 key:value 才算操作符；含 :// 的是 URL，直接跳过。 */
    private val OPERATOR_TOKEN = Regex("^([a-zA-Z]+):(\\S+)$")

    /** 词权重：数字/ASCII 专名 > 长词 > 通用短词。近似 IDF，仅用于排序相对次序。 */
    private fun weightOf(term: String): Double {
        val base = when {
            term.any { it.isDigit() } -> 2.0
            term.all { it.code < 128 } && term.length >= 4 -> 1.8
            term.length >= 4 -> 1.6
            term.length >= 2 -> 1.2
            else -> 1.0
        }
        // 同一查询里重复出现的词略提高权重，但设上限避免虚词刷分。
        return base
    }

    fun analyze(raw: String, explicitDateRange: String? = null): WebSearchQueryPlan {
        val original = raw.trim()
        if (original.isBlank()) {
            return WebSearchQueryPlan(
                originalQuery = original,
                outboundQuery = "",
                topics = emptyList(),
                constraints = WebSearchConstraints(),
                inferredDateRange = null,
                effectiveDateRange = explicitDateRange?.takeIf { it in VALID_DATE_RANGES },
                unsupportedOperators = emptyList(),
            )
        }

        // 1. 操作符预提取：按 token 解析；site 继续发给上游，其余操作符只在结果层执行。
        // 按 token 而不是整串正则，URL 里的 "a:b"、scheme 后的 ":" 才不会被误判成操作符。
        val domains = mutableListOf<String>()
        var filetype: String? = null
        val titleContains = mutableListOf<String>()
        val unsupported = mutableListOf<String>()
        val keptTokens = mutableListOf<String>()
        original.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { token ->
            if (token.contains("://")) {
                keptTokens += token // URL 不是操作符
                return@forEach
            }
            val match = OPERATOR_TOKEN.matchEntire(token) ?: run {
                keptTokens += token
                return@forEach
            }
            val key = match.groupValues[1].lowercase(Locale.ROOT)
            val value = match.groupValues[2].trim().trimEnd(',', '，', ';', '；')
            when (key) {
                "site" -> if (value.isNotBlank()) {
                    domains += value.lowercase(Locale.ROOT)
                    // 保留 site 约束给上游引擎，同时由结果层再次校验域名。
                    keptTokens += "site:$value"
                }
                "filetype" -> if (value.isNotBlank()) filetype = value.lowercase(Locale.ROOT)
                "intitle" -> if (value.isNotBlank()) titleContains += value
                else -> {
                    // 未知操作符：剥离 key 避免污染检索，但保留 value 作为查询词。
                    unsupported += key
                    if (value.isNotBlank()) keptTokens += value
                }
            }
        }
        val stripped = keptTokens.joinToString(" ").trim()
        // site 约束要发给上游，但不是内容主题；否则域名 token 会稀释正文词的召回权重。
        val topicSource = keptTokens
            .filterNot { it.startsWith("site:", ignoreCase = true) }
            .joinToString(" ")

        // 2. 时效信号：只推断，不删词。
        val lower = original.lowercase(Locale.ROOT)
        val inferred = TIME_SIGNALS.firstOrNull { (words, _) -> words.any { lower.contains(it.lowercase(Locale.ROOT)) } }?.second
        val effective = explicitDateRange?.takeIf { it in VALID_DATE_RANGES } ?: inferred

        // 3. 主题词：剔除提问套话与多字弱词后按空白/标点切成连续片段，整段保留不分词。
        // 只剥离多字弱词；单字虚词（的/了/和…）若 replace 会把中文连续串切碎，
        // 它们只在下方按「候选词整词相等」过滤。
        val fillers = ZH_STOP_WORDS + ZH_WEAK_WORDS.filter { it.length >= 2 }
        val zhCleaned = fillers.fold(topicSource) { acc, word -> acc.replace(word, " ") }
        val candidates = zhCleaned
            .split(Regex("[\\s,，。、;；:：!！?？()（）\\[\\]\"'“”‘’/]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val topics = candidates
            .filter { term ->
                val lowTerm = term.lowercase(Locale.ROOT)
                term.length > 1 &&
                    lowTerm !in EN_STOP_WORDS &&
                    term !in ZH_WEAK_WORDS &&
                    term !in ZH_STOP_WORDS
            }
            .map { WeightedTopic(text = it, weight = weightOf(it)) }
            .distinctBy { it.text.lowercase(Locale.ROOT) }

        return WebSearchQueryPlan(
            originalQuery = original,
            outboundQuery = stripped,
            topics = topics,
            constraints = WebSearchConstraints(
                domains = domains.distinct(),
                filetype = filetype,
                titleContains = titleContains.distinct(),
            ),
            inferredDateRange = inferred,
            effectiveDateRange = effective,
            unsupportedOperators = unsupported.distinct(),
        )
    }
}

/** 主题词及其近似权重。 */
data class WeightedTopic(val text: String, val weight: Double)

/** 从 query 预提取出的检索约束，在结果层执行，不依赖上游引擎支持。 */
data class WebSearchConstraints(
    val domains: List<String> = emptyList(),
    val filetype: String? = null,
    val titleContains: List<String> = emptyList(),
) {
    fun isEmpty(): Boolean = domains.isEmpty() && filetype == null && titleContains.isEmpty()

    /** 结果是否满足全部约束；域名用后缀匹配以兼容 www./子域。 */
    fun matches(url: String, title: String, snippet: String): Boolean {
        if (!isEmpty() && domains.isNotEmpty()) {
            val host = runCatching { java.net.URI(url).host?.lowercase(Locale.ROOT) ?: "" }.getOrDefault("")
            if (domains.none { host == it || host.endsWith(".$it") }) return false
        }
        filetype?.let { ext ->
            val hay = (url + " " + title + " " + snippet).lowercase(Locale.ROOT)
            if (!hay.contains(".$ext") && !hay.contains(ext)) return false
        }
        titleContains.forEach { needle ->
            if (!title.contains(needle, ignoreCase = true)) return false
        }
        return true
    }
}

/**
 * 查询分析产物。
 *
 * @param outboundQuery 发给上游引擎的整句（保留 site，剥离不稳定操作符）；**不要**用 topics 拼装替换它。
 * @param effectiveDateRange 显式传入优先，否则用时效词推断值。
 */
data class WebSearchQueryPlan(
    val originalQuery: String,
    val outboundQuery: String,
    val topics: List<WeightedTopic>,
    val constraints: WebSearchConstraints,
    val inferredDateRange: String?,
    val effectiveDateRange: String?,
    val unsupportedOperators: List<String>,
) {
    /** 命中主题词的权重之和 / 总权重。 */
    private fun hitRatio(title: String, snippet: String): Double {
        if (topics.isEmpty()) return 1.0
        val lowerTitle = title.lowercase(Locale.ROOT)
        val lowerSnippet = snippet.lowercase(Locale.ROOT)
        val total = topics.sumOf { it.weight }
        val hit = topics.sumOf { topic ->
            topic.weight * topicHitScore(topic.text.lowercase(Locale.ROOT), lowerTitle, lowerSnippet)
        }
        return if (total <= 0.0) 1.0 else hit / total
    }

    /**
     * 单个主题词的命中程度（0..1）。
     * 中文连续串若要求完整子串匹配会太严（「无日期科技新闻」不含「国内科技新闻」），
     * 因此长中文词改用 2-gram 共现率：命中一半以上片断才算相关。
     * 这样「科技新闻」能命中，「四川省人民政府」只共享「四川」仍被挡在门禁外。
     */
    private fun topicHitScore(term: String, lowerTitle: String, lowerSnippet: String): Double {
        if (lowerTitle.contains(term) || lowerSnippet.contains(term)) return 1.0
        val grams = cjkBigrams(term)
        if (grams.isEmpty()) return 0.0
        val hay = "$lowerTitle $lowerSnippet"
        val ratio = grams.count { hay.contains(it) }.toDouble() / grams.size
        return if (ratio >= 0.5) ratio else 0.0
    }

    private fun cjkBigrams(s: String): List<String> {
        if (s.length < 3 || !s.any { it in '\u3400'..'\u9fff' }) return emptyList()
        return (0 until s.length - 1).map { s.substring(it, it + 2) }.distinct()
    }

    /**
     * 结果是否与查询相关，用于召回质量门禁。
     * 用加权命中比例而非「任一词命中」：只命中一个通用动词（如词典页的 install）
     * 不足以通过，避免把噪声交给上层 LLM。
     */
    fun hasTopicHit(title: String, snippet: String): Boolean = hitRatio(title, snippet) >= TOPIC_HIT_RATIO

    private companion object {
        /** 门禁阈值：命中主题词的权重之和需达到总权重的该比例。 */
        const val TOPIC_HIT_RATIO = 0.34
    }

    /** 结果的相关性分：按主题词权重加权，取代原来的等权 OR 命中。 */
    fun relevanceScore(title: String, snippet: String): Double {
        val lowerTitle = title.lowercase(Locale.ROOT)
        val lowerSnippet = snippet.lowercase(Locale.ROOT)
        return topics.sumOf { topic ->
            val t = topic.text.lowercase(Locale.ROOT)
            val inTitle = if (lowerTitle.contains(t)) 3.0 else topicHitScore(t, lowerTitle, "") * 3.0
            val inSnippet = if (lowerSnippet.contains(t)) 1.0 else topicHitScore(t, "", lowerSnippet)
            topic.weight * (inTitle + inSnippet)
        }
    }
}
