package io.zer0.muse.web.a2a

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A2A (Agent-to-Agent) 1.0 协议数据模型。
 *
 * 参考 A2A 规范：Agent Card（`/.well-known/agent-card.json`）+ JSON-RPC 2.0 端点（`/a2a`）。
 * 本文件只定义线协议结构；执行逻辑见 [A2aHandler]。
 *
 * 设计：与 Muse 现有 `HostWebSocketGateway` 私有协议并存 —— 后者是长连接事件推送，
 * A2A 是请求-响应式 JSON-RPC，供外部 agent/平台按标准调用。
 */

/** Agent Card：声明 agent 身份与能力（`/.well-known/agent-card.json`）。 */
@Serializable
data class AgentCard(
    @SerialName("protocolVersion") val protocolVersion: String = A2A_PROTOCOL_VERSION,
    val name: String,
    val description: String,
    val url: String,
    val version: String = "1.0.0",
    val capabilities: AgentCapabilities = AgentCapabilities(),
    val defaultInputModes: List<String> = listOf("text"),
    val defaultOutputModes: List<String> = listOf("text"),
    val skills: List<AgentSkill> = emptyList(),
)

@Serializable
data class AgentCapabilities(
    val streaming: Boolean = true,
    val pushNotifications: Boolean = false,
)

@Serializable
data class AgentSkill(
    val id: String,
    val name: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
)

// ── JSON-RPC 2.0 信封 ──────────────────────────────────────────────

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: MessageSendParams? = null,
    val id: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: String? = null,
)

// ── A2A 消息 / 任务 ────────────────────────────────────────────────

@Serializable
data class MessageSendParams(
    val message: A2aMessage,
)

@Serializable
data class A2aMessage(
    val role: String = "user",
    val parts: List<A2aPart> = emptyList(),
    val messageId: String? = null,
    /** Muse 扩展：指定会话 id；缺省则新建会话。 */
    val sessionId: String? = null,
)

@Serializable
data class A2aPart(
    /** 目前支持 "text"。 */
    val kind: String = "text",
    val text: String? = null,
)

@Serializable
data class A2aTask(
    val id: String,
    /** submitted / working / completed / failed / canceled */
    val state: String,
    val artifacts: List<A2aArtifact> = emptyList(),
    val error: String? = null,
)

@Serializable
data class A2aArtifact(
    val name: String = "response",
    val parts: List<A2aPart> = emptyList(),
)

internal const val A2A_PROTOCOL_VERSION = "1.0"

/** Agent Card 公开路径（`/.well-known/agent-card.json`）。 */
const val AGENT_CARD_PATH = "/.well-known/agent-card.json"

/** JSON-RPC 端点路径（`/a2a`）。 */
const val A2A_PATH = "/a2a"

/** A2A 任务状态常量。 */
internal object A2aTaskState {
    const val SUBMITTED = "submitted"
    const val WORKING = "working"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELED = "canceled"
}

/** JSON-RPC 错误码（A2A 沿用 JSON-RPC 标准码 + 自定义）。 */
internal object A2aErrors {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val TASK_NOT_FOUND = -32001
}
