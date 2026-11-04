package io.zer0.muse.tools

/**
 * 工具审批状态机。
 *
 * 决定工具调用在执行前是否需要用户审批，并承载审批结果。
 *
 * 状态流转：
 *  - AUTO：按策略自动放行（始终允许）
 *  - PENDING：等待用户决定，生成循环在此中断
 *  - APPROVED：用户批准本次调用，可携带参数覆盖
 *  - DENIED：用户拒绝本次调用（可附原因）
 *  - ANSWERED：用户提供自定义回答，不执行工具
 *
 * v1.x: [Approved] 携带 [Approved.argOverrides],允许用户在审批卡片中覆盖个别参数
 * (典型场景: generate_image 工具的 reference_image,LLM 无法直接提供本地图片,
 * 由用户在审批 UI 中从相册选择,选中后转 data URI 通过 argOverrides 注入工具执行)。
 */
sealed class ToolApprovalState {
    /** 自动审批通过，立即执行。 */
    data object Auto : ToolApprovalState()

    /** 等待用户审批。生成循环应在此中断。 */
    data object Pending : ToolApprovalState()

    /**
     * 用户批准了本次工具调用。
     *
     * @param argOverrides 审批阶段用户覆盖的工具参数(键 → 值);空表示不覆盖,使用 LLM 原始参数。
     * 覆盖值会由 [io.zer0.muse.tools.ToolOrchestrator] 合并进 [io.zer0.ai.core.ToolCall.arguments]
     * 的 JSON 中再执行工具。
     */
    data class Approved(val argOverrides: Map<String, String> = emptyMap()) : ToolApprovalState()

    /**
     * 这次调用没被放行。
     *
     * `reason` 说清"为什么"，[source] 说清"**是谁决定的**" —— 两者都不能少：
     * 原先只有 reason，消费方一律写成「Tool denied by user」，
     * 于是超时没人应答、策略自动拦截、审批通道没就绪都变成"用户拒绝了"。
     * 模型读到这句话会去道歉而不是改道，用户在任务卡上也被算错一次。
     */
    data class Denied(
        val reason: String = "",
        val source: DenySource = DenySource.USER,
    ) : ToolApprovalState()

    /** 用户提供了自定义文本回答，而非执行该工具。 */
    data class Answered(val answer: String) : ToolApprovalState()

    val isTerminal: Boolean
        get() = this is Approved || this is Denied || this is Answered

    val isExecutable: Boolean
        get() = this is Auto || this is Approved
}

/**
 * 拒绝是**谁**决定的。
 *
 * 只有 USER 才是用户的动作。其余三类都是系统行为，写成「用户拒绝」等于把账算到用户头上：
 *
 *  - [TIMEOUT]：审批弹窗挂到超时没人应答（`APPROVAL_TIMEOUT_MS`，默认 30 秒）；
 *  - [POLICY]：权限策略/参数拦截直接挡掉，根本没问用户；
 *  - [SYSTEM]：审批通道未就绪、会话已切走、生成已停止等，无法取得决定。
 */
enum class DenySource {
    USER,
    TIMEOUT,
    POLICY,
    SYSTEM,
}

/**
 * 交给模型与任务卡的那句归因（稳定英文短语，便于模型据此决定改道还是道歉）。
 *
 * 单一出处：各处以前各自拼「Tool denied by user」，拼错了也没人发现。
 */
internal fun denialErrorLabel(source: DenySource): String = when (source) {
    DenySource.USER -> "Tool denied by user"
    DenySource.TIMEOUT -> "Tool approval timed out without an answer"
    DenySource.POLICY -> "Tool call blocked by permission policy"
    DenySource.SYSTEM -> "Tool approval could not be resolved"
}

/**
 * 审计日志里的拒绝备注。
 *
 * USER 保持原 token `user_denied`（日志口径与既有测试都认它），
 * 但超时/策略/系统这三类不能再借这个名字 —— **审计记录把系统行为写成用户动作，
 * 比界面写错更糟：它是事后追查时唯一被相信的东西**。
 */
internal fun denialAuditNote(source: DenySource): String = when (source) {
    DenySource.USER -> "user_denied"
    DenySource.TIMEOUT -> "approval_timed_out"
    DenySource.POLICY -> "policy_denied"
    DenySource.SYSTEM -> "approval_unresolved"
}

/**
 * 针对单个工具的审批策略，持久化到 DataStore。
 */
enum class ToolApprovalPolicy {
    /** 始终自动批准（如 get_time 等安全工具的默认值）。 */
    ALWAYS_ALLOW,

    /** 始终拒绝（相当于禁用该工具）。 */
    ALWAYS_DENY,

    /** 每次都询问用户。 */
    ASK_EVERY_TIME,
}
