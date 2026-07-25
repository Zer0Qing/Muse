package io.zer0.muse.tools

/**
 * 工具风险等级。
 *
 * 用于会话权限体系:不同权限模式下,不同风险等级的工具触发不同的审批策略。
 */
enum class ToolRiskLevel {
    /** 只读/查询类安全工具(如 get_current_time / calculator / web_search)。 */
    SAFE,

    /** 普通工具:可能读取用户数据或产生本地副作用,但可逆(如 clipboard_write / open_app)。 */
    NORMAL,

    /** 高风险工具:可能产生不可逆、跨设备或资金/隐私/安全影响(如 send_sms / add_contact / workspace_delete / execute_javascript)。 */
    HIGH,
}
