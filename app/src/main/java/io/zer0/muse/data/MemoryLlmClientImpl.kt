package io.zer0.muse.data

import android.content.Context
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
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
 * 超时语义(v1.80 L-MEM1): [withTimeout] 超时抛 [kotlinx.coroutines.TimeoutCancellationException]
 * (继承 CancellationException)。下方 catch(CancellationException) 会将其原样向上抛出。
 * 调用方若需区分"超时"与"协程被外部取消",应单独 catch TimeoutCancellationException 处理:
 * ```
 * try { client.callText(...) }
 * catch (e: TimeoutCancellationException) { /* 超时 */ }
 * catch (e: CancellationException) { /* 外部取消 */ }
 * ```
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
        // M-MEM1: 对可重试错误(429/5xx/SocketTimeoutException/IOException)做有限重试,
        // 最多 3 次(1 次原始 + 2 次重试),指数退避 500ms / 1000ms。
        // 避免单次瞬时失败直接导致记忆编译失败。
        val maxAttempts = 3
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                return withTimeout(timeoutMs) {
                    val completion = chatService.completeText(
                        messages = messages,
                        model = resolvedModel,
                        temperature = temperature,
                        maxTokens = maxTokens,
                    )
                    completion.text
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
                        val sb = StringBuilder()
                        chatService.streamChat(
                            messages = messages,
                            model = resolvedModel,
                            temperature = temperature,
                            maxTokens = maxTokens,
                            mode = io.zer0.ai.core.ChatRequestMode.UTILITY,
                        ).collect { event ->
                            if (event is ChatStreamEvent.ContentDelta) {
                                sb.append(event.delta)
                            }
                        }
                        sb.toString()
                    }
                }
                lastError = e
                if (!isRetryable(e) || attempt == maxAttempts) {
                    Logger.w(
                        "MemoryLlmClient",
                        "callText failed (model=${resolvedModel.id}, maxTokens=$maxTokens, attempt=$attempt/$maxAttempts)",
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
}
