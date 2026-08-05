package io.zer0.ai.openai

import io.zer0.common.ErrorCode
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.FreeModelConfig
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ModelContextWindowRegistry
import io.zer0.ai.core.ProviderCompat
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderError
import io.zer0.ai.core.ProviderException
import io.zer0.ai.core.ProviderHttpSupport
import io.zer0.ai.core.ProviderPayloadNormalizer
import io.zer0.ai.core.ProviderPromptPatches
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ReasoningCarrier
import io.zer0.ai.core.ReasoningReplayPolicy
import io.zer0.ai.core.ThinkingFormat
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolCallSanitizer
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.ai.core.toProviderException
import io.zer0.ai.core.ProviderTemplateEngine
import io.zer0.ai.ollama.OllamaVisionInferrer
import io.zer0.ai.registry.ModelRegistry
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.common.toMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * OpenAI 兼容 Provider。同时兼容所有走 OpenAI Chat Completions 协议的中转/自托管服务,
 * 仅 [ProviderConfig.baseUrl] 不同。
 *
 * - 流式: 走 SSE,`data: [DONE]` 表示结束
 * - 取消: UI 调 [ChatRequest.abortSignal] -> 取消 OkHttp Call
 * - 错误: HTTP 非 200 时读 body 提取 OpenAI 错误结构,失败回退原始文本
 */
