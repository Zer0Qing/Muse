package io.zer0.muse.web

import kotlinx.serialization.Serializable

/**
 * Host Mode WebSocket wire protocol.
 *
 * The browser is a presentation client. It sends intent commands and receives
 * snapshots/events produced by the Android runtime; it never supplies a
 * Provider key or a second copy of the chat business logic.
 */
const val CURRENT_PROTOCOL_VERSION: Int = 1

@Serializable
data class HostCommand(
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
    val type: String,
    val requestId: String? = null,
    val sessionId: String? = null,
    val text: String? = null,
    val title: String? = null,
    val archived: Boolean? = null,
    val messageId: String? = null,
    val generationId: String? = null,
    val cursor: Long? = null,
    val sinceEventSeq: Long? = null,
)

@Serializable
data class HostEvent(
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
    val type: String,
    val requestId: String? = null,
    val connectionId: String? = null,
    val eventSeq: Long = 0L,
    val cursor: Long = 0L,
    val sessionId: String? = null,
    val turnId: String? = null,
    val generationId: String? = null,
    val messageId: String? = null,
    val role: String? = null,
    val title: String? = null,
    val content: String? = null,
    val reasoning: String? = null,
    val delta: String? = null,
    val error: String? = null,
    val isStreaming: Boolean? = null,
    val sessions: List<HostSession> = emptyList(),
    val messages: List<HostMessage> = emptyList(),
    val pendingApprovals: List<HostApproval> = emptyList(),
    val capabilityFlags: List<String> = emptyList(),
)

@Serializable
data class HostApproval(
    val toolCallId: String,
    val toolName: String,
    val argumentsPreview: String,
)

@Serializable
data class HostSession(
    val id: String,
    val title: String,
    val assistantId: String,
    val updatedAt: Long,
    val archived: Boolean,
    val pinned: Boolean,
)

@Serializable
data class HostMessage(
    val id: String,
    val role: String,
    val content: String,
    val reasoning: String? = null,
    val modelId: String? = null,
    val createdAt: Long,
    val imageUrls: List<String> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val citationUrls: List<String> = emptyList(),
)
