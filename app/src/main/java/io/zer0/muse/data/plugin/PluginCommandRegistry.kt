package io.zer0.muse.data.plugin

import io.zer0.common.Logger

/**
 * 插件斜杠命令的**发现与校验**。
 *
 * 只做两件事：把当前可用命令列出来、把作者写错的命令指出来。
 * 执行不在这里 —— 它必须走与工具调用同一条执行链（能力账本 + 桥接路由器），
 * 在这里再写一遍"调用插件函数"就等于给命令开了一条旁路。
 *
 * 三条规则都是"静默会伤人"的那类：
 *  1. 命令名必须匹配 `[a-z][a-z0-9_-]{0,31}`：允许大写/空格会让输入解析出现歧义；
 *  2. 同名命令只保留第一个（按插件 id 排序），并在日志里说明被谁占了 ——
 *     静默覆盖会让用户以为装的那个插件"坏了"；
 *  3. 每个插件最多 [MAX_COMMANDS_PER_PLUGIN] 条，防止一个插件把命令列表刷满。
 */
class PluginCommandRegistry(
    private val pluginManager: PluginManager,
    private val pluginsEnabled: suspend () -> Boolean,
) {

    /** 一条可执行命令。 */
    data class Entry(
        val pluginId: String,
        val pluginName: String,
        val name: String,
        val description: String,
        val takesInput: Boolean,
        /** 要调用的插件函数名（已在清单 tools 里声明）。 */
        val handler: String,
    )

    /** 列出当前可用命令：只含已启用、入口校验通过、命令声明合法的插件。 */
    suspend fun list(): List<Entry> {
        if (!pluginsEnabled()) return emptyList()
        val out = LinkedHashMap<String, Entry>()
        val plugins = pluginManager.list()
            .filter { it.enabled && it.kind == KIND_TOOL }
            .sortedBy { it.id }
        for (plugin in plugins) {
            val verified = runCatching { pluginManager.loadVerifiedPlugin(plugin.id) }.getOrNull() ?: continue
            for (command in verified.manifest.commands.take(MAX_COMMANDS_PER_PLUGIN)) {
                val problem = validate(command)
                val existing = out[command.name]
                when {
                    problem != null -> Logger.w(TAG, "插件 ${plugin.id} 的命令被跳过：$problem")
                    existing != null ->
                        Logger.w(TAG, "命令名冲突：/${command.name} 已由 ${existing.pluginId} 占用，忽略 ${plugin.id}")
                    else -> out[command.name] = Entry(
                        pluginId = plugin.id,
                        pluginName = plugin.name,
                        name = command.name,
                        description = command.description,
                        takesInput = command.takesInput,
                        handler = command.handler,
                    )
                }
            }
        }
        return out.values.toList()
    }

    /** 按名字找一条命令（大小写不敏感：用户不会记得大小写）。 */
    suspend fun find(rawName: String): Entry? {
        val name = rawName.trim().removePrefix("/").lowercase()
        return list().firstOrNull { it.name == name }
    }

    companion object {
        private const val TAG = "PluginCommands"

        /** 与 `PluginManifest.kind` 的默认值一致；只对工具型插件暴露命令。 */
        private const val KIND_TOOL = "tool"

        const val MAX_COMMANDS_PER_PLUGIN = 20

        /** 小写字母开头，允许数字/下划线/连字符，最长 32。 */
        val NAME_REGEX = Regex("^[a-z][a-z0-9_-]{0,31}$")

        /** 校验一条命令声明；没问题返回 null，否则返回可直接给作者看的原因。 */
        fun validate(command: PluginCommandDeclaration): String? = when {
            !NAME_REGEX.matches(command.name) ->
                "命令名 '${command.name}' 不合法：需小写字母开头，只含小写字母/数字/_/-，最长 32"
            command.handler.isBlank() -> "命令 /${command.name} 缺 handler"
            command.description.length > MAX_DESCRIPTION_CHARS ->
                "命令 /${command.name} 的说明超过 $MAX_DESCRIPTION_CHARS 字（它要进命令列表，太长会被截断）"
            else -> null
        }

        /** 命令说明上限：它出现在命令列表里，属于上下文预算而不是排版偏好。 */
        const val MAX_DESCRIPTION_CHARS = 120
    }
}
