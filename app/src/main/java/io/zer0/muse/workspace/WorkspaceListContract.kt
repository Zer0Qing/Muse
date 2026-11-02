package io.zer0.muse.workspace

/**
 * 目录列举的**结果合同**（对比清单 P2-01「目录游标」）。
 *
 * 原来的问题：`workspace_list` 把目录里所有条目一次性拼出来。
 * 一个几千项的目录会直接把上下文吃光，而且用户看到的是一段没有尽头、也没法接着看的清单。
 *
 * 这里不做真正的分页游标（多一个 offset 参数会让模型反复试探），而是：
 * **截断到上限 + 明确告诉它还有多少项、下一步该怎么做**。
 * 对模型来说，"缩小范围"比"翻页"更可靠 —— 它看得见自己在哪一层。
 */
object WorkspaceListContract {

    /** 一次最多展示多少项。 */
    const val MAX_ENTRIES = 200

    data class Rendered(val text: String, val shown: Int, val hidden: Int) {
        val truncated: Boolean get() = hidden > 0
    }

    /**
     * @param lines 每条目录项的展示行（调用方已排好序）
     */
    fun render(lines: List<String>, maxEntries: Int = MAX_ENTRIES): Rendered {
        val cap = maxEntries.coerceAtLeast(1)
        if (lines.isEmpty()) return Rendered("(empty)", shown = 0, hidden = 0)
        if (lines.size <= cap) return Rendered(lines.joinToString("\n"), shown = lines.size, hidden = 0)
        val shown = lines.take(cap)
        val hidden = lines.size - cap
        val hint = "--- 还有 $hidden 项未显示（上限 $cap）：请缩小 path 到子目录，或用 workspace_grep 直接找内容 ---"
        return Rendered((shown + hint).joinToString("\n"), shown = cap, hidden = hidden)
    }
}
