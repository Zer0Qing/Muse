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

    /**
     * v1.0.90: 标签名标记检测（含半个标签与孤立闭标签）。
     *
     * 原来调用方只检测 `<mood>` 这种完整开标签，模型吐半个标签（`<mood`）或只吐闭标签
     * （`</mood>`）时会被当成普通正文直接上屏，用户就看到 mood 标签原文。
     * 凡是命中这个标记的内容，都必须走完整清洗路径。
     */
    val TAG_MARKER_REGEX =
        Regex("(?i)[<\\[]/?\\s*(?:mood|mod|think|thinking|reflection|moodfx)")

    /** 只移除内部标签外壳，保留其中的思考内容，供 reasoning 面板使用。 */
    fun stripContainerTags(text: String): String = text
        .replace(Regex("(?i)</?(?:mood|mod|think|thinking|reflection|moodfx)>"), "")
        .replace(Regex("(?i)\\[/?(?:mood|mod|think|thinking|reflection|moodfx)\\]"), "")
        .trim()

    /** 从推理通道移除完整 mood/mod 块,但保留其中其他 think 内容。 */
    fun stripMoodBlocks(text: String): String = moodBlock.replace(text, "").trim()

    /** 返回可以直接展示给用户的正文。 */
    fun stripForDisplay(text: String): String {
        if (text.isBlank()) return ""
        var result = closedInternalBlock.replace(text, "")
        // 未闭合的内部块从标签处截断，避免把思考内容当正文展示。
        result = unclosedInternalStart.replace(result, "")
        // v1.0.90: 上面两条只处理带开标签的情况。只吐半个标签（`<mood`）或孤立的闭标签
        // （`</mood>`、`</think>`）时会原样漏到正文，这里最后再扫一遍标签外壳。
        result = stripContainerTags(result)
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
