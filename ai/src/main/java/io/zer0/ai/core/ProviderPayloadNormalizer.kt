package io.zer0.ai.core

import io.zer0.common.Logger

/**
 * v1.0.5: Provider 出口统一兜底层。
 *
 * 参考 openhanako 的 core/provider-compat.ts 的 normalizeProviderPayload,
 * 在 Provider 把 [UIMessage] 列表翻译成具体协议请求体之前,做一轮与 Provider 无关的
 * 通用清理,消除"历史消息中残留的孤儿 TOOL / 冗余图片标记"等会导致中转站返回 400 的问题。
 *
 * 职责(对齐 openhanako):
 *  1. [stripOrphanToolMessages] — 删除没有前驱 ASSISTANT tool_calls 匹配的孤儿 TOOL 消息
 *  2. [stripNativeMediaAttachmentMarkers] — 当消息携带真实图片/视频时,从 content 中清理
 *     `[attached_image:...]` / `[attached_video:...]` / `[attached_audio:...]` 标记
 *
 * 不在本层处理(由各 Provider 在构造 DTO 时直接判断):
 *  - stripEmptyTools: OpenAIProvider/AnthropicProvider 在构造 OpenAIRequest/AnthropicRequest
 *    时把空 `tools` 列表改为 null(避免序列化出 `"tools": []` 被严格中转站拒绝)
 *  - stripDisabledReasoningEffort: OpenAIProvider 在构造 OpenAIRequest 时把值为
 *    false/none/off 的 reasoning_effort 改为 null
 *  - stripIncompatibleThinking: v1.0.7 后 OpenAIProvider.injectThinkingFormat 按
 *    [ThinkingFormat] 主动注入对应厂商扩展字段(thinking / enable_thinking /
 *    chat_template_kwargs / reasoning),不会产生不兼容字段;ANTHROPIC 格式为 no-op
 *    (AnthropicProvider 走原生 Messages API,不经过 OpenAIProvider 路径)。
 *    故 1muse 当前无需 openhanako 那样的通用 stripIncompatibleThinking 兜底。
 *    未来若实现 ProviderSpecificConfig.Custom.requestTemplate 路径(用户自定义请求模板
 *    可能注入 thinking 字段),需在 injectThinkingFormat 之后加 stripIncompatibleThinking
 *    兜底,剥离与 thinkingFormat 不匹配的 thinking 字段。
 *
 * 调用时机:OpenAIProvider.buildRequestBody / AnthropicProvider.splitSystem 之前,
 * Provider 把 `request.messages` 先过一遍 [normalizeMessages],再做协议翻译。
 */
object ProviderPayloadNormalizer {
    private const val TAG = "ProviderPayloadNormalizer"

    // ── 媒体附件标记正则(对齐 openhanako 的 ATTACHED_MEDIA_MARKER_RE)──
    // 匹配 [attached_image: 任意字符] + 可选换行,global 替换
    private val ATTACHED_IMAGE_MARKER_RE = Regex("""\[attached_image:\s*[^\]]+\]\n?""")
    private val ATTACHED_VIDEO_MARKER_RE = Regex("""\[attached_video:\s*[^\]]+\]\n?""")
    private val ATTACHED_AUDIO_MARKER_RE = Regex("""\[attached_audio:\s*[^\]]+\]\n?""")
    private val MULTIPLE_BLANK_LINES_RE = Regex("""\n{3,}""")

    /**
     * 对 [UIMessage] 列表做 Provider 无关的通用清理。
     *
     * @param messages 原始对话历史(含 system / user / assistant / tool)
     * @param model 目标模型(当前未使用,预留用于未来按模型能力差异化清理)
     * @return 清理后的消息列表(可能比原列表短)
     */
    fun normalizeMessages(messages: List<UIMessage>, model: Model): List<UIMessage> {
        if (messages.isEmpty()) return messages
        var result = messages
        result = stripOrphanToolMessages(result)
        result = stripNativeMediaAttachmentMarkers(result)
        return result
    }

    // ── 1. 孤儿 TOOL 消息清理 ──

