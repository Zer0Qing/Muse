package io.zer0.muse.data

import android.content.Context
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.memory.llm.MemoryLlmClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import io.zer0.muse.R

/**
 * [MemoryLlmClient] 的 app 端实现。
 *
 * memory 模块只定义接口,不直接依赖 [ChatService] —— 这样 memory 模块可以
 * 单独编译。本类由 app 模块注册到 Koin,把 memory 系统的 LLM 调用桥接到
 * [ChatService.completeText](走 Provider 配置 + OpenAI 兼容协议)。
 *
 * 这里复用 ChatService 的 Provider 配置:用户在设置页改 API Key / baseUrl,
 * memory 路径自动跟着生效。
 *
 * v1.78: 加 withTimeout 防止 LLM 调用挂起导致 daily pipeline 永久卡死;
 *        加 Logger 便于排查记忆编译失败;model 为 null 时显式校验。
 *
 * v1.0.50 (记忆系统修复): 对齐 既有实现 的三层防御:
 *  1. **reasoning 兜底**: 推理模型(DeepSeek-R1 / GLM-Z1 等)服务端强制开思考,
 *     可能把全部输出放进 reasoning_content 而 content 为空。此时用 reasoningContent
 *     兜底,避免记忆链路从源头拿到空字符串导致 memory.md 永远 59 字符占位。
 *  2. **think 标签清理**: 部分模型用 `<think>...</think>` 标签包裹输出,在 LLM 客户端
 *     层先清理一道(memory 模块的 [io.zer0.memory.state.CompiledMemoryState] 是第二道)。
 *  3. **reasoning buffer**: 推理模型 +1024 token buffer,防止思考过程挤占可见输出
 *     (使用 `withReasoningHeadroom`)。
 *  4. **空响应报错**: text 和 reasoning 都空时抛错,而非静默返回空串让上层误判成功。
 *
 * 超时语义(v1.80 L-MEM1): [withTimeout] 超时抛 [kotlinx.coroutines.TimeoutCancellationException]
 * (继承 CancellationException)。下方 catch(CancellationException) 会将其原样向上抛出。
 */
