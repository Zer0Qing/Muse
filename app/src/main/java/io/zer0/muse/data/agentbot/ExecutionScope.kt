package io.zer0.muse.data.agentbot

/** 一次前台模型回合固定使用的身份与输入版本。 */
data class AgentTurnIdentity(
    val assistantId: String,
    val sessionId: String,
    val requestId: String,
    val requestRevision: Long,
    val permissionScopeId: String = "",
) {
    fun validate(): String? = when {
        assistantId.isBlank() -> "缺少 assistantId"
        sessionId.isBlank() -> "缺少 sessionId"
        requestId.isBlank() -> "缺少 requestId"
        requestRevision < 1L -> "requestRevision 必须大于 0"
        else -> null
    }
}

data class TaskCreationInput(
    val identity: AgentTurnIdentity,
    val goal: String,
    val acceptanceCriteria: String,
    val resourceScope: String,
    val permissionScopeId: String = identity.permissionScopeId,
    val dedupeKey: String = "task-dispatch:${identity.requestId}",
)

/**
 * 一次执行的身份快照 —— 宿主注入，不从模型参数信任。
 *
 * ## 为什么必须有这个类型
 *
 * 后台任务最容易出的错不是"跑不动"，是**跑错对象**：拿当前 UI 会话当投递目标、
 * 用用户正在看的身份去申请权限、把 A 任务的结果写进 B 会话。这些错误的共同点是
 * "看起来都成功了"。把身份收成一个不可变结构，由宿主在建任务时填一次，
 * 执行链路全程只读它，就没有"从当前焦点推断"的余地。
 *
 * 字段按用途分四组：
 *  - 身份：botId / assistantId / originAgentSessionId
 *  - 任务：taskId / runId / attempt / taskRevision
 *  - 归属：causeEventId / requestMessageId / requestRevision
 *  - 边界：permissionScopeId / resourceScope / generationId
 */
data class ExecutionScope(
    /** 哪个 Bot 接的单。一个 Bot 一个协调者，任务不跨 Bot 迁移。 */
    val botId: String,
    /** 对外身份（助手 id）。风格与人格由它决定，与权限无关。 */
    val assistantId: String,
    /**
     * 发起任务的那段对话。
     *
     * 结果回流只认它 —— 用户切到别的对话之后，任务完成仍然要回到这里，
     * 而不是落到"用户现在正在看哪个会话"。
     */
    val originAgentSessionId: String,
    val taskId: String,
    val runId: String,
    /** 第几次尝试，从 1 起。返工以新 attempt 重跑，旧 attempt 保留终态。 */
    val attempt: Int = 1,
    /** 启动时对应的任务约束版本。迟到结果按它判 stale。 */
    val taskRevision: Int = 1,
    /** 触发这次执行的内部事件 id；首次派发为空串。 */
    val causeEventId: String = "",
    /** 触发这条任务的用户消息 id。 */
    val requestMessageId: String = "",
    /** 生成时的用户输入版本。新输入到达后旧版本的结果不得发布。 */
    val requestRevision: Long = 0L,
    /** 权限范围标识。空表示沿用会话默认策略。 */
    val permissionScopeId: String = "",
    /** 可改动资源范围（路径 / 设备面 / 浏览器）。空表示只读任务。 */
    val resourceScope: String = "",
    /** 子 Agent 线程 id；首次执行时由 Runner 分配，续接时沿用。 */
    val threadId: String = "",
    /** 本代执行的围栏令牌。旧 worker 醒来时令牌落后，写不进当前版本。 */
    val generationId: Long = 0L,
) {
    /**
     * 执行前的基本自检。
     *
     * 这些字段缺失时**不能**降级成"随便挑一个会话"—— 那正是跨会话污染的来源。
     * 返回非空字符串表示不可执行，调用方应把任务标 NEEDS_USER 并说明缺什么。
     */
    fun validate(): String? = when {
        botId.isBlank() -> "缺少 botId"
        taskId.isBlank() -> "缺少 taskId"
        runId.isBlank() -> "缺少 runId"
        originAgentSessionId.isBlank() -> "缺少 originAgentSessionId（禁止用当前会话兜底）"
        taskRevision < 1 -> "taskRevision 必须 ≥ 1"
        attempt < 1 -> "attempt 必须 ≥ 1"
        else -> null
    }
}
