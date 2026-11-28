package io.zer0.muse.data.profile

/**
 * 「关于你」用户画像分域索引与预算裁剪纯引擎（无 Android / Room 依赖，可直接 JVM 单测）。
 *
 * 将用户画像拆成两部分：
 *  1. Core Card：姓名/称呼/重要日期/强置顶摘要（严格 ≤ 120 tokens），常驻轻量注入；
 *  2. 五个垂直分域文档（生活习惯、医疗健康、待人处事、工作与学习、喜好偏好），
 *     存入内部隔离知识库并在对话时按问题主题做 RAG 按需召回（严格 ≤ 350 tokens）。
 */
object ProfileShardEngine {

    const val DEFAULT_CORE_MAX_TOKENS: Int = 120
    const val DEFAULT_SHARD_MAX_TOKENS: Int = 350

    enum class ProfileDomain(val key: String) {
        CORE("core"),
        HABITS("habits"),
        HEALTH("health"),
        INTERPERSONAL("interpersonal"),
        WORK_STUDY("work_study"),
        PREFERENCES("preferences"),
        ;

        companion object {
            val SHARD_DOMAINS: List<ProfileDomain> = listOf(
                HABITS,
                HEALTH,
                INTERPERSONAL,
                WORK_STUDY,
                PREFERENCES,
            )

            fun fromKey(raw: String?): ProfileDomain? = entries.firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) }
        }
    }

    data class ProfileFactInput(
        val id: Long,
        val content: String,
        val category: String = "",
        val tags: List<String> = emptyList(),
        val importance: Float = 0.5f,
        val pinned: Boolean = false,
    )

    private val CORE_KEYWORDS = listOf(
        "名字", "姓名", "叫我", "称呼", "昵称", "生日", "出生", "年龄", "几岁",
        "城市", "住在", "所在地", "时区", "纪念日", "考试日期", "高考日期", "倒计时",
        "my name", "call me", "nickname", "birthday", "timezone", "exam date",
    )

    private val HEALTH_KEYWORDS = listOf(
        "过敏", "病史", "医院", "医生", "就医", "吃药", "药物", "处方", "症状", "发烧",
        "感冒", "头痛", "牙医", "体检", "血压", "血糖", "手术", "慢性病", "医保",
        "生理期", "受伤", "复查", "医疗", "忌药",
        "allergy", "allergic", "medical", "medicine", "doctor", "hospital", "symptom",
        "health", "illness", "medication",
    )

    private val INTERPERSONAL_KEYWORDS = listOf(
        "家人", "父母", "爸爸", "妈妈", "母亲", "父亲", "哥哥", "姐姐", "弟弟", "妹妹",
        "朋友", "闺蜜", "兄弟", "同事", "同学", "领导", "老板", "老师", "伴侣", "对象",
        "女友", "男友", "老公", "老婆", "孩子", "社交", "待人", "人际", "聚会", "礼貌",
        "沟通方式", "称呼对方", "亲戚",
        "family", "friend", "colleague", "partner", "spouse", "parent", "social",
        "interpersonal", "relationship", "boss",
    )

    private val HABITS_KEYWORDS = listOf(
        "作息", "早睡", "晚睡", "熬夜", "起床", "失眠", "午睡", "饮食", "早饭", "早餐",
        "午饭", "晚饭", "喝水", "咖啡", "喝茶", "戒糖", "运动", "健身", "跑步", "散步",
        "瑜伽", "通勤", "习惯", "日常", "规律", "抽烟", "喝酒",
        "sleep", "wake", "bedtime", "habit", "routine", "breakfast", "coffee",
        "exercise", "workout", "gym", "commute", "diet",
    )

    private val WORK_STUDY_KEYWORDS = listOf(
        "工作", "公司", "职业", "岗位", "行业", "团队", "日报", "周报", "汇报", "项目",
        "代码", "编程", "开发", "测试", "设计", "产品", "运营", "面试", "求职", "简历",
        "学校", "大学", "专业", "课程", "作业", "论文", "考研", "考公", "复习", "考试",
        "学习", "英语", "实习", "出差",
        "work", "job", "career", "company", "project", "coding", "developer",
        "study", "school", "university", "major", "exam", "interview", "deadline",
    )

    private val PREFERENCES_KEYWORDS = listOf(
        "喜欢", "讨厌", "不喜欢", "最爱", "偏好", "爱好", "兴趣", "口味", "忌口",
        "风格", "音乐", "电影", "动漫", "书", "游戏", "颜色", "宠物", "猫", "狗", "旅游",
        "语气偏好", "回复长度",
        "like", "love", "hate", "dislike", "prefer", "favorite", "hobby",
        "music", "movie", "game", "book", "style",
    )

    fun estimateTokens(text: String): Int = io.zer0.memory.budget.LlmBudget.countTokens(text)

    /** 把单条画像事实归入 Core 或五大分域之一。 */
    fun classifyFact(content: String, category: String = "", tags: List<String> = emptyList()): ProfileDomain {
        val normalized = buildString {
            append(content)
            if (category.isNotBlank()) {
                append(' ')
                append(category)
            }
            if (tags.isNotEmpty()) {
                append(' ')
                append(tags.joinToString(" "))
            }
        }.lowercase()

        val catLower = category.trim().lowercase()
        val tagText = tags.joinToString(" ").lowercase()

        return when {
            containsAny(normalized, HEALTH_KEYWORDS) || catLower.contains("health") || tagText.contains("医疗") ->
                ProfileDomain.HEALTH
            containsAny(normalized, CORE_KEYWORDS) || catLower == "identity" || catLower == "core" ->
                ProfileDomain.CORE
            containsAny(normalized, INTERPERSONAL_KEYWORDS) || catLower.contains("relationship") || catLower.contains("social") ->
                ProfileDomain.INTERPERSONAL
            containsAny(normalized, HABITS_KEYWORDS) || catLower.contains("habit") ->
                ProfileDomain.HABITS
            containsAny(normalized, WORK_STUDY_KEYWORDS) || catLower.contains("work") || catLower.contains("study") ->
                ProfileDomain.WORK_STUDY
            containsAny(normalized, PREFERENCES_KEYWORDS) || catLower.contains("preference") ->
                ProfileDomain.PREFERENCES
            else -> ProfileDomain.PREFERENCES
        }
    }

    /** 构建核心常驻卡（称呼、关键日期与强置顶要点），严格限制在 [maxTokens] 内。 */
    fun buildCoreCard(facts: List<ProfileFactInput>, maxTokens: Int = DEFAULT_CORE_MAX_TOKENS): String {
        if (facts.isEmpty() || maxTokens <= 0) return ""
        val coreCandidates = facts
            .asSequence()
            .filter { fact ->
                fact.content.isNotBlank() &&
                    (classifyFact(fact.content, fact.category, fact.tags) == ProfileDomain.CORE || fact.pinned)
            }
            .distinctBy { it.content.trim() }
            .sortedWith(
                compareByDescending<ProfileFactInput> { it.pinned }
                    .thenByDescending { classifyFact(it.content, it.category, it.tags) == ProfileDomain.CORE }
                    .thenByDescending { it.importance },
            )
            .toList()
        return packLinesToTokenBudget(
            lines = coreCandidates.map { "- ${it.content.trim()}" },
            maxTokens = maxTokens,
        )
    }

    /** 把五大分域（除 Core 外）聚合成内部知识库文档正文。 */
    fun buildDomainDocuments(facts: List<ProfileFactInput>): Map<ProfileDomain, String> {
        if (facts.isEmpty()) return emptyMap()
        val grouped = LinkedHashMap<ProfileDomain, MutableList<ProfileFactInput>>()
        ProfileDomain.SHARD_DOMAINS.forEach { domain -> grouped[domain] = mutableListOf() }

        facts.forEach { fact ->
            if (fact.content.isBlank()) return@forEach
            val domain = classifyFact(fact.content, fact.category, fact.tags)
            if (domain in ProfileDomain.SHARD_DOMAINS) {
                grouped.getValue(domain).add(fact)
            }
        }

        val result = LinkedHashMap<ProfileDomain, String>()
        grouped.forEach { (domain, list) ->
            if (list.isNotEmpty()) {
                val sorted = list
                    .distinctBy { it.content.trim() }
                    .sortedWith(compareByDescending<ProfileFactInput> { it.pinned }.thenByDescending { it.importance })
                val body = sorted.joinToString("\n") { "- ${it.content.trim()}" }
                result[domain] = body
            }
        }
        return result
    }

    /** 离线或无向量模型时的按需本地分域评分检索（保证不超过 [maxTokens]）。 */
    fun selectLocalRelevant(query: String, domainDocs: Map<ProfileDomain, String>, maxTokens: Int = DEFAULT_SHARD_MAX_TOKENS): String {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank() || domainDocs.isEmpty() || maxTokens <= 0) return ""
        val queryLower = cleanQuery.lowercase()
        val domainHints = detectQueryDomains(queryLower)
        val queryTerms = extractQueryTerms(queryLower)

        val scoredLines = mutableListOf<Pair<Double, String>>()
        domainDocs.forEach { (domain, content) ->
            val domainBoost = if (domain in domainHints) 4.0 else 0.0
            content.lineSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .forEach { line ->
                    val lineLower = line.lowercase()
                    var score = domainBoost
                    queryTerms.forEach { term ->
                        if (lineLower.contains(term)) {
                            score += if (term.length >= 2) 2.5 else 0.5
                        }
                    }
                    if (score > 0.0) {
                        scoredLines += score to "[${domainTitleZh(domain)}] ${line.removePrefix("-").trim()}"
                    }
                }
        }

        val topLines = scoredLines
            .sortedByDescending { it.first }
            .distinctBy { it.second }
            .map { "- ${it.second}" }

        return packLinesToTokenBudget(topLines, maxTokens)
    }

    /** 将外部 RAG 命中的片段按预算压缩打包。 */
    fun packSnippetsToBudget(snippets: List<String>, maxTokens: Int = DEFAULT_SHARD_MAX_TOKENS): String {
        val lines = snippets
            .asSequence()
            .flatMap { it.lineSequence() }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        return packLinesToTokenBudget(lines, maxTokens)
    }

    fun domainTitleZh(domain: ProfileDomain): String = when (domain) {
        ProfileDomain.CORE -> "核心信息"
        ProfileDomain.HABITS -> "生活习惯"
        ProfileDomain.HEALTH -> "医疗与健康"
        ProfileDomain.INTERPERSONAL -> "待人处事与关系"
        ProfileDomain.WORK_STUDY -> "工作与学习"
        ProfileDomain.PREFERENCES -> "喜好与风格"
    }

    private fun packLinesToTokenBudget(lines: List<String>, maxTokens: Int): String {
        if (lines.isEmpty() || maxTokens <= 0) return ""
        val out = StringBuilder()
        for (line in lines) {
            val candidate = if (out.isEmpty()) line else "$out\n$line"
            if (estimateTokens(candidate) <= maxTokens) {
                if (out.isNotEmpty()) out.append('\n')
                out.append(line)
            } else if (out.isEmpty()) {
                out.append(truncateToTokenBudget(line, maxTokens))
                break
            } else {
                break
            }
        }
        return out.toString()
    }

    private fun truncateToTokenBudget(text: String, maxTokens: Int): String =
        io.zer0.memory.budget.LlmBudget.truncateToTokenBudgetStrict(text, maxTokens)

    private fun detectQueryDomains(queryLower: String): Set<ProfileDomain> {
        val matched = linkedSetOf<ProfileDomain>()
        if (containsAny(queryLower, HEALTH_KEYWORDS)) matched += ProfileDomain.HEALTH
        if (containsAny(queryLower, HABITS_KEYWORDS)) matched += ProfileDomain.HABITS
        if (containsAny(queryLower, INTERPERSONAL_KEYWORDS)) matched += ProfileDomain.INTERPERSONAL
        if (containsAny(queryLower, WORK_STUDY_KEYWORDS)) matched += ProfileDomain.WORK_STUDY
        if (containsAny(queryLower, PREFERENCES_KEYWORDS)) matched += ProfileDomain.PREFERENCES
        return matched
    }

    private fun extractQueryTerms(queryLower: String): Set<String> {
        val terms = linkedSetOf<String>()
        Regex("""[a-z0-9_]{2,}""").findAll(queryLower).forEach { terms += it.value }
        val cjkRun = StringBuilder()
        for (ch in queryLower) {
            if (ch.code > 0x7F && ch.isLetterOrDigit()) {
                cjkRun.append(ch)
            } else if (cjkRun.isNotEmpty()) {
                addCjkBigrams(cjkRun.toString(), terms)
                cjkRun.clear()
            }
        }
        if (cjkRun.isNotEmpty()) addCjkBigrams(cjkRun.toString(), terms)
        return terms
    }

    private fun addCjkBigrams(run: String, out: MutableSet<String>) {
        if (run.length <= 1) return
        for (i in 0 until run.length - 1) {
            out += run.substring(i, i + 2)
        }
    }

    private fun containsAny(text: String, keywords: List<String>): Boolean = keywords.any { kw -> text.contains(kw) }
}
