package io.zer0.muse.tools

import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ProviderError
import io.zer0.ai.core.UIMessage
import io.zer0.ai.core.ProviderException
import io.zer0.ai.ChatService
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.transformer.stripThinkTags
import org.koin.core.context.GlobalContext

/**
 * P1-3b 拆域：翻译工具实现（从 ToolRegistry.kt 原样迁移）。
 * 由 TranslateToolsRegistrar 注册到 ToolRegistry。
 */
class TranslateToolsImpl {

    suspend fun execTranslate(args: Map<String, String>): String {
        val text = args["text"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: text"
        val targetLanguage = args["target_language"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: target_language"
        val sourceLanguage = args["source_language"]?.takeIf { it.isNotBlank() }
        val style = args["style"]?.takeIf { it.isNotBlank() } ?: "通用"

        val styleInstruction = when (style) {
            "学术" -> "使用学术风格,用词正式严谨。"
            "商务" -> "使用商务风格,用词专业得体。"
            "口语化" -> "使用口语化风格,自然易懂。"
            "润色" -> "在翻译基础上润色,使译文更流畅优美。"
            "简洁" -> "使用简洁风格,用词精炼。"
            else -> "" // 通用,无额外指令
        }

        // 构建 system prompt(按 TranslateViewModel.buildTranslationPrompt)
        val systemPrompt = buildString {
            if (sourceLanguage != null) {
                append("你是一个专业翻译助手。请将下面的文本从$sourceLanguage 翻译为$targetLanguage。")
            } else {
                append("你是一个专业翻译助手。请自动识别下面文本的语言,并将其翻译为$targetLanguage。")
            }
            append("要求:只输出译文,保留原文格式,原文已是目标语言则原样输出。")
            append(styleInstruction)
        }
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = systemPrompt),
            UIMessage(role = MessageRole.USER, content = text),
        )

        // ChatService 通过 Koin 获取(Safe Mode 下 Koin 可能未初始化,走 resultOf 兜底)
        val koin = resultOf { GlobalContext.get() }.getOrNull()
            ?: return "翻译服务不可用(Koin 未初始化)"
        val chatService = resultOf { koin.get<ChatService>() }.getOrNull()
            ?: return "翻译服务不可用(ChatService 未注册)"

        return resultOf {
            // 优先 completeText(一次性返回,速度快)
            val translated: String = try {
                val completion = chatService.completeText(messages = messages)
                stripThinkTags(completion.text).trim()
            } catch (e: UnsupportedOperationException) {
                // Provider 未实现 completeText,降级流式
                collectTranslateStream(chatService, messages)
            } catch (e: Exception) {
                // 其他错误也降级流式(网络抖动等)
                Logger.w("ToolRegistry", "translate completeText 失败,降级 streamChat", e)
                collectTranslateStream(chatService, messages)
            }
            if (translated.isEmpty()) "翻译结果为空,请检查原文或目标语言。" else translated
        }.onError { msg, _ -> Logger.w("ToolRegistry", "translate 失败: $msg") }
            .getOrNull() ?: "翻译失败,请稍后重试。"
    }

    /** 流式收集翻译结果(completeText 不可用时的降级路径)。 */
    /** 流式收集翻译结果(completeText 不可用时的降级路径)。 */
    suspend fun collectTranslateStream(
        chatService: ChatService,
        messages: List<UIMessage>,
    ): String {
        val sb = StringBuilder()
        chatService.streamChat(messages = messages).collect { event ->
            when (event) {
                is ChatStreamEvent.ContentDelta -> sb.append(event.delta)
                is ChatStreamEvent.Error -> throw ProviderException(
                    providerError = ProviderError.Unknown(displayMessage = event.message),
                    cause = event.throwable,
                )
                else -> { /* 忽略 ReasoningDelta / ToolCallDelta / ImageDelta / Done 等 */ }
            }
        }
        return stripThinkTags(sb.toString()).trim()
    }
}
