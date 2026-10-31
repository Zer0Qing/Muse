package io.zer0.muse.ui.chat
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.tools.ToolPermissionResolver
import io.zer0.muse.ui.PendingToolApproval
import io.zer0.muse.ui.common.feedback.MuseToast
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
    companion object {
        /** 审批超时。界面倒计时必须从这里派生, 不得另存一份数字。 */
        const val TOOL_APPROVAL_TIMEOUT_MS = 30_000L

        /**
         * 失焦 / 退后台期间的累计冻结上限。
         *
         * 用户看不到审批卡时冻结倒计时,让切走 / 离开应用再回来仍能批准;但冻结不能无限,
         * 否则用户不再回来时协程与该会话的工具轮次会永久挂住。超过此值仍按超时收尾。
         */
        const val APPROVAL_UNFOCUSED_PAUSE_MAX_MS = 10 * 60_000L
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
        // AUD-20261011-A2A-ALLOWALL: 全局"本次开启期间批准全部"位只在用户正看着该会话时
        // 升级为 TRUSTED。A2A 远程请求(经 chatViewModel.send() 走同一条审批链、端点只有
        // JWT 无焦点校验)跑到非焦点会话时回落该会话自身模式 —— HIGH 风险设备工具
        // (device_shell / app_clear_data / ui_agent 等)仍走审批卡或 30s 超时拒绝,
        // 不会在无人在屏幕前静默执行。判定口径见 [resolveApprovalMode]。
        val mode = resolveApprovalMode(
            allowAllToolsThisRun = host.stateStore.state.value.appRunAllowAllTools,
            displayedSessionId = displayedSessionId,
            sessionId = sessionId,
            focusedMode = host.stateStore.state.value.sessionPermissionMode,
            persistedModeProvider = {
                host.sessionPermissionStore.getMode(
                    sessionId,
                    host.settings.defaultSessionPermissionModeFlow.first(),
                )
            },
        )
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
                // 预览只有前 200 字符,完整参数一并交给卡片,让它能告诉用户还差多少、
                // 并在展开后展示全量。判定用的也是这份 args,两者同源。
                argumentsFull = args,
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
            // CHAT-08: 审批超时可暂停 — 倒计时按段等待,暂停期间不扣时长,恢复后从剩余时长继续;
            // 任何时刻用户批准/拒绝都立即返回,语义与原 30s 一致。
            // 第二条冻结路径:用户已经看不到这张审批卡时不扣时(切去别的会话、应用退到后台)。
            // 否则用户出去几分钟再回来,只会看到一条早已 Denied 的记录,模型还会把这次超时
            // 当成用户拒绝而改道。判据见 [shouldPauseApprovalTimeout]。
            // 冻结有上限(APPROVAL_UNFOCUSED_PAUSE_MAX_MS):用户不再回来时仍按时收尾,
            // 不让协程与会话轮次永久挂住。折叠阅读属用户主动行为,不受此上限约束。
            val outcome =
                awaitApprovalOutcome(
                    timeoutMs = TOOL_APPROVAL_TIMEOUT_MS,
                    pauseBudgetMs = APPROVAL_UNFOCUSED_PAUSE_MAX_MS,
                    pauseSignals = {
                        val folded = host.approvalTimeoutPaused
                        ApprovalPause(
                            folded = folded,
                            paused =
                            shouldPauseApprovalTimeout(
                                foldedPaused = folded,
                                displayedSessionId = host.currentSessionIdForApproval(),
                                sessionId = sessionId,
                                appInForeground = host.appInForeground,
                            ),
                        )
                    },
                    isStillRegistered = { host.toolApprovalResults[toolCallId] === deferred },
                    awaitOutcome = { sliceMs -> withTimeoutOrNull(sliceMs) { deferred.await() } },
                )
            when (outcome) {
                is ApprovalWaitOutcome.Resolved -> outcome.state
                ApprovalWaitOutcome.Abandoned -> {
                    // 审批已从等待注册表注销(会话被删 / 生成被停 / 同一 toolCallId 重新登记),
                    // 不会再有结论回来 —— 立刻收尾,不留悬挂协程。
                    Logger.d("ToolApproval", "abandoned | tool=$toolName | call=$toolCallId")
                    ToolApprovalState.Denied("Generation stopped")
                }
                ApprovalWaitOutcome.TimedOut -> {
                    // P2-1: 超时后清掉残留审批卡,避免「点了允许也没反应」;并提示用户
                    host.stateStore.state.update {
                        it.copy(
                            pendingToolApprovals = it.pendingToolApprovals.filter { p -> p.toolCallId != toolCallId },
                        )
                    }
                    MuseToast.show(
                        host.appContext.getString(R.string.tool_approval_timeout_hint, toolName),
                    )
                    ToolApprovalState.Denied("Approval timed out")
                }
            }
        } finally {
            host.sessionManager.runtime(sessionId)?.markResumed()
            host.toolApprovalResults.remove(toolCallId)
            host.toolApprovalSessions.remove(toolCallId)
            host.pendingToolApprovalRecords.remove(toolCallId)
        }
    }
}

