package io.zer0.muse.data

/**
 * v2.6.2（用户反馈）: 三层聊天背景的回落解析（纯函数，便于单测）。
 *
 * 优先级：会话级 > 助手级 > 全局默认。
 * 任一层的值为空白字符串时视为"未设置"，继续向下回落。
 *
 * @param sessionId 当前会话 id（Agent 模式传 agentSessionId；null 表示无会话）
 * @param assistantId 当前助手 id
 * @param sessionBackgrounds sessionId → 图片路径
 * @param assistantBackgrounds assistantId → 图片路径
 * @param globalBackground 全局默认背景
 * @return 最终生效的背景路径；三层都没有时返回 null（回落到主题默认背景色）
 */
internal fun resolveChatBackground(
    sessionId: String?,
    assistantId: String?,
    sessionBackgrounds: Map<String, String>,
    assistantBackgrounds: Map<String, String>,
    globalBackground: String?,
): String? {
    val fromSession = sessionId?.takeIf { it.isNotBlank() }?.let { sessionBackgrounds[it] }
    val fromAssistant = assistantId?.takeIf { it.isNotBlank() }?.let { assistantBackgrounds[it] }
    return when {
        !fromSession.isNullOrBlank() -> fromSession
        !fromAssistant.isNullOrBlank() -> fromAssistant
        else -> globalBackground?.takeIf { it.isNotBlank() }
    }
}
