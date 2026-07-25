package io.zer0.muse.tools

import io.zer0.ai.core.UIMessage

/**
 * v1.200: 委派上下文构造器。
 *
 * 从会话消息中提取精简上下文,供 [SkillExecutor.delegateAgent] 使用。
 * 只保留最近 [DEFAULT_MAX_MESSAGES] 条文本消息,过滤掉图片/音频等非文本内容,
 * 避免子助手上下文过长或被多模态内容污染。
 */
object DelegationContextBuilder {

    /** 默认携带的最大上下文消息条数。 */
    const val DEFAULT_MAX_MESSAGES = 8

    /**
     * 从会话消息中提取精简上下文。
     *
     * @param sessionMessages 当前会话的全部消息
     * @param maxMessages 最大携带条数(取最近 N 条)
     * @param includeImages 是否保留图片消息(默认 false,委派子助手时不传图)
     */
    fun build(
        sessionMessages: List<UIMessage>,
        maxMessages: Int = DEFAULT_MAX_MESSAGES,
        includeImages: Boolean = false,
    ): List<UIMessage> {
        return sessionMessages
            .takeLast(maxMessages)
            .filter { msg ->
                msg.content.isNotBlank() && (includeImages || msg.imageUrls.isEmpty())
            }
            .map { msg ->
                if (includeImages) {
                    // 即使保留图片,也去掉工具调用元数据、推理链等多余字段,
                    // 避免子助手请求出现不完整的 tool_calls 或不支持的 reasoning_content。
                    msg.copy(
                        toolCalls = null,
                        toolCallId = null,
                        toolCallInfo = null,
                        reasoning = null,
                        thinkingSignature = null,
                    )
                } else {
                    // 委派子助手时走纯文本路径:清空图片、视频、工具调用、推理链等字段,
                    // 防止向纯文本模型发送图片,或向未启用 tool calling 的请求发送孤儿 toolCalls。
                    msg.copy(
                        imageUrls = emptyList(),
                        imageBase64List = emptyList(),
                        videoFileUri = null,
                        videoMimeType = null,
                        toolCalls = null,
                        toolCallId = null,
                        toolCallInfo = null,
                        reasoning = null,
                        thinkingSignature = null,
                        citationUrls = emptyList(),
                        ragCitations = emptyList(),
                        artifactIds = emptyList(),
                        mood = null,
                        reflection = null,
                        quotedContent = null,
                        reaction = null,
                    )
                }
            }
    }
}
