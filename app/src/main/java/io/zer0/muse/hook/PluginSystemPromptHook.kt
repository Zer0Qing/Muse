package io.zer0.muse.hook

import io.zer0.muse.data.plugin.PluginActivationBridge
import kotlinx.serialization.builtins.serializer

/**
 * 让插件往系统提示词里追加片段（插件计划 P3b）。
 *
 * 走既有 [SystemPromptComposeHook] 派发点（`SystemPromptAssembler` 已经在调用它），
 * 所以不需要改编排器。
 *
 * 安全边界在桥与 [io.zer0.muse.data.plugin.PluginPromptInjection] 里：
 * 能力 `hook.systemPrompt` 默认关闭，追加内容一律带"由哪个插件提供"的标记。
 */
class PluginSystemPromptHook(
    private val bridge: PluginActivationBridge,
) : SystemPromptComposeHook {

    override val id: String = "plugin_system_prompt"

    /** 排在最后：内置的提示词段落先就位，插件的追加内容在末尾。 */
    override val priority: Int = 1

    override suspend fun afterComposeSystemPrompt(context: PromptContext): String {
        val payload = buildString {
            append("{\"locale\":").append(quote(context.locale))
            context.assistantId?.let { append(",\"assistantId\":").append(quote(it)) }
            append('}')
        }
        return bridge.composeSystemPromptAppend(payload).orEmpty()
    }

    private fun quote(value: String): String = io.zer0.common.AppJson.encodeToString(String.serializer(), value)
}
