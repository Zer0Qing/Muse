package io.zer0.muse.web

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.session.SessionEntity
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
) {

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
                val text = command.text?.trim().orEmpty()
                if (text.isEmpty()) throw HostProtocolException("chat.send requires non-empty text")
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
        capabilityFlags = listOf(
            "chat",
            "streaming",
            "generation_control",
            "session_crud",
            "tool_approval",
            "memory_host",
            "rag_host",
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
