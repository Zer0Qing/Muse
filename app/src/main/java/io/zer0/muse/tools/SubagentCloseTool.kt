package io.zer0.muse.tools

import io.zer0.muse.data.subagent.SubagentThreadStore

/**
 * v1.0.53 Phase 1: subagent_close 工具 — 主动关闭子 agent 线程(参考开源实现 subagent_close)。
 *
 * 让主 agent 能关闭不再需要的线程,释放账本。关闭后该 threadId 不可再续接:
 *  - subagent_task 的 reply 会返回 "thread is closed"
 *  - subagent_run 携带该 thread_id 会被 runSerialized 拒并抛 IllegalStateException
 *
 * 安全约束:
 *  - 子 agent 内部禁止调用此工具(防自关)— 已加入
 *    [SubagentRunner.RECURSIVE_FORBIDDEN_TOOLS](子 agent 白名单过滤)
 *  - 风险等级 NORMAL:关闭线程是无破坏性操作(仅改账本状态,不删数据)
 */
object SubagentCloseTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "subagent_close",
        description = "Close a sub-agent thread to release its session bookkeeping. " +
            "After closing, the thread_id can no longer be used for continuation " +
            "(subagent_task reply / subagent_run thread_id will be rejected). " +
            "Use this when you no longer need to continue a sub-agent session.",
        parameters = mapOf(
            "thread_id" to "Required. The thread id to close (returned by subagent_task launch or subagent_run).",
            "reason" to "Optional. Reason for closing (recorded in logs).",
        ),
        required = setOf("thread_id"),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    /**
     * 执行 subagent_close 工具。
     *
     * @param args 工具参数(thread_id 必填 / reason 可选)
     * @param threadStore 子 agent 线程账本(持久化版)
     * @return 操作结果字符串
     */
    suspend fun execute(
        args: Map<String, String>,
        threadStore: SubagentThreadStore,
    ): String {
        val threadId = args["thread_id"]?.trim()
            ?: return "Error: thread_id is required."
        if (threadId.isBlank()) return "Error: thread_id is empty."

        val reason = args["reason"]?.trim()?.takeIf { it.isNotBlank() }
        val closed = threadStore.close(threadId)
        return when {
            closed -> "Thread '$threadId' closed successfully." +
                (reason?.let { " Reason: $it" } ?: "")
            else -> "Thread '$threadId' not found or already closed."
        }
    }
}
