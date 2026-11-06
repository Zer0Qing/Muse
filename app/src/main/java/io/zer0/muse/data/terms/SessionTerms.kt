package io.zer0.muse.data.terms

import io.zer0.common.Logger

/**
 * v2.7.x(E2): 术语的抽取、累计与注入。
 *
 * 抽取必须是**确定性**的,不是"看起来像模型的猜测"。所以只认三类信号:
 *  1. 用户自己给出的定义(「X 就是 Y」「所谓 X，指的是 Y」「X means Y」)；
 *  2. 用户特别框出来的词(「」『』“” ` 等引号里的短短语 —— 人在解释黑话时几乎都会加引号);
 *  3. 标识符形状的词(camelCase / snake_case / 3–8 位全大写缩写,这类通常是团队内部叫法)。
 *
 * **没有**第 4 类:"从中文里切 2–6 字 n-gram,数它出现几次"。那种做法在没有词表的项目里
 * 会把"然后/一下/东西"当术语收集,污染上下文。宁可漏,不可错。
 *
 * 注入侧有两条硬规矩:
 *  - 条数上限 [MAX_INJECT](默认 5)。术语是提示词里最不结构化的一类内容,
 *    放多了会把真正稳定的系统提示挤掉。
 *  - 未经用户确认的条目必须带「不确定」标注。模型把一个猜出来的解释当事实用,
 *    比不知道这个词更糟。
 */
object SessionTerms {

    private const val TAG = "SessionTerms"

    /** 注入上下文的最大条数(计划要求 3–5,取上限 5)。 */
    const val MAX_INJECT = 5

    /** 复现几次才值得问用户确认。 */
    const val CONFIRM_THRESHOLD = 3

    /** 术语长度合理区间(太短没有信息量,太长是句子)。 */
    private const val MIN_TERM_CHARS = 2
    private const val MAX_TERM_CHARS = 16

    /** 一次定义里解释部分的上限。 */
    private const val MAX_MEANING_CHARS = 120

    /**
     * 一条命中。[meaning] 为 null 表示"只知道它被当成一个词在说",不代表它没有含义。
     */
    data class TermHit(val term: String, val meaning: String? = null)

    // ── 抽取 ──────────────────────────────────────────────────

    /**
     * 定义句式。左右两侧都限长,避免把整句话当一个术语。
     *
     * 「我们管这个叫拉条子」这类句子里,术语是"叫"之后的那一段;
     * 而"管"之前那个"这个"只是指代 —— 曾经按"第一个名词"取,结果抽出了「这个」。
     * 所以这里把该句式拆成"管 X 叫 Y"与"叫 X"两种明确形状,再加一张停用词表兜底。
     */
    private val definitionPatterns = listOf(
        // 「X」就是/指的是 Y —— 备选词必须**长在前**:写成"就是|指的是"时,
        // "就是指一起打排位赛"会被"就是"先咬掉,留下一个开头是"指"的解释。
        Regex("「(.{2,16}?)」\\s*(?:就是指|指的是|是指|就是|意思是|叫作|定义为)[:：,，]?\\s*(.{2,120}?)(?:[。！!？?\\n]|$)"),
        // 所谓 X，就是 Y
        Regex("所谓\\s*(.{2,16}?)[,，]\\s*(?:就是指|指的是|就是|意思是)[:：]?\\s*(.{2,120}?)(?:[。！!？?\\n]|$)"),
        // "X" means Y
        Regex("""["'“]([^"'”\n]{2,16})["'”]\s*(?:means|is short for|refers to)\s*(.{2,120}?)(?:[.!?\n]|$)""", RegexOption.IGNORE_CASE),
        // 我们(内部)管 <指代> 叫 <术语>[，<解释>]
        // 注意术语在"叫"**之后**:"管这个叫拉条子"里"这个"只是指代,拉条子才是词。
        Regex(
            "(?:我们|这里|本项目|团队)[^。\\n]{0,12}?管[^。，,\\n]{0,8}?叫(?:作|为)?[:：]?\\s*" +
                "(.{2,16}?)(?:[，,;；]\\s*(?:就是指|指的是|就是|意思是|指)?[:：]?\\s*(.{2,120}?))?(?=[。！!？?\\n]|$)",
        ),
        // 我们(内部)把 X 叫作 / 称为 …
        Regex(
            "(?:我们|这里|本项目|团队)[^。\\n]{0,12}?(?:称为|叫作|叫做)[:：]?\\s*" +
                "(.{2,16}?)(?:[，,;；]\\s*(?:就是指|指的是|就是|意思是)?[:：]?\\s*(.{2,120}?))?(?=[。！!？?\\n]|$)",
        ),
    )

    /**
     * 曾经这里还有一条"只提到名字"的句式（`我们…叫 X`），已经删掉。
     *
     * 删的理由值得留着:中文里"叫/是"是无处不在的动词,任何"我们…叫 X"的宽松模式
     * 都会把「就是指一起打排位赛」这类普通句子切成一个术语。
     * 术语表一旦被普通句子污染,用户就会把整页当噪音忽略 ——
     * 所以宁可漏掉几个真术语(用户可以手动加),也不收进一批假术语。
     */

    /**
     * 指代词与高频虚词。它们在任何句式里都不该成为术语 ——
     * 没有这张表,"管这个叫 X"一类句式就会把"这个"收进术语页,
     * 而一个写着「这个：不确定」的术语条目会把整份术语表的可信度拉平。
     */
    private val stopTerms = setOf(
        "这个", "这些", "那个", "那样", "这样", "这么", "那么", "一种", "一下", "东西",
        "时候", "地方", "什么", "怎么", "如此", "所谓", "我们", "你们", "他们", "这里", "那里",
    )

