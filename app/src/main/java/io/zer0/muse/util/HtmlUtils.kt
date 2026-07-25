package io.zer0.muse.util

import android.os.Build
import android.text.Html

/**
 * v1.131: 统一 HTML → 纯文本剥离工具。
 *
 * 原先 HTML 剥离逻辑散落在 3 个文件,实现策略各异:
 *  - web/WebSearchService.kt#stripHtml — 纯 Regex + 手工实体解码
 *  - doc/DocumentParser.kt#stripHtmlTags — XmlPullParser 优先 + HtmlCompat 回退(保留独立实现,因有换行处理需求)
 *  - tools/SkillExecutor.kt#htmlToText — 移除 script/style 块 + Regex + Html.fromHtml 解实体
 *
 * 此工具提供两个层次的剥离:
 *  - [stripHtmlSimple]: 仅 Regex 剥标签 + 常用实体解码,适合搜索摘要等轻量场景
 *  - [stripHtmlComprehensive]: 含 script/style 块移除 + HtmlCompat 解实体,适合文档解析场景
 *
 * 注:RichContentCard.sanitizeHtml 不属于剥离,是保留 HTML 但移除危险标签,不在统一范围。
 */

private val HTML_TAG_REGEX = Regex("<[^>]+>")

/** 移除 script/style/noscript 块(含内容,跨行,忽略大小写)。 */
private val SCRIPT_STYLE_BLOCK_REGEX = Regex(
    """(?is)<(script|style|noscript)[^>]*>.*?</\1>""",
    RegexOption.IGNORE_CASE,
)

/**
 * 简易 HTML → 纯文本:
 *  1. `Regex("<[^>]+>")` 剥所有标签
 *  2. 手工解码 6 个常用实体(&amp; &lt; &gt; &quot; &#39; &nbsp;)
 *  3. trim 首尾空白
 *
 * 适用:搜索摘要、snippet 等不需要保留换行结构的轻量场景。
 */
fun stripHtmlSimple(s: String): String {
    return s.replace(HTML_TAG_REGEX, "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .trim()
}

/**
 * 完整 HTML → 纯文本:
 *  1. 移除 script/style/noscript 块(含内容)
 *  2. `Regex("<[^>]+>")` 剥所有标签
 *  3. 用 [Html.fromHtml] 解所有 HTML 实体(包括数字实体 `&#160;` 等)
 *
 * 适用:文档解析、富文本内容等需要完整实体解码的场景。
 * 性能:比 [stripHtmlSimple] 略慢(Html.fromHtml 调用开销),但实体覆盖完整。
 */
fun stripHtmlComprehensive(html: String): String {
    val s = html.replace(SCRIPT_STYLE_BLOCK_REGEX, " ")
        .replace(HTML_TAG_REGEX, " ")
    @Suppress("DEPRECATION")
    val spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT)
    } else {
        Html.fromHtml(s)
    }
    return spanned.toString().trim()
}
