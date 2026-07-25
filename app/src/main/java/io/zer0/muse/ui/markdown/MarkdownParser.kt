package io.zer0.muse.ui.markdown

/**
 * Markdown 块级元素 AST(轻量自实现,不引入第三方库)。
 *
 * 支持的块级类型:
 *  - [MarkdownBlock.Paragraph]: 段落(含行内格式:粗体/斜体/行内代码/链接)
 *  - [MarkdownBlock.Heading]: 标题 # ## ###
 *  - [MarkdownBlock.CodeBlock]: 围栏代码块 ```...```
 *  - [MarkdownBlock.ListItem]: 列表项(有序 1. / 无序 - 或 *)
 *  - [MarkdownBlock.Quote]: 引用 >
 *  - [MarkdownBlock.Divider]: 水平线 ---
 *  - [MarkdownBlock.Blank]: 空行(用于段落间距)
 *  - [MarkdownBlock.Formula]: Phase 8.6 LaTeX 公式块 $$...$$
 *  - [MarkdownBlock.Table]: v1.97 Markdown 表格 | a | b |
 */
sealed class MarkdownBlock {
    /** 段落(单行或多行文本,行内格式由 [parseInline] 处理)。 */
    data class Paragraph(val text: String) : MarkdownBlock()

    /** 标题。level 1-3 对应 #/##/###。 */
    data class Heading(val level: Int, val text: String) : MarkdownBlock()

    /** 围栏代码块。language 可空(无语言标注)。 */
    data class CodeBlock(val language: String?, val code: String) : MarkdownBlock()

    /** 列表项。ordered=true 有序,index 为序号;false 无序。 */
    data class ListItem(val ordered: Boolean, val index: Int, val text: String) : MarkdownBlock()

    /** 引用块。text 已去掉 > 前缀。 */
    data class Quote(val text: String) : MarkdownBlock()

    /** Phase 8.6: LaTeX 公式块。latex 是 $$ ... $$ 之间的内容(不含定界符)。 */
    data class Formula(val latex: String) : MarkdownBlock()

    /** v1.97: Markdown 表格。headers 为表头行,rows 为数据行。 */
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock()

    /** 水平分隔线。 */
    object Divider : MarkdownBlock()

    /** 空行(段落间距)。 */
    object Blank : MarkdownBlock()
}

// H-MD2 修复: 所有正则提升为文件级 private val,避免 parseMarkdown 每行重复编译
private val FENCE_REGEX = Regex("^```(.*)$")
private val HEADING_REGEX = Regex("^(#{1,3})\\s+(.+)$")
private val ORDERED_LIST_REGEX = Regex("^(\\d+)\\.\\s+(.+)$")
private val UNORDERED_LIST_REGEX = Regex("^[-*]\\s+(.+)$")
// v1.97: 图片语法正则 — ![](url) 和 ![alt](url),图片已通过 displayImageUris 渲染,文本中跳过
private val IMAGE_SYNTAX_REGEX = Regex("!\\[[^\\]]*\\]\\([^)]+\\)")

/** v1.97: 解析表格行,返回各单元格内容;非表格行返回 null。 */
private fun parseTableRow(line: String): List<String>? {
    val trimmed = line.trim()
    if (!trimmed.contains("|")) return null
    val content = trimmed.trimStart('|').trimEnd('|')
    if (content.isBlank()) return null
    return content.split("|").map { it.trim() }
}

/** v1.97: 判断是否为表格分隔线行(|---|---| 或 |:---:|---:|)。 */
private fun isTableSeparator(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.contains("|")) return false
    val content = trimmed.trimStart('|').trimEnd('|')
    if (content.isBlank()) return false
    return content.split("|").all { cell ->
        val c = cell.trim()
        c.isNotEmpty() && c.all { it == '-' || it == ':' }
    }
}

/**
 * 解析 Markdown 文本为块级元素列表。
 *
 * 策略: 逐行扫描,识别围栏代码块(```...```)优先级最高,
 *        其余按行首符号判定类型,空行保留为 Blank 做段落分隔。
 *
 * Phase 8.6: 支持 $$...$$ 公式块(单行或多行,以 $$ 为开闭定界符)。
 *
 * 不支持的语法(表格/脚注/图片)按段落原样输出。
 */
