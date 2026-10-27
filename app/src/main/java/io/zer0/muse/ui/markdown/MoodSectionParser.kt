package io.zer0.muse.ui.markdown

/**
 * MOOD 块的分段解析。
 *
 * MOOD 内容天然是分段的，典型形态：
 *
 * ```
 * Vibe: 一句话感受
 * Sparks:
 *   - 三条联想
 * Reflections:
 *   - 三条反思
 * Will:
 *   - 两条意志
 * ```
 *
 * 之前整段当纯文本渲染，`-` 缩进和换行全塌成一片，读起来像一坨。
 * 这里拆成「小节 + 条目」，UI 就能按结构排版。
 *
 * 设计取舍：
 *  - 只认行首的 `标签:` 形式，不猜其他写法；
 *  - 没有标签时整段作为单节返回，不丢内容；
 *  - 标签文本原样保留（模型自己的词汇，不做翻译）。
 */
internal object MoodSectionParser {
    /** 小节标题：允许中英文标签与中英冒号。 */
    private val HEADER = Regex(
        "^(Vibe|Sparks|Reflections|Will|心情|灵感|反思|意志)\\s*[:：]\\s*(.*)$",
        RegexOption.IGNORE_CASE,
    )

    private val BULLET_PREFIXES = listOf("-", "•", "*", "·")

    internal data class Section(
        val title: String?,
        val lines: List<String>,
    )

    /** 条目：区分普通段落与列表项，UI 据此决定是否画点。 */
    internal data class Line(val text: String, val bullet: Boolean)

    fun parse(raw: String): List<Section> {
        if (raw.isBlank()) return emptyList()

        val sections = mutableListOf<Section>()
        var currentTitle: String? = null
        var currentLines = mutableListOf<String>()

        fun flush() {
            if (currentTitle != null || currentLines.any { it.isNotBlank() }) {
                sections += Section(currentTitle, currentLines.toList())
            }
            currentTitle = null
            currentLines = mutableListOf()
        }

        for (line in raw.lineSequence()) {
            val header = HEADER.matchEntire(line.trim())
            if (header != null) {
                flush()
                currentTitle = header.groupValues[1]
                val inline = header.groupValues[2].trim()
                if (inline.isNotEmpty()) currentLines += inline
            } else {
                currentLines += line.trimEnd()
            }
        }
        flush()

        // 去掉每节首尾空行，避免折叠态展开时上下留白过大。
        return sections.map { section ->
            section.copy(lines = section.lines.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() })
        }
    }

    /** 把一行判定为列表项还是段落。 */
    fun toLine(text: String): Line {
        val trimmed = text.trimStart()
        val bullet = BULLET_PREFIXES.any { trimmed.startsWith(it) }
        val content = if (bullet) {
            trimmed.drop(1).trim()
        } else {
            trimmed
        }
        return Line(content, bullet)
    }
}
