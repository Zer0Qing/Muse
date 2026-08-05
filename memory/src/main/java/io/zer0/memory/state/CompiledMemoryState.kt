package io.zer0.memory.state

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
        val jsonArrMatch = Regex("""^\s*\[\s*("(?:[^"\\]|\\.)*"(?:\s*,\s*"(?:[^"\\]|\\.)*")*)\s*]\s*$""", RegexOption.MULTILINE).find(s)
        if (jsonArrMatch != null) {
            val items = Regex(""""(?:[^"\\]|\\.)*"""").findAll(jsonArrMatch.groupValues[1])
                .map { it.value.trim('"') }
                .toList()
            if (items.isNotEmpty()) {
                s = items.joinToString("\n") { "- $it" }
            }
        }
        // 压缩 3+ 换行为 2 换行
        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s.trim()
    }

    /**
     * 剥离开头的连续标题行(允许标题行之间有空行)。
     * 遇到第一个非标题、非空行即停止,后续内容(含子标题)原样保留。
     */
    private fun stripLeadingHeadings(value: String): String {
        val lines = value.split(Regex("\\r?\\n"))
        var idx = 0
        // 跳过开头的空行 + 标题行
        while (idx < lines.size) {
            val line = lines[idx]
            if (line.isBlank()) {
                idx++
                continue
            }
            if (RollingSummaryFormat.parseMarkdownHeading(line) != null) {
                idx++
                continue
            }
            break
        }
        return lines.drop(idx).joinToString("\n")
    }

    /**
     * 规范化 LLM 输出:
     *  - 去除 ```markdown / ``` 围栏
     *  - 去除前置 <think> 块
     *  - trim
     */
    fun normalizeLlmResult(value: String, source: String = ""): String {
        if (value.isEmpty()) return ""
        var s = value
        // 去 ```markdown\n...\n``` 围栏
        val fenceMatch = Regex("""^\s*```(?:markdown|md|text)?\s*\n([\s\S]*?)\n```\s*$""").find(s)
        if (fenceMatch != null) s = fenceMatch.groupValues[1]
        s = stripThinkTagBlocks(s)
        return s.trim()
    }

    /** 去除 <think>...</think> / <thinking>...</thinking> 块。 */
    fun stripThinkTagBlocks(value: String): String {
        if (value.isEmpty()) return ""
        var s = value
        // 闭合块
        s = s.replace(Regex("<think(?:ing)?>[\\s\\S]*?</think(?:ing)?>", RegexOption.IGNORE_CASE), "")
        // 残留的开头未闭合块(部分模型只输出 <think> 开头)
        s = s.replace(Regex("<think(?:ing)?>[\\s\\S]*$", RegexOption.IGNORE_CASE), "")
        return s
    }

    /** 检测是否有孤立的 <think> 开头标签(无闭合)。 */
    fun hasDanglingLeadingThinkTag(value: String): Boolean =
        Regex("<think(?:ing)?>", RegexOption.IGNORE_CASE).containsMatchIn(value) &&
            !Regex("</think(?:ing)?>", RegexOption.IGNORE_CASE).containsMatchIn(value)
}
