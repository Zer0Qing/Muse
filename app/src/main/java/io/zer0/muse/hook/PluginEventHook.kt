package io.zer0.muse.hook

import io.zer0.muse.data.plugin.PluginActivationBridge

/**
 * 把工具调用事件转给插件（对比清单插件计划 P3b）。
 *
 * 复用既有的 [ToolLifecycleHook] 派发点，不改编排器签名 ——
 * 与工具阶段通知同一个思路：那条链已经有钩子，加参数只会让它继续膨胀。
 *
 * 只投**工具名**，不投参数：参数里常有用户内容（路径、搜索词、消息正文），
 * 需要这些的插件应该走 `host.tool.invoke` / `chat.read` 那些有独立授权的通道。
 */
class PluginEventHook(
    private val bridge: PluginActivationBridge,
) : ToolLifecycleHook {

    override val id: String = "plugin_event_hook"

    /** 排在最后：被其它 hook 拦下的调用不该惊动插件。 */
    override val priority: Int = 1

    override suspend fun onToolCallRequested(toolName: String, params: Map<String, Any>): ToolCallAction {
        bridge.dispatchToolCall(toolName)
        return ToolCallAction.Allow
    }
}
