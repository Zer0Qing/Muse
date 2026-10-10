package io.zer0.muse.ui.chat
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.tools.SessionPermissionMode
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.tools.ToolPermissionResolver
import io.zer0.muse.ui.PendingToolApproval
import io.zer0.muse.ui.common.feedback.MuseToast
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v2.x 重构 S4: 从 ChatViewModel 提取的工具审批编排。
 *
 * 原位置:ChatViewModel.requestToolApprovalForSession(4218-4331)。
 *
 * 迁移性质:**纯抽取**——代码原样搬入,不改动任何逻辑/顺序/异常语义。
 * ChatViewModel 原位置保留委托调用。
 */
@Suppress(
    "LongParameterList",
    "LongMethod",
    "ComplexMethod",
    "TooGenericExceptionCaught",
    "ReturnCount",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "UnusedPrivateMember",
)
internal class ChatToolApprovalCoordinator(
    private val host: ChatToolApprovalHostBridge,
) {

    /** 从 ChatViewModel 随迁的常量(原审批链直接引用)。 */
    private companion object {
        const val TOOL_APPROVAL_TIMEOUT_MS = 30_000L
    }
    suspend fun requestToolApprovalForSession(
        sessionId: String,
        toolName: String,
        toolCallId: String,
        argsPreview: String,
        args: Map<String, Any?>,
    ): ToolApprovalState {
        // 本会话临时允许只应跳过确认，仍需经过统一解析器的参数硬拒绝。
        // 显式持久化策略优先，避免临时 allow 绕过用户的 ALWAYS_DENY。
        val allowedThisSession = host.sessionPermissionStore.isAllowedThisSession(sessionId, toolName)
        // toolConfigStore 可能未注入(声明为可空,默认 null);未注入时退化为
        // 会话模式+风险等级的默认判定(ASK 下 NORMAL/HIGH 仍会审批),避免首条
        // 需审批工具调用直接 NPE 崩溃。
        // 只取**用户显式配置**的策略:未配置必须是 null,否则会被判定器当成
        // "用户已放行",ASK/STRICT 模式下按风险等级应有的审批会被整段跳过。
        val configuredPolicy = host.toolConfigStore?.getConfiguredPolicy(toolName)
        val perToolPolicy = io.zer0.muse.tools.effectivePerToolPolicy(configuredPolicy, allowedThisSession)
        val displayedSessionId = host.currentSessionIdForApproval()
        val mode =
            if (host.stateStore.state.value.appRunAllowAllTools) {
                // “本次运行全部放行”只跳过逐次审批；仍走统一解析器，保留参数硬拒绝
                // (如 file:// URL / 危险 JS)以及显式 ALWAYS_DENY。
                SessionPermissionMode.TRUSTED
            } else if (displayedSessionId == sessionId) {
                host.stateStore.state.value.sessionPermissionMode
            } else {
                host.sessionPermissionStore.getMode(
                    sessionId,
                    host.settings.defaultSessionPermissionModeFlow.first(),
                )
            }
        val risk = host.toolRegistry.getToolRiskLevel(toolName)
        // v1.x: 审批决策调试日志 — 排查"完全放权不生效/始终允许无效"类问题
        Logger.d(
            "ToolApproval",
            "resolve | tool=$toolName | mode=$mode | risk=$risk | policy=$perToolPolicy" +
                " | allowAllRun=${host.stateStore.state.value.appRunAllowAllTools}",
        )
        // v1.0.53: 传完整 args,参数化策略(open_url/execute_javascript)生效
        val resolved = ToolPermissionResolver.resolve(toolName, risk, mode, perToolPolicy, args)
        Logger.d(
            "ToolApproval",
            "resolved | tool=$toolName | state=$resolved",
        )
        // 状态机闭环:显式列出所有终态分支,确保 ToolApprovalState.Answered 有处理路径
        when (resolved) {
            is ToolApprovalState.Pending -> { /* 待审批,继续走下方用户审批流程 */ }
            is ToolApprovalState.Answered -> {
                // 用户已提供自定义答案(替代工具执行),直接返回该答案
                return resolved
            }
            is ToolApprovalState.Approved, is ToolApprovalState.Auto,
            is ToolApprovalState.Denied,
            -> return resolved
        }

        // 需要用户审批:添加到待审批列表并等待结果
        val deferred = kotlinx.coroutines.CompletableDeferred<ToolApprovalState>()
        host.toolApprovalResults[toolCallId] = deferred
        host.toolApprovalSessions[toolCallId] = sessionId
        val pending =
            PendingToolApproval(
                toolCallId = toolCallId,
                toolName = toolName,
                argumentsPreview = argsPreview,
            )
        host.pendingToolApprovalRecords[toolCallId] = pending
        // v2.0.1: 后台时提醒"等待批准"（前台静默，见 notifyChatPendingApproval 内部判断）
        runCatching {
            host.notificationManager.notifyChatPendingApproval(
                io.zer0.muse.ui.chat.ToolCallVisuals.labelFor(toolName, host.appContext.resources),
            )
        }
        if (displayedSessionId == sessionId) {
            host.stateStore.state.update {
                it.copy(pendingToolApprovals = it.pendingToolApprovals + pending)
            }
        }
        // M1.7: 挂起等待用户审批 -> WAITING_APPROVAL 检查点;恢复/失败后回 GENERATING
        host.sessionManager.runtime(sessionId)?.markWaitingApproval()
        return try {
            // CHAT-08: 审批超时可暂停 — 用户折叠阅读「N 项待审批」期间(host.approvalTimeoutPaused)
            // 倒计时冻结,恢复后继续;任何时刻用户批准/拒绝都立即返回,语义与原 30s 一致。
            var remainingMs = TOOL_APPROVAL_TIMEOUT_MS
            var approvalResult: ToolApprovalState? = null
            while (approvalResult == null) {
                val stepMs = if (host.approvalTimeoutPaused) 200L else minOf(200L, remainingMs)
                val finished = withTimeoutOrNull(stepMs) { deferred.await() }
                if (finished != null) {
                    approvalResult = finished
                } else if (!host.approvalTimeoutPaused) {
                    remainingMs -= stepMs
                    if (remainingMs <= 0) {
                        approvalResult = ToolApprovalState.Denied("Approval timed out")
                        // P2-1: 超时后清掉残留审批卡,避免「点了允许也没反应」;并提示用户
                        host.stateStore.state.update {
                            it.copy(
                                pendingToolApprovals = it.pendingToolApprovals.filter { p -> p.toolCallId != toolCallId },
                            )
                        }
                        MuseToast.show(
                            host.appContext.getString(R.string.tool_approval_timeout_hint, toolName),
                        )
                    }
                }
            }
            approvalResult
        } finally {
            host.sessionManager.runtime(sessionId)?.markResumed()
            host.toolApprovalResults.remove(toolCallId)
            host.toolApprovalSessions.remove(toolCallId)
            host.pendingToolApprovalRecords.remove(toolCallId)
        }
    }
}
