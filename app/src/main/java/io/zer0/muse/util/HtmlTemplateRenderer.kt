package io.zer0.muse.util

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * v1.0.53: HTML 报告模板渲染器(对标 Beautify 整页 HTML 美学规范)。
 *
 * 模板位于 assets/html_templates/report_template.html,占位符:
 *  - {{title}}          报告标题
 *  - {{subtitle_html}}  副标题(可空)
 *  - {{cover_html}}     封面图 HTML(可空)
 *  - {{date}}           日期
 *  - {{sections_html}}  章节 HTML(markdown 转 HTML 后拼接)
 *
 * 模板内置 Muse 美术语言(暖纸底/月桂绿/28px 圆角/衬线标题),
 * 并带 prefers-color-scheme 暗色变量。
 */
class HtmlTemplateRenderer(private val context: Context) {

    companion object {
        private const val TAG = "HtmlTemplateRenderer"
        private const val TEMPLATE_ASSET = "html_templates/report_template.html"
    }

    /** 渲染报告页。 */
    fun render(
        title: String,
        subtitle: String? = null,
        coverImageUrl: String? = null,
        sections: List<Section>,
        date: String = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
    ): String {
        val template = loadTemplate() ?: return ""
        var html = template
        html = html.replace("{{title}}", escapeHtml(title))
        html = html.replace("{{subtitle_html}}", subtitle?.let { "<p class=\"subtitle\">${escapeHtml(it)}</p>" } ?: "")
        html = html.replace(
            "{{cover_html}}",
            coverImageUrl?.let { "<img class=\"cover\" src=\"${escapeHtml(it)}\" alt=\"cover\">" } ?: "",
        )
        html = html.replace("{{date}}", escapeHtml(date))
        html = html.replace("{{sections_html}}", sections.joinToString("\n") { section ->
            buildString {
                append("<section>")
                if (section.index > 0) append("<div class=\"section-label\">0${section.index}</div>")
                append("<h2>").append(escapeHtml(section.title)).append("</h2>")
                append(markdownToHtml(section.markdown))
                append("</section>")
            }
        })
        return html
    }

    /** 报告章节。 */
    data class Section(
        val index: Int,
        val title: String,
        /** Markdown 文本(渲染为 HTML 段落/列表/表格)。 */
        val markdown: String,
    )

    private fun loadTemplate(): String = resultOf {
        context.assets.open(TEMPLATE_ASSET).bufferedReader().use { it.readText() }
    }.onError { msg, t ->
        Logger.w(TAG, "报告模板加载失败: $msg", t)
    }.getOrNull() ?: ""

    /**
     * 轻量 markdown → HTML(标题/列表/表格/代码块/粗斜体)。
     * 不引入完整 markdown 引擎(报告场景够用)。
     */
    private fun markdownToHtml(md: String): String {
        val sb = StringBuilder()
        var inList = false
        var inCode = false
        val codeLines = mutableListOf<String>()

        fun closeList() {
            if (inList) { sb.append("</ul>"); inList = false }
        }

        for (rawLine in md.lines()) {
            val line = rawLine.trimEnd()
            // 代码块
            if (line.startsWith("```")) {
                if (inCode) {
                    sb.append("<pre>").append(codeLines.joinToString("\n").let { escapeHtml(it) }).append("</pre>")
                    codeLines.clear()
                    inCode = false
                } else {
                    closeList()
                    inCode = true
                }
                continue
            }
            if (inCode) { codeLines.add(line); continue }
            if (line.isBlank()) { closeList(); continue }
            when {
                line.startsWith("### ") -> { closeList(); sb.append("<h3>").append(inlineHtml(line.removePrefix("### "))).append("</h3>") }
                line.startsWith("## ") -> { closeList(); sb.append("<h2>").append(inlineHtml(line.removePrefix("## "))).append("</h2>") }
                line.startsWith("# ") -> { closeList(); sb.append("<h2>").append(inlineHtml(line.removePrefix("# "))).append("</h2>") }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    if (!inList) { sb.append("<ul>"); inList = true }
                    sb.append("<li>").append(inlineHtml(line.drop(2))).append("</li>")
                }
                line.startsWith("|") -> sb.append(parseTableRow(line))
                else -> { closeList(); sb.append("<p>").append(inlineHtml(line)).append("</p>") }
            }
        }
        closeList()
        return sb.toString()
    }

    /** 行内 markdown:粗体/斜体/行内代码/链接。 */
    private fun inlineHtml(text: String): String {
        var t = escapeHtml(text)
        t = t.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        t = t.replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
        t = t.replace(Regex("`(.+?)`"), "<code>$1</code>")
        t = t.replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)"), "<a href=\"$2\">$1</a>")
        return t
    }

    /** 表格行:收集行后合并渲染(简单实现:表格按连续 | 行分组)。 */
    private var tableBuffer = mutableListOf<List<String>>()
    private fun parseTableRow(line: String): String {
        val cells = line.trim().trim('|').split('|').map { inlineHtml(it.trim()) }
        tableBuffer.add(cells)
        // 分隔行(| --- |)跳过;收集满 3 行(表头+分隔+首行)前不输出
        if (tableBuffer.size >= 2 && tableBuffer[1].all { it.contains("---") || it.isEmpty() }) {
            // 表头 + 数据开始
            return buildString {
                append("<table><tr>")
                tableBuffer[0].forEach { append("<th>").append(it).append("</th>") }
                append("</tr>")
            }
        }
        if (tableBuffer.size >= 2) {
            return buildString {
                append("<tr>")
                tableBuffer.last().forEach { append("<td>").append(it).append("</td>") }
                append("</tr>")
            }
        }
        return ""
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
