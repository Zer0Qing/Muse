package io.zer0.muse.rag

/**
 * v1.134: BERT WordPiece 分词器(纯 Kotlin 实现,无外部依赖)。
 *
 * 用于 [OnnxEmbeddingProvider] / [OnnxRerankProvider] 将文本转换为 BERT 风格的 token 序列。
 *
 * 算法参考 BERT 官方 tokenizer:
 *  1. 基础分词:小写化、空白分割、标点分割、CJK 单字分割
 *  2. WordPiece:贪婪最长匹配子词,前缀 "##" 表示子词延续
 *  3. 未知 token 映射为 [UNK]
 *
 * @see <a href="https://github.com/google-research/bert">BERT 官方实现</a>
 */
object WordPieceTokenizer {

    /** 完整分词:基础分词 + WordPiece 子词拆分。 */
    fun tokenize(text: String, vocab: Map<String, Int>, maxInputCharsPerWord: Int = 100): List<String> {
        val basicTokens = basicTokenize(text)
        val result = mutableListOf<String>()
        for (token in basicTokens) {
            result.addAll(wordPiece(token, vocab, maxInputCharsPerWord))
        }
        return result
    }

    /**
     * 基础分词:小写化、空白/标点/CJK 分割。
     * 输出 token 仍是"词"级别,不含 WordPiece 子词标记。
     */
    private fun basicTokenize(text: String): List<String> {
        val cleaned = cleanText(text)
        if (cleaned.isEmpty()) return emptyList()
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in cleaned) {
            when {
                ch.isWhitespace() -> flush(current, tokens)
                isPunctuation(ch) -> {
                    flush(current, tokens)
                    tokens.add(ch.toString())
                }
                isCjkChar(ch) -> {
                    // CJK 字符单独成 token(BERT 官方行为)
                    flush(current, tokens)
                    tokens.add(ch.toString())
                }
                else -> current.append(ch)
            }
        }
        flush(current, tokens)
        return tokens.map { it.lowercase() }
    }

    private fun flush(sb: StringBuilder, tokens: MutableList<String>) {
        if (sb.isNotEmpty()) {
            tokens.add(sb.toString())
            sb.clear()
        }
    }

    /** 清理控制字符和零宽字符,规范空白。 */
    private fun cleanText(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            val code = ch.code
            if (code == 0 || code == 0xFFFD || isControl(ch)) continue
            if (isWhitespace(ch)) sb.append(' ')
            else sb.append(ch)
        }
        return sb.toString()
    }

    /**
     * WordPiece 贪婪最长匹配:从 [token] 头部开始,逐步缩短子串直到在 vocab 中命中。
     * 非首字子词加 "##" 前缀。整词未命中则返回 [UNK]。
     */
    private fun wordPiece(token: String, vocab: Map<String, Int>, maxChars: Int): List<String> {
        if (token.isEmpty()) return emptyList()
        if (token.length > maxChars) return listOf("[UNK]")

        val result = mutableListOf<String>()
        val chars = token.toList()
        var start = 0
        while (start < chars.size) {
            var end = chars.size
            var found: String? = null
            while (start < end) {
                val sub = if (start == 0) {
                    chars.subList(start, end).joinToString("")
                } else {
                    "##" + chars.subList(start, end).joinToString("")
                }
                if (vocab.containsKey(sub)) {
                    found = sub
                    break
                }
                end--
            }
            if (found == null) return listOf("[UNK]")
            result.add(found)
            start = end
        }
        return result
    }

    private fun isWhitespace(ch: Char): Boolean =
        ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' ||
            Character.getType(ch) == Character.SPACE_SEPARATOR.toInt()

    private fun isControl(ch: Char): Boolean {
        if (ch == '\t' || ch == '\n' || ch == '\r') return false
        val type = Character.getType(ch)
        return type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
    }

    private fun isPunctuation(ch: Char): Boolean {
        val code = ch.code
        if (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) return true
        val type = Character.getType(ch)
        return type == Character.START_PUNCTUATION.toInt() ||
            type == Character.END_PUNCTUATION.toInt() ||
            type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.OTHER_PUNCTUATION.toInt() ||
            type == Character.CONNECTOR_PUNCTUATION.toInt() ||
            type == Character.DASH_PUNCTUATION.toInt()
    }

    private fun isCjkChar(ch: Char): Boolean {
        val c = ch.code
        return (c in 0x4E00..0x9FFF) ||   // CJK 统一汉字
            (c in 0x3400..0x4DBF) ||       // CJK Extension A
            (c in 0x20000..0x2A6DF) ||     // CJK Extension B
            (c in 0x2A700..0x2B73F) ||     // CJK Extension C
            (c in 0x2B740..0x2B81F) ||     // CJK Extension D
            (c in 0x3000..0x303F) ||       // CJK 符号和标点
            (c in 0xFF00..0xFFEF)          // 半角/全角字符
    }
}