class OpenAIProvider(
    config: ProviderConfig,
) : ProviderHttpSupport(config) {

    override val id: String get() = config.id
    override val displayName: String get() = config.displayName

    /** 解析后的 OpenAI 特定配置(specific 为 null 时按 type 兜底)。L-OAI12: by lazy 避免重复解析。 */
    private val openAIConfig: ProviderSpecificConfig.OpenAI by lazy {
        config.resolvedSpecific() as? ProviderSpecificConfig.OpenAI
            ?: ProviderSpecificConfig.OpenAI()
    }

    /** B3-05: Custom 供应商专用配置(自定义请求模板 / 响应路径 / headers / body 字段)。 */
    private val customConfig: ProviderSpecificConfig.Custom? by lazy {
        config.specific as? ProviderSpecificConfig.Custom
    }

    /**
     * v1.0.7: 是否走 Responses API(/v1/responses 端点)。
     *
     * 按 OpenAI 官方 Responses API 协议实现。
     * 当 [ProviderSpecificConfig.OpenAI.useResponseApi]=true 时:
     *  - streamChat/completeText 改走 [responsesPath] 端点(默认 /responses)
     *  - 请求体改用 ResponsesRequest 结构(messages → input, system → instructions)
     *  - 流式响应改用 response.output_text.delta 事件(替代 delta.content)
     *  - 非流式响应改用 output[] 数组(替代 choices[0].message)
     *
     * 修复"骗用户 bug":ProviderType.OPENAI_RESPONSES + useResponseApi=true 已声明,
     * 但旧版 OpenAIProvider 完全不读此标志,始终走 Chat Completions 协议,
     * 导致用户配置 Responses API 后实际发 Chat Completions 格式到 /chat/completions,
     * 而 Responses API 端点是 /responses 且请求/响应结构完全不同 — 会 404 或 400。
     */
    private val useResponsesApi: Boolean by lazy { openAIConfig.useResponseApi }

    private val sseFactory by lazy { EventSources.createFactory(httpClient) }

    /**
     * 获取实际发送给 API 的 model id。
     *
     * v1.0.2 修复 HTTP 400: 移除 v1.135 引入的自动 `substringAfterLast("/")` 剥离逻辑。
     *
     * 兼容实践结论:全部原样透传 model id,
     * 不做前缀剥离。OpenRouter / Console GO / new-api 等中转站明确要求保留 "provider/model"
     * 斜杠前缀(例如 `openai/gpt-4o`、`anthropic/claude-3`),自动剥离会让中转站找不到
     * 模型,返回 HTTP 400 invalid_request_error。
     *
     * v1.135 的自动剥离本是"修复"中转站 400,实际反而成了新的 400 根因。
     *
     * 现策略:
     * - 默认原样透传
     * - 用户需要剥离时,显式配置 [ProviderSpecificConfig.OpenAI.stripModelPrefix]
     *   (例如 stripModelPrefix = "openai/",会剥离成 "gpt-4o")
     */
    private fun effectiveModelId(modelId: String): String {
        val configuredPrefix = openAIConfig.stripModelPrefix.takeIf { it.isNotBlank() }
        return if (configuredPrefix != null && modelId.startsWith(configuredPrefix)) {
            modelId.removePrefix(configuredPrefix)
        } else {
            modelId
        }
    }

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> {
        // v1.0.7: useResponseApi=true 时走 Responses API 分支(修复"骗用户 bug")
        if (useResponsesApi) return streamChatResponses(request)
        return streamChatCompletions(request)
    }

    /**
     * v1.0.7: Chat Completions API 流式(原 streamChat 主体,提取为独立函数便于分支)。
     */
    private fun streamChatCompletions(request: ChatRequest): Flow<ChatStreamEvent> = callbackFlow {
        val body = buildRequestBody(request)
        // M-OAI4: 改用配置项 chatCompletionsPath(支持 Azure 等中转自定义路径)
        val url = baseUrl() + openAIConfig.chatCompletionsPath
        Logger.i("OpenAIProvider", "streamChat: POST $url model=${request.model} msgs=${request.messages.size}")

        // v1.0.1: httpRequest 改为 var,429 切换 key 后重新构造
        fun buildHttpRequest(): Request = Request.Builder()
            .url(url)
            // v1.0.18: 走免费模型 fallback(用户未填 key + SiliconFlow 白名单模型 → 用内置 key)
            .header("Authorization", "Bearer ${resolveEffectiveApiKey(request.model.id)}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .apply { customConfig?.customHeaders?.forEach { (k, v) -> header(k, v) } }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        var httpRequest = buildHttpRequest()

        // 捕获 ProducerScope,供匿名 listener 内启动重连协程
        val scope = this@callbackFlow

        // v1.135 性能诊断:记录各阶段耗时,帮助定位 OpenCode 等中转平台的延迟来源
        val requestStartAt = System.currentTimeMillis()
        var firstByteAt = 0L
        var firstDeltaAt = 0L

        // M-OAI3: 有限次指数退避重连参数
        val retryCount = AtomicInteger(0)
        // M-OAI3: 若已发出 tool_call 增量则不重试(避免重复)
        val toolCallDeltaSent = AtomicBoolean(false)
        // H-OAI1: 任何 ContentDelta/ReasoningDelta/ToolCallDelta 发出后置 true,
        //   重试前检查 !anyDeltaSent.get(),避免已流出内容被重发(原仅检查 toolCallDeltaSent
        //   不足以覆盖纯文本/纯推理场景的重发风险)。
        val anyDeltaSent = AtomicBoolean(false)
        // M-OAI4: tool_call index 兜底,用累积 Map 按 API index 分配本地递增 index
        val toolCallIndexMap = mutableMapOf<Int, Int>()
        var nextToolCallIndex = 0
        // v1.0.20: stream-guard — 累积 tool_call 的 name / arguments / 已发送标志,
        //   用于拦截空 name 的无效 tool call 并在 Done 时恢复为 ContentDelta
        val toolCallAccMap = mutableMapOf<Int, ToolCallAccState>()
        // v1.0.21: 防止 emitDoneWithStreamGuard 被双重执行(finishReason + [DONE] 各触发一次),
        //   导致空 name tool call 恢复的文本被发送两遍,产生重复内容。
        val streamGuardDone = AtomicBoolean(false)
        // v1.0.23: 流式过早结束检测 — 商汤 deepseek-v4-flash 等 API 流式实现有 bug,
        //   first delta 后立即 finish(只输出 1-4 个字符),但非流式能正常返回完整内容。
        //   记录 ContentDelta 总字符数,在 Done 时检查,若过少且耗时极短则回退到 completeText。
        val contentCharsSent = AtomicInteger(0)
        val emptyNameToolCallCount = AtomicInteger(0)
        val firstDeltaTimestamp = AtomicLong(0L)
        // v1.0.48: reasoning 字符计数 — 当流式只发 reasoning 不发 content 就结束时
        //   (商汤 deepseek-v4-flash 的已知行为),不应触发非流式回退,否则会导致:
        //   1) 回退请求也容易 Connection reset,耗时 20s+ 让对话"卡住不结束"
        //   2) generation finished 后回退仍在后台跑,产生孤立请求
        //   有 reasoning 说明模型确实响应了,直接结束流式让用户看到思考过程即可。
        val reasoningCharsSent = AtomicInteger(0)
        // v1.0.24: 回退协程进行中标志 — 防止第二次 emitDoneWithStreamGuard 调用
        //   (finishReason + [DONE] 各触发一次)在回退协程完成前提前 close flow,
        //   导致 completeText 的结果无法通过已关闭的 channel 发送。
        val pendingFallback = AtomicBoolean(false)

        /**
         * v1.0.20: stream-guard — 在 Done 事件前检查累积的 toolCallAccMap,
         *   把空 name 但有 arguments 的无效 tool call 恢复为 ContentDelta(可见文本)。
         *
         * 实现 `recoverInvalidToolCallText` 语义:
         *  - 空 name 的 tool call 不能执行(找不到对应工具),原本会被静默丢弃
         *  - 把累积的 arguments 作为正文恢复,让用户看到模型实际生成的内容
         *  - 拦截发生在 Done 事件而非每个 delta,因为流式中 name 可能稍后才到
         */
        fun emitDoneWithStreamGuard(finishReason: String?) {
            // v1.0.24: 诊断日志 — 确认 emitDoneWithStreamGuard 是否被调用及各变量值
            // v1.0.21: 防止双重执行 — finishReason 和 [DONE] 各触发一次时,
            //   第二次直接发 Done 并 close,跳过已恢复的文本,避免重复内容。
            // v1.0.24: 若回退协程进行中,第二次调用直接 return,不 close,
            //   等回退协程完成后自行发 Done + close
            // v1.0.53: 第二次调用(finishReason=null)是正常的双触发收尾,不打印 diagnose
            //   (避免日志里出现误导性的 finishReason=null + aborted=true 组合)。
            if (!streamGuardDone.compareAndSet(false, true)) {
                if (pendingFallback.get()) {
                    Logger.d("OpenAIProvider", "stream-guard: 回退进行中, 跳过 Done 事件")
                    return
                }
                // v1.0.53: 第一次 emitDone 已发送 Done+close,此处只需确保流关闭,不重发 Done。
                //   finishReason 和 [DONE] 双触发时,第二次 Done 会带 null finishReason,
                //   冗余的 Done(null) 可能让上层误判流异常结束并触发 abort。
                close()
                return
            }
            // v1.0.47: diagnose 移到首次执行分支(第二次调用不再打印,避免误导日志)
            Logger.d(
                "OpenAIProvider",
                "stream-guard diagnose: emitDone called | finishReason=$finishReason | " +
                    "contentChars=${contentCharsSent.get()} | reasoningChars=${reasoningCharsSent.get()} | anyDeltaSent=${anyDeltaSent.get()} | " +
                    "aborted=${request.abortSignal.aborted} | useResponsesApi=$useResponsesApi | " +
                    "firstDeltaTs=${firstDeltaTimestamp.get()}",
            )
            // v1.0.52: 在恢复逻辑之前计算 fallback 条件 —
            //   如果 finishReason=tool_calls 且存在空 name tool_call,说明模型流式模式下
            //   没有正确输出工具名,需触发非流式回退(非流式模式下 tool_calls 通常完整返回)。
            //   此时不能把 args 恢复为 ContentDelta(那是工具参数,不是正文),否则用户看到 JSON 幻觉。
            val hasEmptyNameToolCall = toolCallAccMap.values.any { !it.recoveredAsContent && it.name.isNullOrBlank() && it.args.isNotEmpty() }

            // v1.0.23: 商汤 deepseek-v4-flash 等流式过早结束 bug 检测
            //   商汤 API 流式实现有 bug:first delta 后立即 finish(只输出 0-4 个字符,
            //   甚至只发 reasoningContent 不发 content),但非流式能正常返回完整内容(text=50-200 chars)。
            //   v1.0.24: 放宽到 0-9 chars,覆盖仅发 reasoning 而不发 content 的情况。
            //   检测条件:ContentDelta 总字符 0-9 且收到过 anyDeltaSent(first delta 已到),
            //   且 first delta 后 500ms 内就 finish,未被用户 abort,非 Responses API。
            //   v1.0.48: reasoning 也要纳入总字符判断 — 商汤流式只发 1-4 个 reasoning 字符就结束,
            //     这不是完整响应(非流式能返回 253+ chars reasoning)。
            //     contentChars + reasoningChars 合计 < 10 才算"过早结束",触发非流式回退。
            //   v1.0.51: 新增"只有 reasoning 没有 content"的回退条件 — 商汤流式可能发大量 reasoning
            //     但 content 始终为 0,combined > 9 跳过回退,用户看到空回复。
            //     此时也应触发非流式回退(非流式能返回 text 或用 reasoning 兜底)。
            val totalChars = contentCharsSent.get()
            val reasoningChars = reasoningCharsSent.get()
            val combinedChars = totalChars + reasoningChars
            val firstDeltaTime = firstDeltaTimestamp.get()
            val deltaDuration = if (firstDeltaTime > 0) System.currentTimeMillis() - firstDeltaTime else Long.MAX_VALUE
            val shouldFallback = anyDeltaSent.get() &&
                !request.abortSignal.aborted &&
                !useResponsesApi &&
                (
                    // 条件 A: 内容极少(combined < 10),且持续时间短(< 500ms) — 原有逻辑
                    (combinedChars in 0..9 && deltaDuration < 500) ||
                    // 条件 B(v1.0.51): content 为 0 但有 reasoning,且持续时间短(< 2s) —
                    //   模型只输出了思考没有产出正文,用户看到空回复,需回退
                    (totalChars == 0 && reasoningChars > 0 && deltaDuration < 2000) ||
                    // 条件 C(v1.0.52): finishReason=tool_calls 但存在空 name tool_call —
                    //   模型流式模式下没有正确输出工具名(如 GLM-4-9B-0414),
                    //   args 被缓冲但不能作为正文(是工具参数),触发非流式回退让模型重新生成
                    (finishReason == "tool_calls" && hasEmptyNameToolCall)
                )

            // v1.0.52: 只在不回退时才恢复空 name tool_call 为 ContentDelta
            //   回退时不恢复(避免把工具参数当正文发给用户,然后又回退重复发送)
            if (!shouldFallback) {
                toolCallAccMap.forEach { (localIndex, acc) ->
                    // v1.0.22: 跳过已增量恢复的 acc,避免重复发送
                    if (acc.recoveredAsContent) return@forEach
                    if (acc.name.isNullOrBlank() && acc.args.isNotEmpty()) {
                        val recoveredText = acc.args.toString()
                        Logger.w(
                            "OpenAIProvider",
                            "stream-guard: 拦截空 name tool call (localIndex=$localIndex, args=${acc.args.length} chars)," +
                                "恢复为文本: ${recoveredText.take(50)}",
                        )
                        contentCharsSent.addAndGet(recoveredText.length)
                        trySend(ChatStreamEvent.ContentDelta(recoveredText))
                    }
                }
            }
            // 清空 map,防止后续误触发再次恢复
            toolCallAccMap.clear()

            if (shouldFallback) {
                pendingFallback.set(true)
                Logger.w(
                    "OpenAIProvider",
                    "stream-guard: 检测到流式过早结束 (chars=$totalChars, reasoning=$reasoningChars, duration=${deltaDuration}ms), 自动回退到非流式请求 | url=$url",
                )
                scope.launch {
                    try {
                        // v1.0.48: 缩短退避到 500ms+jitter — 原 1.5s 导致结束后卡顿明显,
                        //   429 已有重试机制兜底,无需过长退避
                        val backoffMs = 500L + Random.nextLong(0, 300)
                        Logger.d("OpenAIProvider", "stream-guard: 回退前等待 ${backoffMs}ms(rpm 退避)")
                        delay(backoffMs)
                        if (request.abortSignal.aborted) {
                            Logger.d("OpenAIProvider", "stream-guard: 等待期间用户取消,放弃回退")
                            pendingFallback.set(false)
                            trySend(ChatStreamEvent.Done(finishReason))
                            close()
                            return@launch
                        }
                        // v1.0.49: 二次校验 — 等待期间若流式已恢复正常(收到更多 content/reasoning),
                        //   说明商汤流式的"过早结束"是假告警(后续 delta 延迟到达),放弃回退。
                        //   不发 Done 不 close — 让 SSE 继续接收后续 delta,直到真正的 finishReason
                        //   或 onClosed 正常结束。重置 streamGuardDone 允许后续 emitDone 重新触发
                        //   (此时 contentChars 已 > 9, shouldFallback=false, 直接 Done+close)。
                        // v1.0.51: 必须要求 content 增长才认为"流恢复" — 商汤模型可能只发 reasoning
                        //   不发 content,如果仅 reasoning 增长就放弃回退,后续假 finishReason 到达时
                        //   content 仍为 0,用户看到空回复。只有 content > 0 才说明模型开始产出可见内容。
                        val currentContent = contentCharsSent.get()
                        val currentReasoning = reasoningCharsSent.get()
                        if (currentContent > 0) {
                            Logger.i(
                                "OpenAIProvider",
                                "stream-guard: 等待期间流式已恢复(content=$currentContent, reasoning=$currentReasoning),放弃回退,继续接收流式",
                            )
                            pendingFallback.set(false)
                            streamGuardDone.set(false)
                            return@launch
                        }
                        // v1.0.48: 回退重试 — 商汤 API 偶发 Connection reset,重试 1 次提高成功率
                        var completion: ChatCompletion? = null
                        var lastError: Exception? = null
                        for (attempt in 1..2) {
                            if (request.abortSignal.aborted) break
                            try {
                                completion = completeText(request)
                                break
                            } catch (e: Exception) {
                                lastError = e
                                Logger.w("OpenAIProvider", "stream-guard: 非流式回退第 $attempt 次失败: ${e.message}")
                                if (attempt < 2 && !request.abortSignal.aborted) {
                                    // v1.0.53: 429 限流时等满 RPM 窗口(10s)再重试,避免连续撞限流
                                    val isRateLimited = e.message?.contains("429") == true ||
                                        e.message?.contains("rate limited") == true
                                    delay(if (isRateLimited) 10_000L else 1_000L)
                                }
                            }
                        }
                        if (completion != null) {
                            val text = completion.text.orEmpty()
                            val reasoning = completion.reasoningContent.orEmpty()
                            val toolCalls = ToolCallSanitizer.sanitize(completion.toolCalls.orEmpty())
                            if (toolCalls.isNotEmpty()) {
                                // v1.0.53: 回退成功且模型决策调用工具 — 发送 ToolCallDelta,
                                // 不能把 reasoning 当正文(那是思考过程);工具调用交给上层执行。
                                // 此前 toolCalls 被静默丢弃,导致群聊 channel_pass/reply 丢失、
                                // 单聊工具调用(web_search 等)丢失,用户只看到思考文本。
                                // v1.x: reasoning 仍要发给 UI,否则用户只看到首字、没有思考过程。
                                if (reasoning.isNotEmpty()) {
                                    trySend(ChatStreamEvent.ReasoningDelta(reasoning))
                                }
                                toolCalls.forEachIndexed { idx, tc ->
                                    trySend(
                                        ChatStreamEvent.ToolCallDelta(
                                            index = idx,
                                            id = tc.id,
                                            name = tc.name,
                                            argumentsDelta = "",
                                        ),
                                    )
                                    trySend(
                                        ChatStreamEvent.ToolCallDelta(
                                            index = idx,
                                            argumentsDelta = tc.arguments,
                                        ),
                                    )
                                }
                            } else {
                                // v1.0.51: reasoning 兜底 — deepseek-v4-flash 等推理模型可能把全部输出
                                //   放在 reasoning_content 里(text 为空),此时用 reasoning 作为正文发送,
                                //   否则用户看到空回复。与 MemoryLlmClient 的兜底逻辑一致。
                                val effectiveText = text.ifBlank { reasoning }
                                if (effectiveText.isNotEmpty()) {
                                    trySend(ChatStreamEvent.ContentDelta(effectiveText))
                                }
                                if (text.isNotEmpty() && reasoning.isNotEmpty()) {
                                    trySend(ChatStreamEvent.ReasoningDelta(reasoning))
                                }
                            }
                            Logger.i(
                                "OpenAIProvider",
                                "stream-guard: 非流式回退成功 (text=${text.length} chars, reasoning=${reasoning.length} chars, toolCalls=${toolCalls.size})",
                            )
                        } else {
                            Logger.w("OpenAIProvider", "stream-guard: 非流式回退最终失败: ${lastError?.message}")
                        }
                    } catch (e: Exception) {
                        Logger.w("OpenAIProvider", "stream-guard: 非流式回退异常: ${e.message}")
                    }
                    pendingFallback.set(false)
                    trySend(ChatStreamEvent.Done(finishReason))
                    close()
                }
                return
            }

            // 收尾：用户停止时发 StreamInterrupted（让 FirstEventWatchdog 明确感知中断，
            // 不再等待 15s 超时触发非流式回退）；正常完成才发 Done。
            if (request.abortSignal.aborted) {
                scope.trySend(ChatStreamEvent.StreamInterrupted("用户已停止生成"))
            } else {
                trySend(ChatStreamEvent.Done(finishReason))
            }
            close()
        }

        var currentEventSource: EventSource? = null
        // v1.114 修复: 持有底层 Call 引用,awaitClose 时一并 cancel(与 AnthropicProvider/GeminiProvider 一致),
        //   避免 eventSource 尚未创建或 cancel 不及时导致底层连接泄漏。
        var currentCall: Call? = null

        fun connect() {
            // v1.114 修复: 先 newCall 并持有引用,供 awaitClose cancel(按 AnthropicProvider)
            val call = httpClient.newCall(httpRequest)
            currentCall = call
            currentEventSource = sseFactory.newEventSource(httpRequest, object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    firstByteAt = System.currentTimeMillis()
                    Logger.i("OpenAIProvider", "streamChat TTFB: ${firstByteAt - requestStartAt}ms | url=$url")
                    if (!response.isSuccessful) {
                        val code = response.code
                        // v1.0.1: 429 限流时尝试切换 key重试(多 key 场景)
                        if (code == 429 && !anyDeltaSent.get() && retryCount.get() < MAX_RETRIES &&
                            !request.abortSignal.aborted && switchToNextKey()
                        ) {
                            Logger.i("OpenAIProvider", "streamChat onOpen 429 限流,已切换到下一个 key,立即重试")
                            httpRequest = buildHttpRequest()
                            retryCount.incrementAndGet()
                            eventSource.cancel()
                            currentCall?.cancel()
                            scope.launch {
                                if (!request.abortSignal.aborted && !scope.isClosedForSend) {
                                    connect()
                                }
                            }
                            return
                        }
                        // L-OAI1: 用 readBodySafely 替代 runCatching
                        val errText = ProviderHttpSupport.readBodySafely(response)
                        val msg = parseErrorMessage(code, errText)
                        Logger.w("OpenAIProvider", "streamChat onOpen HTTP $code: $msg")
                        // v1.0.28: HTTP 400 时记录请求体和完整响应体,帮助诊断中转站参数错误
                        if (code == 400) {
                            Logger.w("OpenAIProvider", "streamChat 400 请求体(前500字符): ${body.take(500)}")
                            Logger.w("OpenAIProvider", "streamChat 400 完整响应体: $errText")
                        }
                        // v1.0.1: 401/403 鉴权失败时标记当前 key 失败(多 key 场景)
                        if (code == 401 || code == 403) {
                            markKeyFailed(hardBlock = true)
                        }
                        // L-OAI17: 错误事件携带 throwable,便于上层据此区分错误类型
                        trySend(ChatStreamEvent.Error(msg, OpenAIHttpException(code, msg)))
                        close()
                        return
                    }
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    if (data == "[DONE]") {
                        // v1.0.47: 回退进行中时直接丢弃后续 [DONE] 事件,不进 emitDoneWithStreamGuard,
                        //   从源头减少冗余日志(原商汤会连发 8-10 个空事件触发 diagnose 日志刷屏)
                        if (pendingFallback.get()) return
                        // v1.0.20: stream-guard — Done 事件时检查累积 toolCallAccMap,
                        //   空 name 的 tool call 恢复为 ContentDelta
                        emitDoneWithStreamGuard(null)
                        return
                    }
                    // B3-05: Custom 供应商流式响应路径(如 $.choices[0].delta.content)
                    val customStreamPath = customConfig?.streamResponsePath?.takeIf { it.isNotBlank() }
                    if (customStreamPath != null) {
                        val element = resultOf { AppJson.parseToJsonElement(data) }.getOrNull() ?: return
                        val text = extractTextFromElement(
                            ProviderTemplateEngine.extractByPath(element, customStreamPath),
                        )
                        if (!text.isNullOrEmpty()) {
                            if (firstDeltaAt == 0L) {
                                firstDeltaAt = System.currentTimeMillis()
                                firstDeltaTimestamp.set(firstDeltaAt)
                            }
                            anyDeltaSent.set(true)
                            contentCharsSent.addAndGet(text.length)
                            trySend(ChatStreamEvent.ContentDelta(text))
                        }
                        val standardChunk = resultOf { AppJson.decodeFromString<OpenAIStreamChunk>(data) }.getOrNull()
                        val finish = standardChunk?.choices?.firstOrNull()?.finishReason
                        if (!finish.isNullOrBlank() && !pendingFallback.get()) {
                            emitDoneWithStreamGuard(finish)
                        }
                        return
                    }
                    // M-OAI3: 改用 resultOf(会重抛 CancellationException),替代 runCatching(会吞 CancellationException)
                    val chunk = resultOf {
                        AppJson.decodeFromString<OpenAIStreamChunk>(data)
                    }.getOrNull() ?: return

                    val choice = chunk.choices.firstOrNull() ?: return
                    val delta = choice.delta
                    if (delta != null) {
                        if (firstDeltaAt == 0L) {
                            firstDeltaAt = System.currentTimeMillis()
                            firstDeltaTimestamp.set(firstDeltaAt)
                            Logger.i(
                                "OpenAIProvider",
                                "streamChat first delta: ${firstDeltaAt - requestStartAt}ms " +
                                    "(TTFB=${firstByteAt - requestStartAt}ms) | url=$url",
                            )
                        }
                        // v1.0.49: 回退等待期间仍正常发送 delta — 商汤"假过早结束"后后续 delta 会延迟到达,
                        //   若丢弃则放弃回退时内容已丢失且 flow 被 close,用户只看到首字符。
                        //   改为正常发送:放弃回退时内容不丢;回退成功时仅开头 <10 字符可能重复,可接受。
                        delta.reasoningContent?.takeIf { it.isNotEmpty() }
                            ?.let {
                                anyDeltaSent.set(true)
                                reasoningCharsSent.addAndGet(it.length)
                                trySend(ChatStreamEvent.ReasoningDelta(it))
                            }
                        delta.content?.takeIf { it.isNotEmpty() }
                            ?.let {
                                anyDeltaSent.set(true)
                                contentCharsSent.addAndGet(it.length)
                                trySend(ChatStreamEvent.ContentDelta(it))
                            }
                        // Phase 7: 解析 tool_calls 增量(每个 index 对应一个工具调用,arguments 分片累积)
                        delta.toolCalls?.forEach { tc ->
                            // M-OAI4: 用累积 Map 按 index 分配,避免默认 0 合并多个调用。
                            // 新工具调用(首片携带 id 或 name)触发新 index 分配。
                            val apiIndex = tc.index
                            val isNewCall = tc.id != null || tc.function?.name != null
                            if (isNewCall) {
                                toolCallIndexMap[apiIndex] = nextToolCallIndex++
                            }
                            // L-OAI13: toolCallIndexMap 缺失时(首片丢了/乱序)不 fallback 到 0,
                            //   否则会把该片误并入 index=0 的工具调用。跳过该片并记录警告。
                            val localIndex = toolCallIndexMap[apiIndex]
                            if (localIndex == null) {
                                Logger.w(
                                    "OpenAIProvider",
                                    "tool_calls 片段 apiIndex=$apiIndex 未在 map 中找到(首片丢失?),跳过该片",
                                )
                                return@forEach
                            }
                            // v1.0.20: stream-guard — 累积 name 和 arguments。
                            //   即便 name 为空也累积,Done 时若 name 仍为空则恢复为 ContentDelta。
                            val acc = toolCallAccMap.getOrPut(localIndex) { ToolCallAccState() }
                            if (tc.id != null) acc.id = tc.id
                            tc.function?.name?.takeIf { it.isNotBlank() }?.let { acc.name = it }
                            tc.function?.arguments?.let { acc.args.append(it) }

                            // v1.0.22: 已增量恢复为 ContentDelta 的 acc,后续 args 继续作为 ContentDelta 发送
                            //   (一旦判定为空 name 异常 tool call 并增量恢复,name 后到也不撤回已发送的正文)
                            // v1.0.52: recoveredAsContent 不再被增量设置为 true(改为 Done 时根据 finishReason 决定),
                            //   此分支保留向后兼容但实际不会进入
                            if (acc.recoveredAsContent) {
                                val argsDelta = tc.function?.arguments.orEmpty()
                                if (argsDelta.isNotEmpty()) {
                                    trySend(ChatStreamEvent.ContentDelta(argsDelta))
                                }
                                return@forEach
                            }

                            // stream-guard: 空 name 处理
                            //   v1.0.20: 缓冲到 Done 才恢复
                            //   v1.0.22: 改为立即增量恢复为 ContentDelta
                            //   v1.0.52: 回退为只缓冲不发送 — v1.0.22 的增量恢复导致真实工具调用
                            //     (finishReason=tool_calls) 的 arguments 被误转为正文,用户看到 JSON 片段幻觉。
                            //     改为缓冲到 Done,Done 时根据 finishReason 决定:
                            //       - tool_calls + 空 name → 触发非流式回退(模型流式模式未正确输出工具名)
                            //       - 其他 + 空 name → 恢复为 ContentDelta(正文幻觉)
                            val currentName = acc.name
                            if (currentName.isNullOrBlank()) {
                                toolCallDeltaSent.set(true)
                                anyDeltaSent.set(true)
                                // v1.0.52: 只缓冲,不发送 ContentDelta
                                val stormCount = emptyNameToolCallCount.incrementAndGet()
                                if (stormCount > MAX_EMPTY_NAME_TOOL_CALLS) {
                                    // 风暴:小模型把正文拆成大量独立空 name tool call,继续缓冲会拖慢整轮。
                                    // 超过阈值后按正文增量恢复,让上层尽快拿到文本。真实工具调用不会连续出现这么多空 name。
                                    acc.recoveredAsContent = true
                                    val argsDelta = tc.function?.arguments.orEmpty()
                                    if (argsDelta.isNotEmpty()) {
                                        contentCharsSent.addAndGet(argsDelta.length)
                                        trySend(ChatStreamEvent.ContentDelta(argsDelta))
                                    }
                                    Logger.w(
                                        "OpenAIProvider",
                                        "stream-guard: 空 name tool call 超过 $MAX_EMPTY_NAME_TOOL_CALLS 条,按正文增量恢复 (localIndex=$localIndex)",
                                    )
                                    return@forEach
                                }
                                Logger.d(
                                    "OpenAIProvider",
                                    "stream-guard: 空 name tool call 缓冲中 (localIndex=$localIndex, 累积 args=${acc.args.length} chars)",
                                )
                                return@forEach
                            }

                            // name 已到 — 发送 ToolCallDelta
                            toolCallDeltaSent.set(true)
                            anyDeltaSent.set(true)
                            if (!acc.hasEmitted) {
                                // 首次发送 — name 来晚了,一次性带上累积的 arguments(追赶)
                                trySend(ChatStreamEvent.ToolCallDelta(
                                    index = localIndex,
                                    id = acc.id,
                                    name = currentName,
                                    argumentsDelta = acc.args.toString(),
                                ))
                                acc.hasEmitted = true
                            } else {
                                // 后续增量
                                trySend(ChatStreamEvent.ToolCallDelta(
                                    index = localIndex,
                                    id = tc.id,
                                    name = tc.function?.name,
                                    argumentsDelta = tc.function?.arguments.orEmpty(),
                                ))
                            }
                        }
                    }
                    if (!choice.finishReason.isNullOrBlank()) {
                        // v1.0.47: 回退进行中时丢弃 finishReason 事件(同 [DONE] 早退逻辑)
                        if (pendingFallback.get()) return
                        // v1.0.20: stream-guard — Done 事件时检查累积 toolCallAccMap,
                        //   空 name 的 tool call 恢复为 ContentDelta
                        emitDoneWithStreamGuard(choice.finishReason)
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    // v1.0.23: 商汤等 API 可能直接关闭连接,不发 [DONE] 也不发 finishReason
                    //   若尚未触发 stream-guard,在此触发以检测流式过早结束并回退
                    // v1.0.24: 回退进行中时不 close,等回退协程完成
                    if (pendingFallback.get()) {
                        Logger.d("OpenAIProvider", "streamChat onClosed: 回退进行中, 等待完成")
                        return
                    }
                    if (!streamGuardDone.get()) {
                        Logger.d("OpenAIProvider", "streamChat onClosed: 未收到 Done 事件, 触发 stream-guard")
                        emitDoneWithStreamGuard(null)
                    } else {
                        close()
                    }
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?,
                ) {
                    // v1.0.24: 回退进行中时不 close,等回退协程完成
                    if (pendingFallback.get()) {
                        Logger.d("OpenAIProvider", "streamChat onFailure: 回退进行中, 忽略连接错误 t=${t?.message}")
                        return
                    }
                    if (request.abortSignal.aborted) {
                        // v1.0.53: 区分真实中止与收尾清理 — 流正常完成后 ChatViewModel 会 abort signal
                        //   清理资源,此时若 onFailure 回调在飞,原日志会误导为"用户中止"。
                        if (streamGuardDone.get()) {
                            Logger.d("OpenAIProvider", "streamChat onFailure: 流已完成后的收尾回调(忽略,非用户中止)")
                        } else {
                            Logger.d(
                                "OpenAIProvider",
                                "streamChat aborted by user | contentChars=${contentCharsSent.get()} | " +
                                    "streamGuardDone=${streamGuardDone.get()} | anyDeltaSent=${anyDeltaSent.get()}",
                            )
                            // 用户主动停止：发 StreamInterrupted 让下游（含 FirstEventWatchdog）明确感知中断，
                            // 避免静默 close 导致 watchdog 误判为“无首事件”继续触发非流式回退。
                            scope.trySend(ChatStreamEvent.StreamInterrupted("用户已停止生成"))
                        }
                        close()
                        return
                    }
                    // M-OAI3: 判断是否可重试(408/429/5xx/IOException),且未发出任何增量,且未达重试上限
                    // H-OAI1: 重试条件改用 anyDeltaSent(覆盖 Content/Reasoning/ToolCall 三类增量),
                    //   原仅检查 toolCallDeltaSent 会导致已流出的正文/推理被重发。
                    val code = response?.code
                    val isRetryable = (code != null && (code == 408 || code == 429 || code in 500..599))
                        || (t is java.io.IOException)
                    if (isRetryable && !anyDeltaSent.get()
                        && retryCount.incrementAndGet() <= MAX_RETRIES
                        && !request.abortSignal.aborted
                    ) {
                        val attempt = retryCount.get()
                        var backoffMs = (1L shl attempt) * RETRY_BASE_DELAY_MS
                        // M-OAI7: 429 限流优先用 Retry-After 响应头(秒数 → 毫秒)
                        if (code == 429) {
                            backoffMs = response?.header("Retry-After")?.toIntOrNull()
                                ?.let { it * 1000L } ?: backoffMs
                            // v1.0.1: 429 时切换到下一个 key(多 key 场景)
                            //  切换成功后立即重试(不等 backoff),因为新 key 可能未限流
                            if (switchToNextKey()) {
                                Logger.i("OpenAIProvider", "streamChat 429 限流,已切换到下一个 key,立即重试")
                                backoffMs = 0L
                            }
                        }
                        // L-OAI9: 加 jitter(0~499ms),避免多客户端同步重试引发惊群
                        if (backoffMs > 0) {
                            backoffMs += Random.nextLong(0, 500)
                        }
                        // v1.0.1: key 切换后重新构造 httpRequest(更新 Authorization header)
                        httpRequest = buildHttpRequest()
                        Logger.w("OpenAIProvider", "streamChat onFailure, retry $attempt/$MAX_RETRIES after ${backoffMs}ms: ${t?.message ?: code}")
                        scope.launch {
                            delay(backoffMs)
                            if (!request.abortSignal.aborted && !scope.isClosedForSend) {
                                connect()
                            }
                        }
                        return
                    }
                    // L-OAI1: 用 readBodySafely 替代 runCatching
                    // v1.109 修复: SSE 已建立(2xx)后中断是连接断开,不是 HTTP 错误
                    //   优先用 Throwable 信息,避免构造误导性的 "HTTP 200" 错误
                    val msg = response?.let {
                        if (it.code in 200..299) {
                            t?.message?.takeIf { m -> m.isNotBlank() }
                                ?: ErrorCode.STREAM_INTERRUPTED.toMessage()
                        } else {
                            val bodyText = ProviderHttpSupport.readBodySafely(it)
                            parseErrorMessage(it.code, bodyText)
                        }
                    } ?: (t?.message ?: ErrorCode.NETWORK_ERROR.toMessage())
                    Logger.e("OpenAIProvider", "streamChat onFailure: $msg", t)
                    // v1.0.15: 已收到部分内容时发 StreamInterrupted,让 UI 保留已收内容并提示网络中断(可自动重连)
                    if (anyDeltaSent.get()) {
                        trySend(ChatStreamEvent.StreamInterrupted(msg, t))
                    } else {
                        trySend(ChatStreamEvent.Error(msg, t))
                    }
                    close()
                }
            })
        }

        connect()

        awaitClose {
            request.abortSignal.abort()
            currentEventSource?.cancel()
            // v1.114 修复: 同时 cancel 底层 Call(与 AnthropicProvider/GeminiProvider 一致)
            currentCall?.cancel()
        }
        // v1.0.19: 无界 buffer,防止 EventSource 回调突发投递时 trySend 因内部 channel 满
        //   而丢片(使用无界 buffer)。
        //   callbackFlow 默认容量有限,UI 卡顿/收集慢时可能丢字。
    }.flowOn(Dispatchers.IO).buffer(Channel.UNLIMITED)

    /**
     * 非流式聊天。
     *
     * v1.80 (L-OAI15): 设计决策 — completeText 不做自动重试(不同于 streamChat)。
     *   原因:completeText 主要服务于 memory 编译 / fact 抽取等后台任务,
     *   这些任务由 JobWorker 调度,本身已有重试机制(Worker.Result.retry);
     *   在 Provider 层再加一层重试会放大延迟且难以向用户暴露进度。
     *   如确需重试,应由调用方(Worker)根据返回的异常类型决定。
     */
    override suspend fun completeText(request: ChatRequest): ChatCompletion = completeTextImpl(request, 0)

    private suspend fun completeTextImpl(request: ChatRequest, keySwitchDepth: Int = 0): ChatCompletion = withContext(Dispatchers.IO) {
        // v1.0.7: useResponseApi=true 时走 Responses API 分支
        if (useResponsesApi) return@withContext completeTextResponses(request, keySwitchDepth)
        val body = buildRequestBody(request, stream = false)
        // M-OAI4: 改用配置项 chatCompletionsPath(与 streamChat 一致)
        val url = baseUrl() + openAIConfig.chatCompletionsPath
        Logger.i("OpenAIProvider", "completeText: POST $url model=${request.model}")
        // v1.0.1: 用 effectiveApiKey() 支持多 key 轮换
        // v1.0.18: 走免费模型 fallback(用户未填 key + SiliconFlow 白名单模型 → 用内置 key)
        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${resolveEffectiveApiKey(request.model.id)}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .apply { customConfig?.customHeaders?.forEach { (k, v) -> header(k, v) } }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = httpClient.newCall(httpRequest)
        try {
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val code = resp.code
                    // v1.0.1: 429 切换 key 重试(多 key 场景);401/403 标记 key 失败
                    if (code == 429 && keySwitchDepth < MAX_KEY_SWITCHES && switchToNextKey()) {
                        Logger.i("OpenAIProvider", "completeText 429 限流,已切换到下一个 key,重试 ($keySwitchDepth/$MAX_KEY_SWITCHES)")
                        return@withContext completeTextImpl(request, keySwitchDepth + 1)
                    }
                    if (code == 401 || code == 403) {
                        markKeyFailed(hardBlock = true)
                    }
                    // L-OAI1: 用 readBodySafely 替代 runCatching
                    val errText = ProviderHttpSupport.readBodySafely(resp)
                    val msg = parseErrorMessage(code, errText)
                    Logger.w("OpenAIProvider", "completeText HTTP $code: $msg")
                    // v1.0.28: HTTP 400 时记录请求体和完整响应体,帮助诊断中转站参数错误
                    if (code == 400) {
                        Logger.w("OpenAIProvider", "completeText 400 请求体(前500字符): ${body.take(500)}")
                        Logger.w("OpenAIProvider", "completeText 400 完整响应体: $errText")
                    }
                    // L-OAI11: 用自定义异常替代字符串前缀判断
                    throw OpenAIHttpException(code, msg)
                }
                // M-OAI6: body 可能为 null(虽然 OkHttp 实际几乎不为 null,但类型上 Nullable),统一做空安全
                val raw = resp.body?.string()
                    ?: throw ErrorCode.INVALID_RESPONSE.toProviderException("empty_body", resp.code)
                val customResponsePath = customConfig?.responsePath?.takeIf { it.isNotBlank() }
                if (customResponsePath != null) {
                    val parsedJson = AppJson.parseToJsonElement(raw)
                    val text = extractTextFromElement(
                        ProviderTemplateEngine.extractByPath(parsedJson, customResponsePath),
                    ) ?: throw ErrorCode.INVALID_RESPONSE.toProviderException("empty_custom_response")
                    return@withContext ChatCompletion(text = text)
                }
                val parsed = AppJson.decodeFromString<OpenAICompletionResponse>(raw)
                val choice = parsed.choices.firstOrNull()
                    ?: throw ErrorCode.INVALID_RESPONSE.toProviderException("empty_choices")
                val msg = choice.message
                val text = msg?.content.orEmpty()
                val reasoningContent = msg?.reasoningContent.orEmpty()
                val toolCalls = msg?.toolCalls?.map {
                    ToolCall(id = it.id, name = it.function.name, arguments = it.function.arguments)
                }
                // Phase 7: 如果有 tool_calls,允许 text 为空(工具调用场景)
                // M-OAI8: 如果有推理内容(reasoning_content),也允许 text 为空
                //   (部分推理模型在非流式响应中可能只返回 reasoning_content 而 content 为空)
                if (text.isBlank() && toolCalls.isNullOrEmpty() && reasoningContent.isBlank()) {
                    Logger.w("OpenAIProvider", "completeText 返回空文本(无 content/reasoning_content/tool_calls)")
                    throw ErrorCode.INVALID_RESPONSE.toProviderException("empty_text")
                }
                Logger.d("OpenAIProvider", "completeText OK: text=${text.length} chars, reasoning=${reasoningContent.length} chars, toolCalls=${toolCalls?.size ?: 0}")
                ChatCompletion(
                    text = text,
                    finishReason = choice.finishReason,
                    toolCalls = toolCalls,
                    reasoningContent = reasoningContent.takeIf { it.isNotBlank() },
                    usageTokens = parsed.usage?.toUsageTokens(),
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            call.cancel()
            throw e
        } catch (t: Throwable) {
            if (request.abortSignal.aborted) {
                Logger.d("OpenAIProvider", "completeText aborted by user")
            } else if (t is OpenAIHttpException) {
                // L-OAI11: 已记录的 HTTP 错误,不重复 log
            } else {
                Logger.e("OpenAIProvider", "completeText 异常", t)
            }
            throw t
        } finally {
            if (request.abortSignal.aborted) call.cancel()
        }
    }

    private fun baseUrl(): String = config.resolvedBaseUrl()

    /**
     * 拉取上游模型列表。
     *
     * GET {resolvedBaseUrl()}/models(resolvedBaseUrl 已含 /v1),
     * Bearer 鉴权,解析 data[].id。返回的每个 Model 的 id/name 均为上游 id,
     * contextWindow 用 [ModelContextWindowRegistry] 兜底(上游不返回该字段)。
     *
     * [config] 形参允许传入未保存的编辑值(如临时改的 apiKey/baseUrl)。
     *
     * v1.80 (H-OAI2): 显式 catch CancellationException 并 call.cancel(),
     *   否则 OkHttp Call 会继续阻塞 IO 线程最长 300s(readTimeout),导致协程取消后线程仍被占用。
     *
     * v1.132 优化:
     *  - OpenRouter: 解析 context_length / max_completion_tokens / pricing,
     *    动态注册到 [ModelContextWindowRegistry](三层元信息叠加);
     *    并附加 HTTP-Referer / X-Title 归因头
     *  - 过滤异常条目:id 等于 provider 自身 id 的伪模型(过滤 DeepSeek 伪模型)
     *  - 按 id 去重,保留首个
     *  - 按 id 字母序排序,便于用户查找
     *  - 使用独立短超时 client(30s connect + 30s read),避免 listModels 卡顿占用 chat 长连接资源
     *  - 401/403 不 fallback,直接抛错(凭证问题不掩盖,按错误分级处理)
     *
     * v1.0.8 (7.3 / 7.5):
     *  - 服务端 capabilities 多字段名解析:支持 supports_tool_calls / function_calling /
     *    supports_vision / image_input / multimodal / supports_streaming / stream 等字段名,
     *    覆盖不同中转站 / 聚合服务的命名差异。
     *  - fetchModels 瀑布流错误分级日志:在 listModels 入口 / 命中层 / 失败层均记录 Logger,
     *    便于排查"为什么没拉到模型"。
     *  - 401/403 直接抛错(不 fallback);404 / 网络错误也抛错但分类标签不同,
     *    由调用方(ProviderSection.fetchModels)走 URL 多策略补全兜底。
     */
    override suspend fun listModels(config: ProviderConfig): List<Model> = withContext(Dispatchers.IO) {
        val resolvedBaseUrl = config.resolvedBaseUrl()
        // v1.0.18: 免费模型 provider(SiliconFlow + 用户未填 key)不调远程 /models,
        //   直接返回预设的免费模型清单(对齐 FreeModelConfig.FREE_MODEL_IDS)。
        //   避免因无 apiKey 调远程返回 401 / 403,让用户在 SiliconFlow 供应商页面看到
        //   预填的 GLM-4-9B / Qwen3-8B 即可用。用户填 key 后 isFreeProvider 返回 false,
        //   走原远程拉取逻辑解锁全部模型。
        if (FreeModelConfig.isFreeProvider(resolvedBaseUrl, config.apiKey)) {
            Logger.i("OpenAIProvider", "listModels: SiliconFlow 免费模型 provider(未填 key),返回预设 ${config.models.size} 个模型")
            return@withContext config.models
        }
        val url = resolvedBaseUrl.trimEnd('/') + "/models"
        // P2-3: 识别 Ollama 服务(baseUrl host 含 "ollama" 或端口 11434),
        //   命中时对每个模型调用 OllamaVisionInferrer 推断 supportsVision/supportsTools。
        val isOllama = isOllamaEndpoint(resolvedBaseUrl)
        // v1.0.8 (7.5): 入口日志 — 记录目标 URL / providerId / 是否 ollama,
        //   方便从日志中追溯 fetchModels 瀑布流走到了哪一层。
        Logger.i("OpenAIProvider", "listModels: GET $url" + if (isOllama) " (ollama)" else "")

        // v1.0.8 (7.5): 凭证预检 — allowMissingApiKey=false 时 apiKey 为空直接抛错,
        //   避免发无意义的 401 请求浪费一次网络往返。
        if (!config.allowMissingApiKey && config.apiKey.isBlank()) {
            Logger.w("OpenAIProvider", "listModels: apiKey 为空,跳过请求(providerId=${config.id})")
            throw OpenAIHttpException(401, ErrorCode.INVALID_RESPONSE.toMessage("model_list_missing_key", 401))
        }

        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", "application/json")
        // v1.132: OpenRouter 归因头(OpenRouter 官方推荐)
        // 上报应用名 + 来源,既符合 OpenRouter 排名榜规则,也避免被识别为匿名流量
        if (url.contains("openrouter.ai")) {
            builder.header("HTTP-Referer", "https://github.com/zer0/muse")
                .header("X-Title", "Muse")
        }
        val httpRequest = builder.get().build()
        // v1.132: listModels 使用独立短超时 client(30s),与 chat 长超时分离
        val listClient = httpClient.newBuilder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        // H-OAI2: 仿 completeText 模式,显式 catch CancellationException + call.cancel()
        val call = listClient.newCall(httpRequest)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    // L-OAI1: 用 readBodySafely 替代 runCatching
                    val errText = ProviderHttpSupport.readBodySafely(resp)
                    val errMsg = ErrorCode.INVALID_RESPONSE.toMessage("model_list_fetch", resp.code) +
                        errText.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                    // v1.0.8 (7.5): 错误分级日志 — 401/403 鉴权问题 vs 404/5xx 网络问题分别记录
                    val category = when (resp.code) {
                        401, 403 -> "auth_failed"
                        404 -> "endpoint_not_found"
                        in 500..599 -> "server_error"
                        else -> "http_error"
                    }
                    Logger.w("OpenAIProvider", "listModels 失败 [$category] HTTP ${resp.code}: $errMsg")
                    // L-OAI16: 用 OpenAIHttpException 替代通用 RuntimeException,
                    //   保留 HTTP code 作为类型信息(与 completeText 一致),便于上层区分错误来源。
                    throw OpenAIHttpException(resp.code, errMsg)
                }
                // M-OAI6: body 空安全
                val raw = resp.body?.string()
                    ?: throw ErrorCode.INVALID_RESPONSE.toProviderException("model_list_empty", resp.code)
                val parsed = AppJson.decodeFromString<OpenAIModelsResponse>(raw)
                // v1.0.8 (7.5): 成功日志 — 记录上游返回的模型数量,便于排查"返回 0 个模型"场景
                Logger.i("OpenAIProvider", "listModels 成功: 上游返回 ${parsed.data.size} 个模型")
                // v1.132: 去重 + 过滤 + 排序 + 元信息丰富
                val seen = HashSet<String>()
                parsed.data.asSequence()
                    // 过滤空 id 与伪模型(id == provider id,如 deepseek API 偶发返回 "deepseek")
                    .filter { it.id.isNotBlank() && it.id != config.id }
                    // 按 id 去重,保留首个(防止上游返回重复)
                    .filter { seen.add(it.id) }
                    .map { m ->
                        // v1.132: OpenRouter/Together 的 context_length 透传并注册到全局表
                        // v1.0.8 修正:context_window 与 max_completion_tokens 语义不同,
                        // 后者仅用于 maxOutputTokens,不再作为 contextWindow 兜底。
                        val contextLength = m.context_length
                        if (contextLength != null && contextLength > 0) {
                            ModelContextWindowRegistry.register(m.id, contextLength)
                        }
                        // v1.0.8: 统一解析服务端 capabilities + modalities,构造 input/outputModalities。
                        // 优先级: 服务端显式声明 > Ollama 推断 > 默认 text-only。
                        // v1.0.8 (7.3): 扩展多字段名解析 — 不同中转站用不同字段名声明能力,
                        //  这里把所有可能的别名都纳入匹配,提升兼容性。
                        //  例如 OpenRouter 用 "tools",部分中转用 "supports_tool_calls",
                        //  Anthropic 兼容层用 "function_calling",Ollama 用 "tool_call" 等。
                        val capabilitySet = m.capabilities
                            ?.map { it.trim().lowercase() }
                            ?.toSet()
                            ?: emptySet()
                        val modalitySet = m.modalities
                            ?.map { it.trim().lowercase() }
                            ?.toSet()
                            ?: emptySet()

                        // v1.0.8 (7.3): 视觉输入能力 — 支持的字段名变体
                        //  vision / image / image_input / supports_vision / multimodal / image_input_enabled
                        val serverVision = capabilitySet.contains("vision") ||
                            capabilitySet.contains("image") ||
                            capabilitySet.contains("image_input") ||
                            capabilitySet.contains("supports_vision") ||
                            capabilitySet.contains("multimodal") ||
                            modalitySet.contains("image")
                        // v1.0.8 (7.3): 音频输入 — audio / audio_input / supports_audio
                        val serverAudio = capabilitySet.contains("audio") ||
                            capabilitySet.contains("audio_input") ||
                            capabilitySet.contains("supports_audio") ||
                            modalitySet.contains("audio")
                        // v1.0.8 (7.3): 视频输入 — video_input / video / supports_video_input
                        val serverVideoIn = capabilitySet.contains("video_input") ||
                            capabilitySet.contains("video") ||
                            capabilitySet.contains("supports_video_input") ||
                            modalitySet.contains("video")
                        // 视频输出 — video_output / video_generation / supports_video_output
                        val serverVideoOut = capabilitySet.contains("video_output") ||
                            capabilitySet.contains("video_generation") ||
                            capabilitySet.contains("supports_video_output")
                        // 图片输出 — image_generation / image_output / dall-e / supports_image_output
                        val serverImageOut = capabilitySet.contains("image_generation") ||
                            capabilitySet.contains("image_output") ||
                            capabilitySet.contains("dall-e") ||
                            capabilitySet.contains("supports_image_output")
                        // v1.0.8 (7.3): 工具调用 — 支持的字段名变体
                        //  tools / tool_call / tool_calls / function_call / function_calling /
                        //  supports_tool_calls / supports_tools / function_calling_enabled
                        val serverTools = capabilitySet.contains("tools") ||
                            capabilitySet.contains("tool_call") ||
                            capabilitySet.contains("tool_calls") ||
                            capabilitySet.contains("function_call") ||
                            capabilitySet.contains("function_calling") ||
                            capabilitySet.contains("supports_tool_calls") ||
                            capabilitySet.contains("supports_tools") ||
                            capabilitySet.contains("function_calling_enabled")
                        // v1.0.8 (7.3): 推理能力 — reasoning / reasoning_ability / supports_reasoning
                        val serverReasoning = capabilitySet.contains("reasoning") ||
                            capabilitySet.contains("reasoning_ability") ||
                            capabilitySet.contains("supports_reasoning")
                        // v1.0.8 (7.3): 流式输出字段名(streaming / stream / supports_streaming)
                        //  目前仅在注释中列出,不影响 Model.supportsStreaming(默认 true)。
                        //  如需根据上游声明禁用流式,可在此判断后写入 Model.supportsStreaming = false。

                        val inferredVision = isOllama && !serverVision &&
                            OllamaVisionInferrer.inferSupportsVision(m.id)
                        val inferredTools = isOllama && !serverTools &&
                            OllamaVisionInferrer.inferSupportsTools(m.id)

                        val supportsVision = serverVision || inferredVision
                        val supportsVideoOut = serverVideoOut
                        val abilities = buildSet {
                            if (serverTools || inferredTools) add(ModelAbility.TOOL)
                            if (serverReasoning) add(ModelAbility.REASONING)
                        }

                        val inputModalities = buildSet {
                            add("text")
                            if (supportsVision || serverAudio || serverVideoIn) {
                                if (supportsVision) add("image")
                                if (serverAudio) add("audio")
                                if (serverVideoIn) add("video")
                            }
                        }
                        val outputModalities = buildSet {
                            add("text")
                            if (serverImageOut) add("image")
                            if (supportsVideoOut) add("video")
                        }

                        val rawModel = Model(
                            id = m.id,
                            name = m.id,
                            providerId = config.id,
                            // 优先用上游 context_length,其次查注册表(刚 register 的会被命中)
                            contextWindow = contextLength ?: ModelContextWindowRegistry.lookup(m.id),
                            // v1.132: 透传 max_completion_tokens 作为 maxOutputTokens
                            maxOutputTokens = m.max_completion_tokens ?: m.top_provider?.max_completion_tokens,
                            // v1.0.8: 视觉/视频/工具/推理能力(服务端优先,推断兜底)
                            supportsVision = supportsVision,
                            supportsVideo = supportsVideoOut,
                            abilities = abilities,
                            inputModalities = inputModalities,
                            outputModalities = outputModalities,
                        )
                        // v1.0.8: 用 registry 再补全一次能力/模态,覆盖服务端未声明的场景
                        //  (内部会做中转站误标检测,详见 ModelRegistry.enhanceModel)
                        ModelRegistry.enhanceModel(rawModel)
                    }
                    // 按 id 字母序排序,便于用户查找
                    .sortedBy { it.id.lowercase() }
                    .toList()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            call.cancel()
            throw e
        } catch (e: java.io.IOException) {
            // v1.0.8 (7.5): 网络错误日志 — 记录具体 IOException 类型,便于排查 DNS / TLS / 超时
            Logger.w("OpenAIProvider", "listModels 网络错误: ${e.javaClass.simpleName} - ${e.message}")
            throw e
        }
    }

    /**
     * P2-3: 判断 baseUrl 是否为 Ollama 服务。
     *
     * Ollama 默认端口 11434,用户也可能用自定义主机名(如 "ollama.local"),
     * 或在 baseUrl 中显式包含 "ollama" 标识。命中任一即按 Ollama 处理,
     * 触发 [OllamaVisionInferrer] 推断。
     *
     * 注意: 仅用于能力推断分支,不影响请求构造与协议兼容性。
     */
    private fun isOllamaEndpoint(baseUrl: String): Boolean {
        val lower = baseUrl.lowercase()
        return lower.contains(":11434") || lower.contains("ollama")
    }

    /**
     * B3-05: 按 Custom 配置渲染请求体。
     *  - requestTemplate 非空时替换 {{model}} / {{messages}} / {{stream}} / {{prompt}} 等占位符
     *  - customBody 非空时合并到最终 JSON 顶层(模板与默认请求体均生效)
     */
    private fun renderCustomRequestBody(
        defaultBody: String,
        request: ChatRequest,
        effectiveModel: String,
        stream: Boolean,
    ): String {
        val template = customConfig?.requestTemplate?.takeIf { it.isNotBlank() }
        val body = if (template != null) {
            val defaultElement = runCatching { AppJson.parseToJsonElement(defaultBody) }.getOrNull() as? JsonObject
            val variables = mutableMapOf<String, JsonElement>(
                "model" to JsonPrimitive(effectiveModel),
                "stream" to JsonPrimitive(stream),
                "messages" to (defaultElement?.get("messages") ?: JsonArray(emptyList())),
            )
            request.messages.lastOrNull { it.role == MessageRole.USER }?.let {
                variables["prompt"] = JsonPrimitive(it.content)
            }
            val systemText = request.messages.filter { it.role == MessageRole.SYSTEM }
                .joinToString("\n\n") { it.content }
            if (systemText.isNotBlank()) variables["system"] = JsonPrimitive(systemText)
            defaultElement?.get("tools")?.let { variables["tools"] = it }
            defaultElement?.get("temperature")?.let { variables["temperature"] = it }
            defaultElement?.get("max_tokens")?.let { variables["max_tokens"] = it }
            ProviderTemplateEngine.renderRequestTemplate(template, variables)
        } else {
            defaultBody
        }
        return mergeCustomBody(body)
    }

    /** B3-05: 把 customBody 的顶层字段合并进最终请求 JSON。 */
    private fun mergeCustomBody(body: String): String {
        val extras = customConfig?.customBody ?: emptyMap()
        if (extras.isEmpty()) return body
        val element = runCatching { AppJson.parseToJsonElement(body) }.getOrNull()
        val obj = (element as? JsonObject)?.toMutableMap() ?: return body
        obj.putAll(extras)
        return AppJson.encodeToString(JsonObject.serializer(), JsonObject(obj))
    }

    /** B3-05: 把提取到的 JSON 元素转成可读文本(primitive / array / object 常见文本字段)。 */
    private fun extractTextFromElement(element: JsonElement?): String? {
        return when (element) {
            null -> null
            is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }
            is JsonArray -> element.mapNotNull { extractTextFromElement(it) }
                .joinToString("\n")
                .takeIf { it.isNotBlank() }
            is JsonObject -> {
                for (key in listOf("content", "text", "markdown", "value")) {
                    val child = element[key] ?: continue
                    val text = extractTextFromElement(child)
                    if (!text.isNullOrBlank()) return text
                }
                null
            }
        }
    }

    private fun buildRequestBody(request: ChatRequest, stream: Boolean = true): String {
        // v1.0.7: UTILITY 模式强制关思考
        //  utility 路径(memory 摘要 / fact 抽取 / 视觉辅助等后台短文本任务)无需思考链,
        //  强制 effectiveReasoningLevel = OFF 以省 token + 降延迟。
        val effectiveReasoningLevel = if (request.mode == ChatRequestMode.UTILITY) {
            io.zer0.ai.core.ReasoningLevel.OFF
        } else {
            request.reasoningLevel
        }
        // v1.138: 思考等级优化 — 避免简单问题模型过度思考。
        // - AUTO: 不发 reasoning_effort(让服务端自行决定)
        // - OFF: 仅 OpenAI 官方 API(api.openai.com)发 "minimal"(o1/o3 系列最小推理);
        //        第三方中转站不发("minimal" 是 OpenAI 专有值,中转站不识别会返回 400)
        // - LOW/MEDIUM/HIGH/XHIGH: 显式发送对应 effort
        val isOpenAIOfficial = baseUrl().contains("api.openai.com")
        val effort = when (effectiveReasoningLevel) {
            io.zer0.ai.core.ReasoningLevel.AUTO -> null
            io.zer0.ai.core.ReasoningLevel.OFF ->
                if (request.model.supportsReasoning() && isOpenAIOfficial) "minimal" else null
            else -> effectiveReasoningLevel.effort
        }
        // compat 派生:按 type + baseUrl + modelId 三层匹配,决定是否注入 reasoning_effort / tools。
        // 用 effectiveModelId(已剥离 stripModelPrefix)作为 modelId,反映真正发给 API 的 model。
        // H-COMPAT1: 仅在最明显的参数注入点加判断,不重构请求构造主体逻辑。
        val effectiveModel = effectiveModelId(request.model.id)
        val compat: ProviderCompat = config.resolvedCompat(effectiveModel)
        // v1.0.5: Provider 出口兜底 — 先对 UIMessage 列表做 Provider 无关的通用清理
        //  (stripOrphanToolMessages 删孤儿 TOOL 消息 / stripNativeMediaAttachmentMarkers
        //   清理冗余图片标记),再做协议翻译。统一 payload 规范化。
        val normalizedMessages = ProviderPayloadNormalizer.normalizeMessages(
            request.messages, request.model,
        )
        // v1.0.7: Provider Prompt Patches — 注入厂商专属 system prompt 补丁
        //  (当前仅 DeepSeek 推理模型输出契约)
        //  UTILITY 模式下 effectiveReasoningLevel=OFF,ProviderPromptPatches 内部会跳过注入
        val promptPatches = ProviderPromptPatches.getProviderPromptPatches(
            model = request.model,
            baseUrl = baseUrl(),
            thinkingFormat = compat.thinkingFormat,
            reasoningLevel = effectiveReasoningLevel,
        )
        val messagesWithPatches = injectSystemPromptPatches(normalizedMessages, promptPatches)
        val payload = OpenAIRequest(
            model = effectiveModel,
            messages = messagesWithPatches.map { it.toOpenAI(request.model, compat) },
            temperature = request.temperature,
            // v1.0.2 修复 HTTP 400: max_tokens 范围校验,0/负值视为未设置。
            // 部分 OpenAI 兼容中转站严格校验 max_tokens >= 1,直接发 0 会返回 400 invalid_request_error。
            // null 会被 kotlinx.serialization 忽略,不写入请求体,让上游用默认值。
            max_tokens = request.maxTokens?.takeIf { it > 0 },
            stream = stream,
            // compat.supportsToolCalling=false 时强制不发 tools(如 deepseek-reasoner / o1-mini)
            // v1.0.4 修复 HTTP 400 "Tool names must be unique":按 function.name 防御性去重,
            // 即使上游(ToolRegistry + SkillExecutor)漏过同名工具,这里也能拦截,
            // 防止 DeepSeek 等严格校验工具名唯一性的 API 返回 400。
            // v1.0.5: stripEmptyTools — 空 tools 列表改为 null,避免序列化出
            //  `"tools": []` 被严格中转站拒绝。
            tools = if (compat.supportsToolCalling)
                request.tools?.mapNotNull { it.toOpenAISafely() }?.distinctBy { it.function.name }
                    ?.takeIf { it.isNotEmpty() }
            else null,
            // compat.supportsReasoningEffort=false 时强制不发 reasoning_effort
            //   (如 DeepSeek / Zhipu / Gemini OpenAI 兼容层,各自用 reasoning_content / thinking 字段)
            // v1.0.5: stripDisabledReasoningEffort — 值为 false/none/off 时视为未启用,
            //   改为 null 不发送。
            // v1.0.7: thinkingFormat != null 时也不发 reasoning_effort
            //   (改走对应厂商扩展字段,如 thinking / enable_thinking / chat_template_kwargs)
            reasoning_effort = if (compat.supportsReasoningEffort && compat.thinkingFormat == null)
                effort?.takeIf { !isDisabledReasoningEffort(it) }
            else null,
        )
        // v1.0.7: thinkingFormat 注入 — 按厂商扩展字段构造思考参数
        // 按 thinkingFormat 9 种格式映射请求体字段。
        // 实现:先序列化 OpenAIRequest 为 JsonObject,再按 thinkingFormat 追加/修改字段。
        // UTILITY 模式下 effectiveReasoningLevel=OFF,injectThinkingFormat 会写入 disabled
        val thinkingFormat = compat.thinkingFormat
        val defaultBody = if (thinkingFormat == null) {
            AppJson.encodeToString(payload)
        } else {
            injectThinkingFormat(payload, thinkingFormat, effectiveReasoningLevel)
        }
        return renderCustomRequestBody(defaultBody, request, effectiveModel, stream)
    }

    /**
     * v1.0.7: 按 [ThinkingFormat] 注入厂商扩展思考字段。
     *
     * 按 thinkingFormat 9 种格式实现:
     *  - [ThinkingFormat.DEEPSEEK]:不发任何思考参数(服务端默认开,仅消费流式 reasoning_content)
     *  - [ThinkingFormat.KIMI]:`thinking: {type: "enabled"|"disabled", keep: false}`
     *  - [ThinkingFormat.QWEN]:`enable_thinking: bool` + 可选 `thinking_budget: int`(HIGH/XHIGH 时发)
     *  - [ThinkingFormat.QWEN_CHAT_TEMPLATE]:`chat_template_kwargs: {enable_thinking: bool}`
     *  - [ThinkingFormat.ZHIPU]:`thinking: {type: "enabled"|"disabled", clear_thinking: false}`
     *  - [ThinkingFormat.OPENROUTER]:`reasoning: {effort: "low"|"medium"|"high"}`
     *  - [ThinkingFormat.VOLCENGINE]:`thinking: {type: "enabled"|"disabled"}`
     *  - [ThinkingFormat.LONGCAT]:`thinking: {type: "enabled"|"disabled"}`
     *  - [ThinkingFormat.ANTHROPIC]:OpenAIProvider 不消费(AnthropicProvider 自身处理原生 thinking 块)
     *
     * @param payload 原始 OpenAIRequest(已含 messages/tools/stream 等标准字段)
     * @param format thinkingFormat 枚举值(非 null)
     * @param level 推理等级(决定 enabled/disabled + effort 值)
     * @return 注入思考字段后的 JSON 字符串
     */
    private fun injectThinkingFormat(
        payload: OpenAIRequest,
        format: ThinkingFormat,
        level: io.zer0.ai.core.ReasoningLevel,
    ): String {
        // 先把 OpenAIRequest 序列化为 JsonObject
        val jsonElement = AppJson.encodeToJsonElement(OpenAIRequest.serializer(), payload)
        val baseObj = jsonElement as? kotlinx.serialization.json.JsonObject
            ?: return AppJson.encodeToString(payload)
        val baseMap = baseObj.toMutableMap()

        // 推理开关:OFF → disabled,AUTO → enabled(让服务端自行决定深度),其他 → enabled
        val thinkingEnabled = level != io.zer0.ai.core.ReasoningLevel.OFF

        when (format) {
            ThinkingFormat.DEEPSEEK -> {
                // DeepSeek 不发任何思考参数(服务端默认开 R1 思考)
                // reasoning_content 已在流式 delta 中解析,无需请求体控制
            }
            ThinkingFormat.KIMI -> {
                // 月之暗面 Kimi:thinking: {type: "enabled"|"disabled", keep: false}
                // keep=false 表示不保留思考链在最终响应中(节省 tokens)
                baseMap["thinking"] = buildJsonObject {
                    put("type", if (thinkingEnabled) "enabled" else "disabled")
                    put("keep", false)
                }
            }
            ThinkingFormat.QWEN -> {
                // Qwen3:enable_thinking: bool + 可选 thinking_budget: int
                baseMap["enable_thinking"] = JsonPrimitive(thinkingEnabled)
                if (thinkingEnabled && level.budgetTokens != null && level.budgetTokens > 0) {
                    baseMap["thinking_budget"] = JsonPrimitive(level.budgetTokens)
                }
            }
            ThinkingFormat.QWEN_CHAT_TEMPLATE -> {
                // 阿里 Qwen3-Coder:chat_template_kwargs: {enable_thinking: bool}
                // (Qwen3-Coder 用 chat_template 协议,enable_thinking 必须嵌在 chat_template_kwargs 内)
                baseMap["chat_template_kwargs"] = buildJsonObject {
                    put("enable_thinking", thinkingEnabled)
                }
            }
            ThinkingFormat.ZHIPU -> {
                // 智谱 GLM-Z1:thinking: {type: "enabled"|"disabled", clear_thinking: false}
                // clear_thinking=false 表示不清除思考链(保留在响应中)
                baseMap["thinking"] = buildJsonObject {
                    put("type", if (thinkingEnabled) "enabled" else "disabled")
                    put("clear_thinking", false)
                }
            }
            ThinkingFormat.OPENROUTER -> {
                // OpenRouter 聚合中转:reasoning: {effort: "low"|"medium"|"high"}
                // (Chat Completions 协议扩展,与 Responses API 的 reasoning 不同)
                if (thinkingEnabled && level.effort != null) {
                    // OpenRouter 不接受 "minimal",OFF 时不发 reasoning 字段
                    val openRouterEffort = when (level) {
                        io.zer0.ai.core.ReasoningLevel.LOW -> "low"
                        io.zer0.ai.core.ReasoningLevel.MEDIUM -> "medium"
                        io.zer0.ai.core.ReasoningLevel.HIGH, io.zer0.ai.core.ReasoningLevel.XHIGH -> "high"
                        else -> null  // AUTO/OFF 不发
                    }
                    openRouterEffort?.let {
                        baseMap["reasoning"] = buildJsonObject {
                            put("effort", it)
                        }
                    }
                }
            }
            ThinkingFormat.VOLCENGINE -> {
                // 火山引擎 Doubao Thinking:thinking: {type: "enabled"|"disabled"}
                baseMap["thinking"] = buildJsonObject {
                    put("type", if (thinkingEnabled) "enabled" else "disabled")
                }
            }
            ThinkingFormat.LONGCAT -> {
                // 美团 LongCat:thinking: {type: "enabled"|"disabled"}
                baseMap["thinking"] = buildJsonObject {
                    put("type", if (thinkingEnabled) "enabled" else "disabled")
                }
            }
            ThinkingFormat.ANTHROPIC -> {
                // Anthropic 原生 thinking 块 — OpenAIProvider 不消费
                // (AnthropicProvider 自身走原生 Messages API,不经过此路径)
            }
        }

        return AppJson.encodeToString(kotlinx.serialization.json.JsonObject(baseMap))
    }

    /**
     * v1.0.5: 判断 reasoning_effort 值是否为"已禁用"语义。
     *
     * false / null / 空串 / "none" / "off" / "disabled" 均视为已禁用,不应发送给 API。
     */
    private fun isDisabledReasoningEffort(value: String?): Boolean {
        if (value == null) return true
        val normalized = value.lowercase()
        return normalized.isEmpty() || normalized == "none" || normalized == "off" || normalized == "disabled" || normalized == "false"
    }

    /**
     * L-OAI14: 解析 [parametersJsonSchema] 失败时跳过该工具并记录警告,
     *   避免单个工具的非法 schema 导致整个请求失败(其他工具仍可正常调用)。
     */
    private fun ToolDefinition.toOpenAISafely(): OpenAITool? = try {
        OpenAITool(
            function = OpenAIToolFunction(
                name = name,
                description = description,
                parameters = AppJson.decodeFromString(JsonElement.serializer(), parametersJsonSchema),
            ),
        )
    } catch (t: Throwable) {
        if (t is kotlin.coroutines.cancellation.CancellationException) throw t
        Logger.w(
            "OpenAIProvider",
            "工具 '$name' 的 parametersJsonSchema 解析失败,跳过该工具: ${t.message}",
        )
        null
    }

    /**
     * Phase 8.6: UIMessage -> OpenAIMessage。
     *
     * 多模态处理:
     *  - 无图片:imageBase64List 为空 -> content = JsonPrimitive(text)(纯字符串)
     *  - 有图片:imageBase64List 非空 -> content = JsonArray([
     *      {type:"text", text:"..."},
     *      {type:"image_url", image_url:{url:"data:image/jpeg;base64,..."}}
     *    ])(OpenAI Vision 协议)
     *
     * M-OAI5: 图片 mime type 从 base64 头部 magic bytes 推断。
     * M-OAI8: base64 图片数量限制(<=4张)+ 单张大小限制(<=2MB),超限丢弃。
     * L-OAI7: assistant + tool_calls 时 content 为空传 JsonNull 而非空串。
     *
     * v1.0.5: 防御性视觉过滤 — 当 [model] 不支持视觉输入时,即使消息携带图片
     *  也走纯文本路径(丢弃 imageBase64List),避免向纯文本模型发送图片触发 400。
     *  这是 Provider 出口的最后一道防线,正常情况下 ChatViewModel 的视觉辅助路由
     *  应已在调用 streamChat 前清空图片(由 VisionBridge.prepare 注入描述后清空)。
     *  此过滤仅用于兜底:历史消息残留图片 / 调用方遗漏清空等异常场景。
     */
    private fun UIMessage.toOpenAI(model: Model, compat: ProviderCompat? = null): OpenAIMessage = OpenAIMessage(
        role = when (role) {
            MessageRole.SYSTEM -> "system"
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
            MessageRole.TOOL -> "tool"
        },
        content = if (imageBase64List.isEmpty() || !model.supportsVisionInput()) {
            // v1.0.5: 模型不支持视觉但消息携带图片时,丢弃图片走纯文本路径(防御性)
            if (imageBase64List.isNotEmpty() && !model.supportsVisionInput()) {
                Logger.w(
                    "OpenAIProvider",
                    "toOpenAI: 模型 ${model.id} 不支持视觉,丢弃 ${imageBase64List.size} 张图片(防御性过滤)",
                )
            }
            // v1.0.2 修复 HTTP 400: assistant + tool_calls 时 content 为空,改传空字符串而非 JsonNull。
            // 按兼容实践:assistant + tool_calls 时 content 规范化为空字符串。
            // 将 null/undefined content 规范化为 "" 空字符串,避免 OpenAI 兼容协议(尤其严格的中转站)
            // 拒绝 content: null 的 assistant 消息。原 L-OAI7 传 JsonNull 在部分中转站会触发 400。
            // OpenAI 兼容协议也要求 content 为空字符串而非 null。
            if (role == MessageRole.ASSISTANT && !toolCalls.isNullOrEmpty() && content.isBlank()) {
                JsonPrimitive("")
            } else {
                JsonPrimitive(content)
            }
        } else {
            buildJsonArray {
                // 文本 part(即使为空也添加,避免 messages[].content 为空数组)
                add(buildJsonObject {
                    put("type", "text")
                    put("text", content)
                })
                // M-OAI8: 图片数量限制 + 单张大小限制,超限丢弃
                val validImages = imageBase64List.filter { it.isNotEmpty() }
                if (validImages.size > MAX_VISION_IMAGES) {
                    Logger.w("OpenAIProvider", "图片数量 ${validImages.size} 超过上限 $MAX_VISION_IMAGES,丢弃多余的")
                }
                validImages.take(MAX_VISION_IMAGES).forEach { b64 ->
                    if (b64.length > MAX_IMAGE_BASE64_LEN) {
                        Logger.w("OpenAIProvider", "图片 base64 长度 ${b64.length} 超过 $MAX_IMAGE_BASE64_LEN,丢弃")
                        return@forEach
                    }
                    // M-OAI5: 从 magic bytes 推断 mime type
                    val mimeType = inferMimeType(b64)
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            put("url", "data:$mimeType;base64,$b64")
                        })
                    })
                }
            }
        },
        toolCalls = toolCalls?.map { it.toOpenAI() },
        toolCallId = toolCallId,
        // v1.0.7: 历史推理回放 — 按 reasoningReplayContract.carrier/policy 决定是否注入 reasoning_content
        reasoningContent = computeReasoningContentForReplay(compat),
    )

    /**
     * v1.0.7: 计算历史推理回放的 reasoning_content 值。
     *
     * 仅当 compat.reasoningReplayContract.carrier == REASONING_CONTENT 时考虑注入
     * (Kimi / DeepSeek / MiMo / Zhipu Chat Completions 协议)。
     *
     * policy 决策:
     *  - [ReasoningReplayPolicy.NONE]:不注入(返回 null)
     *  - [ReasoningReplayPolicy.PRESERVE]:始终注入(若 reasoning 非空)
     *  - [ReasoningReplayPolicy.REQUIRE_TOOL_CALL]:仅 ASSISTANT + tool_calls 非空时注入
     *    (对齐 Kimi/DeepSeek fail-closed:这些厂商要求 tool_calls 消息必带 reasoning_content,
     *     否则返回 400;非 tool_calls 消息则不发,避免污染普通对话)
     *
     * 其他 carrier(REASONING_ITEMS / REASONING_DETAILS / THINKING_BLOCKS / THOUGHT_SIGNATURE)
     * 不通过此函数处理 — 它们走 Responses API / OpenRouter / Anthropic / Gemini 各自路径。
     */
    private fun UIMessage.computeReasoningContentForReplay(compat: ProviderCompat?): String? {
        val contract = compat?.reasoningReplayContract ?: return null
        if (contract.carrier != ReasoningCarrier.REASONING_CONTENT) return null
        val reasoningText = reasoning?.takeIf { it.isNotBlank() } ?: return null
        return when (contract.policy) {
            ReasoningReplayPolicy.NONE -> null
            ReasoningReplayPolicy.PRESERVE -> reasoningText
            ReasoningReplayPolicy.REQUIRE_TOOL_CALL -> {
                // 仅 ASSISTANT + toolCalls 非空时注入(fail-closed 原则)
                if (role == MessageRole.ASSISTANT && !toolCalls.isNullOrEmpty()) reasoningText else null
            }
        }
    }

    /**
     * v1.0.7: 把 Provider Prompt Patches 注入到 messages 列表。
     *
     * 注入策略:
     *  - patches 为空:原样返回(不复制,零开销)
     *  - 已有 system 消息:把 patches 追加到**第一条** system 消息的 content 末尾(用双换行分隔)
     *    (避免新增多条 system 消息,部分中转站对 system 消息数量有严格校验)
     *  - 无 system 消息:在列表开头插入一条新的 system 消息(content = patches 拼接)
     *
     * 注:本函数只处理 Chat Completions 协议(messages 数组);
     *   Responses API 的 instructions 字段在 [buildResponsesRequestBody] 中单独处理。
     */
    private fun injectSystemPromptPatches(
        messages: List<UIMessage>,
        patches: List<String>,
    ): List<UIMessage> {
        if (patches.isEmpty()) return messages
        val patchText = patches.joinToString("\n\n")
        val firstSystemIdx = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (firstSystemIdx >= 0) {
            // 追加到第一条 system 消息末尾
            messages.toMutableList().also { list ->
                val orig = list[firstSystemIdx]
                list[firstSystemIdx] = orig.copy(
                    content = orig.content + "\n\n" + patchText,
                )
            }
        } else {
            // 无 system 消息,在开头插入一条
            listOf(
                UIMessage(role = MessageRole.SYSTEM, content = patchText),
            ) + messages
        }
    }

    private fun ToolCall.toOpenAI(): OpenAIToolCall = OpenAIToolCall(
        id = id,
        function = OpenAIToolCallFunction(name = name, arguments = arguments),
    )

    /**
     * M-OAI5: 从 base64 头部 magic bytes 推断图片 MIME 类型。
     * - jpeg: 0xFFD8
     * - png:  0x89504E47
     * - gif:  0x47494638
     * - webp: 0x52494646
     * 回退 image/jpeg。
     */
    private fun inferMimeType(base64: String): String {
        return try {
            // 取前 8 个 base64 字符(6 字节)用于判断 magic bytes
            val prefix = base64.take(8).padEnd(8, '=')
            val bytes = java.util.Base64.getMimeDecoder().decode(prefix)
            when {
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
                bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                    bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
                bytes.size >= 4 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
                    bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte() -> "image/gif"
                bytes.size >= 4 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                    bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() -> "image/webp"
                else -> "image/jpeg"
            }
        } catch (e: Exception) {
            "image/jpeg"
        }
    }

    /**
     * 解析错误消息。
     * M-OAI1: 用 classifyHttpCode 分类状态码。
     * M-OAI2: 消息截断(take 200)。
     * L-OAI10: 追加 detail.code 字段。
     * L-OAI11: 移除一次重复截断(原 safeBody=take(200) + msg.take(200) 两次截断)。
     */
    private fun parseErrorMessage(code: Int, body: String): String {
        // M-OAI1: HTTP 状态码分类
        val category = ProviderHttpSupport.classifyHttpCode(code)
        // M-OAI3: 改用 resultOf(会重抛 CancellationException),替代 runCatching
        val detail = resultOf {
            AppJson.decodeFromString<OpenAIErrorBody>(body).error
        }.getOrNull()
        return buildString {
            append("HTTP ").append(code)
            category?.let { append(" [").append(it).append("]") }
            // 优先用解析出的 detail.message,回退到原始 body(统一在此处截断一次)
            // L-OAI11: 移除 safeBody=body.take(200) 的预先截断,仅在此处 take(200)
            val msg = detail?.message?.takeIf { it.isNotBlank() }
                ?: body.takeIf { it.isNotBlank() }
            msg?.let { append(": ").append(it.take(200)) }
            // L-OAI10: 追加 detail.code 字段
            detail?.code?.let { codeElem ->
                val codeStr = when (codeElem) {
                    is JsonPrimitive -> codeElem.content
                    else -> codeElem.toString()
                }
                if (codeStr.isNotBlank()) append(" [code=").append(codeStr).append("]")
            }
            detail?.type?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(")") }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // v1.0.7: OpenAI Responses API 实现(/v1/responses 端点)
    //
    // 按 OpenAI 官方 Responses API 协议实现。
    // 与 Chat Completions API 的关键差异:
    //  - 请求体:messages → input;system role → instructions 顶层字段;
    //    max_tokens → max_output_tokens;新增 reasoning: {effort, summary}
    //  - 流式响应:event: response.output_text.delta + data: {delta: "..."},
    //    结束标记 response.completed(并发 data: [DONE])
    //  - 非流式响应:choices[0].message.content → output[] 数组,
    //    reasoning / function_call 都是 output 顶层 sibling(不在 message 内)
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * v1.0.7: Responses API 流式实现。
     *
     * SSE 事件类型(按 Responses API 规范):
     *  - response.output_text.delta:正文增量 → ContentDelta
     *  - response.output_text.done:正文结束(text 兜底)
     *  - response.reasoning_summary_text.delta:推理摘要增量 → ReasoningDelta
     *  - response.function_call_arguments.delta:工具调用参数增量 → ToolCallDelta
     *  - response.output_item.added:新增 output item(如 function_call 起始,含 id+name)
     *  - response.completed:流结束(response 字段含最终 output 数组)→ Done
     *  - data: [DONE]:OpenAI 通用结束符 → Done
     */
    private fun streamChatResponses(request: ChatRequest): Flow<ChatStreamEvent> = callbackFlow {
        val body = buildResponsesRequestBody(request, stream = true)
        val url = baseUrl() + openAIConfig.responsesPath
        Logger.i("OpenAIProvider", "streamChatResponses: POST $url model=${request.model} msgs=${request.messages.size}")

        fun buildHttpRequest(): Request = Request.Builder()
            .url(url)
            // v1.0.18: 走免费模型 fallback(用户未填 key + SiliconFlow 白名单模型 → 用内置 key)
            .header("Authorization", "Bearer ${resolveEffectiveApiKey(request.model.id)}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .apply { customConfig?.customHeaders?.forEach { (k, v) -> header(k, v) } }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        var httpRequest = buildHttpRequest()
        val scope = this@callbackFlow
        val requestStartAt = System.currentTimeMillis()
        var firstByteAt = 0L
        var firstDeltaAt = 0L

        val retryCount = AtomicInteger(0)
        val anyDeltaSent = AtomicBoolean(false)
        // Responses API 工具调用累积:output_index → 本地 index 映射
        val toolCallIndexMap = mutableMapOf<String, Int>()
        var nextToolCallIndex = 0
        // output_item.added 事件携带的 function_call 起始信息(id+name)
        val pendingFunctionCalls = mutableMapOf<String, Pair<String, String>>() // item_id → (call_id, name)
        // v1.0.20: stream-guard — 累积 tool_call 的 name / arguments / 已发送标志,
        //   用于拦截空 name 的无效 tool call 并在 Done 时恢复为 ContentDelta
        val toolCallAccMap = mutableMapOf<Int, ToolCallAccState>()
        // v1.0.21: 防止 emitDoneWithStreamGuard 被双重执行
        val streamGuardDone = AtomicBoolean(false)

        /**
         * v1.0.20: stream-guard — 在 Done 事件前检查累积的 toolCallAccMap,
         *   把空 name 但有 arguments 的无效 tool call 恢复为 ContentDelta(可见文本)。
         *   与 ChatCompletions 路径的 emitDoneWithStreamGuard 对称。
         */
        fun emitDoneWithStreamGuard(finishReason: String?) {
            if (!streamGuardDone.compareAndSet(false, true)) {
                trySend(ChatStreamEvent.Done(finishReason))
                close()
                return
            }
            toolCallAccMap.forEach { (localIndex, acc) ->
                // v1.0.22: 跳过已增量恢复的 acc,避免重复发送
                if (acc.recoveredAsContent) return@forEach
                if (acc.name.isNullOrBlank() && acc.args.isNotEmpty()) {
                    val recoveredText = acc.args.toString()
                    Logger.w(
                        "OpenAIProvider",
                        "stream-guard(Responses): 拦截空 name tool call (localIndex=$localIndex, args=${acc.args.length} chars)," +
                            "恢复为文本: ${recoveredText.take(50)}",
                    )
                    trySend(ChatStreamEvent.ContentDelta(recoveredText))
                }
            }
            toolCallAccMap.clear()
            trySend(ChatStreamEvent.Done(finishReason))
            close()
        }

        var currentEventSource: EventSource? = null
        var currentCall: Call? = null

        fun connect() {
            val call = httpClient.newCall(httpRequest)
            currentCall = call
            currentEventSource = sseFactory.newEventSource(httpRequest, object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    firstByteAt = System.currentTimeMillis()
                    Logger.i("OpenAIProvider", "streamChatResponses TTFB: ${firstByteAt - requestStartAt}ms | url=$url")
                    if (!response.isSuccessful) {
                        val code = response.code
                        if (code == 429 && !anyDeltaSent.get() && retryCount.get() < MAX_RETRIES &&
                            !request.abortSignal.aborted && switchToNextKey()
                        ) {
                            Logger.i("OpenAIProvider", "streamChatResponses onOpen 429 限流,已切换 key,立即重试")
                            httpRequest = buildHttpRequest()
                            retryCount.incrementAndGet()
                            eventSource.cancel()
                            currentCall?.cancel()
                            scope.launch {
                                if (!request.abortSignal.aborted && !scope.isClosedForSend) {
                                    connect()
                                }
                            }
                            return
                        }
                        val errText = ProviderHttpSupport.readBodySafely(response)
                        val msg = parseErrorMessage(code, errText)
                        Logger.w("OpenAIProvider", "streamChatResponses onOpen HTTP $code: $msg")
                        if (code == 401 || code == 403) {
                            markKeyFailed(hardBlock = true)
                        }
                        trySend(ChatStreamEvent.Error(msg, OpenAIHttpException(code, msg)))
                        close()
                        return
                    }
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    // Responses API 同时用 [DONE] 和 response.completed 作结束标记
                    if (data == "[DONE]") {
                        // v1.0.20: stream-guard — Done 事件时检查累积 toolCallAccMap,
                        //   空 name 的 tool call 恢复为 ContentDelta
                        emitDoneWithStreamGuard(null)
                        return
                    }

                    val event = resultOf {
                        AppJson.decodeFromString<ResponsesStreamEvent>(data)
                    }.getOrNull() ?: return

                    if (firstDeltaAt == 0L && (event.delta != null || event.text != null)) {
                        firstDeltaAt = System.currentTimeMillis()
                        Logger.i(
                            "OpenAIProvider",
                            "streamChatResponses first delta: ${firstDeltaAt - requestStartAt}ms " +
                                "(TTFB=${firstByteAt - requestStartAt}ms) | url=$url",
                        )
                    }

                    when (event.type) {
                        "response.output_text.delta" -> {
                            event.delta?.takeIf { it.isNotEmpty() }?.let {
                                anyDeltaSent.set(true)
                                trySend(ChatStreamEvent.ContentDelta(it))
                            }
                        }
                        "response.output_text.done" -> {
                            // 兜底完整文本(若 delta 累积为空才用)
                            // 此处不直接发送,留给 response.completed 处理
                        }
                        "response.reasoning_summary_text.delta" -> {
                            event.delta?.takeIf { it.isNotEmpty() }?.let {
                                anyDeltaSent.set(true)
                                trySend(ChatStreamEvent.ReasoningDelta(it))
                            }
                        }
                        "response.function_call_arguments.delta" -> {
                            // 工具调用参数增量,按 item_id 累积
                            val itemId = event.item_id ?: return
                            val localIndex = toolCallIndexMap.getOrPut(itemId) { nextToolCallIndex++ }
                            anyDeltaSent.set(true)
                            // v1.0.20: stream-guard — 累积 name 和 arguments。
                            //   name 来自 output_item.added(可能为空),args 来自本事件增量。
                            val acc = toolCallAccMap.getOrPut(localIndex) { ToolCallAccState() }
                            // 首片附带 id+name(若 output_item.added 已缓存);后续片不重复取
                            if (!acc.hasEmitted) {
                                val (callId, name) = pendingFunctionCalls.remove(itemId) ?: ("" to "")
                                if (callId.isNotEmpty()) acc.id = callId
                                if (name.isNotBlank()) acc.name = name
                            }
                            event.delta?.let { acc.args.append(it) }

                            // v1.0.22: 已增量恢复为 ContentDelta 的 acc,后续 args 继续作为 ContentDelta 发送
                            if (acc.recoveredAsContent) {
                                val argsDelta = event.delta.orEmpty()
                                if (argsDelta.isNotEmpty()) {
                                    trySend(ChatStreamEvent.ContentDelta(argsDelta))
                                }
                                return
                            }

                            // stream-guard: 空 name 处理
                            //   v1.0.20: 缓冲到 Done 才恢复 — 导致小模型"只输出首字即结束"假象
                            //   v1.0.22: 改为立即增量恢复为 ContentDelta
                            val currentName = acc.name
                            if (currentName.isNullOrBlank()) {
                                val argsDelta = event.delta.orEmpty()
                                if (argsDelta.isNotEmpty()) {
                                    acc.recoveredAsContent = true
                                    trySend(ChatStreamEvent.ContentDelta(argsDelta))
                                    Logger.d(
                                        "OpenAIProvider",
                                        "stream-guard(Responses): 增量恢复空 name tool call 为 ContentDelta (localIndex=$localIndex, 本次=${argsDelta.length} chars, 累积=${acc.args.length} chars)",
                                    )
                                } else {
                                    Logger.d(
                                        "OpenAIProvider",
                                        "stream-guard(Responses): 空 name tool call 无 args 增量,跳过 (localIndex=$localIndex, 累积 args=${acc.args.length} chars)",
                                    )
                                }
                                return
                            }

                            // name 已到 — 发送 ToolCallDelta
                            if (!acc.hasEmitted) {
                                // 首次发送 — 一次性带上累积的 arguments(追赶)
                                trySend(ChatStreamEvent.ToolCallDelta(
                                    index = localIndex,
                                    id = acc.id,
                                    name = currentName,
                                    argumentsDelta = acc.args.toString(),
                                ))
                                acc.hasEmitted = true
                            } else {
                                // 后续增量
                                trySend(ChatStreamEvent.ToolCallDelta(
                                    index = localIndex,
                                    id = null,
                                    name = null,
                                    argumentsDelta = event.delta.orEmpty(),
                                ))
                            }
                        }
                        "response.output_item.added" -> {
                            // 新增 output item,解析 function_call 起始信息
                            val item = event.item ?: return
                            val itemType = (item as? kotlinx.serialization.json.JsonObject)
                                ?.get("type")?.let { (it as? JsonPrimitive)?.content }
                            if (itemType == "function_call") {
                                val obj = item as? kotlinx.serialization.json.JsonObject ?: return
                                val itemId = obj["id"]?.let { (it as? JsonPrimitive)?.content } ?: return
                                val callId = obj["call_id"]?.let { (it as? JsonPrimitive)?.content } ?: ""
                                val name = obj["name"]?.let { (it as? JsonPrimitive)?.content } ?: ""
                                // v1.0.20: stream-guard — 同步写入 accumulator(name 可能为空,后续 delta 据此判断)
                                val localIndex = toolCallIndexMap.getOrPut(itemId) { nextToolCallIndex++ }
                                val acc = toolCallAccMap.getOrPut(localIndex) { ToolCallAccState() }
                                if (callId.isNotEmpty()) acc.id = callId
                                if (name.isNotBlank()) acc.name = name
                                // 仍保留 pendingFunctionCalls 兼容旧路径(args delta 首片会尝试取)
                                pendingFunctionCalls[itemId] = callId to name
                            }
                        }
                        "response.completed" -> {
                            // 流结束,带最终 response 对象
                            val status = event.response?.status
                            // B5-03: 从最终 output 提取 reasoning 签名/encrypted_content 供落库回放
                            val reasoningItem = event.response?.output?.firstOrNull { it.type == "reasoning" }
                            if (reasoningItem != null &&
                                (!reasoningItem.id.isNullOrBlank() || !reasoningItem.encryptedContent.isNullOrBlank())
                            ) {
                                trySend(ChatStreamEvent.ReasoningDelta(
                                    "", signature = reasoningItem.id, encryptedContent = reasoningItem.encryptedContent,
                                ))
                            }
                            // v1.0.20: stream-guard — Done 事件时检查累积 toolCallAccMap,
                            //   空 name 的 tool call 恢复为 ContentDelta
                            emitDoneWithStreamGuard(status)
                        }
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    // v1.0.23: 同 ChatCompletions 路径,未收到 Done 事件时触发 stream-guard
                    if (!streamGuardDone.get()) {
                        Logger.d("OpenAIProvider", "streamChatResponses onClosed: 未收到 Done 事件, 触发 stream-guard")
                        emitDoneWithStreamGuard(null)
                    } else {
                        close()
                    }
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?,
                ) {
                    if (request.abortSignal.aborted) {
                        Logger.d("OpenAIProvider", "streamChatResponses aborted by user")
                        trySend(ChatStreamEvent.Error("aborted", t))
                        close()
                        return
                    }
                    val code = response?.code ?: -1
                    // 有限次指数退避重连(仅网络层错误,且未发出任何 delta)
                    if (code <= 0 && !anyDeltaSent.get() && retryCount.get() < MAX_RETRIES) {
                        val attempt = retryCount.incrementAndGet()
                        val backoffMs = (RETRY_BASE_DELAY_MS * (1 shl (attempt - 1))) +
                            Random.nextLong(0, 200)
                        Logger.w("OpenAIProvider", "streamChatResponses onFailure, retry $attempt/$MAX_RETRIES after ${backoffMs}ms: ${t?.message ?: code}")
                        scope.launch {
                            delay(backoffMs)
                            if (!request.abortSignal.aborted && !scope.isClosedForSend) {
                                connect()
                            }
                        }
                        return
                    }
                    val msg = response?.let {
                        if (it.code in 200..299) {
                            t?.message?.takeIf { m -> m.isNotBlank() }
                                ?: ErrorCode.STREAM_INTERRUPTED.toMessage()
                        } else {
                            val bodyText = ProviderHttpSupport.readBodySafely(it)
                            parseErrorMessage(it.code, bodyText)
                        }
                    } ?: (t?.message ?: ErrorCode.NETWORK_ERROR.toMessage())
                    Logger.e("OpenAIProvider", "streamChatResponses onFailure: $msg", t)
                    // v1.0.15: 已收到部分内容时发 StreamInterrupted,让 UI 保留已收内容并提示网络中断(可自动重连)
                    if (anyDeltaSent.get()) {
                        trySend(ChatStreamEvent.StreamInterrupted(msg, t))
                    } else {
                        trySend(ChatStreamEvent.Error(msg, t))
                    }
                    close()
                }
            })
        }

        connect()

        awaitClose {
            request.abortSignal.abort()
            currentEventSource?.cancel()
            currentCall?.cancel()
        }
        // v1.0.19: 无界 buffer,防止 EventSource 回调突发投递时 trySend 因内部 channel 满
        //   而丢片(使用无界 buffer)。
        //   callbackFlow 默认容量有限,UI 卡顿/收集慢时可能丢字。
    }.flowOn(Dispatchers.IO).buffer(Channel.UNLIMITED)

    /**
     * v1.0.7: Responses API 非流式实现。
     *
     * 解析 output[] 数组,提取:
     *  - type="message" 的 content[].text(可见正文)
     *  - type="reasoning" 的 summary(推理内容,存入 reasoningContent)
     *  - type="function_call" 的 call_id/name/arguments(工具调用)
     */
    private suspend fun completeTextResponses(request: ChatRequest, keySwitchDepth: Int = 0): ChatCompletion = withContext(Dispatchers.IO) {
        val body = buildResponsesRequestBody(request, stream = false)
        val url = baseUrl() + openAIConfig.responsesPath
        Logger.i("OpenAIProvider", "completeTextResponses: POST $url model=${request.model}")
        val httpRequest = Request.Builder()
            .url(url)
            // v1.0.18: 走免费模型 fallback(用户未填 key + SiliconFlow 白名单模型 → 用内置 key)
            .header("Authorization", "Bearer ${resolveEffectiveApiKey(request.model.id)}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .apply { customConfig?.customHeaders?.forEach { (k, v) -> header(k, v) } }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = httpClient.newCall(httpRequest)
        try {
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val code = resp.code
                    if (code == 429 && keySwitchDepth < MAX_KEY_SWITCHES && switchToNextKey()) {
                        Logger.i("OpenAIProvider", "completeTextResponses 429 限流,已切换 key,重试 ($keySwitchDepth/$MAX_KEY_SWITCHES)")
                        return@withContext completeTextResponses(request, keySwitchDepth + 1)
                    }
                    if (code == 401 || code == 403) {
                        markKeyFailed(hardBlock = true)
                    }
                    val errText = ProviderHttpSupport.readBodySafely(resp)
                    val msg = parseErrorMessage(code, errText)
                    Logger.w("OpenAIProvider", "completeTextResponses HTTP $code: $msg")
                    throw OpenAIHttpException(code, msg)
                }
                val raw = resp.body?.string()
                    ?: throw ErrorCode.INVALID_RESPONSE.toProviderException("empty_body", resp.code)
                val parsed = AppJson.decodeFromString<ResponsesResult>(raw)
                val text = extractResponsesVisibleText(parsed)
                val reasoningContent = extractResponsesReasoning(parsed)
                val toolCalls = extractResponsesToolCalls(parsed)
                // B5-03: 提取 reasoning item 的签名与 encrypted_content 供多轮回放
                val reasoningItem = parsed.output.firstOrNull { it.type == "reasoning" }

                if (text.isBlank() && toolCalls.isNullOrEmpty() && reasoningContent.isBlank()) {
                    Logger.w("OpenAIProvider", "completeTextResponses 返回空(output 无 message/reasoning/function_call)")
                    throw ErrorCode.INVALID_RESPONSE.toProviderException("empty_text")
                }
                Logger.d("OpenAIProvider", "completeTextResponses OK: text=${text.length} chars, reasoning=${reasoningContent.length} chars, toolCalls=${toolCalls?.size ?: 0}")
                ChatCompletion(
                    text = text,
                    finishReason = parsed.status,
                    toolCalls = toolCalls,
                    reasoningContent = reasoningContent.takeIf { it.isNotBlank() },
                    thinkingSignature = reasoningItem?.id,
                    thinkingEncryptedContent = reasoningItem?.encryptedContent,
                    usageTokens = parsed.usage?.toUsageTokens(),
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            call.cancel()
            throw e
        } catch (t: Throwable) {
            if (request.abortSignal.aborted) {
                Logger.d("OpenAIProvider", "completeTextResponses aborted by user")
            } else if (t is OpenAIHttpException) {
                // 已记录的 HTTP 错误
            } else {
                Logger.e("OpenAIProvider", "completeTextResponses 异常", t)
            }
            throw t
        } finally {
            if (request.abortSignal.aborted) call.cancel()
        }
    }

    /**
     * v1.0.7: 构造 Responses API 请求体。
     *
     * 转换规则:
     *  - SYSTEM 消息提取到 instructions 顶层字段(不入 input 数组)
     *  - USER/ASSISTANT/TOOL 消息转为 input 数组项
     *  - ASSISTANT 消息的 toolCalls 转为 function_call 项(顶层 sibling,不在 message 内)
     *  - TOOL 消息转为 function_call_output 项(用 toolCallId 作为 call_id)
     *  - 多模态 content 用 ResponsesInputItem.content(JsonElement)承载
     *
     * 推理配置:
     *  - reasoningLevel != OFF/AUTO 时,构造 reasoning: {effort} 字段
     *  - Codex 协议(responsesPath=/codex/responses)强制 store=false
     *
     * v1.0.7 TODO(reasoning-items-replay):REASONING_ITEMS carrier 的 reasoning item 回放
     *  尚未实现。OpenAI Responses API 要求 reasoning item 必须携带 encrypted_content
     *  才能完整回放(服务端用加密负载验证思考链完整性),仅有 summary 文本不够。
     *  实现路径:
     *   1. streamChatResponses 解析 response.completed 事件时,从 output[type=reasoning]
     *      提取 encrypted_content + id,存入 UIMessage.thinkingSignature
     *   2. 此处遍历 normalizedMessages,若 ASSISTANT 消息的 thinkingSignature 非空,
     *      在对应 message item 之前插入 type="reasoning" 的 input item
     *      (带 id + encrypted_content,无 summary)
     *  当前不实现:避免构造不完整的 reasoning item 触发服务端 400。
     *  影响:OpenAI Responses API 多轮对话中,模型无法"看见"上一轮的完整思考链,
     *  但不会 fail-closed(不像 Kimi/DeepSeek 那样强制要求 reasoning_content)。
     */
    private fun buildResponsesRequestBody(request: ChatRequest, stream: Boolean = true): String {
        val effectiveModel = effectiveModelId(request.model.id)
        val normalizedMessages = ProviderPayloadNormalizer.normalizeMessages(
            request.messages, request.model,
        )
        // v1.0.7: compat 派生(Responses API 路径,用于 ProviderPromptPatches 判定)
        val compat: ProviderCompat = config.resolvedCompat(effectiveModel)
        // v1.0.7: UTILITY 模式强制关思考
        val effectiveReasoningLevel = if (request.mode == ChatRequestMode.UTILITY) {
            io.zer0.ai.core.ReasoningLevel.OFF
        } else {
            request.reasoningLevel
        }

        // 提取 system → instructions;其余消息转 input 项
        val instructionsBuilder = StringBuilder()
        val inputItems = mutableListOf<ResponsesInputItem>()
        for (msg in normalizedMessages) {
            if (msg.role == MessageRole.SYSTEM) {
                // SYSTEM 消息合并到 instructions(多条用换行分隔)
                if (instructionsBuilder.isNotEmpty()) instructionsBuilder.append("\n\n")
                instructionsBuilder.append(msg.content)
            } else {
                // B5-03: OpenAI Responses 多轮 thinking 回放 — 在 assistant 消息前插入 reasoning item
                if (msg.role == MessageRole.ASSISTANT && !msg.thinkingEncryptedContent.isNullOrBlank()) {
                    inputItems.add(ResponsesInputItem(
                        type = "reasoning",
                        id = msg.thinkingSignature,
                        encrypted_content = msg.thinkingEncryptedContent,
                    ))
                }
                inputItems.add(msg.toResponsesInputItem(request.model))
                // ASSISTANT 消息的 toolCalls 转为 function_call 顶层 sibling
                if (msg.role == MessageRole.ASSISTANT && !msg.toolCalls.isNullOrEmpty()) {
                    msg.toolCalls.forEach { tc ->
                        inputItems.add(ResponsesInputItem(
                            type = "function_call",
                            call_id = tc.id,
                            name = tc.name,
                            arguments = tc.arguments,
                        ))
                    }
                }
            }
        }

        // v1.0.7: Provider Prompt Patches — 追加到 instructions 末尾
        //  (Responses API 的 system prompt 走顶层 instructions 字段,不进 input 数组)
        //  UTILITY 模式下 effectiveReasoningLevel=OFF,ProviderPromptPatches 内部会跳过注入
        val promptPatches = ProviderPromptPatches.getProviderPromptPatches(
            model = request.model,
            baseUrl = baseUrl(),
            thinkingFormat = compat.thinkingFormat,
            reasoningLevel = effectiveReasoningLevel,
        )
        for (patch in promptPatches) {
            if (instructionsBuilder.isNotEmpty()) instructionsBuilder.append("\n\n")
            instructionsBuilder.append(patch)
        }

        val instructions = instructionsBuilder.toString().takeIf { it.isNotBlank() }

        // 推理配置(effort 映射,UTILITY 模式下 effectiveReasoningLevel=OFF → "minimal")
        val effort = when (effectiveReasoningLevel) {
            io.zer0.ai.core.ReasoningLevel.AUTO -> null
            io.zer0.ai.core.ReasoningLevel.OFF -> "minimal"
            else -> effectiveReasoningLevel.effort
        }
        val reasoning = effort?.let { ResponsesReasoningConfig(effort = it) }

        // Codex 协议强制 store=false(/codex/responses 路径识别)
        val isCodex = openAIConfig.responsesPath.contains("codex")
        val store = if (isCodex) false else null

        val payload = ResponsesRequest(
            model = effectiveModel,
            input = inputItems,
            instructions = instructions,
            stream = stream,
            max_output_tokens = request.maxTokens?.takeIf { it > 0 },
            temperature = request.temperature,
            tools = request.tools?.mapNotNull { it.toOpenAISafely() }?.distinctBy { it.function.name }
                ?.takeIf { it.isNotEmpty() },
            reasoning = reasoning,
            store = store,
        )
        return AppJson.encodeToString(payload)
    }

    /**
     * v1.0.7: UIMessage → ResponsesInputItem。
     *
     * Responses API 的 input 项与 Chat Completions 的 messages 项结构不同:
     *  - type="message"(普通消息),role + content
     *  - type="function_call"(assistant 工具调用,由 buildResponsesRequestBody 单独构造)
     *  - type="function_call_output"(tool 消息回填,call_id + output)
     *
     * content 字段:
     *  - 纯文本:string(直接传 JsonPrimitive)
     *  - 多模态:JsonArray([{type:"input_text", text}, {type:"input_image", image_url}])
     *    (注意:Responses API 用 input_text / input_image,而非 Chat Completions 的 text / image_url)
     */
    private fun UIMessage.toResponsesInputItem(model: Model): ResponsesInputItem {
        // TOOL 消息转 function_call_output(回填工具结果)
        if (role == MessageRole.TOOL) {
            return ResponsesInputItem(
                type = "function_call_output",
                call_id = toolCallId,
                content = JsonPrimitive(content),
            )
        }

        val roleStr = when (role) {
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
            MessageRole.SYSTEM -> "system"
            MessageRole.TOOL -> "user"  // 兜底(理论上不会到这里)
        }

        val contentElement = if (imageBase64List.isEmpty() || !model.supportsVisionInput()) {
            if (imageBase64List.isNotEmpty() && !model.supportsVisionInput()) {
                Logger.w(
                    "OpenAIProvider",
                    "toResponsesInputItem: 模型 ${model.id} 不支持视觉,丢弃 ${imageBase64List.size} 张图片(防御性过滤)",
                )
            }
            JsonPrimitive(content)
        } else {
            // 多模态:Responses API 用 input_text / input_image(注意与 Chat Completions 的 text / image_url 不同)
            buildJsonArray {
                add(buildJsonObject {
                    put("type", "input_text")
                    put("text", content)
                })
                val validImages = imageBase64List.filter { it.isNotEmpty() }
                if (validImages.size > MAX_VISION_IMAGES) {
                    Logger.w("OpenAIProvider", "图片数量 ${validImages.size} 超过上限 $MAX_VISION_IMAGES,丢弃多余的")
                }
                validImages.take(MAX_VISION_IMAGES).forEach { b64 ->
                    if (b64.length > MAX_IMAGE_BASE64_LEN) {
                        Logger.w("OpenAIProvider", "图片 base64 长度 ${b64.length} 超过 $MAX_IMAGE_BASE64_LEN,丢弃")
                        return@forEach
                    }
                    val mimeType = inferMimeType(b64)
                    add(buildJsonObject {
                        put("type", "input_image")
                        put("image_url", "data:$mimeType;base64,$b64")
                    })
                }
            }
        }

        return ResponsesInputItem(
            type = "message",
            role = roleStr,
            content = contentElement,
        )
    }

    /**
     * v1.0.7: 从 ResponsesResult 提取可见正文。
     *
     * 优先级:
     *  1. 顶层 output_text 字段(若非空直接用)
     *  2. output[] 中 type="message" 的 content[].text(type="output_text" 或 "text")
     *
     * 不能从 type="reasoning" 的 item 提取文本
     */
    private fun extractResponsesVisibleText(result: ResponsesResult): String {
        result.outputText?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        return result.output
            .filter { it.type == "message" }
            .flatMap { it.content ?: emptyList() }
            .filter { it.type == "output_text" || it.type == "text" }
            .mapNotNull { it.text?.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /**
     * v1.0.7: 从 ResponsesResult 提取推理内容(reasoning item 的 summary)。
     */
    private fun extractResponsesReasoning(result: ResponsesResult): String {
        return result.output
            .filter { it.type == "reasoning" }
            .flatMap { it.summary?.let { s -> parseSummaryTexts(s) } ?: emptyList() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    /** v1.0.7: 解析 reasoning item 的 summary 字段(JsonElement,可能是数组或字符串)。 */
    private fun parseSummaryTexts(summary: JsonElement): List<String> {
        return when (summary) {
            is kotlinx.serialization.json.JsonPrimitive -> listOf(summary.content)
            is kotlinx.serialization.json.JsonArray -> summary.mapNotNull { el ->
                (el as? kotlinx.serialization.json.JsonObject)
                    ?.get("text")?.let { (it as? JsonPrimitive)?.content }
            }
            else -> emptyList()
        }
    }

    /**
     * v1.0.7: 从 ResponsesResult 提取工具调用。
     *
     * output[] 中 type="function_call" 的项含 call_id / name / arguments。
     */
    private fun extractResponsesToolCalls(result: ResponsesResult): List<ToolCall>? {
        val calls = result.output
            .filter { it.type == "function_call" }
            .mapNotNull { item ->
                val callId = item.callId ?: return@mapNotNull null
                val name = item.name ?: return@mapNotNull null
                ToolCall(id = callId, name = name, arguments = item.arguments.orEmpty())
            }
        return calls.takeIf { it.isNotEmpty() }
    }

    private companion object {
        // L-OAI12: 移除 charset=utf-8(OpenAI / 中转普遍按 UTF-8 处理 application/json,
        //   且 OkHttp 对 application/json 默认即按 UTF-8 解码;显式 charset 在某些中转下
        //   反而被严格校验导致 415)。
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        // M-OAI3: 流式重连参数
        const val MAX_RETRIES = 3
        const val RETRY_BASE_DELAY_MS = 1000L
        // completeText 429 切换 key 最大次数,防止无限递归
        const val MAX_KEY_SWITCHES = 3
        /** 空 name tool call 风暴阈值:超过后按正文增量恢复,避免无限缓冲拖慢流式。 */
        const val MAX_EMPTY_NAME_TOOL_CALLS = 10
        // M-OAI8: Vision 图片限制
        const val MAX_VISION_IMAGES = 4
        const val MAX_IMAGE_BASE64_LEN = 2 * 1024 * 1024  // 2MB
    }
}

/**
 * L-OAI11: OpenAI HTTP 错误异常,携带状态码,替代字符串前缀判断。
 *
 * v1.0.27 Phase 5-A: 改为继承 [ProviderException],让消费端可通过
 * `(throwable as? ProviderException)?.providerError` 拿到类型化错误。
 */
internal class OpenAIHttpException(val code: Int, message: String) : ProviderException(
    providerError = when (code) {
        401, 403 -> ProviderError.AuthError(httpCode = code, displayMessage = message)
        400, 422, 404 -> ProviderError.InvalidRequest(httpCode = code, displayMessage = message)
        429 -> ProviderError.RateLimit(httpCode = code, displayMessage = message)
        in 500..599 -> ProviderError.ServerError(httpCode = code, displayMessage = message)
        else -> ProviderError.Unknown(httpCode = code, displayMessage = message)
    },
)

/**
 * v1.0.20: stream-guard 累积器 — 累积单个 tool_call 的 name / arguments / 是否已发送。
 *
 * 采用 `invalidToolCalls` 缓冲机制:
 *  - 流式中 name 可能为空(小模型生成空 name tool call),此时缓冲 arguments 不发送 ToolCallDelta
 *  - 若 name 在后续 delta 中到达,首次发送时一次性带上累积的 arguments(追赶)
 *  - 若直到 Done 事件 name 仍为空,把累积的 arguments 转为 ContentDelta 恢复为可见文本
 *
 * 线程安全:EventSource 回调串行触发(单线程),无需同步原语。
 */
private class ToolCallAccState {
    /** 工具调用 id(首个 chunk 携带)。 */
    var id: String? = null
    /** 函数名(累积首个非空值;Done 时若仍为 null/blank 视为无效 tool call)。 */
    var name: String? = null
    /** 累积的 arguments 片段。 */
    val args: StringBuilder = StringBuilder()
    /** 是否已向下游发送过 ToolCallDelta(用于 name 来晚时的追赶发送)。 */
    var hasEmitted: Boolean = false
    /**
     * v1.0.22: 是否已作为 ContentDelta 增量恢复过(空 name tool call 增量恢复模式)。
     *
     * v1.0.20 的缓冲到 Done 模式存在严重体验问题:GLM-4-9B-0414 等小模型会把
     * 整段正文拆成大量独立 index 的空 name tool call(每个 1-4 chars),
     * stream-guard 全部缓冲到 Done 才一次性恢复,用户只看到 1 个字就以为结束按了停止。
     *
     * v1.0.22 改为增量恢复:首个 args 到达时若 name 仍为空,立即作为 ContentDelta 发送,
     * 后续 args 直接增量发送,避免缓冲导致"看似只输出首字即结束"的假象。
     *
     * 正常 tool call 的 name 通常在首片就携带(OpenAI 规范),若首片无 name 基本可判定为
     * 小模型异常输出,直接增量恢复为正文是合理的。
     */
    var recoveredAsContent: Boolean = false
}
