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

    /**
     * 口语化/客套前缀与提问套话 — 搜索引擎对它们零收益,反而稀释关键词。
     * 仅当 query 较长(像一句话而非关键词串)时才剔除,短 keyword 不动。
     */
    private val LEADING_FILLERS = listOf(
        "请问", "请帮我", "帮我", "帮我查", "帮我搜索", "帮我搜", "麻烦", "麻烦你",
        "能不能", "可不可以", "可以帮我", "我想知道", "我想了解", "你知道", "我问问",
        "查一下", "查查", "搜索一下", "搜一下", "有没有", "有哪些", "什么是", "怎么",
        "please", "can you", "could you", "i want to know", "i wonder", "tell me",
    )

    private val TRAILING_FILLERS = listOf(
        "吗",
        "呢",
        "吧",
        "啊",
        "呀",
        "谢谢",
        "谢谢你",
        "多谢",
    )

    /** 超过该长度才做"口语提炼",避免把正常短 query 误删。 */
    private const val REFINE_MIN_LENGTH = 12

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

    /**
     * 查询提炼 — 把"像一句话"的 query 收敛为关键词串,改善搜索精准度。
     *
     * 设计原则:
     *  1. 保守 — 只在 query 明显偏长(≥ [REFINE_MIN_LENGTH] 字符)时启用;
     *     短 keyword(如 "DeepSeek V4 Flash 模型")原样保留;
     *  2. 只剔口语噪声 — 去句首客套/提问套话、句末语气词,不做分词/去停用词
     *     (中文停用词误删风险高,宁可保守);
     *  3. 不截断 — 查询长度由上游 provider 自己约束；本层不能静默丢掉尾部主题词。
     *
     * 注意:本函数幂等 — refine(refine(x)) == refine(x)。
     */
    fun refine(raw: String): String {
        var query = normalize(raw)
        if (query.length < REFINE_MIN_LENGTH) return query

        // 1. 去句首填充词(循环,处理叠加,如"请问帮我查一下")
        var changed = true
        while (changed) {
            changed = false
            for (filler in LEADING_FILLERS) {
                if (query.startsWith(filler, ignoreCase = true) && query.length > filler.length) {
                    query = query.substring(filler.length).trimStart(' ', ',', '，', '。', '?', '？', ':', '：')
                    changed = true
                }
            }
        }

        // 2. 去句末语气词(单个字符,去一层即可)
        for (filler in TRAILING_FILLERS) {
            if (query.endsWith(filler) && query.length > filler.length) {
                query = query.dropLast(filler.length).trimEnd(' ', ',', '，', '。', '?', '？')
                break
            }
        }

        return query.ifBlank { normalize(raw) }
    }
}
