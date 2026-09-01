package io.zer0.muse.ui.chat

import io.zer0.common.Logger
import io.zer0.muse.tools.SessionPermissionMode
import io.zer0.muse.tools.SessionPermissionStore
import io.zer0.muse.tools.ToolApprovalPolicy
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.tools.ToolConfigStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.x: 从 ChatViewModel 抽离的工具审批/权限 Controller。
 *
 * 职责:待审批工具调用的参考图覆盖、"始终允许/本次运行全部放行"勾选态、
 * 会话级工具权限模式、以及批准/拒绝(经共享的 [approvalResults] 完成等待中的 deferred)。
 */
class ChatToolController(
    private val accessor: ChatStateAccessor,
    private val sessionPermissionStore: SessionPermissionStore,
    /** 与生成侧 requestToolApproval 共享的等待结果表(键 = toolCallId)。 */
    private val approvalResults: ConcurrentHashMap<String, CompletableDeferred<ToolApprovalState>>,
    private val toolConfigStore: ToolConfigStore?,
) {

    /** 设置待审批工具调用的参考图覆盖值(data URI;null 清除已选图片)。 */
    fun setToolApprovalReferenceImage(toolCallId: String, dataUri: String?) {
        accessor.update { current ->
            current.copy(
                pendingToolApprovals = current.pendingToolApprovals.map { approval ->
                    if (approval.toolCallId == toolCallId) approval.copy(referenceImageOverride = dataUri)
                    else approval
                }
            )
        }
    }

    /** 更新待审批工具调用的"始终允许"勾选状态。 */
    fun setToolApprovalAlwaysAllow(toolCallId: String, alwaysAllow: Boolean) {
        accessor.update { current ->
            current.copy(
                pendingToolApprovals = current.pendingToolApprovals.map { approval ->
                    if (approval.toolCallId == toolCallId) approval.copy(alwaysAllow = alwaysAllow)
                    else approval
                }
            )
        }
    }

    /** 更新待审批工具调用的"本次开启期间批准全部"勾选状态,同时写 appRunAllowAllTools。 */
    fun setToolApprovalAppRunAllowAll(toolCallId: String, allowAll: Boolean) {
        accessor.update { current ->
            current.copy(
                appRunAllowAllTools = allowAll,
                pendingToolApprovals = current.pendingToolApprovals.map { approval ->
                    if (approval.toolCallId == toolCallId) approval.copy(appRunAllowAll = allowAll)
                    else approval
                }
            )
        }
    }

    /** P3: 设置当前会话的工具权限模式(内存态 + 持久化)。 */
    fun setSessionPermissionMode(mode: SessionPermissionMode) {
        val sessionId = if (accessor.snapshot.isAgentMode) {
            accessor.snapshot.agentSessionId
        } else {
            accessor.snapshot.currentSessionId
        } ?: return
        accessor.update { it.copy(sessionPermissionMode = mode) }
        accessor.coroutineScope.launch {
            sessionPermissionStore.setMode(sessionId, mode)
        }
    }

    /** 批准工具调用:移除待审批项,把用户在卡片选的参考图作为 argOverrides 注入,并完成等待结果。 */
    fun approveToolCall(toolCallId: String) {
        val pending = accessor.snapshot.pendingToolApprovals.firstOrNull { it.toolCallId == toolCallId } ?: return
        accessor.update {
            it.copy(pendingToolApprovals = it.pendingToolApprovals.filter { p -> p.toolCallId != toolCallId })
        }
        val argOverrides = if (!pending.referenceImageOverride.isNullOrBlank() &&
            pending.toolName in REFERENCE_IMAGE_TOOL_NAMES
        ) {
            mapOf("reference_image" to pending.referenceImageOverride)
        } else {
            emptyMap()
        }
        approvalResults[toolCallId]?.complete(ToolApprovalState.Approved(argOverrides))
    }

    /** 拒绝工具调用:移除待审批项,并完成等待结果。 */
    fun denyToolCall(toolCallId: String, reason: String) {
        val pending = accessor.snapshot.pendingToolApprovals.firstOrNull { it.toolCallId == toolCallId } ?: return
        accessor.update {
            it.copy(pendingToolApprovals = it.pendingToolApprovals.filter { p -> p.toolCallId != toolCallId })
        }
        approvalResults[toolCallId]?.complete(ToolApprovalState.Denied(reason))
    }

    /** 持久化单工具策略(由审批卡片"始终允许"按钮触发)。与本次批准解耦。 */
    fun persistToolPolicy(toolCallId: String, policy: ToolApprovalPolicy) {
        val pending = accessor.snapshot.pendingToolApprovals.firstOrNull { it.toolCallId == toolCallId } ?: return
        val store = toolConfigStore ?: return
        accessor.coroutineScope.launch {
            runCatching { store.setPolicy(pending.toolName, policy) }
                .onFailure { Logger.w("ChatVM", "persistToolPolicy(${pending.toolName}) 失败: ${it.message}") }
        }
    }

    /** 把工具加入当前会话的临时允许集合(本会话不再问,仅内存不持久化)。 */
    fun allowToolForSession(toolCallId: String) {
        val pending = accessor.snapshot.pendingToolApprovals.firstOrNull { it.toolCallId == toolCallId } ?: return
        val sessionId = if (accessor.snapshot.isAgentMode) {
            accessor.snapshot.agentSessionId
        } else {
            accessor.snapshot.currentSessionId
        } ?: return
        sessionPermissionStore.allowToolForSession(sessionId, pending.toolName)
    }

    companion object {
        /** 支持在审批卡片中选取本地参考图的工具名集合。 */
        private val REFERENCE_IMAGE_TOOL_NAMES: Set<String> = setOf("generate_image")
    }
}
