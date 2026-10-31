package io.zer0.muse.proactive

import io.zer0.common.Logger

/**
 * 解析模型对主动消息请求的回复。
 *
 * 单独成文件是因为这里的容错策略会反复调整（不同模型吐格式的方式差别极大），
 * 而它跟「怎么构造提示词」没有任何关系，改一个不该动另一个。
 *
 * 容错优先级：能认出 JSON 就用 JSON；认不出但有正文就宽松接受；
 * 什么都不像就当成**失败**而不是跳过 —— 两者要分开，
 * 因为「模型跑飞」和「模型决定不说」的处置完全相反：
 * 前者值得重试，后者重试只会逼它硬凑。
 */
object ProactiveReplyParser {

    private const val TAG = "ProactiveReply"

    /** 正文硬上限（字）。超过这个长度一定要截，与提示词里的「一到三句话」一致。 */
    const val MAX_CONTENT_CHARS = 200

    /** 正文至少要这么多个字（标点、空白不算），否则等同于没说。 */
    const val MIN_CONTENT_CHARS = 2

    /** 句末标点。截断优先落在这些字符后面，不硬切半个词。 */
    private const val SENTENCE_ENDS = "。！？!?…；;\n"

    /** 解析结果。 */
    sealed interface Result {
        /** 现在不该说。理由只进日志，不给用户看。 */
        data class Skip(val reason: String) : Result

        /** 说，正文在 [content]。 */
        data class Message(val thoughtId: String?, val content: String, val tone: String) : Result

        /** 输出不可用。与 [Skip] 必须分开：前者要重试，后者是正常决策。 */
        data class Failed(val reason: String) : Result
    }

    fun parse(raw: String): Result {
        val text = stripFences(raw).trim()
        val parsed = when {
            text.isEmpty() -> Result.Failed("模型返回空内容")
            else -> extractJsonObject(text)?.let { parseJson(it) } ?: fallbackToProse(text)
        }
        return if (parsed is Result.Message) validate(parsed) else parsed
    }

    /**
     * 正文硬校验。
     *
     * 解析层是唯一能拦住「模型写小作文」的地方：提示词里写「一到三句话」只是建议，
     * 而发出去的长文用户只能在通知栏看到前几十个字，还不如在这里压住。
     * 太短同理 —— 一个字或一个标点不是「说话」，按 skip 处理（不是失败，不该重试）。
     */
    private fun validate(message: Result.Message): Result {
        val content = message.content.trim()
        if (content.count { it.isLetterOrDigit() } < MIN_CONTENT_CHARS) {
            Logger.w(TAG, "正文过短，按跳过处理 | len=${content.length}")
            return Result.Skip("正文过短（${content.length} 字）")
        }
        if (content.length <= MAX_CONTENT_CHARS) return message.copy(content = content)
        val truncated = truncateToSentence(content)
        Logger.i(TAG, "正文超长已截断 | from=${content.length} | to=${truncated.length}")
        return message.copy(content = truncated)
    }

    /**
     * 按句子边界截到 [MAX_CONTENT_CHARS] 以内。
     *
     * 先找最后一个句末标点；它太靠前（不到上限一半）时退而求其次，
     * 在最后一个空白处断开（英文正文靠这个不切断单词）；再不行才硬切。
     */
    private fun truncateToSentence(text: String): String {
        val head = text.take(MAX_CONTENT_CHARS)
        val sentenceEnd = head.indexOfLast { it in SENTENCE_ENDS }
        if (sentenceEnd >= MAX_CONTENT_CHARS / 2) return head.substring(0, sentenceEnd + 1).trim()
        val space = head.indexOfLast { it.isWhitespace() }
        if (space >= MAX_CONTENT_CHARS / 2) return head.substring(0, space).trim()
        return head.trim()
    }

    /** 已经是纯 JSON 的情况。 */
    private fun parseJson(json: String): Result {
        val skipValue = Regex("\"skip\"\\s*:\\s*(true|false)")
            .find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()

        val reason = Regex("\"reason\"\\s*:\\s*\"([^\"]*)\"").find(json)
            ?.groupValues?.get(1).orEmpty()

        val content = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
            ?.groupValues?.get(1)
            ?.let { unescape(it) }
            ?.trim()
            .orEmpty()

        return when {
            skipValue == true -> Result.Skip(reason.ifBlank { "模型判断现在不适合说" })
            content.isNotBlank() -> {
                val thoughtId = Regex("\"thought_id\"\\s*:\\s*\"([^\"]*)\"").find(json)
                    ?.groupValues?.get(1)
                    ?.takeIf { it.isNotBlank() && it != "null" }
                val tone = Regex("\"tone\"\\s*:\\s*\"([^\"]*)\"").find(json)
                    ?.groupValues?.get(1).orEmpty()
                Result.Message(thoughtId, content, tone)
            }
            // JSON 里既没 skip=true 也没 content：当成失败，让上层重试
            else -> Result.Failed("JSON 缺 content 字段")
        }
    }

    private fun fallbackToProse(text: String): Result {
        if (!looksLikeProse(text)) return Result.Failed("输出无法解析")
        Logger.w(TAG, "模型未按 JSON 返回，按纯文本正文接受")
        return Result.Message(null, text, "")
    }

    private fun stripFences(raw: String): String =
        raw.replace(Regex("^```(?:json)?\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("\\s*```$", RegexOption.MULTILINE), "")

    /** 从可能混着说明文字的输出里抠出第一个平衡的 JSON 对象。 */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        var depth = 0
        var inString = false
        var escaped = false
        var stop = -1
        for (i in start.coerceAtLeast(0) until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) {
                        stop = i
                        break
                    }
                }
            }
        }
        return if (start >= 0 && stop > start) text.substring(start, stop + 1) else null
    }

    /** JSON 字符串里的常见转义还原。 */
    private fun unescape(s: String): String = s
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    /**
     * 判断输出是否像一段正常的话。
     *
     * 门槛：长度合理、不含明显的代码/键值结构。
     * 这个兜底存在是因为有些小模型就是不肯返回 JSON，
     * 而它写出来的话往往是可用的 —— 扔掉它等于白白失败一次。
     */
    private fun looksLikeProse(text: String): Boolean {
        val structural = text.count { it == '{' || it == '}' || it == ':' }
        return text.length in 4..800 &&
            !text.contains("\"skip\"") &&
            !text.contains("thought_id") &&
            structural < 3
    }
}
