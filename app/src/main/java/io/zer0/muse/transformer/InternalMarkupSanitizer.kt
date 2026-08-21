package io.zer0.muse.transformer

/**
 * 用户不可见的内部标记清洗器。
 *
 * 模型有时会把 mood / mod / think / reflection 直接写进 content，尤其是
 * 中转模型或流式输出被截断时。内部字段负责承载这些内容，正文、会话预览和
 * 标题生成只能使用本清洗器后的文本。
 */
object InternalMarkupSanitizer {
    private val closedInternalBlock = Regex(
        "(?is)(?:<(?:(?:mood|mod|think|thinking|reflection|moodfx))>|\\[(?:(?:mood|mod|think|thinking|reflection|moodfx))\\])" +
            "[\\s\\S]*?" +
            "(?:</(?:(?:mood|mod|think|thinking|reflection|moodfx))>|\\[/(?:(?:mood|mod|think|thinking|reflection|moodfx))\\])",
    )
    private val unclosedInternalStart = Regex(
        "(?is)(?:<(?:(?:mood|mod|think|thinking|reflection|moodfx))>|\\[(?:(?:mood|mod|think|thinking|reflection|moodfx))\\]).*$",
    )
    private val moodBlock = Regex(
        "(?is)(?:<(?:mood|mod)>|\\[(?:mood|mod)\\])([\\s\\S]*?)(?:</(?:mood|mod)>|\\[/(?:mood|mod)\\])",
    )

    /** 只移除内部标签外壳，保留其中的思考内容，供 reasoning 面板使用。 */
    fun stripContainerTags(text: String): String = text
        .replace(Regex("(?i)</?(?:mood|mod|think|thinking|reflection|moodfx)>"), "")
        .replace(Regex("(?i)\\[/?(?:mood|mod|think|thinking|reflection|moodfx)\\]"), "")
        .trim()

    /** 返回可以直接展示给用户的正文。 */
    fun stripForDisplay(text: String): String {
        if (text.isBlank()) return ""
        var result = closedInternalBlock.replace(text, "")
        // 未闭合的内部块从标签处截断，避免把思考内容当正文展示。
        result = unclosedInternalStart.replace(result, "")
        return result.replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /** 仅提取 mood/mod 内容，兼容历史消息和不同模型的标签习惯。 */
    fun extractMood(text: String): String? = moodBlock.findAll(text)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .trim()
        .ifBlank { null }
}
