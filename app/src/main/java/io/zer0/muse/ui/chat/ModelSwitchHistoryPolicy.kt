package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage

/**
 * 切换模型/服务商之后，历史里那些「只对上一个模型有意义」的片段该怎么处理。
 *
 * 为什么需要：工具调用轮次里，assistant 消息可能带着上一模型专属的结构
 * （思考签名、加密推理片段、服务商私有的工具调用编码）。换模型后把这些原样回传，
 * 轻则被忽略、重则整轮请求被拒 —— 用户看到的是「换了模型就发不出去了」。
 *
 * 判定原则：**只丢「新模型用不上且会带来风险」的部分，不丢对话内容**。
 * 正文永远保留；被清掉的是附加在消息上的私有结构。
 */
internal object ModelSwitchHistoryPolicy {

    /**
     * 按「当前模型」清洗历史。
     *
     * @param history 待发送的历史（已按 token 预算截断）
     * @param currentModelId 本轮要用的模型 id；null 表示没指定，此时不做任何改动
     */
    fun sanitize(history: List<UIMessage>, currentModelId: String?): List<UIMessage> {
        if (currentModelId.isNullOrBlank()) return history
        var changed = false
        val result = history.map { message ->
            val fromOtherModel = !message.modelId.isNullOrBlank() && message.modelId != currentModelId
            if (!fromOtherModel) return@map message
            changed = true
            // 换过模型的这条消息：正文与角色保留，只剥掉只属于旧模型的附加结构。
            message.copy(
                reasoning = null,
                thinkingSignature = null,
                thinkingEncryptedContent = null,
            )
        }
        return if (changed) result else history
    }

    /**
     * 这条历史里有没有「来自别的模型」的工具调用痕迹。
     *
     * 有的话，续写时要格外小心：工具调用 id 往往由服务商生成，跨服务商不一定能对上。
     * 这里只回答「有没有」，具体怎么处理由调用方决定 —— 判定与动作分开，便于单独验证。
     */
    fun hasForeignToolTurns(history: List<UIMessage>, currentModelId: String?): Boolean {
        if (currentModelId.isNullOrBlank()) return false
        return history.any { message ->
            message.role == MessageRole.TOOL &&
                !message.modelId.isNullOrBlank() &&
                message.modelId != currentModelId
        }
    }
}
