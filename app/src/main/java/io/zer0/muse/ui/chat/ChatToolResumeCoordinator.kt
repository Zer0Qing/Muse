package io.zer0.muse.ui.chat
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.chat.PendingToolCallStore
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.tools.captureLargeToolOutput
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.ToolCallRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v2.x 重构 S5: 从 ChatViewModel 提取的待恢复工具调用编排。
 *
 * 原位置:ChatViewModel.resumePendingToolCalls。
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
    "UnusedPrivateMember", "LoopWithTooManyJumpStatements", "ComplexCondition",
)
internal class ChatToolResumeCoordinator(
    private val host: ChatToolApprovalHostBridge,
) {

    /** 从 ChatViewModel 随迁的常量(原恢复链直接引用)。 */
    private companion object {
        const val TOOL_TIMEOUT_MS = 120_000L
    }
    fun resumePendingToolCalls(chatId: String) {
        // 防止与正在进行的流式生成冲突
        if (host.stateStore.state.value.isStreaming) {
            host.addError(ChatErrorType.UNKNOWN, host.appContext.getString(R.string.err_chat_resume_busy))
            return
        }
        host.coroutineScope.launch {
            val pendings =
                resultOf { PendingToolCallStore.getForChat(chatId) }
                    .onError { msg, t ->
                        Logger.e("ChatVM", "resumePendingToolCalls getForChat 失败: $msg", t)
                        host.addError(
                            ChatErrorType.UNKNOWN,
                            host.appContext.getString(R.string.err_chat_resume_read_failed, t?.message ?: msg),
                        )
                    }.getOrNull() ?: emptyList()
            if (pendings.isEmpty()) {
                host.stateStore.state.update { it.copy(pendingToolCallCount = 0) }
                return@launch
            }
            // 审批等待不能在重启后伪造 Deferred 或自动放行；要求用户显式丢弃，
            // 或由后续专门的“重新请求审批”流程重新创建当前代的审批上下文。
            val approvalPending =
                pendings.filter {
                    it.executionState == PendingToolCallStore.APPROVAL_PENDING
                }
            if (approvalPending.isNotEmpty()) {
                host.stateStore.state.update { it.copy(pendingToolCallCount = pendings.size) }
                host.addError(
                    ChatErrorType.TOOL_ERROR,
                    "有 ${approvalPending.size} 个工具调用在进程终止前等待审批，已阻止自动恢复；请丢弃后重新发起请求。",
                    true,
                )
                Logger.w(
                    "ChatVM",
                    "拒绝自动恢复审批挂起工具: count=${approvalPending.size}, sessionId=$chatId",
                )
                return@launch
            }
            // 加载启用的 skill 列表,构建 id → SkillEntity 映射(与 launchStream 内的逻辑一致)
            // v1.0.47 P3: 会话级 skill 覆盖 — 优先用 session.skillIdsJson(非"[]"且非空),
            // 否则回退到 assistant.skillIdsJson(默认行为不变)
            val sessionSkillIdsJson =
                host.stateStore.state.value.sessions
                    .firstOrNull { it.id == chatId }?.skillIdsJson
            val effectiveSkillIdsJson =
                if (!sessionSkillIdsJson.isNullOrEmpty() && sessionSkillIdsJson != "[]") {
                    sessionSkillIdsJson
                } else {
                    host.stateStore.state.value.currentAssistant?.skillIdsJson
                }
            val enabledSkillIds =
                effectiveSkillIdsJson?.let { json ->
                    runCatching { host.idListJson.decodeFromString<List<String>>(json) }.getOrNull()
                }
            // 审计修复 (A-04/A-05/A-06): 与 ChatStreamCoordinator.resolveToolsAndModel
            // 同一套过滤 — 本地工具同名 skill 与 channel_* 群聊 skill 不进 skillMap,
            // 定义与执行统一走本地实现,主会话不可冒充 agent 群聊发言。
            val localToolNames = host.toolRegistry.listTools().map { it.name }.toSet()
            val skillMap =
                resultOf { host.skillRepository.listEnabledByIds(enabledSkillIds) }
                    .getOrNull()
                    ?.filterNot { it.id in localToolNames || it.id.startsWith("channel_") }
                    ?.associateBy { it.id } ?: emptyMap()

            // v1.0.4 (P0): 进入"等待首 token"阶段 + 设置工具恢复进度文本,
            // 让 ShimmerBubble 在工具执行期间显示"正在执行 web_search (1/3)…"
            // (原来此阶段 isStreaming=false,ShimmerBubble 不显示,用户看到空白)
            host.stateStore.state.update {
                it.copy(
                    isStreaming = true,
                    isWaitingFirstToken = true,
                    toolProgressMessage = host.appContext.getString(R.string.tool_resume_starting),
                    errors = emptyList(),
                )
            }

            // 逐个执行 pending 工具,构造 TOOL 消息
            val now = System.currentTimeMillis()
            for ((stepIndex, pending) in pendings.withIndex()) {
                // 每步更新进度文本(skill 内部的 onProgress 会进一步覆盖为"正在搜索..."等具体文案)
                host.stateStore.state.update {
                    it.copy(
                        toolProgressMessage =
                        host.appContext.getString(
                            R.string.tool_resume_step,
                            pending.toolName,
                            stepIndex + 1,
                            pendings.size,
                        ),
                    )
                }
                // P0-6: 恢复前重跑审批 — 防"保存后、写审批态前"被杀的高危调用被免审批执行。
                // 判 Pending 时重新弹审批卡 await 用户决策;判 Denied/Answered 时丢弃记录并跳过。
                val recheck = host.recheckApprovalForResume(chatId, pending)
                if (recheck is ToolApprovalState.Denied) {
                    resultOf { PendingToolCallStore.remove(pending.toolCallId) }
                        .onError { msg, t -> Logger.w("ChatVM", "恢复时丢弃被拒工具失败: $msg", t) }
                    Logger.w("ChatVM", "恢复时工具被拒绝并丢弃: tool=${pending.toolName}, sessionId=$chatId")
                    continue
                }
                if (recheck is ToolApprovalState.Answered) {
                    resultOf { PendingToolCallStore.remove(pending.toolCallId) }
                        .onError { msg, t -> Logger.w("ChatVM", "恢复时丢弃已答工具失败: $msg", t) }
                    Logger.w("ChatVM", "恢复时工具收到自定义答案,丢弃: tool=${pending.toolName}, sessionId=$chatId")
                    continue
                }
                val toolResult =
                    resultOf {
                        withTimeoutOrNull(TOOL_TIMEOUT_MS) {
                            val skill = skillMap[pending.toolName]
                            if (skill != null) {
                                // v1.0.4 (P0): 传 onProgress 回调,SkillExecutor 在调用 web_search 等
                                // 耗时工具前会回调"正在搜索..."等本地化文本,覆盖默认的"正在执行 xxx"
                                host.skillExecutor.execute(
                                    skill = skill,
                                    argumentsJson = pending.arguments,
                                    onProgress = { msg ->
                                        host.stateStore.state.update { state -> state.copy(toolProgressMessage = msg) }
                                    },
                                    sessionId = chatId,
                                )
                            } else {
                                withContext(Dispatchers.IO) {
                                    host.routeGuard.executeFromJson(pending.toolName, pending.arguments)
                                }
                            }
                        }
                    }.getOrNull() ?: host.appContext.getString(
                        R.string.err_chat_tool_timeout,
                        pending.toolName,
                        (TOOL_TIMEOUT_MS / 1000).toInt(),
                    )
                val finalResult =
                    captureLargeToolOutput(
                        context = host.appContext,
                        filePrefix = "resumed_${pending.toolCallId}",
                        output = toolResult,
                    )
                // 构造 TOOL 消息:保留原始 toolCallId,让 LLM 能对应上之前发出的 tool_calls
                val toolMsg =
                    UIMessage(
                        role = MessageRole.TOOL,
                        content = finalResult,
                        toolCallId = pending.toolCallId,
                    )
                // 追加到 host.stateStore.messages.value(launchStream 会从 messages.dropLast(1) 取历史)
                host.stateStore.messages.value = host.stateStore.messages.value + toolMsg
                // 持久化到 DB(供下次启动时 LLM 仍能看到工具结果)
                resultOf { host.sessionRepository.upsertMessage(chatId, toolMsg) }
                    .onError { msg, t ->
                        Logger.e("ChatVM", "resumePendingToolCalls upsertMessage 失败: $msg", t)
                        host.addError(
                            ChatErrorType.UNKNOWN,
                            host.appContext.getString(R.string.err_chat_tool_result_save_failed, t?.message ?: msg),
                        )
                    }
                // 同步记录到 toolCallHistory(InputBar 动态胶囊展示)
                val isSuccess = host.isToolResultSuccess(finalResult)
                host.stateStore.state.update {
                    it.copy(
                        toolCallHistory =
                        it.toolCallHistory +
                            ToolCallRecord(
                                toolName = pending.toolName,
                                arguments = pending.arguments,
                                result = finalResult,
                                isSuccess = isSuccess,
                                timestamp = now,
                            ),
                    )
                }
                // 从 pending store 移除(已执行完成)
                resultOf { PendingToolCallStore.remove(pending.toolCallId) }
                    .onError { msg, t -> Logger.w("ChatVM", "resumePendingToolCalls remove 失败: $msg", t) }
            }

            // 清空 pending 计数(Banner 隐藏)+ 清空工具恢复进度文本
            // (ShimmerBubble 将回退到默认"思考中",直到 launchStream 首 token 到达)
            host.stateStore.state.update {
                it.copy(
                    pendingToolCallCount = 0,
                    toolProgressMessage = null,
                )
            }

            // 追加空 ASSISTANT 占位消息,触发 launchStream 让 LLM 基于工具结果继续回复
            val assistantMsg = UIMessage(role = MessageRole.ASSISTANT, content = "")
            host.stateStore.messages.value = host.stateStore.messages.value + assistantMsg
            host.stateStore.state.update {
                it.copy(
                    isStreaming = true,
                    // v1.0.3: 断点续传也进入"等待首 token"阶段
                    isWaitingFirstToken = true,
                    errors = emptyList(),
                )
            }
            host.launchStream(assistantMsg.id, chatId)
        }
    }
}
