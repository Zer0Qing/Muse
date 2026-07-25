package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.assistant.AssistantRegex

/**
 * v1.97: 正则替换 Transformer — 在消息管道中对 user/assistant 文本应用助手级正则规则。
 *
 * 参考 rikkahub 的 AssistantRegex 设计。
 *
 * 行为:
 *  - 从 context.extra("regex_rules") 取预解析好的规则列表
 *    (ChatViewModel 已从 AssistantEntity.regexRulesJson 反序列化)
 *    extra 契约: key="regex_rules",类型 List<AssistantRegex>,可为 null
 *  - 仅应用 visualOnly=false 的规则(visualOnly=true 的规则仅在 UI 显示时替换,不影响 LLM 输入)
 *  - 根据 affectingScope 决定应用范围:
 *    - "user": 仅 user 消息
 *    - "assistant": 仅 assistant 消息(历史上下文中的助手回复)
 *    - "both": user + assistant 消息
 *  - SYSTEM 消息不做替换(避免破坏系统提示结构)
 *  - 单条规则正则编译失败时跳过该规则,不阻断其他规则
 *
 * 在管道中的位置:位于 TemplateTransformer 之后,ThinkTagTransformer 之前。
 * 这样模板变量先替换完成,正则规则基于最终文本生效。
 */
class RegexMessageTransformer : Transformer {

    override val name: String = "Regex"

    override suspend fun transform(
        messages: List<UIMessage>,
        context: TransformContext,
    ): List<UIMessage> {
        val rules = (context.extra("regex_rules") as? List<*>)?.filterIsInstance<AssistantRegex>()
            ?: return messages
        if (rules.isEmpty()) return messages

        // 仅 visualOnly=false 的规则在管道中应用
        val pipelineRules = rules.filter { it.enabled && !it.visualOnly && it.findRegex.isNotBlank() }
        if (pipelineRules.isEmpty()) return messages

        var changed = false
        val result = messages.map { msg ->
            // SYSTEM 消息不做替换,避免破坏系统提示结构
            if (msg.role == MessageRole.SYSTEM) return@map msg

            val scope = when (msg.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                else -> return@map msg
            }
            val newContent = RegexTransformer.applyRules(
                text = msg.content,
                rules = pipelineRules,
                scope = scope,
                visualOnly = false,
            )
            if (newContent != msg.content) {
                changed = true
                msg.copy(content = newContent)
            } else {
                msg
            }
        }
        return if (changed) result else messages
    }
}
