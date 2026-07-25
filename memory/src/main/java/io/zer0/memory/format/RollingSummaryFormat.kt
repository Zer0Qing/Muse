package io.zer0.memory.format

// NOTE-i18n: section 标题作为 markdown 解析契约参与 LLM 输出匹配,需契约与文案分离架构改动后才能提取。

/**
 * Rolling summary 格式契约单一源头 (openhanako rolling-summary-format.ts 移植)。
 *
 * 摘要必须包含两个标题段: 重要事实(Key Facts) + 事情经过(Timeline)。
 * 这是 compileFacts 提取事实的契约基础 —— 任何破坏该结构的摘要都视为格式错误。
 *
 * 使用方:
 *  - [io.zer0.memory.summary.SessionSummaryManager](产出摘要)
 *  - [io.zer0.memory.compile.MemoryCompiler](消费 facts 段)
 *  - [io.zer0.memory.prompt.RollingSummaryPrompt](prompt 模板)
 */
object RollingSummaryFormat {

    /** facts 段标题(任意 1-6 级,大小写不敏感)。下标 0=中文, 1=英文。 */
    val FACT_SECTION_TITLES = listOf("重要事实", "Key Facts")

    /** timeline 段标题。下标 0=中文, 1=英文。 */
    val TIMELINE_SECTION_TITLES = listOf("事情经过", "Timeline")

    /** 格式修复最大重试次数(超过则抛错,由调用方走失败通道)。 */
    const val MAX_REPAIRS = 1

    private fun isZh(locale: String): Boolean = locale.startsWith("zh")

    /** 按 locale 取 facts 段标题文本。 */
    fun getFactSectionTitle(locale: String = "zh-CN"): String =
        if (isZh(locale)) FACT_SECTION_TITLES[0] else FACT_SECTION_TITLES[1]

    /** 按 locale 取 timeline 段标题文本。 */
    fun getTimelineSectionTitle(locale: String = "zh-CN"): String =
        if (isZh(locale)) TIMELINE_SECTION_TITLES[0] else TIMELINE_SECTION_TITLES[1]

    /** prompt 中的"输出格式要求"文案块。 */
    fun buildFormatRequirements(locale: String = "zh-CN"): String {
        if (!isZh(locale)) {
            return """
## Output Format
The final answer must contain exactly two third-level headings, with fixed text and order:
1. The first line must be `### Key Facts`
2. The second heading must be `### Timeline`

The body under both headings must use unordered lists. Each list item must start with `- `.
If a section has no content, output one list item: `- None`.
Do not output any preamble, conclusion, XML tags, or code fences outside those headings.
            """.trimIndent()
        }
        return """
## 输出格式
最终答案必须只包含两个三级标题,标题文本和顺序固定:
1. 第一行必须是 `### 重要事实`
2. 第二个标题必须是 `### 事情经过`

两个标题下的正文都必须使用无序列表。列表项必须以 `- ` 开头。
如果某一节没有内容,也要输出一个列表项:`- 无`。
标题之外不要输出前言、后记、XML 标签或代码块。
        """.trimIndent()
    }

    /** 格式修复调用的稳定 system 指令。 */
    fun buildRepairPrompt(locale: String = "zh-CN"): String {
        val requirements = buildFormatRequirements(locale)
        if (!isZh(locale)) {
            return """
You are the format repairer for the memory system's rolling summaries. The previous summary draft violates the required fixed structure and cannot be parsed by the memory system. Rearrange the information in the given draft into the required structure: do not add, remove, or rewrite any factual content, do not explain, and output only the full repaired summary.

$requirements
            """.trimIndent()
        }
        return """
你是记忆系统滚动摘要的格式修复器。上一步生成的摘要草稿不符合要求的固定结构,记忆系统无法解析。请把给定草稿中的信息原样重排进规定结构:不要新增、删除或改写事实内容,不要解释,直接输出修复后的摘要全文。

$requirements
        """.trimIndent()
    }

    /** 格式修复调用的动态输入: 失败原因 + 待修复草稿。 */
    fun buildRepairInput(
        locale: String = "zh-CN",
        issues: List<String> = emptyList(),
        summaryText: String = "",
    ): String {
        val zh = isZh(locale)
        val issuesLabel = if (zh) "## 校验失败原因" else "## Validation Failures"
        val draftLabel = if (zh) "## 待修复草稿" else "## Draft To Repair"
        val issueLines = issues
            .map { "- ${it.trim()}" }
            .filter { it != "- " }
            .joinToString("\n")
            .ifEmpty { if (zh) "- 未知" else "- unknown" }

        return "$issuesLabel\n\n$issueLines\n\n$draftLabel\n\n<draft-summary>\n$summaryText\n</draft-summary>"
    }

