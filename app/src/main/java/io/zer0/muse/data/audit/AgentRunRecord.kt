package io.zer0.muse.data.audit

/**
 * F-08: Agent Run 统一收据 — 单次工具执行的完整记录。
 *
 * 用于把 Agent 循环内的每一次工具执行(父/子 agent 相同)落成结构化记录,
 * 供内存诊断(AuditLogPage 或调试页)与审计日志使用。字段均为摘要/原始值,
 * 不携带敏感参数(argumentsSummary/resultSummary 已截断)。
 *
 * @param runId 本次工具调用的唯一 id(取 ToolCall.id),子 agent 以此为 parentRunId
 * @param parentRunId 父级 run id(null 表示根会话内的工具调用;delegate_agent/
 *   subagent_task 的子 agent 工具调用携带发起者 runId,形成调用链)
 * @param status 与 [io.zer0.muse.tools.ToolOrchestrator.ToolExecStatus] 一致
 */
data class AgentRunRecord(
    val runId: String,
    val parentRunId: String? = null,
    val sessionId: String,
    val round: Int,
    val toolName: String,
    val argumentsSummary: String = "",
    val resultSummary: String = "",
    val status: String,
    val durationMs: Long,
    /** F-11: 本轮 LLM 可见(暴露)的工具 id 集合(逗号分隔),工具暴露/执行快照证据。 */
    val exposedToolIds: String = "",
    val timestamp: Long = System.currentTimeMillis(),
)