fun parseMarkdown(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    // L10 修复: 兼容 Windows 换行(\r\n)与旧 Mac 换行(\r),原先 split("\n") 会把 \r 留在行尾污染代码块
    val lines = text.split(Regex("\\r?\\n"))
    var i = 0
    var orderedIndex = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // ── 围栏代码块 ────────────────────────────────────────────────
        val fenceMatch = FENCE_REGEX.matchEntire(trimmed)
        if (fenceMatch != null) {
            val language = fenceMatch.groupValues[1].trim().ifBlank { null }
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            // i 指向闭合 ```(若存在),跳过
            if (i < lines.size) i++
            blocks.add(MarkdownBlock.CodeBlock(language, codeLines.joinToString("\n")))
            orderedIndex = 0
            continue
        }

        // ── Phase 8.6: 公式块 $$...$$ ─────────────────────────────────
        // 单行公式: $$ E = mc^2 $$ 在同一行
        // 多行公式: $$ 开始,后续行直到 $$ 结束
        // M2 修复: 公式块开始判定更严格,避免 $$ 前缀的普通文本(如 "$$ 价格"、"$100")被误识别为公式。
        // 判定逻辑: 仅当整行恰好为 "$$",或同行闭合(以 $$ 开头且以 $$ 结尾且长度>4,如 "$$ E=mc^2 $$")
        // 时才进入公式块;单纯以 $$ 开头的普通文本按段落处理,不再吞掉后续段落。
        val isFormulaBlock = trimmed == "$$" ||
            (trimmed.startsWith("$$") && trimmed.endsWith("$$") && trimmed.length > 4)
        if (isFormulaBlock) {
            val afterOpen = trimmed.removePrefix("$$")
            // 同行闭合: $$ E = mc^2 $$
            if (afterOpen.contains("$$")) {
                val latex = afterOpen.substringBefore("$$").trim()
                if (latex.isNotEmpty()) {
                    blocks.add(MarkdownBlock.Formula(latex))
                    orderedIndex = 0
                    i++
                    continue
                }
            }
            // 多行: $$ 开始,直到下一个含 $$ 的行
            val formulaLines = mutableListOf<String>()
            // 行内 $$ 后的内容作为第一行(若非空)
            val firstLine = afterOpen.trim()
            if (firstLine.isNotEmpty()) formulaLines.add(firstLine)
            i++
            var closed = false
            while (i < lines.size) {
                val fl = lines[i]
                if (fl.trim().contains("$$")) {
                    val beforeClose = fl.substringBefore("$$").trim()
                    if (beforeClose.isNotEmpty()) formulaLines.add(beforeClose)
                    closed = true
                    i++
                    break
                }
                formulaLines.add(fl)
                i++
            }
            if (formulaLines.isNotEmpty()) {
                blocks.add(MarkdownBlock.Formula(formulaLines.joinToString("\n").trim()))
            }
            if (!closed && i < lines.size) i++  // 容错:未闭合 $$ 也跳出
            orderedIndex = 0
            continue
        }

        // H-MD2 修复: 正则已提升为文件级 private val,此处直接引用
        val headingMatch = HEADING_REGEX.matchEntire(trimmed)
        val orderedListMatch = ORDERED_LIST_REGEX.matchEntire(trimmed)
        val unorderedListMatch = UNORDERED_LIST_REGEX.matchEntire(trimmed)

        // v1.97: 表格识别 — 当前行含 | 且下一行是分隔线行
        val tableHeaders = parseTableRow(line)
        if (tableHeaders != null && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            val rows = mutableListOf<List<String>>()
            i += 2
            while (i < lines.size) {
                val row = parseTableRow(lines[i])
                if (row != null && row.size == tableHeaders.size) {
                    rows.add(row)
                    i++
                } else {
                    break
                }
            }
            blocks.add(MarkdownBlock.Table(tableHeaders, rows))
            orderedIndex = 0
            continue
        }

        when {
            // ── 空行 ──────────────────────────────────────────────────
            trimmed.isEmpty() -> {
                blocks.add(MarkdownBlock.Blank)
                orderedIndex = 0
            }
            // ── 水平线 ────────────────────────────────────────────────
            trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                blocks.add(MarkdownBlock.Divider)
                orderedIndex = 0
            }
            // ── 标题 # ## ### ─────────────────────────────────────────
            headingMatch != null -> {
                val level = headingMatch.groupValues[1].length
                val content = headingMatch.groupValues[2].trim()
                blocks.add(MarkdownBlock.Heading(level, content))
                orderedIndex = 0
            }
            // ── 引用 > ────────────────────────────────────────────────
            // L-MD13 修复: 用 trimStart { it == '>' } 处理嵌套引用(>> >>> 等),
            // 原先 removePrefix(">") 只去一个 >,嵌套引用会残留 > 前缀
            trimmed.startsWith(">") -> {
                val content = trimmed.trimStart { it == '>' }.trim()
                blocks.add(MarkdownBlock.Quote(content))
                orderedIndex = 0
            }
            // ── 有序列表 1. 2. ────────────────────────────────────────
            orderedListMatch != null -> {
                val idx = orderedListMatch.groupValues[1].toIntOrNull() ?: (++orderedIndex)
                if (idx == 1) orderedIndex = 1 else orderedIndex = idx
                val content = orderedListMatch.groupValues[2].trim()
                blocks.add(MarkdownBlock.ListItem(ordered = true, index = idx, text = content))
            }
            // ── 无序列表 - 或 * ───────────────────────────────────────
            unorderedListMatch != null -> {
                val content = unorderedListMatch.groupValues[1].trim()
                blocks.add(MarkdownBlock.ListItem(ordered = false, index = 0, text = content))
                orderedIndex = 0
            }
            // ── 段落(默认) ──────────────────────────────────────────
            else -> {
                // v1.97: 跳过 markdown 图片语法 ![](url),图片已通过 displayImageUris 渲染
                val cleaned = IMAGE_SYNTAX_REGEX.replace(trimmed, "").trim()
                if (cleaned.isNotEmpty()) {
                    blocks.add(MarkdownBlock.Paragraph(cleaned))
                }
                orderedIndex = 0
            }
        }
        i++
    }

    return blocks
}
