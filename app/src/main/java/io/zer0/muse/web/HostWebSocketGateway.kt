package io.zer0.muse.web

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.TextTruncation
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.schedule.ChatGenerationManager
import io.zer0.muse.ui.ChatStreamPhase
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.SsrfGuard
import io.zer0.muse.ui.chat.parseQuotedContent
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.UUID

/**
 * Host Mode WebSocket adapter.
 *
 * ChatViewModel remains the sole owner of generation, persistence, tools and
 * approvals. This adapter only translates browser commands and publishes a
 * session/message snapshot, so the Web client cannot diverge from Android
 * behavior by reimplementing the chat pipeline.
 */
@Suppress("TooManyFunctions")
internal class HostWebSocketGateway(
    private val chatViewModel: ChatViewModel,
    private val generationManager: ChatGenerationManager,
    private val sessionRepo: SessionRepository,
    private val assistantRepo: AssistantRepository,
    private val settingsRepo: SettingsRepository,
    /** 设置文案解析:资源名 → 已本地化文本(随当前应用语言)。 */
    private val settingsStrings: HostSettingsStrings,
    /** 媒体引用 → `/api/media/<token>` 的转换,与读取路由共用同一本账。 */
    private val mediaLinks: HostMediaLinks,
    /** 只读命令（记忆 / 知识库 / 定时任务）的数据源集合。 */
    private val readSources: HostReadSources = HostReadSources.Empty,
) {

    /** F-39: 多客户端串行 / 焦点隔离令牌。 */
    private val focus = HostFocusTokenHolder()

    /** settings.snapshot / settings.update 的实现(白名单、类型校验与文案解析在里面)。 */
    private val settingsCommands = HostSettingsCommands(RepositorySettingsAccess(settingsRepo), settingsStrings)

    /** session.rename / archive / delete 的实现(焦点校验在里面)。 */
    private val sessionCommands = HostSessionCommands(
        focus = focus,
        mutations = object : HostSessionMutations {
            override fun rename(sessionId: String, title: String) = chatViewModel.renameSession(sessionId, title)

            override fun archive(sessionId: String, archived: Boolean) =
                chatViewModel.setSessionArchived(sessionId, archived)

            override fun delete(sessionId: String) = chatViewModel.deleteSession(sessionId)
        },
    )

    private data class ConnectionContext(
        val id: String = UUID.randomUUID().toString(),
        var eventSeq: Long = 0L,
        val history: ArrayDeque<HostEvent> = ArrayDeque(),
        val completedRequestIds: LinkedHashSet<String> = linkedSetOf(),
    )

    private companion object {
        const val MAX_EVENT_HISTORY = 128
        const val MAX_COMPLETED_REQUESTS = 256

        /**
         * `chat.send` 等待乐观追加的 user 消息露面多久。
         *
         * 发送命令只表示「已交给 ChatViewModel」,消息 id 在入队那一步才生成;这里等一小会儿
         * 把它拿到,是为了让 `command.accepted` 能带回网页端要用来渲染状态、后续还能 `chat.retry` 的那条消息。
         * 等不到不算失败:消息已经交出去了,只是 `messageId` 为空。
         */
        const val SEND_ACK_TIMEOUT_MS = 2_000L

        /** 工具参数预览上限(约 500 字符)。 */
        const val TOOL_ARGUMENTS_PREVIEW_LIMIT = 500

        /**
         * 引用正文的回显上限（约 2000 字符）。
         *
         * 引用块本身就是被引消息的全文，而快照随每个流式增量重发一次；2000 字足够覆盖正常引用，
         * 又不至于把一整篇长文反复推过 WebSocket。`message.quote` 与本快照字段共用同一上限。
         */
        const val QUOTE_PREVIEW_LIMIT = 2_000

        /**
         * 工具结果预览上限(约 1000 字符)。
         *
         * 一条读文件/联网搜索结果动辄几十 KB,而快照每收到一个流式增量就重发一次;
         * 1000 字够网页端渲染展开态的前后两段,又不会把整份结果反复推过 WebSocket。
         */
        const val TOOL_RESULT_PREVIEW_LIMIT = 1_000

        /** B-31: chat.send 与其他网络入口共用统一 SSRF 文本判定。 */
        fun containsBlockedUrl(text: String): Boolean = SsrfGuard.hasBlockedUrlInText(text)

        /** 截断工具预览:保头保尾,中间给出省略量(与上下文压缩同一口径)。 */
        fun preview(text: String, limit: Int): String = TextTruncation.headTailText(text, limit)
    }

    suspend fun serve(connection: DefaultWebSocketServerSession) = coroutineScope {
        val context = ConnectionContext()
        val sendMutex = Mutex()
        val snapshotJob = launch {
            combine(
                chatViewModel.state,
                chatViewModel.messages,
                generationManager.activeGenerations,
            ) { state, messages, generations ->
                snapshotOf(state, messages, generations[state.currentSessionId])
            }.distinctUntilChanged().collect { event ->
                sendEvent(connection, event, context, sendMutex)
            }
        }
        // F-39: 新连接建立即抢占焦点(后续写命令的持有者)。
        focus.acquire(context.id, chatViewModel.state.value.currentSessionId)
        try {
            sendEvent(connection, currentSnapshot(), context, sendMutex)
            for (frame in connection.incoming) {
                if (frame !is Frame.Text) continue
                try {
                    val command = decodeCommand(frame.data.decodeToString())
                    if (command.protocolVersion != CURRENT_PROTOCOL_VERSION) {
                        throw HostProtocolException("unsupported host protocol version: ${command.protocolVersion}")
                    }
                    if (command.requestId != null && command.requestId in context.completedRequestIds) {
                        sendEvent(
                            connection,
                            HostEvent(type = "command.duplicate", requestId = command.requestId),
                            context,
                            sendMutex,
                        )
                    } else {
                        handle(command, connection, context, sendMutex)
                        command.requestId?.let { rememberCompletedRequest(context, it) }
                    }
                } catch (error: HostProtocolException) {
                    Logger.w("HostWebSocket", "协议命令失败: ${error.message}", error)
                    sendEvent(
                        connection,
                        HostEvent(type = "error", error = error.message),
                        context,
                        sendMutex,
                    )
                }
            }
        } finally {
            snapshotJob.cancel()
            // F-39: 连接生命周期清理 — 断开时释放焦点,允许其他客户端抢占。
            focus.release(context.id)
        }
    }

    @Suppress("CyclomaticComplexMethod", "ThrowsCount", "TooManyFunctions", "LongMethod")
    private suspend fun handle(
        command: HostCommand,
        connection: DefaultWebSocketServerSession,
        context: ConnectionContext,
        sendMutex: Mutex,
    ) {
        val requestId = command.requestId
        when (command.type) {
            "hello" -> sendEvent(
                connection,
                currentSnapshot().copy(requestId = requestId),
                context,
                sendMutex,
            )
            "state.sync" -> syncFromCursor(command, connection, context, sendMutex)
            "session.new" -> {
                // B-30: 新建会话是写命令,仅持焦点客户端可执行。
                if (!focus.holds(context.id, command.sessionId)) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = "focus_not_held"), context, sendMutex)
                    return
                }
                // 指定助手时先验存在:建一个挂在不存在助手上的会话，会讓人以为选错了助手。
                val requestedAssistantId = command.assistantId?.trim()?.takeIf { it.isNotEmpty() }
                if (requestedAssistantId != null && assistantRepo.getById(requestedAssistantId) == null) {
                    sendEvent(
                        connection,
                        HostEvent(type = "error", requestId = requestId, error = "assistant_not_found"),
                        context,
                        sendMutex,
                    )
                    return
                }
                chatViewModel.createNewSession(assistantIdOverride = requestedAssistantId)
                sendEvent(connection, HostEvent(type = "command.accepted", requestId = requestId), context, sendMutex)
            }
            "session.select" -> {
                // F-39: 切换会话是写命令,仅持焦点客户端可执行。
                if (!focus.holds(context.id, command.sessionId)) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = "focus_not_held"), context, sendMutex)
                    return
                }
                val sessionId = requireSessionId(command)
                chatViewModel.switchSession(sessionId)
                awaitSession(sessionId)
                sendEvent(
                    connection,
                    HostEvent(type = "command.accepted", requestId = requestId, sessionId = sessionId),
                    context,
                    sendMutex,
                )
            }
            "chat.send" -> {
                // F-39: 发送是写命令,仅持焦点客户端可执行。
                if (!focus.holds(context.id, command.sessionId)) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = "focus_not_held"), context, sendMutex)
                    return
                }
                val text = command.text?.trim().orEmpty()
                if (text.isEmpty()) throw HostProtocolException("chat.send requires non-empty text")
                // B-31: SSRF 防护 — 交由 SsrfGuard 从文本提取 http(s) URL 并逐个判定私网/回环/链路本地/
                // IPv6/IP 字面量,任一命中即拒绝。替代原先漏链本地/IPv6/整数 IP 的自造正则,复用统一判断逻辑。
                if (containsBlockedUrl(text)) {
                    Logger.w("HostWebSocket", "chat.send SSRF 拦截: 文本含内网 URL")
                    throw HostProtocolException("message contains internal URLs (SSRF blocked)")
                }
                command.sessionId?.let { sessionId ->
                    if (chatViewModel.state.value.currentSessionId != sessionId) {
                        chatViewModel.switchSession(sessionId)
                        awaitSession(sessionId)
                    }
                }
                // 引用目标必须在当前会话内，否则不发送（不能把另一个会话的正文带过来）。
                val quoted = resolveQuotedMessage(command.quotedMessageId)
                if (quoted is QuotedMessage.Rejected) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = quoted.reason), context, sendMutex)
                    return
                }
                // 发送前拍个快照,发送后新增的那条 user 消息就是本次的 messageId。
                val previousIds = chatViewModel.messages.value.mapTo(HashSet()) { it.id.toString() }
                // 引用走 App 自己的机制：设上 replyingTo 再发送，正文会按 `> …` 格式落库，
                // 与手机端引用发送同一条路。
                (quoted as? QuotedMessage.Resolved)?.message?.let(chatViewModel::setReplyingTo)
                chatViewModel.updateInput(text)
                chatViewModel.send()
                sendEvent(
                    connection,
                    HostEvent(
                        type = "command.accepted",
                        requestId = requestId,
                        sessionId = chatViewModel.state.value.currentSessionId,
                        messageId = awaitSentUserMessage(previousIds),
                    ),
                    context,
                    sendMutex,
                )
            }
            "chat.retry" -> {
                // 重发是写命令,与 chat.send 同规则:未持焦点的回 focus_not_held,且不碰任何消息。
                if (!focus.holds(context.id, command.sessionId)) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = "focus_not_held"), context, sendMutex)
                    return
                }
                sendEvent(connection, retryEvent(command), context, sendMutex)
            }
            "tool.approval.resolve" -> {
                // B-12: 审批放行属于写命令,必须先过焦点隔离,否则多浏览器可并发放行彼此的工具调用。
                if (!focus.holds(context.id, command.sessionId)) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = "focus_not_held"), context, sendMutex)
                    return
                }
                val toolCallId = command.generationId?.takeIf { it.isNotBlank() }
                    ?: throw HostProtocolException("tool.approval.resolve requires toolCallId in generationId")
                val decision = command.text?.trim().orEmpty()
                when {
                    // B-12: answered 收紧 — 此层拿不到该工具声明的参数列表做键白名单,为规避浏览器
                    // 客户端用 argOverrides 注入 shell/path/command 等敏感键覆盖 LLM 原始参数以绕过放行检查,
                    // 本版本整体禁用参数覆盖:answered 与 approved 等价,仅放行 LLM 原始参数(argOverrides 合并路径 void)。
                    decision == "answered" -> chatViewModel.approveToolCall(toolCallId)
                    decision == "approved" -> chatViewModel.approveToolCall(toolCallId)
                    decision == "denied" -> chatViewModel.denyToolCall(toolCallId, "web_denied")
                    else -> throw HostProtocolException("unsupported approval decision: $decision")
                }
                sendEvent(
                    connection,
                    HostEvent(type = "command.accepted", requestId = requestId),
                    context,
                    sendMutex,
                )
            }
            // B-30: 改名 / 归档 / 删除是写命令 — 焦点校验与错误事件在 HostSessionCommands 里。
            "session.rename", "session.archive", "session.delete" -> sendEvent(
                connection,
                sessionCommands.handle(command, context.id),
                context,
                sendMutex,
            )
            "chat.regenerate" -> {
                // B-30: 重新生成是写命令,仅持焦点客户端可执行。
                if (!focus.holds(context.id, command.sessionId)) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = "focus_not_held"), context, sendMutex)
                    return
                }
                chatViewModel.regenerateLastAssistant()
                sendEvent(connection, HostEvent(type = "command.accepted", requestId = requestId), context, sendMutex)
            }
            "chat.continue" -> {
                // B-30: 继续生成是写命令,仅持焦点客户端可执行。
                if (!focus.holds(context.id, command.sessionId)) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = "focus_not_held"), context, sendMutex)
                    return
                }
                chatViewModel.continueGeneration()
                sendEvent(connection, HostEvent(type = "command.accepted", requestId = requestId), context, sendMutex)
            }
            "chat.stop" -> {
                // F-39: 停止是写命令,仅持焦点客户端可执行。
                if (!focus.holds(context.id, command.sessionId)) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = "focus_not_held"), context, sendMutex)
                    return
                }
                chatViewModel.stop()
                sendEvent(
                    connection,
                    HostEvent(
                        type = "command.accepted",
                        requestId = requestId,
                        sessionId = chatViewModel.state.value.currentSessionId,
                    ),
                    context,
                    sendMutex,
                )
            }
            // F-38: 消息级命令 — 复制/引用文本回客户端剪贴板;读命令无需焦点校验。
            // 引用额外要求目标属于当前会话:它是“把这句引到输入框”,跨会话引一句别的会话的话
            // 在界面上无处安放。复制保持原语义(只取纯文本)不变。
            //
            // 与 `chat.send.quotedMessageId` 的分工:本命令是“先看被引用正文”(预览/回读),
            // 那个字段是“发送时携带引用”。两者返回的内容与截断口径一致,可以混用。
            "message.copy", "message.quote" -> {
                val messageId = command.messageId?.takeIf { it.isNotBlank() }
                    ?: throw HostProtocolException("${command.type} requires messageId")
                val uiMessage = sessionRepo.getMessageAsUiMessage(messageId)
                    ?: throw HostProtocolException("message '$messageId' not found")
                if (command.type == "message.quote" && !belongsToCurrentSession(messageId)) {
                    sendEvent(
                        connection,
                        HostEvent(type = "error", requestId = requestId, error = "message_not_in_current_session"),
                        context,
                        sendMutex,
                    )
                    return
                }
                sendEvent(
                    connection,
                    HostEvent(
                        type = if (command.type == "message.copy") "message.copy" else "message.quote",
                        requestId = requestId,
                        sessionId = chatViewModel.state.value.currentSessionId,
                        messageId = messageId,
                        content = quotePreview(uiMessage.content),
                    ),
                    context,
                    sendMutex,
                )
            }
            // 消息级写命令:删除与收藏都只改当前会话的可见消息,与 chat.send 同一条焦点规则。
            "message.delete" -> {
                if (!focus.holds(context.id, command.sessionId)) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = "focus_not_held"), context, sendMutex)
                    return
                }
                sendEvent(connection, deleteMessagesEvent(command), context, sendMutex)
            }
            "message.favorite" -> {
                if (!focus.holds(context.id, command.sessionId)) {
                    sendEvent(connection, HostEvent(type = "error", requestId = requestId, error = "focus_not_held"), context, sendMutex)
                    return
                }
                sendEvent(connection, setFavoriteEvent(command), context, sendMutex)
            }
            "history.page" -> {
                val sessionId = requireSessionId(command)
                val limit = (command.limit ?: 50).coerceIn(1, 200)
                val offset = (command.offset ?: 0).coerceAtLeast(0)
                // 复用 SessionRepository 最近的 limit+offset 条,再在内存里裁出本页(升序)。
                val page = sessionRepo.getRecentMessages(sessionId, offset + limit).drop(offset).take(limit)
                sendEvent(
                    connection,
                    HostEvent(
                        type = "history.page",
                        requestId = requestId,
                        sessionId = sessionId,
                        messages = page.map { it.toHostMessage() },
                    ),
                    context,
                    sendMutex,
                )
            }
            "history.search" -> {
                val query = command.query?.trim().orEmpty()
                if (query.isEmpty()) throw HostProtocolException("history.search requires query")
                val results = sessionRepo.searchMessages(query).take(50)
                val messages = results.map { hit ->
                    HostMessage(
                        id = hit.messageId,
                        role = hit.role.lowercase(),
                        content = hit.content,
                        createdAt = hit.createdAt,
                    )
                }
                sendEvent(
                    connection,
                    HostEvent(
                        type = "history.search",
                        requestId = requestId,
                        content = query,
                        messages = messages,
                    ),
                    context,
                    sendMutex,
                )
            }
            // 设置主页:分组与入口由 App 端给出,只读,不需焦点。
            "settings.snapshot" -> sendEvent(
                connection,
                HostEvent(
                    type = "settings.snapshot",
                    requestId = requestId,
                    settings = settingsCommands.snapshot(),
                ),
                context,
                sendMutex,
            )
            // 设置写入:白名单 + 类型校验,白名单外的键回错误事件而不是静默忽略。
            //
            // 这里**不查会话焦点**:会话写要串行是有理由的(避免两个客户端同时往同一会话里塞消息),
            // 全局设置没有这个问题——手机正在聊天的时候,网页改一个开关不应该被拒;
            // 两端同时改同一个键按后写覆盖即可。
            //
            // 写入成功只回 `command.accepted`:新值随**下一次** `settings.snapshot` 下发,
            // 不在这里额外推一份。客户端提交的就是它本地要显示的值,拉一次快照即可对齐规范化后的结果
            // （比如数字去小数点、枚举大小写）,避免每次开关都多推一整份设置树。
            "settings.update" -> {
                when (val result = settingsCommands.update(command.key, command.value)) {
                    is HostSettingsUpdateResult.Applied -> sendEvent(
                        connection,
                        HostEvent(type = "command.accepted", requestId = requestId),
                        context,
                        sendMutex,
                    )
                    is HostSettingsUpdateResult.Rejected -> sendEvent(
                        connection,
                        HostEvent(type = "error", requestId = requestId, error = result.reason),
                        context,
                        sendMutex,
                    )
                }
            }
            // 只读一览:助手 / 供应商 / 会话元信息。
            "assistants.list" -> sendEvent(
                connection,
                HostReadCommands.assistantsEvent(
                    assistants = assistantRepo.getAll(),
                    defaultAssistantId = settingsRepo.defaultAssistantIdFlow.first(),
                    requestId = requestId,
                    mediaLinks = mediaLinks,
                ),
                context,
                sendMutex,
            )
            "providers.list" -> sendEvent(
                connection,
                HostReadCommands.providersEvent(
                    providers = settingsRepo.providersFlow.first(),
                    selectedModelId = settingsRepo.selectedModelIdFlow.first(),
                    requestId = requestId,
                ),
                context,
                sendMutex,
            )
            "sessions.detail" -> {
                val sessionId = requireSessionId(command)
                val session = sessionRepo.getSessionById(sessionId)
                // 会话不存在时不查消息表,直接回 session_not_found。
                val messageCount = if (session == null) 0 else sessionRepo.getMessageCount(sessionId)
                sendEvent(
                    connection,
                    HostReadCommands.sessionDetailEvent(session, messageCount, requestId),
                    context,
                    sendMutex,
                )
            }
            "assistants.detail" -> {
                val assistantId = command.assistantId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw HostProtocolException("assistants.detail requires assistantId")
                sendEvent(
                    connection,
                    HostReadCommands.assistantDetailEvent(
                        assistant = assistantRepo.getById(assistantId),
                        requestId = requestId,
                        mediaLinks = mediaLinks,
                    ),
                    context,
                    sendMutex,
                )
            }
            // 记忆只读:会话/主记忆池那一层的条目列表与关键词检索(见 HostMemoryReads 的覆盖说明)。
            "memory.list" -> sendEvent(
                connection,
                HostMemoryReads.listEvent(
                    records = readSources.memory.entries(command.assistantId),
                    cursor = command.cursor?.toLongOrNull(),
                    limit = command.limit ?: HostMemoryReads.DEFAULT_LIMIT,
                    requestId = requestId,
                ),
                context,
                sendMutex,
            )
            "memory.search" -> {
                val query = command.query?.trim().orEmpty()
                if (query.isEmpty()) throw HostProtocolException("memory.search requires query")
                val limit = command.limit ?: HostMemoryReads.DEFAULT_LIMIT
                sendEvent(
                    connection,
                    HostMemoryReads.searchEvent(
                        query = query,
                        records = readSources.memory.search(query, command.assistantId, limit),
                        requestId = requestId,
                    ),
                    context,
                    sendMutex,
                )
            }
            // 知识与自动化分类页:全部只读,不参与焦点锁(与 assistants.list 同级)。
            "knowledge.list" -> sendEvent(
                connection,
                HostKnowledgeReads.listEvent(readSources.knowledge.bases(), requestId),
                context,
                sendMutex,
            )
            "knowledge.documents" -> {
                val kbId = command.kbId?.trim().orEmpty()
                if (kbId.isEmpty()) throw HostProtocolException("knowledge.documents requires kbId")
                sendEvent(
                    connection,
                    HostKnowledgeReads.documentsEvent(
                        kbId = kbId,
                        page = readSources.knowledge.documents(
                            kbId = kbId,
                            cursor = command.cursor?.trim()?.takeIf { it.isNotEmpty() },
                            limit = HostKnowledgeReads.pageLimit(command.limit),
                        ),
                        requestId = requestId,
                    ),
                    context,
                    sendMutex,
                )
            }
            "tasks.list" -> sendEvent(
                connection,
                HostTaskReads.listEvent(readSources.tasks.tasks(), requestId),
                context,
                sendMutex,
            )
            // 系统状态:数据与隐私页 / 平台专有页的只读快照,不参与焦点锁。
            // 体积统计在数据源内部走 IO 调度器,不阻塞这条连接协程。
            "system.status" -> sendEvent(
                connection,
                HostSystemStatusReads.event(readSources.system.status(), requestId),
                context,
                sendMutex,
            )
            else -> throw HostProtocolException("unsupported host command: ${command.type}")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun decodeCommand(text: String): HostCommand = try {
        AppJson.decodeFromString<HostCommand>(text)
    } catch (error: Exception) {
        throw HostProtocolException(
            "invalid host command: ${error.message ?: "malformed JSON"}",
            error,
        )
    }

    private suspend fun awaitSession(sessionId: String) {
        try {
            withTimeout(5_000L) {
                chatViewModel.state.first { it.currentSessionId == sessionId }
            }
        } catch (error: TimeoutCancellationException) {
            throw HostProtocolException(
                "session '$sessionId' did not become active within 5 seconds",
                error,
            )
        }
    }

    /** 等乐观追加的新 user 消息露面;超时返回 null,不影响命令本身被受理。 */
    private suspend fun awaitSentUserMessage(previousIds: Set<String>): String? {
        val message = withTimeoutOrNull(SEND_ACK_TIMEOUT_MS) {
            chatViewModel.messages
                .mapNotNull { messages -> messages.firstOrNull { it.role == MessageRole.USER && it.id.toString() !in previousIds } }
                .first()
        } ?: return null
        return message.id.toString()
    }

    /**
     * `chat.retry` 的裁决与执行(焦点已在命令分支里校验过)。
     *
     * 目标对不上时不回 `command.accepted`,而是给出可读的失败原因,免得网页端
     * 以为重发已启动。
     */
    @Suppress("ThrowsCount")
    private fun retryEvent(command: HostCommand): HostEvent {
        val messageId = command.messageId?.takeIf { it.isNotBlank() }
            ?: throw HostProtocolException("chat.retry requires messageId")
        return when (retryTarget(messageId)) {
            RetryTarget.NOT_FOUND -> HostEvent(
                type = "error",
                requestId = command.requestId,
                error = "message_not_found",
            )

            RetryTarget.NOT_SUPPORTED -> HostEvent(
                type = "error",
                requestId = command.requestId,
                error = "retry_unsupported_message",
            )

            RetryTarget.RETRYABLE -> {
                chatViewModel.regenerateLastAssistant()
                HostEvent(type = "command.accepted", requestId = command.requestId, messageId = messageId)
            }
        }
    }

    /**
     * `chat.retry` 能不能落在本条消息上。
     *
     * App 里的重发入口只有一个:失败横幅上的「重试」,它重新生成当前分支的最后一条助手回复。
     * 所以这里只接两种目标:最后一条助手消息(失败气泡本身)与最后一条用户消息
     * (发送未递达、需要重跑这一轮)。其他消息一律拒掉 —— 目标不对却默默重生成另一条,
     * 比拒绝危得多。
     */
    private fun retryTarget(messageId: String): RetryTarget {
        val messages = chatViewModel.messages.value
        val target = messages.firstOrNull { it.id.toString() == messageId } ?: return RetryTarget.NOT_FOUND
        val lastAssistantId = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
        val lastUserId = messages.lastOrNull { it.role == MessageRole.USER }?.id
        return when {
            target.role == MessageRole.ASSISTANT && target.id == lastAssistantId -> RetryTarget.RETRYABLE
            target.role == MessageRole.USER && target.id == lastUserId -> RetryTarget.RETRYABLE
            else -> RetryTarget.NOT_SUPPORTED
        }
    }

    private enum class RetryTarget { RETRYABLE, NOT_FOUND, NOT_SUPPORTED }

    /** 目标消息是否属于当前会话;找不到会话或查不到消息都算不属于。 */
    private suspend fun belongsToCurrentSession(messageId: String): Boolean {
        val currentSessionId = chatViewModel.state.value.currentSessionId ?: return false
        return sessionRepo.getMessageById(messageId)?.sessionId == currentSessionId
    }

    /** `chat.send.quotedMessageId` 的解析结果。 */
    private sealed interface QuotedMessage {
        /** 没带引用目标，按普通发送处理。 */
        data object None : QuotedMessage

        data class Resolved(val message: UIMessage) : QuotedMessage

        data class Rejected(val reason: String) : QuotedMessage
    }

    /**
     * 解析引用目标。
     *
     * 只认当前会话内的消息：带着期会话的 id 发送等于跨会话带正文，直接拒掉不发送；
     * 当前会话内查不到消息（刚被删掉）也给明确错误，不静默降级成普通发送 ——
     * 用户看到的引用条突然消失比报一个错更难理解。
     */
    private suspend fun resolveQuotedMessage(quotedMessageId: String?): QuotedMessage {
        val id = quotedMessageId?.trim()?.takeIf { it.isNotEmpty() } ?: return QuotedMessage.None
        if (!belongsToCurrentSession(id)) return QuotedMessage.Rejected("message_not_in_current_session")
        val target = sessionRepo.getMessageAsUiMessage(id) ?: return QuotedMessage.Rejected("message_not_found")
        return QuotedMessage.Resolved(target)
    }

    /**
     * `message.delete` 的裁决与执行。
     *
     * 只删当前会话里真实存在的消息：不存在的、别会话的 id 进 `skippedMessageIds` 而不是报错
     * （删一条已被别的端删掉的消息，与“删成功”一样都不该让界面报红）。
     * 整条命令是原子的：只要有一个目标是正在生成的那条末条助手消息，就把整批拒掉，
     * 不出现“删了一半”的中间态。
     */
    @Suppress("ThrowsCount")
    private fun deleteMessagesEvent(command: HostCommand): HostEvent {
        val requestId = command.requestId
        val requested = command.messageIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (requested.isEmpty()) throw HostProtocolException("message.delete requires messageIds")
        val existingIds = chatViewModel.messages.value.mapTo(HashSet()) { it.id.toString() }
        val targets = requested.filter { it in existingIds }
        if (targets.isNotEmpty() && isStreamingReply(targets)) {
            return HostEvent(type = "error", requestId = requestId, error = "message_is_streaming")
        }
        val messageIds = targets.mapNotNull { runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull() }
        chatViewModel.deleteMessages(messageIds)
        return HostEvent(
            type = "command.accepted",
            requestId = requestId,
            sessionId = chatViewModel.state.value.currentSessionId,
            deletedCount = messageIds.size,
            skippedMessageIds = requested.filterNot { it in existingIds },
        )
    }

    /** 目标里有没有“正在生成的那条助手回复”：流式中且是该会话末条助手消息。 */
    private fun isStreamingReply(targets: List<String>): Boolean {
        if (!chatViewModel.state.value.isStreaming) return false
        val lastAssistantId = chatViewModel.messages.value
            .lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.id?.toString()
            ?: return false
        return lastAssistantId in targets
    }

    /**
     * `message.favorite` 的裁决与执行。
     *
     * 目标状态由调用方给定（不受当前值影响），所以重复点同一个状态是幂等的：
     * 已是目标值时不会翻转，也不会报错。
     */
    @Suppress("ThrowsCount")
    private suspend fun setFavoriteEvent(command: HostCommand): HostEvent {
        val requestId = command.requestId
        val messageId = command.messageId?.takeIf { it.isNotBlank() }
            ?: throw HostProtocolException("message.favorite requires messageId")
        val favored = command.favored
            ?: throw HostProtocolException("message.favorite requires favored")
        val messageUuid = runCatching {
            kotlin.uuid.Uuid.parse(messageId)
        }.getOrNull()
        if (messageUuid == null || sessionRepo.getMessageById(messageId) == null) {
            return HostEvent(type = "error", requestId = requestId, error = "message_not_found")
        }
        chatViewModel.setMessageFavorite(messageUuid, favored)
        return HostEvent(type = "command.accepted", requestId = requestId, messageId = messageId)
    }

    private fun requireSessionId(command: HostCommand): String = command.sessionId?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw HostProtocolException("${command.type} requires sessionId")

    private suspend fun syncFromCursor(
        command: HostCommand,
        connection: DefaultWebSocketServerSession,
        context: ConnectionContext,
        sendMutex: Mutex,
    ) {
        // 事件序号是数字,游标字段统一按文本收,这里转回数值。
        val requestedCursor = command.cursor?.toLongOrNull()
        val history = context.history.toList()
        val canReplay = requestedCursor != null &&
            (history.isEmpty() || requestedCursor >= history.first().eventSeq - 1L)
        if (canReplay) {
            val replay = history.filter { it.eventSeq > requestedCursor }
            if (replay.isNotEmpty()) {
                replay.forEachIndexed { index, event ->
                    sendRawEvent(
                        connection,
                        event.copy(requestId = if (index == replay.lastIndex) command.requestId else null),
                        sendMutex,
                    )
                }
                return
            }
        }
        sendEvent(
            connection,
            currentSnapshot().copy(requestId = command.requestId),
            context,
            sendMutex,
        )
    }

    private fun rememberCompletedRequest(context: ConnectionContext, requestId: String) {
        context.completedRequestIds += requestId
        while (context.completedRequestIds.size > MAX_COMPLETED_REQUESTS) {
            context.completedRequestIds.remove(context.completedRequestIds.first())
        }
    }

    private suspend fun sendRawEvent(connection: DefaultWebSocketServerSession, event: HostEvent, sendMutex: Mutex) {
        sendMutex.withLock { connection.send(Frame.Text(AppJson.encodeToString(event))) }
    }

    private suspend fun sendEvent(
        connection: DefaultWebSocketServerSession,
        event: HostEvent,
        context: ConnectionContext,
        sendMutex: Mutex,
    ): HostEvent {
        val sequenced = event.copy(
            connectionId = context.id,
            eventSeq = context.eventSeq++,
            cursor = context.eventSeq,
        )
        context.history.addLast(sequenced)
        while (context.history.size > MAX_EVENT_HISTORY) context.history.removeFirst()
        sendMutex.withLock { connection.send(Frame.Text(AppJson.encodeToString(sequenced))) }
        return sequenced
    }

    private fun currentSnapshot(): HostEvent {
        val state = chatViewModel.state.value
        return snapshotOf(
            state,
            chatViewModel.messages.value,
            generationManager.activeGenerations.value[state.currentSessionId],
        )
    }

    private fun snapshotOf(
        state: ChatUiState,
        messages: List<UIMessage>,
        active: ChatGenerationManager.ActiveGeneration? = null,
    ): HostEvent {
        val statusContext = MessageStatusContext.of(state, messages)
        return HostEvent(
            type = "state.snapshot",
            sessionId = state.currentSessionId,
            turnId = active?.turnId,
            generationId = active?.generationId,
            isStreaming = state.isStreaming,
            // 归档会话照常下发（用 archived 标记区分，网页端按自己的开关过滤）；
            // 只排除真删掉的（deletedAt 非空）。
            sessions = state.sessions.filterNot { it.deletedAt != null }.map { it.toHostSession() },
            messages = messages.map { it.toHostMessage(statusContext) },
            pendingApprovals = chatViewModel.state.value.pendingToolApprovals.map {
                HostApproval(it.toolCallId, it.toolName, it.argumentsPreview)
            },
            // F-36: capabilityFlags 只声明有对应协议命令的实现能力。
            // memory 这一批只落地只读的 memory.list / memory.search(会话/主记忆池那一层),
            // 记忆写入与 RAG 还没有对应命令,不在这里声明。
            capabilityFlags = listOf(
                "chat",
                "streaming",
                "generation_control",
                "session_crud",
                "tool_approval",
                "history",
                "message_clipboard",
                "android_runtime",
                "settings_snapshot",
                "settings_update",
                "assistants",
                "assistant_detail",
                "providers",
                "session_detail",
                "media",
                "tool_call_details",
                "message_status",
                "message_write",
                "message_multi_select",
                "memory_list",
                "memory_search",
                "knowledge",
                "tasks",
                "system_status",
            ),
        )
    }

    /**
     * 快照之外的上下文:同一份消息列表里,哪一条是「正在进行的那一轮」。
     *
     * App 本身不持久化消息级状态(只有会话级的 isStreaming / 错误横幅),这里把那些信号
     * 投影到单条消息上,语义与 App 里看到的反馈一致:
     *  - 最后一条助手消息 + 正在流式 = streaming;
     *  - 最后一条助手消息 + 本轮以 FAILED 收场且错误没被关掉 = failed(错误横幅的重试入口就在它身上);
     *  - 最后一条消息是用户消息且首个 token 还没回 = sending(乐观追加已上屏,还没交给模型)。
     *
     * 历史分页(history.page)里的消息都是已落库的过去消息,用 [Idle]:一律 sent。
     */
    private data class MessageStatusContext(
        val lastMessageId: String?,
        val lastAssistantId: String?,
        val streaming: Boolean,
        val waitingFirstToken: Boolean,
        val failed: Boolean,
        val errorText: String?,
    ) {
        companion object {
            val Idle = MessageStatusContext(
                lastMessageId = null,
                lastAssistantId = null,
                streaming = false,
                waitingFirstToken = false,
                failed = false,
                errorText = null,
            )

            fun of(state: ChatUiState, messages: List<UIMessage>): MessageStatusContext = MessageStatusContext(
                lastMessageId = messages.lastOrNull()?.id?.toString(),
                lastAssistantId = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id?.toString(),
                streaming = state.isStreaming,
                waitingFirstToken = state.isWaitingFirstToken,
                // 生成阶段停在 FAILED 且错误还在展示才算这条消息失败;用户关掉错误横幅就是接受了现状。
                failed = !state.isStreaming &&
                    state.streamState.phase == ChatStreamPhase.FAILED &&
                    state.errors.isNotEmpty(),
                errorText = state.errors.lastOrNull()?.message,
            )
        }
    }

    private fun SessionEntity.toHostSession(): HostSession = HostSession(
        id = id,
        title = title,
        assistantId = assistantId,
        updatedAt = updatedAt,
        archived = archived,
        pinned = pinned,
    )

    private fun UIMessage.toHostMessage(context: MessageStatusContext = MessageStatusContext.Idle): HostMessage {
        val status = statusOf(context)
        return HostMessage(
            id = id.toString(),
            role = role.name.lowercase(),
            content = content,
            reasoning = reasoning,
            modelId = modelId,
            createdAt = createdAt,
            imageUrls = mediaLinks.links(imageUrls, id.toString()),
            toolCallId = toolCallId,
            toolName = toolCallInfo?.toolName,
            citationUrls = citationUrls,
            voiceUrl = mediaLinks.voiceLink(voiceUri, id.toString()),
            voiceDurationMs = voiceDurationMs.takeIf { it > 0L },
            toolCalls = hostToolCalls(),
            status = status,
            errorText = if (status == HostMessageStatus.FAILED) context.errorText else null,
            quotedContent = quotedContentOf(),
        )
    }

    /**
     * 引用回复里的被引用正文。
     *
     * 与 App 消息气泡同一规则：字段优先（编辑/草稿态），否则对用户消息解析 `> ` 开头的引用块
     * —— 发送时正文就是按这个格式落库的，所以快照能原样把引用卡还原出来。
     */
    private fun UIMessage.quotedContentOf(): String? {
        val raw = quotedContent ?: if (role == MessageRole.USER) parseQuotedContent(content).first else null
        return raw?.takeIf { it.isNotBlank() }?.let(::quotePreview)
    }

    /** 引用正文的截断口径：`message.quote` 与快照里的 `quotedContent` 共用这一个函数。 */
    private fun quotePreview(text: String): String = TextTruncation.headTailText(text, QUOTE_PREVIEW_LIMIT)

    private fun UIMessage.statusOf(context: MessageStatusContext): String {
        val messageId = id.toString()
        return when {
            context.failed && role == MessageRole.ASSISTANT && messageId == context.lastAssistantId ->
                HostMessageStatus.FAILED

            context.streaming && role == MessageRole.ASSISTANT && messageId == context.lastAssistantId ->
                HostMessageStatus.STREAMING

            context.streaming && context.waitingFirstToken && role == MessageRole.USER &&
                messageId == context.lastMessageId -> HostMessageStatus.SENDING

            else -> HostMessageStatus.SENT
        }
    }

    /**
     * 本条消息的工具调用。
     *
     * 两个来源拼成一份数组:[io.zer0.ai.core.UIMessage.toolCalls] 是助手一次提出的调用请求
     * (只有名字与参数,结果不在这条消息上),[io.zer0.ai.core.UIMessage.toolCallInfo] 是带结果的工具卡片。
     * 同一工具名的待结果请求会被工具卡片合并,不会重复占两项。
     */
    private fun UIMessage.hostToolCalls(): List<HostToolCall> {
        val calls = toolCalls?.map { request ->
            HostToolCall(
                callId = request.id,
                name = request.name,
                argumentsPreview = preview(request.arguments, TOOL_ARGUMENTS_PREVIEW_LIMIT),
            )
        }?.toMutableList() ?: mutableListOf()
        val info = toolCallInfo ?: return calls
        val pending = calls.indexOfFirst { it.name == info.toolName && it.resultPreview == null }
        val settled = HostToolCall(
            callId = if (pending >= 0) calls[pending].callId else toolCallId ?: "$id:tool",
            name = info.toolName,
            argumentsPreview = preview(info.arguments, TOOL_ARGUMENTS_PREVIEW_LIMIT),
            // 结果为空给 null 而不是空串:网页端据此区分「未回结果」与「回了空结果」。
            resultPreview = info.result.takeIf { it.isNotBlank() }?.let { preview(it, TOOL_RESULT_PREVIEW_LIMIT) },
            success = info.isSuccess,
            durationMs = info.durationMs,
        )
        if (pending >= 0) calls[pending] = settled else calls.add(settled)
        return calls
    }
}
