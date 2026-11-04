package io.zer0.muse.accessibility

/** 无障碍路径/文本策略纯函数,供 accessibility 与 app 模块共用并便于单测。 */
object AccessibilityPathUtils {

    /**
     * 解析节点路径字符串。
     *
     * 合法路径形如 "0.1.2",根必须为 0;非法输入返回空列表。
     *
     * 负索引也判非法：`nodeId` 是**模型给的**（工具参数里直接写路径），
     * "0.-1" 以前能通过解析、再交给 `AccessibilityNodeInfo.getChild(-1)` ——
     * 那要么抛异常要么返回 null，取决于 ROM；这里在纯函数层先挡掉，
     * 让"非法输入"只有一种表现（空列表），不用每个 ROM 各猜一次。
     */
    fun parseNodePath(path: String): List<Int> {
        if (path.isBlank()) return emptyList()
        val parts = path.split('.')
        val indices = parts.map { it.toIntOrNull() ?: return emptyList() }
        if (indices.first() != 0) return emptyList()
        if (indices.any { it < 0 }) return emptyList()
        return indices
    }

    /** 密码字段保留控件元数据用于定位/输入,但绝不把值或描述交给自动化模型。 */
    fun textForModel(value: CharSequence?, isPassword: Boolean): String? =
        if (isPassword) null else value?.toString()?.takeIf { it.isNotBlank() }

    /**
     * 转义文本中的换行/制表符/反斜杠/方括号,保证单行输出。
     *
     * 左右方括号都要转义：层级输出用 `[0.1.2]` 作节点前缀，
     * 以前只转义了 `]`，注释却写着"方括号" —— 文本里含 `[` 时，
     * 看层级的人（以及读它的模型）会把它和节点前缀混在一起。
     */
    fun escapeText(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
        .replace("[", "\\[")
        .replace("]", "\\]")
}
