package io.zer0.muse.accessibility

/** B8-05: 无障碍路径解析与文本转义纯函数,便于单测。 */
internal object AccessibilityPathUtils {

    /**
     * 解析节点路径字符串。
     *
     * 合法路径形如 "0.1.2",根必须为 0;非法输入返回空列表。
     */
    fun parseNodePath(path: String): List<Int> {
        if (path.isBlank()) return emptyList()
        val parts = path.split('.')
        val indices = parts.map { it.toIntOrNull() ?: return emptyList() }
        if (indices.first() != 0) return emptyList()
        return indices
    }

    /** 转义文本中的换行/制表符/反斜杠/方括号,保证单行输出。 */
    fun escapeText(text: String): String =
        text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("]", "\\]")
}
