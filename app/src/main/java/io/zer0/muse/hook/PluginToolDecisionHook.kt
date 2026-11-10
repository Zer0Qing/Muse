package io.zer0.muse.hook

import io.zer0.muse.data.plugin.PluginActivationBridge

/**
 * 决策型钩子：让插件**拦下**某次工具调用（插件计划 P3b）。
 *
 * 权限方向是单向的：插件只能**追加阻止**，不能放行审批本来会拦的调用 ——
 * 所以这条 hook 与审批闸门是"串联收窄"，不是"二选一"。
 *
 * 参数会传给插件（它需要据此判断，例如"阻止对非 https 地址的请求"），
 * 这是 `hook.toolCallBefore` 属于敏感能力的原因之一；另一面是它能让用户的操作失败。
 */
class PluginToolDecisionHook(
    private val bridge: PluginActivationBridge,
) : ToolLifecycleHook {

    override val id: String = "plugin_tool_decision"

    /** 排在最前面：能在别人之前拦下就别拖到最后。 */
    override val priority: Int = 90

    override suspend fun onToolCallRequested(toolName: String, params: Map<String, Any>): ToolCallAction {
        val reason = bridge.decideToolCall(toolName, argsToJson(params)) ?: return ToolCallAction.Allow
        return ToolCallAction.Block(reason)
    }

    /**
     * 参数转 JSON。
     *
     * 值统一按字符串传：插件判断"参数里有没有敏感字样/地址"够用了，
     * 而保留原始类型需要为任意嵌套结构做序列化，收益不成比例。
     */
    internal fun argsToJson(params: Map<String, Any>): String = kotlinx.serialization.json.JsonObject(
        params.mapValues { (_, value) ->
            kotlinx.serialization.json.JsonPrimitive(value.toString())
        },
    ).toString()
}
