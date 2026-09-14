package io.zer0.muse.web

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.ui.SsrfGuard
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.schedule.ChatGenerationManager
import java.util.UUID
import io.zer0.muse.ui.ChatViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Host Mode WebSocket adapter.
 *
 * ChatViewModel remains the sole owner of generation, persistence, tools and
 * approvals. This adapter only translates browser commands and publishes a
 * session/message snapshot, so the Web client cannot diverge from Android
 * behavior by reimplementing the chat pipeline.
 */
@Suppress("TooManyFunctions")
class HostWebSocketGateway(
    private val chatViewModel: ChatViewModel,
    private val generationManager: ChatGenerationManager,
    private val sessionRepo: SessionRepository,
) {

    /**
     * F-39: 多客户端串行 / 焦点隔离令牌。
     *
     * chat.send / chat.stop / session.select 等写命令直接改共享 ChatViewModel,若不隔离,
     * 两个浏览器可并发交叉中断彼此生成。焦点令牌保证任意时刻至多一个连接持有本状态,
     * 其余连接的写命令返回明确的 focus_not_held 错误。
     */
    private class FocusTokenHolder {
        private val mutex = Mutex()
        private var holderConnectionId: String? = null
        private var holderSessionId: String? = null

        /** 抢占焦点;仅当当前无人持有或就是本连接时成功。 */
        suspend fun acquire(connectionId: String, sessionId: String?): Boolean = mutex.withLock {
            if (holderConnectionId == null || holderConnectionId == connectionId) {
                holderConnectionId = connectionId
                if (!sessionId.isNullOrBlank()) holderSessionId = sessionId
                true
            } else {
                false
            }
        }

        /**
         * 写命令前校验;仅持焦点的连接可通过,并顺手刷新会话焦点。
         * 若当前无人持有(比如持有人已断开),首次写命令即抢占("首次 send 抢占")。
         */
        suspend fun holds(connectionId: String, sessionId: String?): Boolean = mutex.withLock {
            when {
                holderConnectionId == connectionId -> {
                    if (!sessionId.isNullOrBlank()) holderSessionId = sessionId
                    true
                }
                holderConnectionId == null -> {
                    holderConnectionId = connectionId
                    if (!sessionId.isNullOrBlank()) holderSessionId = sessionId
                    true
                }
                else -> false
            }
        }

        /** 连接断开时释放焦点(仅当该连接还持有);若此前已被新连接抢占则不动。 */
        suspend fun release(connectionId: String) = mutex.withLock {
            if (holderConnectionId == connectionId) {
                holderConnectionId = null
                holderSessionId = null
            }
        }
    }

    private val focus = FocusTokenHolder()

    private data class ConnectionContext(
        val id: String = UUID.randomUUID().toString(),
        var eventSeq: Long = 0L,
        val history: ArrayDeque<HostEvent> = ArrayDeque(),
        val completedRequestIds: LinkedHashSet<String> = linkedSetOf(),
    )

    private companion object {
        const val MAX_EVENT_HISTORY = 128
        const val MAX_COMPLETED_REQUESTS = 256
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
                snapshotOf(
                    state.currentSessionId,
                    state.sessions,
                    messages,
                    state.isStreaming,
                    generations[state.currentSessionId],
                )
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
                chatViewModel.createNewSession()
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
                // C-4: SSRF 防护 — 检查消息文本中是否包含内网 URL,防止通过 WebSocket 间接访问内网
                val ssrfUrls = Regex("""https?://(?:127\.|10\.|172\.(?:1[6-9]|2\d|3[01])\.|192\.168\.|localhost|0\.0\.0\.0)[^\s\"'`)>,;]+""")
                    .findAll(text)
                    .map { it.value }
                    .toList()
                if (ssrfUrls.isNotEmpty()) {
                    Logger.w("HostWebSocket", "chat.send SSRF 拦截: ${ssrfUrls.joinToString(", ")}")
                    throw HostProtocolException("message contains internal URLs (SSRF blocked)")
                }
                command.sessionId?.let { sessionId ->
                    if (chatViewModel.state.value.currentSessionId != sessionId) {
                        chatViewModel.switchSession(sessionId)
                        awaitSession(sessionId)
                    }
                }
                chatViewModel.updateInput(text)
                chatViewModel.send()
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
            "tool.approval.resolve" -> {
                val toolCallId = command.generationId?.takeIf { it.isNotBlank() }
                    ?: throw HostProtocolException("tool.approval.resolve requires toolCallId in generationId")
                val decision = command.text?.trim().orEmpty()
                when {
                    // F-37: answered 语义 — 携带用户填写的参数覆盖映射,透传给审批结果对象,
                    // 由 ToolOrchestrator 合并进工具 arguments(覆盖值优先于 LLM 原始参数)后放行。
                    decision == "answered" -> {
                        if (command.argOverrides.isEmpty()) {
                            throw HostProtocolException("tool.approval.resolve answered requires argOverrides")
                        }
                        chatViewModel.approveToolCallWithOverrides(toolCallId, command.argOverrides)
                    }
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
            "session.rename" -> {
                val sessionId = requireSessionId(command)
                val title = command.title?.trim().orEmpty()
                if (title.isEmpty()) throw HostProtocolException("session.rename requires title")
                chatViewModel.renameSession(sessionId, title)
                sendEvent(
                    connection,
                    HostEvent(type = "command.accepted", requestId = requestId, sessionId = sessionId),
                    context,
                    sendMutex,
                )
            }
            "session.archive" -> {
                val sessionId = requireSessionId(command)
                val archived = command.archived ?: throw HostProtocolException("session.archive requires archived")
                chatViewModel.setSessionArchived(sessionId, archived)
                sendEvent(
                    connection,
                    HostEvent(type = "command.accepted", requestId = requestId, sessionId = sessionId),
                    context,
                    sendMutex,
                )
            }
            "session.delete" -> {
                val sessionId = requireSessionId(command)
                chatViewModel.deleteSession(sessionId)
                sendEvent(
                    connection,
                    HostEvent(type = "command.accepted", requestId = requestId, sessionId = sessionId),
                    context,
                    sendMutex,
                )
            }
            "chat.regenerate" -> {
                chatViewModel.regenerateLastAssistant()
                sendEvent(connection, HostEvent(type = "command.accepted", requestId = requestId), context, sendMutex)
            }
            "chat.continue" -> {
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
            "message.copy", "message.quote" -> {
                val messageId = command.messageId?.takeIf { it.isNotBlank() }
                    ?: throw HostProtocolException("${command.type} requires messageId")
                val uiMessage = sessionRepo.getMessageAsUiMessage(messageId)
                    ?: throw HostProtocolException("message '$messageId' not found")
                sendEvent(
                    connection,
                    HostEvent(
                        type = if (command.type == "message.copy") "message.copy" else "message.quote",
                        requestId = requestId,
                        messageId = messageId,
                        content = uiMessage.content,
                    ),
                    context,
                    sendMutex,
                )
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

    private fun requireSessionId(command: HostCommand): String =
        command.sessionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw HostProtocolException("${command.type} requires sessionId")

    private suspend fun syncFromCursor(
        command: HostCommand,
        connection: DefaultWebSocketServerSession,
        context: ConnectionContext,
        sendMutex: Mutex,
    ) {
        val requestedCursor = command.cursor
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

    private suspend fun sendRawEvent(
        connection: DefaultWebSocketServerSession,
        event: HostEvent,
        sendMutex: Mutex,
    ) {
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
            state.currentSessionId,
            state.sessions,
            chatViewModel.messages.value,
            state.isStreaming,
            generationManager.activeGenerations.value[state.currentSessionId],
        )
    }

    private fun snapshotOf(
        sessionId: String?,
        sessions: List<SessionEntity>,
        messages: List<UIMessage>,
        isStreaming: Boolean,
        active: ChatGenerationManager.ActiveGeneration? = null,
    ): HostEvent {
        return HostEvent(
        type = "state.snapshot",
        sessionId = sessionId,
        turnId = active?.turnId,
        generationId = active?.generationId,
        isStreaming = isStreaming,
        sessions = sessions.filterNot { it.archived || it.deletedAt != null }.map { it.toHostSession() },
        messages = messages.map { it.toHostMessage() },
        pendingApprovals = chatViewModel.state.value.pendingToolApprovals.map {
            HostApproval(it.toolCallId, it.toolName, it.argumentsPreview)
        },
        // F-36: capabilityFlags 只声明有对应协议命令的实现能力。memory_host/rag_host 尚无
        // 对应命令且无后端实现,若声明浏览器会显示"记忆/RAG 可用"却操作无效,故移除;
        // 待阶段四落地 memory/rag 命令后再恢复声明。
        capabilityFlags = listOf(
            "chat",
            "streaming",
            "generation_control",
            "session_crud",
            "tool_approval",
            "history",
            "message_clipboard",
            "android_runtime",
        ),
        )
    }

    private fun SessionEntity.toHostSession(): HostSession = HostSession(
        id = id,
        title = title,
        assistantId = assistantId,
        updatedAt = updatedAt,
        archived = archived,
        pinned = pinned,
    )

    private fun UIMessage.toHostMessage(): HostMessage = HostMessage(
        id = id.toString(),
        role = role.name.lowercase(),
        content = content,
        reasoning = reasoning,
        modelId = modelId,
        createdAt = createdAt,
        imageUrls = imageUrls,
        toolCallId = toolCallId,
        toolName = toolCallInfo?.toolName,
        citationUrls = citationUrls,
    )

    private class HostProtocolException(
        message: String,
        cause: Throwable? = null,
    ) : IllegalArgumentException(message, cause)
}
