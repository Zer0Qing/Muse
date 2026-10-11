package io.zer0.muse.ui.chat

import io.zer0.muse.tools.SessionPermissionMode

/**
 * AUD-20261011-A2A-ALLOWALL: 工具审批的会话模式判定(纯函数,可单测)。
 *
 * 关键安全口径:[allowAllToolsThisRun] 是**进程级** UI 状态位
 * (用户在任意审批卡勾选"本次开启期间批准全部"后置 true),只能代表"用户当前正看着、
 * 且愿意对眼前这次放行"。它只在 [displayedSessionId] 与请求会话 [sessionId] 一致
 * (即用户正看着该会话)时才升级为 TRUSTED。
 *
 * 否则(A2A 远程请求切走的会话 / A2A 新建会话 / 后台继续生成)回落到该会话自己的
 * 权限模式 —— 默认 ASK 下 HIGH 风险工具仍会走审批卡或 30s 超时拒绝,
 * 不会在无人在屏幕前静默执行 device_shell / app_clear_data / ui_agent。
 *
 * @param displayedSessionId 用户当前正查看的会话(无焦点时为 null)
 * @param focusedMode 焦点会话的会话权限模式
 * @param persistedModeProvider 取非焦点会话自身的持久化模式
 */
internal suspend fun resolveApprovalMode(
    allowAllToolsThisRun: Boolean,
    displayedSessionId: String?,
    sessionId: String,
    focusedMode: SessionPermissionMode,
    persistedModeProvider: suspend () -> SessionPermissionMode,
): SessionPermissionMode {
    val focused = displayedSessionId != null && displayedSessionId == sessionId
    return when {
        allowAllToolsThisRun && focused -> SessionPermissionMode.TRUSTED
        focused -> focusedMode
        else -> persistedModeProvider()
    }
}
