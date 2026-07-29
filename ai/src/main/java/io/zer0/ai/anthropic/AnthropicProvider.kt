package io.zer0.ai.anthropic

import io.zer0.common.ErrorCode
import io.zer0.common.toMessage
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelContextWindowRegistry
import io.zer0.ai.core.ProviderCompat
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderHttpSupport
import io.zer0.ai.core.ProviderPayloadNormalizer
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Anthropic Claude Provider。
 *
 * API: Messages API (/v1/messages)
 *  - system 是顶层字段(从 UIMessage SYSTEM 抽出)
 *  - 流式事件: content_block_delta(text_delta / thinking_delta / input_json_delta / signature_delta)
 *    / content_block_start / message_start / message_delta / message_stop / error
 *  - thinking 模型(claude-3-7-sonnet / claude-opus-4)的思考链走 thinking_delta
 *  - 认证: x-api-key + anthropic-version: 2023-06-01
 *
 * Phase 8.1 (M9): 支持 Prompt Caching。当 [ProviderSpecificConfig.Anthropic.promptCaching]=true 时,
 *  在 system prompt 和最后一条用户消息上加 cache_control 标记,Claude 会缓存前缀 5m/1h。
 *  重复请求只计 10% 输入 token 成本,长对话场景显著省钱。
 *
 * Phase 8.1 (M11): 支持 ReasoningLevel。根据 [ChatRequest.reasoningLevel] 映射 thinking 配置:
 *  - OFF → thinking=disabled(不发 thinking 字段)
 *  - AUTO → 不发 thinking 字段(让服务端决定)
 *  - LOW/MEDIUM/HIGH → thinking={type:enabled, budget_tokens:1000/2000/8000}
 *  - XHIGH → thinking={type:enabled, budget_tokens:16000}
 *
 * v1.80 (H-ANT1~4 / M-ANT1~11 / L-ANT1~11): 继承 ProviderHttpSupport 统一 HTTP 客户端,
 *  修复 thinking_delta 读取、流式 tool_use、error 事件、断连重试、结构化错误、
 *  tool_result/tool_use 块、图片类型推断、分页拉取等。
 */
