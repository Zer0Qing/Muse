package io.zer0.muse.data.agentbot

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Reconnect 卡对应的可修复故障类型。 */
@Serializable
enum class ReconnectActionKind {
    API_KEY,
    ACCESSIBILITY,
    RUNTIME,
    CHANNEL,
    COMPUTE_NODE,
}

/**
 * Reconnect 卡结构化载荷。
 *
 * 只保存任务 id、可执行动作与简短原因，真实凭据与远端地址不进入卡片。
 */
@Serializable
data class ReconnectCardPayload(
    val action: ReconnectActionKind,
    val taskId: String = "",
    val reason: String = "",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(raw: String): ReconnectCardPayload? = runCatching { json.decodeFromString<ReconnectCardPayload>(raw) }.getOrNull()

        /**
         * 从失败摘要推断可修复类型；识别不到时返回 null，避免把所有失败都伪装成可重连。
         */
        fun classify(taskId: String, summary: String): ReconnectCardPayload? {
            val text = summary.lowercase()
            val action = when {
                listOf("api key", "unauthorized", "401", "鉴权", "密钥").any(text::contains) -> ReconnectActionKind.API_KEY
                listOf("accessibility", "无障碍", "自动化服务").any(text::contains) -> ReconnectActionKind.ACCESSIBILITY
                listOf("runtime", "proot", "运行环境", "environment").any(text::contains) -> ReconnectActionKind.RUNTIME
                listOf("channel", "渠道", "webhook", "外部连接").any(text::contains) -> ReconnectActionKind.CHANNEL
                listOf("offline", "unreachable", "node", "离线", "云端电脑", "连接失败").any(text::contains) -> ReconnectActionKind.COMPUTE_NODE
                else -> return null
            }
            return ReconnectCardPayload(action = action, taskId = taskId, reason = summary.take(160))
        }
    }
}
