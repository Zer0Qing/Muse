package io.zer0.memory.state

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import io.zer0.common.Logger
import io.zer0.memory.format.RollingSummaryFormat

/**
 * 编译产物状态管理。
 *
 * 职责:
 *  - section body 规范化(去 <think> 块、去标题行、压多空行)
 *  - LLM 输出结果规范化(去围栏、去前置思考块)
 *  - reset 标记读写(记忆重置水印,过滤旧摘要)
 */
object CompiledMemoryState {

    /** 编译产物文件名常量。 */
    val COMPILED_FILES = listOf("memory.md", "facts.md", "today.md", "week.md", "longterm.md")

    private const val TAG: String = "CompiledMemoryState"

    // 2026-11-01 性能整改:这些正则原先在函数体里现场构造。
    // normalizeSectionBody 每次调用要新建 3 个、stripLeadingHeadings 1 个、
    // normalizeLlmOutput 3 个；而 readCompiledMemoryMarkdown 一次读会调
    // normalizeSectionBody 四次（facts/today/week/longterm），叠加起来是每读一次
    // 记忆就重新编译十几个 Pattern。全部提成常量后只编译一次。
    private val JSON_STRING_ARRAY_RE =
        Regex("""^\s*\[\s*("(?:[^"\\]|\\.)*"(?:\s*,\s*"(?:[^"\\]|\\.)*")*)\s*]\s*$""", RegexOption.MULTILINE)
    private val MANY_BLANK_LINES_RE = Regex("\n{3,}")
    private val LINE_BREAK_RE = Regex("\\r?\\n")
    private val FENCED_BLOCK_RE = Regex("""^\s*```(?:markdown|md|text)?\s*\n([\s\S]*?)\n```\s*$""")
    private val THINK_BLOCK_RE = Regex("<think(?:ing)?>[\\s\\S]*?</think(?:ing)?>", RegexOption.IGNORE_CASE)
    private val THINK_OPEN_TAIL_RE = Regex("<think(?:ing)?>[\\s\\S]*$", RegexOption.IGNORE_CASE)
    private val THINK_OPEN_RE = Regex("<think(?:ing)?>", RegexOption.IGNORE_CASE)
    private val THINK_CLOSE_RE = Regex("</think(?:ing)?>", RegexOption.IGNORE_CASE)

    /** 段标题词表：正文里出现这些就是"外层标题被复述了一遍"，剥掉。 */
    private val SECTION_TITLES: Set<String> =
        (RollingSummaryFormat.FACT_SECTION_TITLES + RollingSummaryFormat.TIMELINE_SECTION_TITLES)
            .map { title -> title.trim().lowercase() }
            .toSet()

    /** 到这个层级及以下的开头标题当作模型开场白，不是内容。 */
    private const val DOCUMENT_HEADING_MAX_LEVEL: Int = 2

    /**
     * 规范化 section body:
     *  - 去除 <think>...</think> 块(部分模型输出思考标签)
     *  - 去除开头的前置 markdown 标题行(避免 LLM 输出 # 标题污染 assemble)
     *    注意: 只去掉 body 开头的连续标题,保留正文中的子标题结构
     *    (例如 facts 段内的 ### Work / ### Personal 子分类)
     *  - 压缩多空行为单空行
     *  - JSON 字符串数组 ["a","b"] 转 bullet list
     */
    fun normalizeSectionBody(value: String): String {
        if (value.isEmpty()) return ""
        var s = value
        s = stripThinkTagBlocks(s)
        // 只去掉 body 开头的连续标题行(前置标题),保留正文中的子标题结构。
        // 遇到第一个非标题、非空行即停止剥离,后续内容原样保留。
        s = stripLeadingHeadings(s)
        // JSON 字符串数组 → bullet list (["a","b"] → "- a\n- b")
        val jsonArrMatch = JSON_STRING_ARRAY_RE.find(s)
        if (jsonArrMatch != null) {
            // 必须走真正的 JSON 解码：旧实现只把外层引号 trim 掉，
            // 数组里 `"喜欢\"美式\""` 会变成 `喜欢\"美式\"` —— 反斜杠被原样带进记忆，
            // 用户在记忆页看到的就是这串转义符，注入给模型的上下文里也带着。
            val items = runCatching {
                Json.decodeFromString(ListSerializer(String.serializer()), jsonArrMatch.value)
            }.getOrNull().orEmpty()
            if (items.isNotEmpty()) {
                s = items.joinToString("\n") { item -> "- ${item.trim()}" }
            }
        }
        // 压缩 3+ 换行为 2 换行
        s = s.replace(MANY_BLANK_LINES_RE, "\n\n")
        return s.trim()
    }