/**
 * 审批倒计时是否应在当前这一步冻结。
 *
 * 两条独立路径:用户主动折叠阅读([foldedPaused]),或用户已经看不到这张审批卡 ——
 * 应用不在前台([appInForeground] 为 false),或审批所属会话不再是前台会话。
 * 焦点未知([displayedSessionId] 为 null)时不判失焦,维持原有计时行为。
 */
internal fun shouldPauseApprovalTimeout(
    foldedPaused: Boolean,
    displayedSessionId: String?,
    sessionId: String,
    appInForeground: Boolean = true,
): Boolean = foldedPaused || !appInForeground || (displayedSessionId != null && displayedSessionId != sessionId)

/** 倒计时单段步长:暂停期间的探活间隔,也是扣时的粒度。 */
private const val APPROVAL_WAIT_STEP_MS = 200L

/** 单步冻结信号。[folded] 是用户折叠阅读(不受暂停预算约束),[paused] 是当前是否冻结计时。 */
internal data class ApprovalPause(val folded: Boolean, val paused: Boolean)

/** [awaitApprovalOutcome] 的终局。 */
internal sealed interface ApprovalWaitOutcome {
    /** 用户或外部路径给出了结论。 */
    data class Resolved(val state: ToolApprovalState) : ApprovalWaitOutcome

    /** 倒计时用尽(含暂停预算用尽)。 */
    data object TimedOut : ApprovalWaitOutcome

    /** 审批已从等待注册表注销,不会再有结论回来。 */
    data object Abandoned : ApprovalWaitOutcome
}

/**
 * 可暂停的审批倒计时等待。
 *
 * 每一段先等 [awaitOutcome] 给出结论,再结算时长:未冻结的段从 [timeoutMs] 里扣,
 * 冻结的段不扣。因此暂停 5 分钟回来仍剩原本的剩余时长,而不是重新计满。
 * 冻结段照样按 [APPROVAL_WAIT_STEP_MS] 探活,外部批准/拒绝不必等恢复就能返回。
 *
 * @param timeoutMs 未冻结状态下的总时长
 * @param pauseBudgetMs 非折叠冻结的累计上限;用尽后按超时收尾,避免永久挂住
 * @param pauseSignals 每步的冻结信号
 * @param isStillRegistered 审批是否仍在等待注册表里;false 表示不会再有结论
 * @param awaitOutcome 等待给定毫秒数,有结论返回结论,本段超时返回 null
 */
@Suppress("ReturnCount")
internal suspend fun awaitApprovalOutcome(
    timeoutMs: Long,
    pauseBudgetMs: Long,
    pauseSignals: () -> ApprovalPause,
    isStillRegistered: () -> Boolean,
    awaitOutcome: suspend (Long) -> ToolApprovalState?,
): ApprovalWaitOutcome {
    var remainingMs = timeoutMs
    var pauseElapsedMs = 0L
    while (true) {
        val pause = pauseSignals()
        val sliceMs = if (pause.paused) APPROVAL_WAIT_STEP_MS else minOf(APPROVAL_WAIT_STEP_MS, remainingMs)
        awaitOutcome(sliceMs)?.let { return ApprovalWaitOutcome.Resolved(it) }
        // 与并发取消路径(cancelPendingApprovalsForSession 先 remove 再 complete)之间有一个极小的
        // 窗口:探活若恰好落在两步中间,这里会返回 Abandoned 而没拿到那次 complete 的结论。
        // 两条路径的终局都是 Denied,只是原因字符串取自哪一边,不需要为此加锁或重排。
        if (!isStillRegistered()) return ApprovalWaitOutcome.Abandoned
        if (!pause.paused) {
            remainingMs -= sliceMs
            if (remainingMs <= 0L) return ApprovalWaitOutcome.TimedOut
        } else if (!pause.folded) {
            // 折叠阅读是用户主动行为,不计入暂停预算;失焦/退后台的冻结必须有上限。
            pauseElapsedMs += sliceMs
            if (pauseElapsedMs >= pauseBudgetMs) return ApprovalWaitOutcome.TimedOut
        }
    }
}
