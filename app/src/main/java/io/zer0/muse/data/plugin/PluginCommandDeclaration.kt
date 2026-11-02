package io.zer0.muse.data.plugin

import kotlinx.serialization.Serializable

/**
 * 插件声明的一条斜杠命令。
 *
 * 与 `tools` 的关系：`handler` **必须**是 `tools` 里已声明的 `functionName` ——
 * 执行入口 `loadVerifiedFunction` 只认清单声明过的函数，所以命令不会成为绕过声明的新入口。
 */
@Serializable
data class PluginCommandDeclaration(
    /** 命令名（不含斜杠），形如 `daily-report`。 */
    val name: String,
    /** 给用户看的说明，出现在命令列表里。 */
    val description: String = "",
    /** 插件函数名（须在 `tools` 中声明）。 */
    val handler: String,
    /** 是否把 `/cmd 后面的文本` 作为 `input` 传给 handler。 */
    val takesInput: Boolean = false,
)
