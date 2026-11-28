package io.zer0.muse.channel

/** 外部 IM 渠道支持的远程斜杠指令。 */
enum class ChannelSlashCommand {
    NEW_SESSION,
    STATUS,
    PAUSE_AI,
    RESUME_AI,
    HELP,
}

/**
 * 纯函数解析器：从入站纯文本识别远程控制指令，不碰 IO 或 UI。
 *
 * 支持带 `@bot_name` 后缀的形式（如 Telegram 群聊 `/status@my_bot`）。
 */
object ChannelSlashCommandParser {
    fun parse(rawText: String): ChannelSlashCommand? {
        val trimmed = rawText.trim()
        if (!trimmed.startsWith("/")) return null
        val token = trimmed.substringBefore(' ').substringBefore('@').lowercase()
        return when (token) {
            "/new", "/reset" -> ChannelSlashCommand.NEW_SESSION
            "/status" -> ChannelSlashCommand.STATUS
            "/pause" -> ChannelSlashCommand.PAUSE_AI
            "/resume" -> ChannelSlashCommand.RESUME_AI
            "/help" -> ChannelSlashCommand.HELP
            else -> null
        }
    }
}

/**
 * 群聊触发判定策略（纯函数，零 IO）。
 *
 * 私聊始终放行；群聊按 [ChannelConfig.groupTriggerMode] 决定是否触发 AI 自动回复：
 *  - `all`: 群里所有文本都触发；
 *  - `mention`: 需要 `@机器人` 提及标记或正文中出现 `@botDisplayName`；
 *  - `keyword`: 正文包含任意配置关键词（大小写不敏感）。
 */
object ChannelGroupTriggerPolicy {
    fun shouldRespond(config: ChannelConfig, isGroup: Boolean, isMentioned: Boolean, text: String): Boolean {
        if (!isGroup) return true
        return when (config.groupTriggerMode) {
            ChannelConfig.GROUP_TRIGGER_MENTION -> {
                if (isMentioned) return true
                val botName = config.botDisplayName.trim().removePrefix("@")
                botName.isNotBlank() && text.contains("@$botName", ignoreCase = true)
            }
            ChannelConfig.GROUP_TRIGGER_KEYWORD ->
                config.triggerKeywords.any { kw ->
                    val cleaned = kw.trim()
                    cleaned.isNotEmpty() && text.contains(cleaned, ignoreCase = true)
                }
            else -> true
        }
    }
}
