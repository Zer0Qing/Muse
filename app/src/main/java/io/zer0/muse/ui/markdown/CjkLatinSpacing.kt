package io.zer0.muse.ui.markdown

/**
 * 中西文混排（CJK 与拉丁字母 / 数字之间）的轻量视觉呼吸间距排版器。
 *
 * 为纯中文/纯英文段落保持零分配快路径；在汉字与英文单词/数字之间自动插入窄空格 `\u2009`，
 * 同时跳过 URL、邮箱与行内 LaTeX `$...$` 公式片段，避免误改可跳转链接或公式源码。
 */
object CjkLatinSpacing {

    const val THIN_SPACE: Char = '\u2009'

    /** 对正文段落做混排窄空格注入；无边界命中时直接原样返回。 */
    fun format(text: String): String {
        if (text.length < 2) return text
        if (!hasMixedBoundary(text)) return text

        val out = StringBuilder(text.length + 12)
        var i = 0
        val len = text.length
        while (i < len) {
            // 保护 URL（http:// / https://）与邮箱 token 原样输出
            if (startsWithUrl(text, i)) {
                val end = findTokenEnd(text, i)
                if (i > 0 && needsEdgeGap(out.lastOrNull(), text[i])) {
                    out.append(THIN_SPACE)
                }
                out.append(text, i, end)
                if (end < len && needsEdgeGap(text[end - 1], text[end])) {
                    out.append(THIN_SPACE)
                }
                i = end
                continue
            }

            // 保护行内 LaTeX $...$ 不被改写
            if (text[i] == '$' && (i == 0 || text[i - 1] != '\\')) {
                val closeIdx = findClosingInlineMath(text, i)
                if (closeIdx > i) {
                    out.append(text, i, closeIdx + 1)
                    i = closeIdx + 1
                    continue
                }
            }

            val ch = text[i]
            if (out.isNotEmpty() && needsEdgeGap(out[out.length - 1], ch)) {
                out.append(THIN_SPACE)
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    /** 判断左右相邻两个字符之间是否应当补充窄空格。 */
    fun needsEdgeGap(left: Char?, right: Char?): Boolean {
        if (left == null || right == null) return false
        if (left.isWhitespace() || right.isWhitespace()) return false
        return (isCjk(left) && isLatinOrDigit(right)) ||
            (isLatinOrDigit(left) && isCjk(right))
    }

    fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x4E00..0x9FFF) ||
            (code in 0x3400..0x4DBF) ||
            (code in 0xF900..0xFAFF)
    }

    fun isLatinOrDigit(ch: Char): Boolean = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '%'

    private fun hasMixedBoundary(text: String): Boolean {
        for (i in 1 until text.length) {
            if (needsEdgeGap(text[i - 1], text[i])) return true
        }
        return false
    }

    private fun startsWithUrl(text: String, index: Int): Boolean = text.regionMatches(index, "https://", 0, 8, ignoreCase = true) ||
        text.regionMatches(index, "http://", 0, 7, ignoreCase = true)

    private fun findTokenEnd(text: String, start: Int): Int {
        var idx = start
        while (idx < text.length && !text[idx].isWhitespace()) {
            idx++
        }
        return idx
    }

    private fun findClosingInlineMath(text: String, openDollarIndex: Int): Int {
        if (openDollarIndex + 1 >= text.length || text[openDollarIndex + 1] == '$') return -1
        var idx = openDollarIndex + 1
        while (idx < text.length) {
            val c = text[idx]
            if (c == '\n') return -1
            if (c == '$' && text[idx - 1] != '\\') {
                return idx
            }
            idx++
        }
        return -1
    }
}
