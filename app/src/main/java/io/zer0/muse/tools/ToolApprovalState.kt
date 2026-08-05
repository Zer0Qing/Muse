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

    /** 用户拒绝了本次工具调用。 */
    data class Denied(val reason: String = "") : ToolApprovalState()

    /** 用户提供了自定义文本回答，而非执行该工具。 */
    data class Answered(val answer: String) : ToolApprovalState()

    val isTerminal: Boolean
        get() = this is Approved || this is Denied || this is Answered

    val isExecutable: Boolean
        get() = this is Auto || this is Approved
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
