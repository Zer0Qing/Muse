package io.zer0.muse.web.a2a

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.transformer.stripThinkTags
import kotlinx.coroutines.flow.first

/**
 * A2A 独立文本生成路径：不引用 ChatViewModel/UI 状态，也不暴露工具。
 *
 * 使用 handler-owned Agent session 的有限历史 + 默认助手 prompt/preset，
 * 单轮调用 ChatService.completeText(tools=null)。设备工具只允许本机 UI 交互路径触发。
 */
internal class A2aHeadlessGenerator(
    private val chatService: ChatService,
    private val sessionRepository: SessionRepository,
    private val assistantRepository: AssistantRepository,
) {
    suspend fun complete(sessionId: String, prompt: String): String {
        val assistant = assistantRepository.getById(DEFAULT_ASSISTANT_ID)
        val history = sessionRepository.observeMessages(sessionId).first().takeLast(MAX_CONTEXT_MESSAGES)
        val messages = buildList {
            assistant?.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                add(UIMessage(role = MessageRole.SYSTEM, content = it))
            }
            if (assistant != null) addAll(assistantRepository.parsePresetMessages(assistant))
            addAll(history)
            add(UIMessage(role = MessageRole.USER, content = prompt))
        }
        val completion = chatService.completeText(
            messages = messages,
            temperature = assistant?.temperature,
            maxTokens = assistant?.maxTokens,
            tools = null,
        )
        return stripThinkTags(completion.text).trim()
    }

    private companion object {
        const val DEFAULT_ASSISTANT_ID = "default"
        const val MAX_CONTEXT_MESSAGES = 40
    }
}