class MemoryLlmClientImpl(
    private val chatService: ChatService,
    private val settings: SettingsRepository,
    private val context: Context,
) : MemoryLlmClient {

    override suspend fun callText(
        systemPrompt: String,
        userContent: String,
        model: Model?,
        temperature: Float,
        maxTokens: Int,
        timeoutMs: Long,
    ): String {
        val resolvedModel = model ?: settings.getSelectedModel()
        if (resolvedModel == null) {
            throw IllegalStateException(context.getString(R.string.memory_llm_no_model_configured))
        }
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = systemPrompt),
            UIMessage(role = MessageRole.USER, content = userContent),
        )
        // v1.0.50: reasoning buffer — 推理模型 +1024 token,防止思考挤占可见输出
        val effectiveMaxTokens = withReasoningBuffer(maxTokens, resolvedModel)
        // M-MEM1: 对可重试错误(429/5xx/SocketTimeoutException/IOException)做有限重试,
        // 最多 3 次(1 次原始 + 2 次重试),指数退避 500ms / 1000ms。
        val maxAttempts = 3
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                return withTimeout(timeoutMs) {
                    val completion = chatService.completeText(
                        messages = messages,
                        model = resolvedModel,
                        temperature = temperature,
                        maxTokens = effectiveMaxTokens,
                    )
                    // v1.0.50: reasoning 兜底 — text 为空时用 reasoningContent,
                    //   避免推理模型把输出全放进 reasoning 导致记忆链路拿到空串
                    val raw = completion.text.ifBlank { completion.reasoningContent.orEmpty() }
                    if (raw.isBlank()) {
                        // text 和 reasoning 都空 → 明确报错,而非静默返回空串让上层误判成功
                        throw IllegalStateException(
                            context.getString(R.string.memory_llm_empty_response, resolvedModel.id),
                        )
                    }
                    // v1.0.50: LLM 客户端层 think 标签清理(第一道防御)
                    stripThinkTags(raw)
                }
            } catch (e: CancellationException) {
                // M-MEM1: 协程取消必须向上抛出,不可在下面的 catch (e: Exception) 中被吞掉或被记录为失败
                throw e
            } catch (e: Exception) {
                // 流式降级:部分中转站不支持非流式 /v1/chat/completions(stream=false),
                // 返回 HTTP 400 invalid_request_error。此时回退到 streamChat 收集完整文本。
                if (isHttp400(e) && attempt == 1) {
                    Logger.i("MemoryLlmClient", "completeText HTTP 400,降级为流式调用 (model=${resolvedModel.id})")
                    return@callText withTimeout(timeoutMs) {
                        val contentSb = StringBuilder()
                        val reasoningSb = StringBuilder()
                        chatService.streamChat(
                            messages = messages,
                            model = resolvedModel,
                            temperature = temperature,
                            maxTokens = effectiveMaxTokens,
                            mode = io.zer0.ai.core.ChatRequestMode.UTILITY,
                        ).collect { event ->
                            when (event) {
                                is ChatStreamEvent.ContentDelta -> contentSb.append(event.delta)
                                is ChatStreamEvent.ReasoningDelta -> reasoningSb.append(event.delta)
                                else -> Unit
                            }
                        }
                        // v1.0.50: 流式降级同样 reasoning 兜底
                        val raw = contentSb.toString().ifBlank { reasoningSb.toString() }
                        if (raw.isBlank()) {
                            // v1.0.53: 流式降级后仍为空,优先抛出原始 HTTP 400 错误(如 thinking 字段不支持),
                            // 而非"空响应"错误 — 避免掩盖真正的失败原因(如 UNKNOWN_FIELD: thinking)
                            throw e
                        }
                        stripThinkTags(raw)
                    }
                }
                lastError = e
                if (!isRetryable(e) || attempt == maxAttempts) {
                    Logger.w(
                        "MemoryLlmClient",
                        "callText failed (model=${resolvedModel.id}, maxTokens=$effectiveMaxTokens, attempt=$attempt/$maxAttempts)",
                        e,
                    )
                    throw e
                }
                val backoffMs = 500L * (1L shl (attempt - 1)) // 500ms, 1000ms
                Logger.w(
                    "MemoryLlmClient",
                    "callText 可重试失败,attempt=$attempt/$maxAttempts,${backoffMs}ms 后重试 (model=${resolvedModel.id})",
                    e,
                )
                delay(backoffMs)
            }
        }
        throw lastError ?: IllegalStateException(context.getString(R.string.memory_llm_call_text_failed))
    }

    /**
     * v1.0.50: reasoning buffer — 使用 `withReasoningHeadroom`。
     *
     * 推理模型(ModelAbility.REASONING)的思考过程会消耗 token 预算,如果 maxTokens 设得太小,
     * 思考结束后可见输出可能被截断。所以在可见预算基础上加 buffer,让模型有空间完成思考
     * 并输出完整结果。非推理模型不加 buffer。
     */
    private fun withReasoningBuffer(visibleMaxTokens: Int, model: Model): Int {
        if (visibleMaxTokens <= 0) return visibleMaxTokens
        if (ModelAbility.REASONING !in model.abilities) return visibleMaxTokens
        val buffer = REASONING_BUFFER_TOKENS
        val modelLimit = model.maxOutputTokens?.takeIf { it > 0 } ?: return visibleMaxTokens + buffer
        return maxOf(visibleMaxTokens, minOf(modelLimit, visibleMaxTokens + buffer))
    }

    /**
     * v1.0.50: 去除 `<think>...</think>` / `<thinking>...</thinking>` 标签块。
     *
     * 部分模型(尤其走中转站的)用标签而非 reasoning_content 字段包裹思考内容,
     * 如果不清理,标签内容会被当作正文存入记忆,污染 memory.md。
     * 这是 LLM 客户端层的第一道清理,memory 模块的 CompiledMemoryState 是第二道。
     */
    private fun stripThinkTags(value: String): String {
        if (value.isEmpty()) return ""
        var s = value
        // 闭合块
        s = s.replace(Regex("<think(?:ing)?>[\\s\\S]*?</think(?:ing)?>", RegexOption.IGNORE_CASE), "")
        // 残留的开头未闭合块(部分模型只输出 <think> 开头)
        s = s.replace(Regex("<think(?:ing)?>[\\s\\S]*$", RegexOption.IGNORE_CASE), "")
        return s.trim()
    }

    /**
     * M-MEM1: 判断异常是否可重试。
     * - SocketTimeoutException / IOException:网络瞬时失败(连接中断/读写超时)
     * - 消息以 "HTTP 429" 开头:触发限流(Provider 抛出的 HTTP 错误消息形如 "HTTP 429 [触发限流]: ...")
     * - 消息匹配 "HTTP 5xx":服务端临时错误
     */
    private fun isRetryable(e: Throwable): Boolean {
        if (e is java.net.SocketTimeoutException) return true
        if (e is java.io.IOException) return true
        val msg = e.message ?: return false
        if (msg.startsWith("HTTP 429")) return true
        if (Regex("HTTP 5\\d\\d").containsMatchIn(msg)) return true
        return false
    }

    /**
     * 判断异常是否为 HTTP 400(中转站不支持非流式请求等)。
     */
    private fun isHttp400(e: Throwable): Boolean {
        val msg = e.message ?: return false
        return msg.startsWith("HTTP 400")
    }

    private companion object {
        /** v1.0.50: 推理模型 buffer token 数(对齐 既有实现 DEFAULT_REASONING_HEADROOM_TOKENS)。 */
        private const val REASONING_BUFFER_TOKENS = 1024
    }
}
