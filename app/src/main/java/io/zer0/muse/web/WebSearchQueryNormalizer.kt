package io.zer0.muse.web

import java.text.Normalizer

/**
 * 联网搜索 query 规范化。
 *
 * LLM 有时会把思考过程里的检索表达式原样塞进 query，例如：
 * `""ornith1.5-35ba3b”模型 OR model"`。
 * 畸形引号会让 Bing 把整段当成错误的短语/操作符表达式，结果完全失真。
 * 这里只清理格式噪声，保留关键词和正常的 OR 等检索词。
 */
object WebSearchQueryNormalizer {

    private val WHITESPACE = Regex("\\s+")
    private val INVISIBLE = Regex("[\\u200B-\\u200D\\uFEFF]")

    fun normalize(raw: String): String {
        var query = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace('\u2018', ' ')
            .replace('\u2019', ' ')
            .replace('\u201C', ' ')
            .replace('\u201D', ' ')
            .replace('\u300C', ' ')
            .replace('\u300D', ' ')
            .replace(INVISIBLE, " ")
            .replace(WHITESPACE, " ")
            .trim()

        // 引号数量不成对，或模型把整条 query 包成了多层引号时，
        // 去掉 ASCII 双引号，避免搜索引擎进入错误的短语解析状态。
        val quoteCount = query.count { it == '"' }
        if (quoteCount % 2 != 0 || query.startsWith('"') || query.endsWith('"')) {
            query = query.replace("\"", "")
        }

        return query.replace(WHITESPACE, " ").trim()
    }
}
