package io.zer0.muse.ui.chat

private const val QUOTE_PREFIX = "> "

/**
 * 从 content 开头解析引用块。
 *
 * 返回 Pair<引用内容(去前缀), 正文>。若无引用前缀则返回 null to content。
 */
fun parseQuotedContent(content: String): Pair<String?, String> {
    val lines = content.lines()
    if (lines.isEmpty()) return null to content
    val quoteLines = mutableListOf<String>()
    var i = 0
    while (i < lines.size && lines[i].startsWith(QUOTE_PREFIX)) {
        quoteLines.add(lines[i].removePrefix(QUOTE_PREFIX))
        i++
    }
    // 跳过引用后的空行
    while (i < lines.size && lines[i].isBlank()) {
        i++
    }
    if (quoteLines.isEmpty()) return null to content
    val quote = quoteLines.joinToString("\n")
    val body = lines.drop(i).joinToString("\n")
    return quote to body
}

/**
 * 把引用内容与正文拼接为持久化格式。
 *
 * 格式:每行引用内容前加 "> ",后接空行与正文。
 */
fun buildQuotedContent(quote: String, body: String): String {
    val trimmedQuote = quote.trim()
    val trimmedBody = body.trim()
    return if (trimmedQuote.isEmpty()) {
        trimmedBody
    } else {
        trimmedQuote.lines().joinToString("\n") { "> $it" } + "\n\n" + trimmedBody
    }
}
