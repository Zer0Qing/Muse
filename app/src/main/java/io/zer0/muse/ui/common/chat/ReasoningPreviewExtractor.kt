package io.zer0.muse.ui.common.chat

/**
 * 确定性思考摘要提取器（纯本地计算，零额外模型调用）。
 *
 * 从流式或已完成的思考文本中抽取两类信息：
 * 1. [extractCurrentFocus]：当前正在推演的小节标题或最新一句焦点，用于标题栏实时副标；
 * 2. [extractLivePreviewWindow]：末尾 3–4 行左右的实时滚动思考区间，呈现思考推进节奏。
 */
object ReasoningPreviewExtractor {

    private val MARKDOWN_HEADING_RE = Regex("^#{1,6}\\s+(.+)$")
    private val BOLD_HEADING_RE = Regex("^\\*{2}([^*]{2,48})\\*{2}\\s*[:\uFF1A]?\\s*$")
    // 步骤前缀：第N步 / Step N / 1. —— 步骤词用 Unicode 转义，避免源码出现字面量中文。
    private val STEP_PREFIX_RE = Regex(
        "^(?:\u7b2c[\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u5341\u3007\u4e24\\d]+[\u6b65\u9636\u6bb5\u9879]|Step\\s*\\d+|\\d+[.)\u3001])\\s*[:\uFF1A]?\\s*(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val INLINE_MARKDOWN_NOISE_RE = Regex("[`*_~#<>\\[\\]]+")
    private val MULTI_SPACE_RE = Regex("\\s+")
    private val CLAUSE_SPLIT_RE = Regex("[\u3002\uFF01\uFF1F.!?\\n;\uFF1B]+")
    private val BLANK_LINE_RUN_RE = Regex("\\n{3,}")

    /**
     * 提取模型当前思考焦点（简短单行，最多 [maxChars] 字符）。
     *
     * 优先逆序命中模型常用的思考小节标题（`**...**` / `### ...` / `第一步：...`），
     * 若无显式小节标题，则回退到最新一句非空子句。
     */
    fun extractCurrentFocus(reasoning: String, maxChars: Int = 22): String {
        val trimmed = reasoning.trim()
        if (trimmed.isEmpty()) return ""

        val lines = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        // 1. 逆序扫描最近 12 行，优先取显式思考小节标题
        val scanStart = (lines.size - 12).coerceAtLeast(0)
        for (i in lines.lastIndex downTo scanStart) {
            val line = lines[i]
            val heading = MARKDOWN_HEADING_RE.matchEntire(line)?.groupValues?.getOrNull(1)
                ?: BOLD_HEADING_RE.matchEntire(line)?.groupValues?.getOrNull(1)
                ?: STEP_PREFIX_RE.matchEntire(line)?.groupValues?.getOrNull(1)
            val cleaned = cleanInline(heading ?: continue)
            if (cleaned.length >= 2) {
                return clampText(cleaned, maxChars)
            }
        }

        // 2. 兜底：取末尾有效短句（跳过过短碎片）
        val tailBlock = lines.takeLast(4).joinToString("\n")
        val clauses = CLAUSE_SPLIT_RE.split(tailBlock)
            .map { cleanInline(it) }
            .filter { it.length >= 4 }
        val chosen = clauses.lastOrNull() ?: cleanInline(lines.last())
        return clampText(chosen, maxChars)
    }

    /**
     * 提取用于折叠态实时滚动窗口的末尾思考片段（保留自然分段，压平连续空行）。
     */
    fun extractLivePreviewWindow(reasoning: String, maxChars: Int = 320): String {
        val trimmed = reasoning.trim()
        if (trimmed.isEmpty()) return ""
        val tail = if (trimmed.length > maxChars) {
            val cut = trimmed.takeLast(maxChars)
            // 尽量从第一个换行或空格处开始，避免开头被切在半个词上
            val boundary = cut.indexOfFirst {
                it == '\n' || it == ' ' || it == '\uFF0C' || it == '\u3002'
            }
            val aligned = if (boundary in 1..24) cut.substring(boundary + 1) else cut
            "\u2026" + aligned.trimStart()
        } else {
            trimmed
        }
        return tail.replace(BLANK_LINE_RUN_RE, "\n\n")
    }

    private fun cleanInline(raw: String): String = raw
        .replace(INLINE_MARKDOWN_NOISE_RE, "")
        .replace(MULTI_SPACE_RE, " ")
        .trim()
        .trimEnd(':', '\uFF1A', ',', '\uFF0C', '-', '\u2014')

    private fun clampText(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take((maxChars - 1).coerceAtLeast(1)).trimEnd() + "\u2026"
    }
}