    /**
     * 剥离开头的连续标题行(允许标题行之间有空行)。
     *
     * **只剥两类**，其余标题当正文留着：
     *
     *  - 命中段标题本身（`重要事实` / `Key Facts` / `事情经过` / `Timeline`）——
     *    模型常把外层标题再复述一遍，而它在提取时已经被消费过一次，留着会在装配时重复；
     *  - 1~2 级的文档级前言（`# 摘要`、`## Overview`）—— 那不是内容，是模型的开场白。
     *
     * 为什么不能"开头标题一律剥掉"：本层注释里自己举的例子就是
     * facts 段内的 `### Work` / `### Personal` 子分类 —— 正文以子分类开头时，
     * 一律剥会把**分类名丢掉**，事实被摊平成一大片，用户看不出哪条属于哪类。
     * 那是内容损失，比留着一条前言标题严重得多。
     */
    private fun stripLeadingHeadings(value: String): String {
        val lines = value.split(LINE_BREAK_RE)
        var idx = 0
        while (idx < lines.size) {
            val line = lines[idx]
            if (line.isBlank()) {
                idx++
                continue
            }
            val heading = RollingSummaryFormat.parseMarkdownHeading(line)
            if (heading != null && isStrippableHeading(heading)) {
                idx++
                continue
            }
            break
        }
        return lines.drop(idx).joinToString("\n")
    }

    private fun isStrippableHeading(heading: RollingSummaryFormat.Heading): Boolean =
        heading.level <= DOCUMENT_HEADING_MAX_LEVEL ||
            heading.title.trim().lowercase() in SECTION_TITLES

    /**
     * 规范化 LLM 输出:
     *  - 去除 ```markdown / ``` 围栏
     *  - 去除前置 <think> 块
     *  - trim
     *
     * [source] 是调用方给的环节名（`compileFacts` / `compileToday` …）。
     * 去掉围栏或思考块**说明模型没守提示词的输出契约**，属于要能查到的事：
     * 记忆"看着怪"时，这一行能直接定位是哪一步、哪个环节产出的。
     */
    fun normalizeLlmResult(value: String, source: String = ""): String {
        if (value.isEmpty()) return ""
        var s = value
        // 去 ```markdown\n...\n``` 围栏
        val fenceMatch = FENCED_BLOCK_RE.find(s)
        if (fenceMatch != null) {
            logContractBreak(source, "markdown 围栏")
            s = fenceMatch.groupValues[1]
        }
        val withoutThink = stripThinkTagBlocks(s)
        if (withoutThink != s) {
            logContractBreak(source, "think 标签")
        }
        s = withoutThink
        return s.trim()
    }

    private fun logContractBreak(source: String, what: String) {
        Logger.w(TAG, "$source 的产出带了$what，不符合输出契约，已剥除后继续")
    }

    /** 去除 <think>...</think> / <thinking>...</thinking> 块。 */
    fun stripThinkTagBlocks(value: String): String {
        if (value.isEmpty()) return ""
        var s = value
        // 闭合块
        s = s.replace(THINK_BLOCK_RE, "")
        // 残留的开头未闭合块(部分模型只输出 <think> 开头)
        s = s.replace(THINK_OPEN_TAIL_RE, "")
        return s
    }

    /** 检测是否有孤立的 <think> 开头标签(无闭合)。 */
    fun hasDanglingLeadingThinkTag(value: String): Boolean = THINK_OPEN_RE.containsMatchIn(value) &&
        !THINK_CLOSE_RE.containsMatchIn(value)
}
