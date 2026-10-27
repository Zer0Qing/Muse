package io.zer0.muse.ui.chat

/**
 * 后台子 Agent 活动。
 *
 * 打包成一个对象是因为它有两个来源（活跃线程 + 待处理任务），
 * 而扁条不该为了它们各自多开一个参数。
 */
internal data class SubagentActivity(
    val threads: List<io.zer0.muse.data.subagent.SubagentThreadStore.ThreadEntry> = emptyList(),
    val pending: List<io.zer0.muse.tools.DeferredResultStore.DeferredTask> = emptyList(),
) {
    /** 两者通常一致，取较大值兜底解耦场景（同 SubagentTaskListCard 的口径）。 */
    val count: Int get() = maxOf(threads.size, pending.size)

    val isEmpty: Boolean get() = threads.isEmpty() && pending.isEmpty()
}
