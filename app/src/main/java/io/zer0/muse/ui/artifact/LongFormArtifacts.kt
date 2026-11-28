package io.zer0.muse.ui.artifact

import android.content.Context
import io.zer0.muse.R
import io.zer0.muse.data.artifact.ArtifactEntity

/**
 * 长文交付物的自动入库。
 *
 * 之前产物库只收两类东西：显式的 `<artifact>` 标签，以及 svg/html/chart/mermaid 围栏块。
 * 模型直接写一篇多级标题的长报告时两边都不占，产物库就一直是空的 —— 用户明明看到了
 * 一份成品，切到产物库却什么都没有。
 *
 * 这里只做「同步入库」，不改消息正文：正文已经完整显示在气泡里，再替换成占位符
 * 只会让阅读变差。入库的 id 以 `doc_` 开头，渲染侧据此跳过，避免同一条消息下面
 * 再挂一张重复的产物卡。
 */
object LongFormArtifacts {

    /** 低于这个长度不算「交付物」：短回复、寒暄、代码片段都不该进产物库。 */
    private const val MIN_DELIVERABLE_CHARS = 800

    /** 至少要有这么多级标题，才认定是结构化文档而不是一坨长段落。 */
    private const val MIN_HEADINGS = 2

    private const val MAX_TITLE_CHARS = 60

    private val HEADING_REGEX = Regex("""^\s{0,3}#{1,3}\s+(.+)$""", RegexOption.MULTILINE)

    /** 自动同步产物的 id 前缀；渲染侧按它排除重复展示。 */
    const val ID_PREFIX = "doc_"

    fun idFor(messageId: String): String = "$ID_PREFIX$messageId"

    /**
     * 从一段助手回复里抽出一份长文交付物。
     *
     * 已经是 `<artifact>` 抽取结果的内容（含 `[artifact:` 占位符）不再重复入库，
     * 否则同一段文字会在产物库里出现两次。
     */
    fun extract(sessionId: String, messageId: String, content: String, context: Context? = null): ArtifactEntity? {
        if (messageId.isBlank() || sessionId.isBlank()) return null
        val text = content.trim()
        if (text.length < MIN_DELIVERABLE_CHARS) return null
        if (text.contains("[artifact:")) return null
        val headings = HEADING_REGEX.findAll(text).map { it.groupValues[1].trim() }.toList()
        if (headings.size < MIN_HEADINGS) return null
        val title = headings.firstOrNull { it.isNotBlank() }
            ?.take(MAX_TITLE_CHARS)
            ?: context?.getString(R.string.artifact_default_title)
            ?: "Document"
        val now = System.currentTimeMillis()
        return ArtifactEntity(
            id = idFor(messageId),
            sessionId = sessionId,
            messageId = messageId,
            title = title,
            type = "markdown",
            content = text,
            language = "markdown",
            createdAt = now,
            updatedAt = now,
        )
    }
}
