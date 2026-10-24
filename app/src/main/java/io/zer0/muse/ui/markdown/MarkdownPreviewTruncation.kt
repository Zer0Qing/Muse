package io.zer0.muse.ui.markdown

/**
 * 结构感知的 Markdown 预览截断。
 *
 * 背景（审计 §11-9）：收藏卡片预览此前是 `body.take(280)` —— 纯字符级切口。
 * 后果有两类，都是渲染层才炸、数据层看不出来的：
 *  - 切在未闭合的 ``` 代码围栏里：围栏永远不闭合，后续内容整体变代码块；
 *  - 切在 `**`、`*`、`_`、`[N]` 这类成对标记中间：标记半截留在文本里，
 *    有时变成字面量星号，有时把后文整体吞成粗体/链接。
 *
 * 这里的做法是两级：
 *  1. **按块聚合**：先按空行切段，逐段累加，到接近上限就停。整块保留意味着
 *     列表、引用、表格、代码块一般整段完整，不会出现"半个表格"。
 *  2. **尾部修复**：单块本身就超长时才在上限处硬切，切完再做一次"未闭合构造"
 *     修复（围栏 / 行内代码 / 成对强调符 / 方括号引用），宁可少显示几个字，
 *     也不把半截标记交给渲染器。
 *
 * 纯函数、不依赖 Android，便于单测。
 */
object MarkdownPreviewTruncation {

    /** 收藏卡片预览的长度上限。 */
    const val DEFAULT_LIMIT: Int = 280

    /** 省略号。放在这里而不是调用方，保证"截断"和"截断标记"永远同时出现或同时不出现。 */
    const val ELLIPSIS: String = "…"

    /** 代码围栏行程标记：``` 或 ~~~。 */
    private const val FENCE_MARK: String = "```"

    /** 圈选里剔除尾部的标记，顺序有讲究：先长后短，避免 ** 被单个 * 提前处理。 */
    private val INLINE_MARKERS = listOf("**", "__", "*", "_", "[")

    /**
     * 把 [text] 截到不超过 [limit] 字符的预览，并保证不切断 Markdown 结构。
     *
     * - 原文不超过 [limit]：原样返回（不加省略号）
     * - 超长：返回 `前缀 + [ELLIPSIS]`
     * - 结果不会落在未闭合代码围栏 / 行内代码 / 强调符 / 半个 `[N]` 中间
     */
    fun preview(text: String, limit: Int = DEFAULT_LIMIT): String {
        val safeLimit = limit.coerceAtLeast(1)
        return if (text.length <= safeLimit) text else truncated(text, safeLimit)
    }

    private fun truncated(text: String, limit: Int): String = repairOpenConstructs(collectBlocks(text, limit)).trimEnd() + ELLIPSIS

    /** 按空行分块累加，返回不超过 [limit] 的整块前缀。第一块就超长时退化为硬切。 */
    private fun collectBlocks(text: String, limit: Int): String {
        val blocks = mutableListOf<String>()
        val current = StringBuilder()
        text.split('\n').forEach { line ->
            if (line.isBlank() && current.isNotEmpty()) {
                blocks += current.toString()
                current.setLength(0)
            } else if (line.isNotBlank()) {
                if (current.isNotEmpty()) current.append('\n')
                current.append(line)
            }
        }
        if (current.isNotEmpty()) blocks += current.toString()
        if (blocks.isEmpty()) return text

        val first = blocks.first()
        return if (first.length > limit) {
            hardCut(first, limit)
        } else {
            blocks.drop(1).fold(first) { acc, block ->
                if (acc.length + 2 + block.length <= limit) "$acc\n\n$block" else acc
            }
        }
    }

    /** 硬切到 [limit] 字符，尽量落在换行边界上（表格行、列表项尤其明显）。 */
    private fun hardCut(text: String, limit: Int): String {
        val raw = limit.coerceAtMost(text.length)
        val lastNewline = text.lastIndexOf('\n', raw - 1)
        val cut = if (lastNewline > limit / 2) lastNewline else raw
        return text.substring(0, cut)
    }

    /**
     * 修复切完后的"未闭合构造"，让渲染器拿到的永远是自洽片段。
     *
     * 先砍代码围栏 —— 围栏内的 `**`、`[` 都是字面量，不该交给后面的行内检查。
     */
    private fun repairOpenConstructs(text: String): String {
        val withoutFence = closeCodeFence(text)
        val withoutInlineCode = dropUnbalancedTail(withoutFence, "`")
        return INLINE_MARKERS.fold(withoutInlineCode) { acc, marker -> dropUnbalancedTail(acc, marker) }
    }

    /**
     * 代码围栏未闭合：从最后一个未闭合的开围栏处整段砍掉。
     *
     * 选择"砍掉"而不是"补一个 ``` 收尾"：补围栏会把预览里最后半截代码当成
     * 合法代码块渲染出来，看起来像是内容本身如此；砍掉更诚实，省略号已经告诉
     * 用户还有下文。
     */
    private fun closeCodeFence(text: String): String {
        var openIndex = -1
        var offset = 0
        text.split('\n').forEach { line ->
            if (line.trimStart().startsWith(FENCE_MARK) || line.trimStart().startsWith("~~~")) {
                openIndex = if (openIndex < 0) offset else -1
            }
            offset += line.length + 1
        }
        return if (openIndex >= 0) text.substring(0, openIndex) else text
    }

    /**
     * 砍掉最后一个"落单标记"之后的内容。
     *
     * 行内代码的反引号在原文上数（掩码会把它们盖掉），其余标记在掩码串上数 ——
     * 掩码串与原文等长，下标可直接用于切原文。
     */
    private fun dropUnbalancedTail(text: String, marker: String): String {
        val masked = if (marker == "`") text else maskCodeAndEscapes(text)
        val cut = unbalancedIndex(masked, marker)
        return if (cut > 0) text.substring(0, cut) else text
    }

    /** 未配对标记的起始下标；-1 表示没有。`[` 按"后面还有没有 `]`"判断。 */
    private fun unbalancedIndex(masked: String, marker: String): Int {
        if (marker == "[") {
            val open = masked.lastIndexOf('[')
            return if (open >= 0 && masked.indexOf(']', open) < 0) open else -1
        }
        val positions = mutableListOf<Int>()
        var index = masked.indexOf(marker)
        while (index >= 0) {
            positions += index
            index = masked.indexOf(marker, index + marker.length)
        }
        return if (positions.size % 2 == 0) -1 else positions.last()
    }

    /**
     * 把转义字符与行内代码替换成等长空格，保证下标与原文对齐。
     *
     * 不 masked 掉的话，代码段里的 `*` 会干扰配对计数，导致过度截断。
     */
    private fun maskCodeAndEscapes(text: String): String {
        val chars = text.toCharArray()
        var i = 0
        while (i < chars.size) {
            i += when {
                chars[i] == '\\' && i + 1 < chars.size -> {
                    chars[i] = ' '
                    chars[i + 1] = ' '
                    2
                }
                chars[i] == '`' -> maskCodeSpan(text, chars, i)
                else -> 1
            }
        }
        return String(chars)
    }

    /** 掩掉从 [start] 开始的一段反引号包裹的行内代码，返回步进长度。 */
    private fun maskCodeSpan(text: String, chars: CharArray, start: Int): Int {
        var end = start
        while (end < chars.size && chars[end] == '`') end++
        val runLength = end - start
        val closing = text.indexOf("`".repeat(runLength), end)
        val stop = (if (closing >= 0) closing + runLength else end).coerceAtMost(chars.size)
        for (j in start until stop) chars[j] = ' '
        return stop - start
    }
}
