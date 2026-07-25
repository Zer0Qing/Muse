package io.zer0.muse.data.session

/**
 * Phase 10.3: FTS4 中文分词管理器。
 *
 * 采用应用层 ngram 方案(避免 NDK 依赖 + 兼容所有 Android 版本):
 *  - 中文(CJK 统一表意文字 + 假名)做 2-gram 滑窗,空格分隔
 *  - 英文/数字保留原词(小写化)
 *  - 标点/空格作为分隔符
 *
 * 索引时:把 message.content 转成 ngram,存入 messages_fts 虚拟表
 * 查询时:把 query 转成 ngram,用 FTS4 MATCH(默认 AND 语义)
 *
 * 示例:
 *  - "你好世界" → "你好 好世 世界"
 *  - "hello 你好" → "hello 你好"
 *  - "GPT-4 模型" → "gpt 4 模型"(GPT-4 拆成 gpt 和 4)
 *
 * 召回率:2-gram 覆盖大部分中文模糊匹配(单字查询除外,单字走 LIKE fallback)。
 * 索引体积:约 2x 原文(中文 2-gram,英文不膨胀)。
 */
object MessageFtsManager {

    /** CJK 2-gram 滑窗的窗口大小。 */
    private const val NGRAM_SIZE = 2

    /** 判断字符是否为 CJK(中文/日文/韩文)。 */
    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x4E00..0x9FFF) ||  // CJK 统一表意文字
               (code in 0x3400..0x4DBF) ||  // CJK 扩展 A
               (code in 0x3040..0x309F) ||  // 平假名
               (code in 0x30A0..0x30FF) ||  // 片假名
               (code in 0xAC00..0xD7AF)     // 韩文音节
    }

    /**
     * 把文本转成 ngram 序列(空格分隔的 token 串)。
     *
     * 中文做 2-gram,英文/数字保留原词(小写化),标点作为分隔符。
     * 单个 CJK 字符(孤立)直接作为 token(支持单字查询)。
     */
    fun toNgram(text: String): String {
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
            // 尾部单字也加入(支持末尾单字查询)
            if (s.isNotEmpty()) {
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
     * - 多 token 用空格连接(FTS4 默认 AND 语义)
     * - 对特殊字符做转义(FTS4 的 " * : 等)
     */
    fun toMatchQuery(query: String): String {
        val ngram = toNgram(query)
        if (ngram.isBlank()) return ""
        // 转义 FTS4 特殊字符:用双引号包裹每个 token
        return ngram.split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { "\"${it.replace("\"", "")}\"" }
    }
}
