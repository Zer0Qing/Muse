package io.zer0.muse.data.plugin

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.routing.UtilityModelRouter
import io.zer0.muse.data.routing.UtilityTier

/**
 * 插件调用模型的唯一通道（`model_call`）。
 *
 * 刻意**复用**既有的模型路由与 `UTILITY` 请求通道，不新建第二条出口：
 * 第二条出口意味着第二份计费、第二份限流、第二份日志，而那三件事迟早只有一份生效。
 *
 * 边界：
 *  - 走 `UTILITY` + `ReasoningLevel.OFF`：这是"插件请帮我把这段改写一下"级别的活，
 *    不是新一轮对话，不该开思考链、不该进任何会话历史；
 *  - 模型选择跟辅助模型路由（小工具档），拿不到时回落主对话模型 —— 与子代理同一套回落规则；
 *  - **单次执行内的总成本由该插件的超时预算封顶**：桥接调用本身是串行的，
 *    而每次桥接又受 [PluginHostApi.MAX_BRIDGE_CALLS] 限制，所以不需要再造一个计数器
 *    去管"调了几次模型"——重复实现一个已经有主的约束，只会多一处会漂移的真相。
 */
class PluginModelBridge(
    private val chatService: ChatService,
    private val settings: SettingsRepository?,
) {

    /**
     * @return 模型输出文本。异常直接抛出，由调用方转成可读的桥接失败。
     */
    suspend fun complete(prompt: String, system: String?, maxTokens: Int): String {
        val routed = settings?.let { store ->
            runCatching { UtilityModelRouter(store).resolve(UtilityTier.SMALL) }.getOrNull()
        }
        val messages = buildList {
            if (!system.isNullOrBlank()) add(UIMessage(role = MessageRole.SYSTEM, content = system))
            add(UIMessage(role = MessageRole.USER, content = prompt))
        }
        val completion = chatService.completeText(
            messages = messages,
            model = routed?.second,
            providerConfig = routed?.first,
            maxTokens = maxTokens,
            tools = null,
            reasoningLevel = ReasoningLevel.OFF,
            mode = ChatRequestMode.UTILITY,
        )
        return completion.text
    }

    companion object {
        /** 单次 `model_call` 的输入上限：超了要先让插件自己缩，而不是静默截断喂给模型。 */
        const val MAX_PROMPT_CHARS = 8 * 1024

        /** 输出回传给插件的上限。 */
        const val MAX_OUTPUT_CHARS = 4 * 1024

        /** 默认与封顶的输出长度：插件可以调小，不能调大过这个顶。 */
        const val DEFAULT_MAX_TOKENS = 512
        const val MAX_TOKENS_CAP = 2_048
    }
}
