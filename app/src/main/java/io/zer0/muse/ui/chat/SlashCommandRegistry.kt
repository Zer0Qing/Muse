package io.zer0.muse.ui.chat

import io.zer0.muse.R

/**
 * v1.97: 斜杠命令注册表。
 *
 * 在输入框输入 / 开头的命令时,由 ChatViewModel.executeSlashCommand 解析执行。
 * 命令不经过 LLM,直接在客户端处理。
 */
enum class SlashCommand(
    val command: String,
    val descriptionResId: Int,
) {
    NEW("new", R.string.slash_command_new),
    COMPACT("compact", R.string.slash_command_compact),
    RESET("reset", R.string.slash_command_reset),
    PIN("pin", R.string.slash_command_pin),
    ARCHIVE("archive", R.string.slash_command_archive);

    companion object {
        /** 从输入文本解析命令。返回 null 表示不是斜杠命令。 */
        fun parse(text: String): SlashCommand? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("/")) return null
            val name = trimmed.removePrefix("/").split("\\s+".toRegex()).firstOrNull()?.lowercase() ?: return null
            return entries.firstOrNull { it.command == name }
        }

        /** 判断文本是否是斜杠命令(用于发送前拦截)。 */
        fun isSlashCommand(text: String): Boolean = parse(text) != null

        /** 获取所有命令的名称列表(用于自动补全 UI)。 */
        fun allCommandNames(): List<String> = entries.map { "/${it.command}" }
    }
}
