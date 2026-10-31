package io.zer0.memory.fact

/**
 * Facts FTS4 中文分词管理器。
 *
 * 采用应用层 ngram 方案(避免 NDK 依赖 + 兼容所有 Android 版本):
 *  - 中文(CJK 统一表意文字 + 假名)做 2-gram 滑窗,空格分隔
 *  - 英文/数字保留原词(小写化)
 *  - 标点/空格作为分隔符
 *
 * 索引时:把 fact 内容转成 ngram(含末位单字),存入 facts_fts 虚拟表。
 * 查询时:把 query 转成 ngram(不含末位单字),用 FTS4 MATCH(默认 AND,可改 OR)。
 *
 * 示例:
 *  - "你好世界" → "你好 好世 世界"
 *  - "hello 你好" → "hello 你好"
 *  - "GPT-4 模型" → "gpt 4 模型"
 */
object FactFtsManager {

    /** CJK 2-gram 滑窗的窗口大小。 */
    private const val NGRAM_SIZE = 2

    /** FTS4 MATCH 表达式里的 OR 连接符。 */
    private const val OR_OPERATOR = " OR "

    /** 判断字符是否为 CJK(中文/日文/韩文)。 */
    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x4E00..0x9FFF) || // CJK 统一表意文字
            (code in 0x3400..0x4DBF) || // CJK 扩展 A
            (code in 0x3040..0x309F) || // 平假名
            (code in 0x30A0..0x30FF) || // 片假名
            (code in 0xAC00..0xD7AF) // 韩文音节
    }

    /**
     * 把文本转成 ngram 序列(空格分隔的 token 串)。
     *
     * 中文做 2-gram,英文/数字保留原词(小写化),标点作为分隔符。
     * 单个 CJK 字符(孤立)直接作为 token(支持单字查询)。
     *
     * @param includeTrailingTailChar 是否额外输出 CJK 段的末位单字。索引侧需要它来覆盖
     *   奇数长度片段的末尾字符;查询侧不需要 —— 末位单字在 AND 语义下只会加约束,不会加召回。
     */
    fun toNgram(text: String, includeTrailingTailChar: Boolean = true): String {
        if (text.isBlank()) return ""
        val tokens = mutableListOf<String>()
        val cjkBuffer = StringBuilder()
        val asciiBuffer = StringBuilder()

        fun flushCjk() {
            if (cjkBuffer.isEmpty()) return
            val s = cjkBuffer.toString()
            if (s.length >= NGRAM_SIZE) {
                for (i in 0..s.length - NGRAM_SIZE) {
                    tokens.add(s.substring(i, i + NGRAM_SIZE))
                }
            }
            // 尾部单字也加入(索引侧用它覆盖末尾字符)
            if (includeTrailingTailChar && s.isNotEmpty()) {
                tokens.add(s.last().toString())
            }
            cjkBuffer.clear()
        }

        fun flushAscii() {
            if (asciiBuffer.isEmpty()) return
            tokens.add(asciiBuffer.toString().lowercase())
            asciiBuffer.clear()
        }

        for (ch in text) {
            when {
                isCjk(ch) -> {
                    flushAscii()
                    cjkBuffer.append(ch)
                }
                ch.isLetterOrDigit() -> {
                    flushCjk()
                    asciiBuffer.append(ch)
                }
                else -> {
                    flushCjk()
                    flushAscii()
                }
            }
        }
        flushCjk()
        flushAscii()
        return tokens.joinToString(" ")
    }

    /**
     * 把查询词转成 FTS4 MATCH 表达式。
     *
     * - 空查询返回空字符串(调用方应跳过搜索)
     * - 单 token 直接返回
     * - 多 token 默认用空格连接(FTS4 默认 AND 语义);[orSemantics] 为 true 时用 OR 连接
     * - 对特殊字符做转义(FTS4 的 " * : 等)
     *
     * 查询侧不输出末位单字 token:同一行事实里散落的词本来就不共享 2-gram,
     * 再要求末位单字命中只会让召回更少。
     */
    fun toMatchQuery(query: String, orSemantics: Boolean = false): String {
        val ngram = toNgram(query, includeTrailingTailChar = false)
        if (ngram.isBlank()) return ""
        val joiner = if (orSemantics) OR_OPERATOR else " "
        // 转义 FTS4 特殊字符:用双引号包裹每个 token
        return ngram.split(' ')
            .filter { it.isNotBlank() }
            .joinToString(joiner) { "\"${it.replace("\"", "")}\"" }
    }

    /**
     * 把查询串拆成检索词:空白与标点都是分隔符,不做分词判断。
     *
     * 用于 LIKE 兜底:整串当子串查不到时,拆成词逐个查,任一命中即可作为候选。
     * 原样的词序与大小写保持不变(LIKE 由 SQLite 决定大小写敏感性),重复词只留一个。
     */
    fun toQueryTerms(query: String): List<String> {
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
     * 判断查询是否只包含孤立单 CJK 字符(此时 FTS4 2-gram 无法命中,应回退 LIKE)。
     */
    fun shouldFallbackToLike(query: String): Boolean {
        val ngram = toNgram(query, includeTrailingTailChar = false)
        if (ngram.isBlank()) return true
        val tokens = ngram.split(' ').filter { it.isNotBlank() }
        // 全是单字符 token 时(无 2-gram),FTS4 召回不足,回退 LIKE
        return tokens.isNotEmpty() && tokens.all { it.length == 1 }
    }
}