    /**
     * 删除没有前驱 ASSISTANT tool_calls 匹配的孤儿 TOOL 消息。
     *
     * 场景:
     *  - 用户手动编辑历史,删除了某个 ASSISTANT 消息但保留了对应的 TOOL 回填消息
     *  - 工具调用循环中 ASSISTANT 消息被异常中断,但 TOOL 消息已追加到历史
     *  - 上游 SDK 的 transform-messages 逻辑丢弃了 ASSISTANT 的 tool_calls 字段
     *
     * OpenAI 兼容协议(尤其严格的中转站)要求每个 `role: tool` 消息都有前驱带匹配
     * `tool_calls` 的 assistant 消息,否则返回 400 invalid_request_error:
     * `An assistant message with 'tool_calls' must be turned before ...`
     *
     * 对齐 openhanako core/provider-compat/tool-pairing.ts 的 stripOrphanToolResults。
     *
     * 实现说明:收集所有 ASSISTANT 消息携带的 tool_calls id,然后保留 toolCallId
     * 在集合中的 TOOL 消息。这等价于"存在匹配"——在正常对话流中,ASSISTANT 的
     * tool_calls 总是出现在对应的 TOOL 消息之前,因此"存在匹配"与"前驱匹配"等价。
     */
    private fun stripOrphanToolMessages(messages: List<UIMessage>): List<UIMessage> {
        // 收集所有 ASSISTANT 消息携带的 tool_calls id
        val parentToolCallIds = HashSet<String>()
        messages.forEach { msg ->
            if (msg.role == MessageRole.ASSISTANT && !msg.toolCalls.isNullOrEmpty()) {
                msg.toolCalls.forEach { tc ->
                    if (tc.id.isNotBlank()) parentToolCallIds.add(tc.id)
                }
            }
        }

        val hasToolMsg = messages.any { it.role == MessageRole.TOOL }
        if (!hasToolMsg) return messages

        if (parentToolCallIds.isEmpty()) {
            // 没有任何 ASSISTANT tool_calls,所有 TOOL 消息都是孤儿
            val removed = messages.count { it.role == MessageRole.TOOL }
            Logger.w(TAG, "stripOrphanToolMessages: 无 ASSISTANT tool_calls,删除 $removed 条孤儿 TOOL 消息")
            return messages.filter { it.role != MessageRole.TOOL }
        }

        // 保留非 TOOL 消息 + toolCallId 在 parentToolCallIds 中的 TOOL 消息
        var removed = 0
        val filtered = messages.filter { msg ->
            if (msg.role != MessageRole.TOOL) {
                true
            } else {
                val matched = msg.toolCallId != null && msg.toolCallId in parentToolCallIds
                if (!matched) {
                    removed++
                    Logger.w(
                        TAG,
                        "stripOrphanToolMessages: 删除孤儿 TOOL 消息 toolCallId=${msg.toolCallId}",
                    )
                }
                matched
            }
        }
        return if (removed == 0) messages else filtered
    }

    // ── 2. 原生媒体附件标记清理 ──

    /**
     * 当消息携带真实图片([UIMessage.imageBase64List])或视频([UIMessage.videoFileUri])时,
     * 从 content 中清理 `[attached_image:...]` / `[attached_video:...]` / `[attached_audio:...]` 标记。
     *
     * 场景:
     *  - 上游调用方(如文件引用 / 工具结果)在 content 里插入了 `[attached_image: path]`
     *    作为占位符,同时 imageBase64List 携带了真实图片 base64
     *  - Provider 会把真实图片翻译成 `image_url` / `source.base64` 发给模型
     *  - 如果不清理标记,模型会同时看到标记文本和真实图片,造成混淆
     *
     * 当消息没有真实图片/视频时,保留标记(让模型至少知道有图片引用,即使看不到原图)。
     *
     * 对齐 openhanako core/provider-compat.ts 的 stripNativeMediaAttachmentMarkers。
     */
    private fun stripNativeMediaAttachmentMarkers(messages: List<UIMessage>): List<UIMessage> {
        var changed = false
        val result = messages.map { msg ->
            val hasImage = msg.imageBase64List.isNotEmpty()
            val hasVideo = !msg.videoFileUri.isNullOrBlank()
            if (!hasImage && !hasVideo) return@map msg
            val cleaned = stripMediaMarkers(msg.content, stripImage = hasImage, stripVideo = hasVideo)
            if (cleaned == msg.content) return@map msg
            changed = true
            msg.copy(content = cleaned)
        }
        return if (changed) result else messages
    }

    /**
     * 从文本中删除媒体附件标记,并规整多余空行。
     */
    private fun stripMediaMarkers(
        text: String,
        stripImage: Boolean,
        stripVideo: Boolean,
    ): String {
        if (text.isEmpty()) return text
        var result = text
        if (stripImage) result = result.replace(ATTACHED_IMAGE_MARKER_RE, "")
        if (stripVideo) {
            result = result.replace(ATTACHED_VIDEO_MARKER_RE, "")
            // 视频场景通常也伴随音频轨道,一并清理
            result = result.replace(ATTACHED_AUDIO_MARKER_RE, "")
        }
        // 规整 3+ 连续换行为 2 个,并 trim 首尾空白(对齐 openhanako)
        result = result.replace(MULTIPLE_BLANK_LINES_RE, "\n\n").trim()
        return result
    }
}