class AnthropicProvider(
    config: ProviderConfig,
) : ProviderHttpSupport(config) {

    override val id: String get() = config.id
    override val displayName: String get() = config.displayName

    /** 解析后的 Anthropic 特定配置(specific 为 null 时按 type 兜底)。 */
    private val anthropicConfig: ProviderSpecificConfig.Anthropic
        get() = config.resolvedSpecific() as? ProviderSpecificConfig.Anthropic
            ?: ProviderSpecificConfig.Anthropic()

    // M-ANT9: writeTimeout 已由 ProviderHttpSupport 统一配置,不再自行创建 client

    private val sseFactory by lazy { EventSources.createFactory(httpClient) }

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> = callbackFlow {
        val producerScope = this
        // v1.0.5: Provider 出口兜底 — 先对 UIMessage 列表做通用清理(对齐 openhanako normalizeProviderPayload)
        val normalizedMessages = ProviderPayloadNormalizer.normalizeMessages(
            request.messages, request.model,
        )
        val (system, messages) = splitSystem(normalizedMessages, request.model)
        val body = buildRequestBody(
            model = request.model.id,
            system = system,
            messages = messages,
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            stream = true,
            reasoningLevel = request.reasoningLevel,
            tools = request.tools,
        )
        val url = baseUrl() + anthropicConfig.messagesPath
        Logger.i("AnthropicProvider", "streamChat: POST ${sanitizeUrl(url)} model=${request.model.id} msgs=${request.messages.size}")

        // M-ANT1: 跟踪已接收 content,断连重试时记录(Anthropic 不支持流式续传,重连后重新生成)
        val accumulatedContent = StringBuilder()
        // H-ANT2 / L-ANT2: 跟踪每个 content block 的类型
        val blockContext = mutableMapOf<Int, AnthropicContentBlock>()
        // M-ANT4: 签名累积(thinking 块结束时通过 ReasoningDelta.signature 发出,供消费方存入 UIMessage.thinkingSignature)
        val signatureAccumulator = StringBuilder()
        // M-ANT1: message_delta 携带的 stop_reason,延迟到 message_stop 时随 Done 一起发(避免重复发 Done)
        var pendingStopReason: String? = null
        // H-ANT1: 任何 ContentDelta/ReasoningDelta/ToolCallDelta 发出后置 true,重试前检查避免重复发内容
        val anyDeltaSent = AtomicBoolean(false)
        // H-ANT3: 流是否已正常结束(message_stop/error/onFailure 终结路径置 true),onClosed 据此判断异常关闭
        val finished = AtomicBoolean(false)
        // M-ANT2/L-ANT2: onOpen HTTP 错误也参与重试(原仅 onFailure 处理),与 onFailure 共用 retryCount
        var retryCount = 0
        val maxRetries = 3
        // M-ANT3: AtomicReference 替代普通 var,保证 listener 回调与 awaitClose 间的可见性
        val currentEventSource = AtomicReference<EventSource?>(null)
        val currentCall = AtomicReference<okhttp3.Call?>(null)

        fun connect() {
            // L-ANT3: 重连前清空累积,避免上一轮残留内容混入新一轮
            accumulatedContent.setLength(0)
            signatureAccumulator.setLength(0)
            blockContext.clear()
            pendingStopReason = null
            // v1.0.1: 用 effectiveApiKey() 支持多 key 轮换
            val httpRequest = Request.Builder()
                .url(url)
                .header("x-api-key", effectiveApiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("anthropic-beta", buildBetaHeader(thinkingEnabled = isThinkingEnabled(request.reasoningLevel)))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val call = httpClient.newCall(httpRequest)
            currentCall.set(call)
            val listener = object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    if (!response.isSuccessful) {
                        // M-ANT10 / M-ANT11: readBodyCapped + 先 cancel 再 close
                        val errText = readBodyCapped(response)
                        val code = response.code
                        // v1.0.1 (P1): 重试条件对齐 OpenAI/Gemini — 429/408/5xx 均重试
                        //   原 Anthropic 仅重试 529/503,429 限流时用户只能手动重试
                        val isRetryable = code == 429 || code == 408 || code == 503 || code == 529 || code in 500..599
                        // v1.0.1 (P0): 429 限流时先尝试切换 key(多 key 场景)
                        if (code == 429 && !anyDeltaSent.get() && retryCount < maxRetries &&
                            !request.abortSignal.aborted && !producerScope.isClosedForSend &&
                            switchToNextKey()
                        ) {
                            retryCount++
                            Logger.i("AnthropicProvider",
                                "streamChat onOpen 429 限流,已切换到下一个 key,立即重试 " +
                                    "($retryCount/$maxRetries)")
                            eventSource.cancel()
                            call.cancel()
                            producerScope.launch {
                                if (request.abortSignal.aborted || producerScope.isClosedForSend) return@launch
                                connect()
                            }
                            return
                        }
                        if (isRetryable && !anyDeltaSent.get() && retryCount < maxRetries &&
                            !request.abortSignal.aborted && !producerScope.isClosedForSend
                        ) {
                            retryCount++
                            // v1.0.1 (P1): 加 jitter(0~499ms),与 OpenAI 对齐
                            val baseDelay = 1000L shl (retryCount - 1)  // 1s / 2s / 4s
                            val delayMs = baseDelay + kotlin.random.Random.nextLong(0, 500)
                            // v1.0.1: 429 限流优先用 Retry-After 头
                            val retryAfter = if (code == 429) {
                                response.header("Retry-After")?.toIntOrNull()?.let { it * 1000L }
                            } else null
                            val finalDelay = retryAfter ?: delayMs
                            Logger.w("AnthropicProvider",
                                "streamChat onOpen retryable HTTP $code, " +
                                    "retry $retryCount/$maxRetries after ${finalDelay}ms")
                            eventSource.cancel()
                            call.cancel()
                            producerScope.launch {
                                delay(finalDelay)
                                if (request.abortSignal.aborted || producerScope.isClosedForSend) return@launch
                                connect()
                            }
                            return
                        }
                        val msg = parseErrorMessage(code, errText)
                        Logger.w("AnthropicProvider", "streamChat onOpen HTTP $code: $msg")
                        // v1.0.1: 401/403 鉴权失败时标记当前 key 失败
                        if (code == 401 || code == 403) {
                            markKeyFailed(hardBlock = true)
                        }
                        eventSource.cancel()
                        finished.set(true)
                        trySend(ChatStreamEvent.Error(msg))
                        close()
                    }
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    if (data.isBlank()) return
                    // L-ANT1: 用 resultOf 替代 runCatching,正确传播 CancellationException
                    val chunk = resultOf {
                        AppJson.decodeFromString<AnthropicStreamEvent>(data)
                    }.getOrNull() ?: return

                    when (chunk.type) {
                        // L-ANT1: message_start 解析 usage
                        "message_start" -> {
                            val usage = chunk.message?.usage
                            if (usage != null) {
                                Logger.d("AnthropicProvider",
                                    "message_start: input=${usage.input_tokens} output=${usage.output_tokens} " +
                                        "cache_read=${usage.cache_read_input_tokens} cache_creation=${usage.cache_creation_input_tokens}")
                            }
                        }
                        // H-ANT2 / L-ANT2: content_block_start 跟踪 block 类型,tool_use 发 ToolCallDelta
                        "content_block_start" -> {
                            val idx = chunk.index ?: 0
                            val block = chunk.content_block
                            if (block != null) {
                                blockContext[idx] = block
                                if (block.type == "tool_use") {
                                    anyDeltaSent.set(true)
                                    trySend(ChatStreamEvent.ToolCallDelta(
                                        index = idx,
                                        id = block.id,
                                        name = block.name,
                                    ))
                                }
                            }
                        }
                        "content_block_delta" -> {
                            val delta = chunk.delta ?: return
                            val idx = chunk.index ?: 0
                            when (delta.type) {
                                // H-ANT1: thinking_delta 读 delta.thinking(非 delta.text)
                                "thinking_delta" -> delta.thinking?.takeIf { it.isNotEmpty() }
                                    ?.let {
                                        anyDeltaSent.set(true)
                                        trySend(ChatStreamEvent.ReasoningDelta(it))
                                    }
                                "text_delta" -> delta.text?.takeIf { it.isNotEmpty() }?.let {
                                    accumulatedContent.append(it)
                                    anyDeltaSent.set(true)
                                    trySend(ChatStreamEvent.ContentDelta(it))
                                }
                                // H-ANT2: input_json_delta 工具参数增量
                                "input_json_delta" -> delta.partial_json?.takeIf { it.isNotEmpty() }?.let {
                                    anyDeltaSent.set(true)
                                    trySend(ChatStreamEvent.ToolCallDelta(index = idx, argumentsDelta = it))
                                }
                                // M-ANT4: signature_delta 累积签名,并通过 ReasoningDelta.signature 发出,
                                // 供消费方(ChatViewModel)存入 UIMessage.thinkingSignature 用于多轮 thinking 回放
                                "signature_delta" -> delta.signature?.let { sig ->
                                    if (sig.isNotEmpty()) {
                                        signatureAccumulator.append(sig)
                                        anyDeltaSent.set(true)
                                        trySend(ChatStreamEvent.ReasoningDelta(delta = "", signature = sig))
                                    }
                                }
                            }
                        }
                        // M-ANT1: message_delta 仅记录 stop_reason,不再立即发 Done(避免与 message_stop 重复发 Done)
                        "message_delta" -> {
                            val stopReason = chunk.delta?.stop_reason
                            if (stopReason != null) {
                                pendingStopReason = stopReason
                            }
                        }
                        // L-ANT6: message_stop 兜底 Done 再 close(M-ANT1: 仅此处发 Done)
                        "message_stop" -> {
                            if (signatureAccumulator.isNotEmpty()) {
                                Logger.d("AnthropicProvider",
                                    "streamChat signature accumulated: ${signatureAccumulator.length} chars")
                            }
                            finished.set(true)
                            trySend(ChatStreamEvent.Done(pendingStopReason))
                            close()
                        }
                        // H-ANT4: error 事件
                        "error" -> {
                            val errMsg = chunk.error?.message ?: "Anthropic stream error"
                            Logger.w("AnthropicProvider", "streamChat error event: ${chunk.error?.type}: $errMsg")
                            finished.set(true)
                            trySend(ChatStreamEvent.Error(errMsg))
                            close()
                        }
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    // H-ANT3: 未正常结束(未收到 message_stop/error)即关闭,发 Error 终止事件,
                    // 避免消费者(UI loading)无限等待
                    if (!finished.get()) {
                        Logger.w("AnthropicProvider", "streamChat onClosed before finished")
                        // v1.0.15: 已收到部分内容时降级为 StreamInterrupted,UI 保留已收内容并提示网络中断
                        if (anyDeltaSent.get()) {
                            trySend(ChatStreamEvent.StreamInterrupted(ErrorCode.STREAM_INTERRUPTED.toMessage("anthropic")))
                        } else {
                            trySend(ChatStreamEvent.Error(ErrorCode.STREAM_INTERRUPTED.toMessage("anthropic")))
                        }
                    }
                    close()
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?,
                ) {
                    if (request.abortSignal.aborted) {
                        Logger.d("AnthropicProvider", "streamChat aborted by user")
                        finished.set(true)
                        close(); return
                    }
                    val code = response?.code
                    // v1.0.1 (P1): 重试条件对齐 OpenAI/Gemini — 429/408/5xx/IOException 均重试
                    //   原 Anthropic 仅重试 529/503,429 限流和 IOException 不会被重试
                    val isRetryable = (code != null && (code == 429 || code == 408 || code == 503 || code == 529 || code in 500..599))
                        || (t is java.io.IOException)
                    // v1.0.1 (P0): 429 限流时先尝试切换 key(多 key 场景)
                    if (code == 429 && !anyDeltaSent.get() && retryCount < maxRetries &&
                        !request.abortSignal.aborted && !producerScope.isClosedForSend &&
                        switchToNextKey()
                    ) {
                        retryCount++
                        Logger.i("AnthropicProvider",
                            "streamChat onFailure 429 限流,已切换到下一个 key,立即重试 " +
                                "($retryCount/$maxRetries)")
                        eventSource.cancel()
                        producerScope.launch {
                            if (request.abortSignal.aborted || producerScope.isClosedForSend) return@launch
                            connect()
                        }
                        return
                    }
                    // H-ANT1: 已发出任何增量则不重试(避免消费者收到重复内容)
                    if (isRetryable && !anyDeltaSent.get() && retryCount < maxRetries &&
                        !producerScope.isClosedForSend
                    ) {
                        retryCount++
                        // v1.0.1 (P1): 加 jitter(0~499ms),与 OpenAI 对齐(原 Anthropic 无 jitter)
                        val baseDelay = 1000L shl (retryCount - 1)  // 1s / 2s / 4s
                        var delayMs = baseDelay + kotlin.random.Random.nextLong(0, 500)
                        // v1.0.1: 429 限流优先用 Retry-After 头
                        if (code == 429) {
                            delayMs = response?.header("Retry-After")?.toIntOrNull()
                                ?.let { it * 1000L } ?: delayMs
                        }
                        Logger.w("AnthropicProvider",
                            "streamChat retryable failure(code=$code t=${t?.message}), " +
                                "retry $retryCount/$maxRetries after ${delayMs}ms, " +
                                "accumulated=${accumulatedContent.length} chars, anyDeltaSent=${anyDeltaSent.get()}")
                        eventSource.cancel()
                        producerScope.launch {
                            delay(delayMs)
                            if (request.abortSignal.aborted || producerScope.isClosedForSend) return@launch
                            connect()
                        }
                        return
                    }
                    // v1.109 修复: SSE 已建立(2xx)后中断是连接断开,优先用 Throwable 信息
                    val msg = response?.let {
                        if (it.code in 200..299) {
                            t?.message?.takeIf { m -> m.isNotBlank() }
                                ?: ErrorCode.STREAM_INTERRUPTED.toMessage("anthropic")
                        } else {
                            val body = readBodyCapped(it)
                            parseErrorMessage(it.code, body)
                        }
                    } ?: (t?.message ?: ErrorCode.NETWORK_ERROR.toMessage())
                    Logger.e("AnthropicProvider", "streamChat onFailure: $msg", t)
                    finished.set(true)
                    // v1.0.15: 已收到部分内容时发 StreamInterrupted,让 UI 保留已收内容并提示网络中断(可自动重连)
                    if (anyDeltaSent.get()) {
                        trySend(ChatStreamEvent.StreamInterrupted(msg, t))
                    } else {
                        trySend(ChatStreamEvent.Error(msg, t))
                    }
                    close()
                }
            }

            currentEventSource.set(sseFactory.newEventSource(httpRequest, listener))
        }

        connect()

        awaitClose {
            // M-ANT2: 不调 request.abortSignal.abort()(修改调用方对象),只 cancel HTTP 资源
            currentEventSource.get()?.cancel()
            currentCall.get()?.cancel()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun completeText(request: ChatRequest): ChatCompletion = completeTextImpl(request, 0)

    private suspend fun completeTextImpl(request: ChatRequest, keySwitchDepth: Int = 0): ChatCompletion = withContext(Dispatchers.IO) {
        // v1.0.5: Provider 出口兜底 — 先对 UIMessage 列表做通用清理(对齐 openhanako normalizeProviderPayload)
        val normalizedMessages = ProviderPayloadNormalizer.normalizeMessages(
            request.messages, request.model,
        )
        val (system, messages) = splitSystem(normalizedMessages, request.model)
        val body = buildRequestBody(
            model = request.model.id,
            system = system,
            messages = messages,
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            stream = false,
            reasoningLevel = request.reasoningLevel,
            tools = request.tools,
        )
        val url = baseUrl() + anthropicConfig.messagesPath
        Logger.i("AnthropicProvider", "completeText: POST ${sanitizeUrl(url)} model=${request.model.id}")
        // v1.0.1: 用 effectiveApiKey() 支持多 key 轮换
        val httpRequest = Request.Builder()
            .url(url)
            .header("x-api-key", effectiveApiKey())
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("anthropic-beta", buildBetaHeader(thinkingEnabled = isThinkingEnabled(request.reasoningLevel)))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = httpClient.newCall(httpRequest)
        try {
            // L-ANT10: execute 前检查 abortSignal
            if (request.abortSignal.aborted) {
                Logger.d("AnthropicProvider", "completeText aborted before execute")
                throw kotlinx.coroutines.CancellationException("aborted by user")
            }
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val code = resp.code
                    // v1.0.1: 429 切换 key 重试;401/403 标记 key 失败
                    if (code == 429 && keySwitchDepth < MAX_KEY_SWITCHES && switchToNextKey()) {
                        Logger.i("AnthropicProvider", "completeText 429 限流,已切换到下一个 key,重试 ($keySwitchDepth/$MAX_KEY_SWITCHES)")
                        return@withContext completeTextImpl(request, keySwitchDepth + 1)
                    }
                    if (code == 401 || code == 403) {
                        markKeyFailed(hardBlock = true)
                    }
                    // M-ANT10: readBodyCapped 替代 runCatching
                    val errText = readBodyCapped(resp)
                    val msg = parseErrorMessage(code, errText)
                    Logger.w("AnthropicProvider", "completeText HTTP $code: $msg")
                    throw RuntimeException(msg)
                }
                val raw = resp.body.string()
                val parsed = AppJson.decodeFromString<AnthropicCompletionResponse>(raw)
                // M-ANT4: 遍历所有 content blocks,拼接所有 text block
                val text = parsed.content
                    .filter { it.type == "text" }
                    .joinToString("\n") { it.text }
                // M-ANT4: 可选地把 thinking 放入 reasoning(ChatCompletion 无 reasoning 字段,记日志)
                val thinkingText = parsed.content
                    .filter { it.type == "thinking" }
                    .joinToString("\n") { it.text }
                if (thinkingText.isNotBlank()) {
                    Logger.d("AnthropicProvider", "completeText thinking: ${thinkingText.length} chars")
                }
                // M-ANT4: 提取 tool_use 块为 toolCalls
                val toolCalls = parsed.content
                    .filter { it.type == "tool_use" }
                    .mapNotNull { block ->
                        val tcId = block.id ?: return@mapNotNull null
                        val tcName = block.name ?: return@mapNotNull null
                        val tcArgs = block.input?.toString() ?: "{}"
                        ToolCall(id = tcId, name = tcName, arguments = tcArgs)
                    }
                    .takeIf { it.isNotEmpty() }
                if (text.isBlank() && toolCalls.isNullOrEmpty()) {
                    Logger.w("AnthropicProvider", "completeText 返回空文本(thinking 可能被吃掉)")
                    throw RuntimeException(ErrorCode.INVALID_RESPONSE.toMessage("empty_text"))
                }
                Logger.d("AnthropicProvider", "completeText OK: ${text.length} chars, toolCalls=${toolCalls?.size ?: 0}")
                ChatCompletion(text = text, finishReason = parsed.stop_reason, toolCalls = toolCalls)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            call.cancel()
            throw e
        } catch (t: Throwable) {
            if (request.abortSignal.aborted) {
                Logger.d("AnthropicProvider", "completeText aborted by user")
            } else if (t is RuntimeException && (t.message?.startsWith("Anthropic") == true || t.message?.contains("HTTP") == true)) {
                // 已记录的 HTTP 错误,不重复 log
            } else {
                Logger.e("AnthropicProvider", "completeText 异常", t)
            }
            throw t
        } finally {
            if (request.abortSignal.aborted) call.cancel()
        }
    }

    /**
     * 把 SYSTEM 消息抽出为顶层 system 字段,其余按 user/assistant 顺序保留。
     *
     * Phase 8.1 (M9): 若启用 promptCaching,在 system 块和最后一条 user 消息上加 cache_control。
     *
     * v1.80 (M-ANT7): Anthropic 原生 tool_use/tool_result 支持:
     *  - TOOL 消息构造标准 tool_result content block(role="user")
     *  - ASSISTANT 携带 toolCalls 时构造 tool_use content block(而非降级纯文本)
     *
     * v1.80 (M-ANT6): 图片 media_type 从 base64 magic bytes 推断。
     *
     * v1.80 (M-ANT4): v1.121 已完成多轮 thinking 完整回放。
     *  ASSISTANT 消息构建时,若 msg.reasoning 非空且 msg.thinkingSignature 非空,
     *  在 content 数组首位插入 {type:"thinking", thinking, signature} block。
     *  仅有 reasoning 无 signature 时仍发 thinking block(无 signature 字段)。
     *  signature 由 ChatStreamEvent.ReasoningDelta.signature 累积存入 UIMessage.thinkingSignature。
     */
    private fun splitSystem(messages: List<UIMessage>, model: Model): Pair<List<AnthropicSystemBlock>?, List<AnthropicMessage>> {
        val systemParts = messages.filter { it.role == MessageRole.SYSTEM }
            .map { it.content }
            .filter { it.isNotBlank() }
        val cacheControl = if (anthropicConfig.promptCaching) {
            AnthropicCacheControl(ttl = anthropicConfig.promptCacheTtl.takeIf { it.isNotBlank() })
        } else null

        val system = systemParts.takeIf { it.isNotEmpty() }?.let {
            listOf(AnthropicSystemBlock(
                text = it.joinToString("\n\n"),
                cache_control = cacheControl,
            ))
        }

        val rest = messages
            .filter { it.role != MessageRole.SYSTEM }
            .map { msg ->
                // Phase 8.5 S19: Anthropic 仅接受 "user" / "assistant" 两种 role
                val anthropicRole = when (msg.role) {
                    MessageRole.USER, MessageRole.TOOL -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    MessageRole.SYSTEM -> "user"  // 不会到达(SYSTEM 已在前面过滤),编译完整性
                }
                // M-ANT7: TOOL 消息构造 tool_result;ASSISTANT toolCalls 构造 tool_use
                // M-ANT6: 图片 media_type 从 base64 magic bytes 推断
                val anthropicContent: kotlinx.serialization.json.JsonElement = when {
                    msg.role == MessageRole.TOOL && msg.toolCallId != null -> {
                        // M-ANT7: 标准 tool_result content block
                        buildJsonArray {
                            add(buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", msg.toolCallId)
                                put("content", msg.content)
                            })
                        }
                    }
                    msg.role == MessageRole.ASSISTANT -> {
                        // ASSISTANT 消息:按需包含 thinking/text/tool_use content blocks
                        buildJsonArray {
                            // M-ANT4: 多轮 thinking 回放 — 如 msg 有 reasoning(思考文本)且 thinkingSignature,
                            // 在 content 数组首位插入 {type:"thinking", thinking, signature} block,
                            // 让服务端可验证前序思考的完整性,避免每次重新生成 thinking。
                            if (!msg.reasoning.isNullOrBlank() && !msg.thinkingSignature.isNullOrBlank()) {
                                add(buildJsonObject {
                                    put("type", "thinking")
                                    put("thinking", msg.reasoning)
                                    put("signature", msg.thinkingSignature)
                                })
                            } else if (!msg.reasoning.isNullOrBlank()) {
                                // 有思考文本但无 signature(如旧版响应),仍发 thinking block 但不带 signature。
                                // 无 signature 时服务端会重新生成 thinking,但思考文本可供参考。
                                add(buildJsonObject {
                                    put("type", "thinking")
                                    put("thinking", msg.reasoning)
                                })
                            }
                            if (msg.content.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", msg.content)
                                })
                            }
                            if (!msg.toolCalls.isNullOrEmpty()) {
                                msg.toolCalls.forEach { tc ->
                                    val inputJson = resultOf {
                                        AppJson.parseToJsonElement(tc.arguments.ifBlank { "{}" })
                                    }.getOrNull() ?: buildJsonObject {}
                                    add(buildJsonObject {
                                        put("type", "tool_use")
                                        put("id", tc.id)
                                        put("name", tc.name)
                                        put("input", inputJson)
                                    })
                                }
                            }
                        }
                    }
                    msg.imageBase64List.isNotEmpty() && model.supportsVisionInput() -> {
                        // Anthropic Vision: content = [
                        //   {type:"text", text:"..."},
                        //   {type:"image", source:{type:"base64", media_type:"image/jpeg", data:"..."}}
                        // ]
                        buildJsonArray {
                            if (msg.content.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", msg.content)
                                })
                            }
                            msg.imageBase64List.forEach { b64 ->
                                if (b64.isNotEmpty()) {
                                    add(buildJsonObject {
                                        put("type", "image")
                                        put("source", buildJsonObject {
                                            put("type", "base64")
                                            put("media_type", inferImageMediaType(b64))
                                            put("data", b64)
                                        })
                                    })
                                }
                            }
                        }
                    }
                    else -> {
                        // v1.0.5: 防御性视觉过滤 — 模型不支持视觉但消息携带图片时,丢弃图片走纯文本
                        if (msg.imageBase64List.isNotEmpty() && !model.supportsVisionInput()) {
                            Logger.w(
                                "AnthropicProvider",
                                "splitSystem: 模型 ${model.id} 不支持视觉,丢弃 ${msg.imageBase64List.size} 张图片(防御性过滤)",
                            )
                        }
                        JsonPrimitive(msg.content)
                    }
                }
                AnthropicMessage(role = anthropicRole, content = anthropicContent)
            }

        // 在最后一条 user 消息上加 cache_control(让对话前缀缓存)
        val withLastCached = if (anthropicConfig.promptCaching && rest.isNotEmpty()) {
            val lastUserIdx = rest.indexOfLast { it.role == "user" }
            if (lastUserIdx >= 0) {
                rest.mapIndexed { idx, msg ->
                    if (idx == lastUserIdx) msg.copy(cache_control = cacheControl) else msg
                }
            } else rest
        } else rest

        return system to withLastCached
    }

    private fun baseUrl(): String = config.resolvedBaseUrl()

    /**
     * 拉取上游模型列表。
     *
     * GET {resolvedBaseUrl()}/models(resolvedBaseUrl 已含 /v1),
     * x-api-key + anthropic-version 鉴权,解析 data[].id。
     * contextWindow 用 [ModelContextWindowRegistry] 兜底。
     *
     * v1.80 (L-ANT8): 支持分页,循环请求直到 has_more==false。
     * v1.80 (L-ANT11): 日志 URL 脱敏(移除 query 参数)。
     *
     * v1.132 优化(参考 rikkahub/kelivo/openhanako):
     *  - 短超时 client(30s),避免分页请求卡顿占用 chat 长连接
     *  - 按 id 去重 + 字母序排序
     *  - 过滤空 id
     */
    override suspend fun listModels(config: ProviderConfig): List<Model> = withContext(Dispatchers.IO) {
        val specific = config.resolvedSpecific() as? ProviderSpecificConfig.Anthropic
            ?: ProviderSpecificConfig.Anthropic()
        val base = config.resolvedBaseUrl().trimEnd('/') + specific.modelsPath
        // v1.132: 短超时 client
        val listClient = httpClient.newBuilder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val seen = HashSet<String>()
        val models = mutableListOf<Model>()
        var afterId: String? = null
        var hasMore = false
        var pageCount = 0
        val maxPages = 20  // 安全上限,防止异常响应导致无限循环

        do {
            // M-ANT5: 用 HttpUrl.Builder.addQueryParameter 对 after_id 做 URL 编码,
            // 避免特殊字符(如 +/=/空格)直接字符串拼接导致请求 URL 非法
            val urlBuilder = base.toHttpUrl().newBuilder()
                .addQueryParameter("limit", "100")
            if (afterId != null) urlBuilder.addQueryParameter("after_id", afterId)
            val url = urlBuilder.build()
            Logger.i("AnthropicProvider", "listModels: GET ${sanitizeUrl(url.toString())}")
            val httpRequest = Request.Builder()
                .url(url)
                .header("x-api-key", config.apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("Accept", "application/json")
                .get()
                .build()
            listClient.newCall(httpRequest).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // M-ANT10: readBodyCapped 替代 runCatching
                    val errText = readBodyCapped(resp)
                    throw RuntimeException(
                        ErrorCode.INVALID_RESPONSE.toMessage("model_list_fetch", resp.code)
                            + errText.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                    )
                }
                val raw = resp.body.string()
                val parsed = AppJson.decodeFromString<AnthropicModelsResponse>(raw)
                parsed.data.map { m ->
                    Model(
                        id = m.id,
                        name = m.id,
                        providerId = config.id,
                        contextWindow = ModelContextWindowRegistry.lookup(m.id),
                    )
                }
                    // v1.132: 过滤空 id + 去重
                    .filter { it.id.isNotBlank() && seen.add(it.id) }
                    .also { models.addAll(it) }
                hasMore = parsed.has_more
                afterId = parsed.last_id
            }
            pageCount++
        } while (hasMore && afterId != null && pageCount < maxPages)

        // v1.132: 按 id 字母序排序,便于用户查找
        models.sortedBy { it.id.lowercase() }
    }

    /**
     * 构建请求体。Phase 8.1 (M11): 根据 reasoningLevel 映射 thinking 字段。
     *
     * v1.80 (H-ANT3): thinking 启用时确保 max_tokens > budget_tokens + 1024。
     * v1.80 (M-ANT3): thinking 启用时不传 temperature(设为 null)。
     * v1.80 (H-ANT2): 透传 tools(ToolDefinition → AnthropicTool),启用原生 function calling。
     */
    private fun buildRequestBody(
        model: String,
        system: List<AnthropicSystemBlock>?,
        messages: List<AnthropicMessage>,
        temperature: Float?,
        maxTokens: Int?,
        stream: Boolean,
        reasoningLevel: ReasoningLevel? = null,
        tools: List<ToolDefinition>? = null,
    ): String {
        // 映射 ReasoningLevel → thinking 字段
        val thinking: AnthropicThinking? = reasoningLevel?.let { level ->
            when (level) {
                ReasoningLevel.OFF -> null  // 显式关闭,不发 thinking 字段
                ReasoningLevel.AUTO -> null  // 自动,让服务端决定
                ReasoningLevel.LOW, ReasoningLevel.MEDIUM,
                ReasoningLevel.HIGH, ReasoningLevel.XHIGH ->
                    level.budgetTokens?.let { AnthropicThinking(budget_tokens = it) }
            }
        }

        // H-ANT3: thinking 启用时确保 max_tokens > budget_tokens + 1024
        val effectiveMaxTokens = if (thinking != null) {
            maxOf(maxTokens ?: 4096, thinking.budget_tokens + 4096)
        } else {
            maxTokens ?: 4096
        }

        // M-ANT3: thinking 启用时不传 temperature(Anthropic 要求 thinking 模式不设 temperature)
        val effectiveTemperature = if (thinking != null) null else temperature?.toDouble()

        // H-ANT2: ToolDefinition → AnthropicTool(input_schema 直接透传 JSON Schema)
        // H-COMPAT1: compat.supportsToolCalling=false 时强制不发 tools
        //   (Anthropic 默认 true,留此判断保持集中化派生,便于未来扩展)
        val compat: ProviderCompat = config.resolvedCompat(model)
        val anthropicTools = if (compat.supportsToolCalling) {
            tools?.takeIf { it.isNotEmpty() }?.map { td ->
                AnthropicTool(
                    name = td.name,
                    description = td.description,
                    inputSchema = resultOf {
                        AppJson.parseToJsonElement(td.parametersJsonSchema)
                    }.getOrNull() ?: buildJsonObject {},
                )
            }?.takeIf { it.isNotEmpty() }  // v1.0.5: stripEmptyTools — 空 tools 列表改 null,避免 `"tools": []` 被拒绝
        } else null

        val payload = AnthropicRequest(
            model = model,
            messages = messages,
            system = system,
            max_tokens = effectiveMaxTokens,
            temperature = effectiveTemperature,
            stream = stream,
            thinking = thinking,
            tools = anthropicTools,
        )
        return AppJson.encodeToString(payload)
    }

    /**
     * v1.80 (M-ANT2): 返回结构化错误消息,按 error.type 区分。
     */
    private fun parseErrorMessage(code: Int, body: String): String {
        if (body.isBlank()) {
            val category = ProviderHttpSupport.classifyHttpCode(code)
            return buildString {
                append("HTTP ").append(code)
                category?.let { append(" [").append(it).append("]") }
            }
        }
        val detail = resultOf {
            AppJson.decodeFromString<AnthropicErrorBody>(body).error
        }.getOrNull()
        val category = when (detail?.type) {
            "authentication_error" -> "authentication failed"
            "rate_limit_error" -> "rate limited"
            "overloaded_error" -> "overloaded"
            "invalid_request_error" -> "invalid request"
            "permission_error" -> "permission denied"
            "api_error" -> "API error"
            "not_found_error" -> "not found"
            else -> ProviderHttpSupport.classifyHttpCode(code)
        }
        return buildString {
            append("HTTP ").append(code)
            category?.let { append(" [").append(it).append("]") }
            detail?.type?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(")") }
            detail?.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
        }
    }

    /**
     * v1.80 (M-ANT6): 从 base64 magic bytes 推断图片 MIME 类型,回退 image/jpeg。
     */
    private fun inferImageMediaType(b64: String): String {
        val trimmed = b64.trim()
        return when {
            trimmed.startsWith("/9j/") -> "image/jpeg"
            trimmed.startsWith("iVBOR") -> "image/png"
            trimmed.startsWith("R0lGOD") -> "image/gif"
            trimmed.startsWith("UklGR") -> "image/webp"
            else -> "image/jpeg"
        }
    }

    /**
     * v1.80 (L-ANT11): 日志 URL 脱敏,移除 query 参数(可能含敏感信息)。
     */
    private fun sanitizeUrl(url: String): String {
        val qIdx = url.indexOf('?')
        return if (qIdx >= 0) url.substring(0, qIdx) else url
    }

    /**
     * v1.80 (L-ANT5): 判断 reasoningLevel 是否实际启用 thinking(对应 buildRequestBody 中 thinking != null)。
     *  仅 LOW/MEDIUM/HIGH/XHIGH 且 budgetTokens != null 时为 true;OFF/AUTO 为 false。
     *  与 buildRequestBody 的 thinking 计算逻辑保持一致,确保 beta header 仅在 thinking 实际发送时追加。
     */
    private fun isThinkingEnabled(level: ReasoningLevel?): Boolean {
        if (level == null || level == ReasoningLevel.OFF || level == ReasoningLevel.AUTO) return false
        return level.budgetTokens != null
    }

    /**
     * v1.80 (L-ANT7 / M-ANT8): 构建 anthropic-beta header。
     *  - L-ANT5: 仅在 [thinkingEnabled] 为 true 时追加 interleaved-thinking-2025-05-14
     *    (原默认总是发送,非 thinking 请求多发该 beta 头无意义且可能触发服务端告警)
     *  - promptCacheTtl == "1h" 时追加 extended-cache-ttl-2025-04-11
     */
    private fun buildBetaHeader(thinkingEnabled: Boolean): String {
        val parts = mutableListOf<String>()
        if (thinkingEnabled) {
            parts.add(BETA_INTERLEAVED_THINKING)
        }
        if (anthropicConfig.promptCacheTtl == "1h") {
            parts.add(BETA_EXTENDED_CACHE_TTL)
        }
        return parts.joinToString(",")
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val BETA_INTERLEAVED_THINKING = "interleaved-thinking-2025-05-14"
        const val BETA_EXTENDED_CACHE_TTL = "extended-cache-ttl-2025-04-11"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        // completeText 429 切换 key 最大次数,防止无限递归
        const val MAX_KEY_SWITCHES = 3
    }
}