    /** 引号里的短语(没有解释也算候选)。 */
    private val quotedPattern = Regex("[「『“\"`]([^「』”\"`\\n]{2,16})[」』”\"`]")

    /** 标识符形状:camelCase / snake_case / 全大写缩写。 */
    private val identifierPatterns = listOf(
        Regex("\\b[a-z][a-z0-9]*[A-Z][A-Za-z0-9]*\\b"),
        Regex("\\b[a-z][a-z0-9]*_[a-z0-9][A-Za-z0-9_]*\\b"),
        Regex("\\b[A-Z][A-Z0-9]{2,7}\\b"),
    )

    /** 这些全大写串是通用缩写,不是团队黑话,列出来避免每段英文都收一串候选。 */
    private val ignoredAcronyms = setOf(
        "API", "HTTP", "HTTPS", "URL", "URI", "JSON", "XML", "YAML", "SQL", "SDK", "IDE",
        "AI", "ML", "UI", "UX", "OK", "TODO", "FAQ", "PDF", "PNG", "JPG", "GIF", "HTML", "CSS",
        "IOS", "MAC", "WIN", "CPU", "GPU", "RAM", "MB", "GB", "TB", "ID", "DB", "LLM", "PR",
    )

    /** 从一条用户消息里抽候选(纯函数,便于逐条测)。 */
    fun extract(text: String): List<TermHit> {
        val hits = LinkedHashMap<String, String?>()

        definitionPatterns.forEach { regex ->
            regex.findAll(text).forEach { matched ->
                val term = normalize(matched.groupValues[1])
                val meaning = matched.groups[2]?.value?.let { normalizeMeaning(it) }
                if (term != null) merge(hits, term, meaning)
            }
        }
        quotedPattern.findAll(text).forEach { matched ->
            val term = normalize(matched.groupValues[1])
            if (term != null) merge(hits, term, hits[term])
        }
        identifierPatterns.forEach { regex ->
            regex.findAll(text).forEach { matched ->
                val raw = matched.value
                if (ignoredAcronyms.contains(raw.uppercase())) return@forEach
                val term = normalize(raw)
                if (term != null) merge(hits, term, hits[term])
            }
        }
        // 定义句式给出的解释优先:后面遇到的裸候选不许把它覆盖成 null
        return hits.map { (term, meaning) -> TermHit(term, meaning) }
    }

    /**
     * 规范化候选;不合格返回 null。
     *
     * 判据合成一个布尔而不是逐条 early return:规则会一条条加,
     * 一个出口的结构让人一眼看得清"到底什么算不合格",而五个 if-return 会慢慢长出第六个。
     */
    private fun normalize(raw: String): String? {
        val trimmed = raw.trim().trimEnd('。', '，', ',', '、', '.', '：', ':', '；', ';', '！', '？')
        val acceptable = trimmed.length in MIN_TERM_CHARS..MAX_TERM_CHARS &&
            trimmed.none { it == '\n' } &&
            trimmed.any { it.isLetter() } &&
            !isStopword(trimmed)
        return if (acceptable) trimmed else null
    }

    /**
     * 指代词与虚词。整词命中直接排除;
     * 「以虚词开头且尾巴短到几乎没内容」(如"这么办")也排除 —— 宁漏不错,
     * 因为一条写着「这个：不确定」的术语会把整页的可信度拉平。
     */
    private fun isStopword(value: String): Boolean = stopTerms.contains(value) ||
        stopTerms.any { stop ->
            value.length > stop.length && value.startsWith(stop) && value.length - stop.length < MIN_TERM_CHARS
        }

    private fun normalizeMeaning(raw: String): String? {
        val trimmed = raw.trim().trimEnd('。', '，', ',', '、', '.', '！', '？')
        if (trimmed.isBlank()) return null
        return trimmed.take(MAX_MEANING_CHARS)
    }

    private fun merge(hits: LinkedHashMap<String, String?>, term: String, meaning: String?) {
        val existing = hits[term]
        hits[term] = if (meaning != null) meaning else existing
    }

    // ── 注入 ──────────────────────────────────────────────────

    /**
     * 把选中的术语渲染成系统提示的一段。空集合返回空串(调用方据此不拼这一节)。
     *
     * 未确认条目一律带「不确定」。这一句不是客套:模型读到没有标注的候选解释,
     * 会当成用户的既定说法直接使用,而在别的会话里它可能完全不是那个意思。
     */
    fun renderInjectionBlock(terms: List<SessionTermEntity>): String {
        if (terms.isEmpty()) return ""
        val body = terms.take(MAX_INJECT).joinToString("\n") { term ->
            val head = if (term.confirmed) "- ${term.term}" else "- ${term.term}(不确定,用户尚未确认)"
            val meaning = term.meaning?.takeIf { it.isNotBlank() }
            if (meaning == null) head else "$head:$meaning"
        }
        return "<session_terms>\n以下词是用户在自己会话里的说法,含义按标注处理:\n$body\n</session_terms>"
    }

    /** 候选→确认提示的阈值判断,单独一个函数是为了让"3 次"这个数只出现在一处。 */
    fun awaitingConfirmation(occurrences: Int, confirmed: Boolean): Boolean = !confirmed && occurrences >= CONFIRM_THRESHOLD

    /** 供日志与测试:抽取结果按 term 排序稳定输出。 */
    fun describe(hits: List<TermHit>): String = hits.joinToString(", ") { hit ->
        if (hit.meaning == null) hit.term else "${hit.term}→${hit.meaning.take(20)}"
    }

    internal fun log(msg: String) = Logger.i(TAG, msg)
}