    /** 解析 markdown 标题行(1-6 级)。 */
    data class Heading(val level: Int, val title: String)

    fun parseMarkdownHeading(line: String): Heading? {
        val match = Regex("^(#{1,6})[ \\t]+(.+?)[ \\t]*$").find(line) ?: return null
        val title = match.groupValues[2].replace(Regex("[ \\t]+#+[ \\t]*$"), "").trim()
        return Heading(match.groupValues[1].length, title)
    }

    private fun norm(title: String): String = title.trim().lowercase()

    /** 提取 markdown 中第一个命中标题段的正文(到下一个同级或更高级标题为止)。 */
    fun extractMarkdownSection(markdown: String, titles: List<String>): String {
        if (markdown.isEmpty()) return ""
        val wanted = titles.map(::norm).toHashSet()
        val lines = markdown.split(Regex("\\r?\\n"))

        for (i in lines.indices) {
            val heading = parseMarkdownHeading(lines[i]) ?: continue
            if (norm(heading.title) !in wanted) continue

            val body = mutableListOf<String>()
            for (j in (i + 1) until lines.size) {
                val next = parseMarkdownHeading(lines[j])
                if (next != null && next.level <= heading.level) break
                body.add(lines[j])
            }
            return body.joinToString("\n").trim()
        }
        return ""
    }

    /** 摘要里是否存在 facts 段标题(不要求正文非空)。 */
    fun hasFactSectionHeading(markdown: String): Boolean {
        if (markdown.isEmpty()) return false
        val wanted = FACT_SECTION_TITLES.map(::norm).toHashSet()
        for (line in markdown.split(Regex("\\r?\\n"))) {
            val heading = parseMarkdownHeading(line) ?: continue
            if (norm(heading.title) in wanted) return true
        }
        return false
    }

    /** 提取摘要中的 facts 段正文。 */
    fun extractFactSection(markdown: String): String =
        extractMarkdownSection(markdown, FACT_SECTION_TITLES)

    /** facts 段正文是否是显式空标记(- 无 / - None)。 */
    fun isEmptyFactSection(text: String): Boolean {
        val lines = text.split(Regex("\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return true
        return lines.all { line ->
            val itemText = line.replace(Regex("^[-*+][ \\t]+"), "").trim().lowercase()
            itemText == "无" || itemText == "none"
        }
    }

    private fun findHeading(lines: List<String>, titles: List<String>): Pair<Int, Int>? {
        val wanted = titles.map(::norm).toHashSet()
        for (i in lines.indices) {
            val heading = parseMarkdownHeading(lines[i]) ?: continue
            if (norm(heading.title) in wanted) return i to heading.level
        }
        return null
    }

    data class ValidationResult(val ok: Boolean, val issues: List<String>)

    /**
     * 写入前结构校验。拦截四类破坏 compileFacts 提取假设的问题:
     *  1. 缺 facts 段标题
     *  2. 缺 timeline 段标题
     *  3. timeline 标题比 facts 标题层级更深且在其后(facts 段收不了尾)
     *  4. facts 段正文为空(契约要求空时显式写 - 无 / - None)
     */
    fun validate(text: String): ValidationResult {
        val issues = mutableListOf<String>()
        val lines = text.split(Regex("\\r?\\n"))

        val fact = findHeading(lines, FACT_SECTION_TITLES)
        val timeline = findHeading(lines, TIMELINE_SECTION_TITLES)

        if (fact == null) issues += """missing fact section heading ("### 重要事实" / "### Key Facts")"""
        if (timeline == null) issues += """missing timeline section heading ("### 事情经过" / "### Timeline")"""
        if (fact != null && timeline != null && timeline.first > fact.first && timeline.second > fact.second) {
            issues += "timeline heading is nested deeper than the fact heading, so the fact section cannot be delimited"
        }
        if (fact != null) {
            val body = extractFactSection(text)
            if (body.isEmpty()) {
                issues += """fact section body is empty; write "- 无" / "- None" when there are no facts"""
            }
        }
        return ValidationResult(issues.isEmpty(), issues)
    }
}
