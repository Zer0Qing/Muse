package io.zer0.ai.gemini

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
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Google Gemini Provider(同时支持 generativelanguage API + Vertex AI)。
 *
 * ## generativelanguage API(默认)
 *  - URL: {baseUrl}/models/{model}:streamGenerateContent?key={apiKey}&alt=sse
 *  - 认证: API key 作为 query param(?key=...)
 *
 * ## Vertex AI(Phase 9.4 M10,服务账号认证)
 *  - URL: https://{location}-aiplatform.googleapis.com/v1/projects/{projectId}/locations/{location}/publishers/google/models/{model}:streamGenerateContent?alt=sse
 *  - 认证: Authorization: Bearer {access_token}(从服务账号 JWT 交换得到)
 *  - 请求体格式与 generativelanguage API 相同(都是 GeminiRequest)
 *  - 服务账号需在 GCP 项目授予 "Vertex AI User" 角色
 *
 * 通用:
 *  - systemInstruction 是顶层字段(从 UIMessage SYSTEM 抽出)
 *  - 流式: alt=sse 返回标准 SSE,每个 data: 是一个 GenerateContentResponse
 *  - assistant 在 Gemini 里叫 "model"
 *  - Gemini 无原生思考链公开 API,reasoning 走无(留空)
 *
 * Phase 9.4 (M10): 加 [vertexAiAuthToken] 字段。当 [ProviderSpecificConfig.Gemini.useVertexAI]
 * 为 true 且 [ProviderSpecificConfig.Gemini.useServiceAccount] 为 true 时,
 * 用 [VertexAiAuthToken] 获取 Bearer token。
 *
 * v1.80 修复(继承 ProviderHttpSupport,函数调用 / 安全过滤 / 重试 / 日志脱敏等):
 *  - H-GEM1: listModels 日志不打印含 ?key= 的完整 URL
 *  - H-GEM2: 函数调用支持(tools 透传 / functionCall 解析 / toolCalls 回填)
 *  - H-GEM3: ASSISTANT toolCalls → functionCall part;TOOL → functionResponse part
 *  - H-GEM4: promptFeedback.blockReason / SAFETY finishReason → Error
 *  - M-GEM1..14 / L-GEM7..11: 见各处注释
 */
class GeminiProvider(
    config: ProviderConfig,
) : ProviderHttpSupport(config) {

    override val id: String get() = config.id
    override val displayName: String get() = config.displayName

    /**
     * Phase 9.4: Vertex AI 服务账号认证器(useVertexAI && useServiceAccount 时非 null)。
     *
     * L-GEM7: 改为 by lazy,避免在主线程构造 Provider 时同步解析 PEM 私钥,
     * 实际首次取 token(在 IO 线程)时才解析。
     */
    private val vertexAiAuthToken: VertexAiAuthToken? by lazy {
        val specific = config.resolvedSpecific()
        if (specific is ProviderSpecificConfig.Gemini &&
            specific.useVertexAI &&
            specific.useServiceAccount &&
            specific.serviceAccountEmail.isNotBlank() &&
            specific.privateKey.isNotBlank()
        ) {
            // L-GEM1: 用 resultOf 替代 runCatching,正确传播 CancellationException
            // M-VTX1: 传入共享 httpClient,避免 VertexAiAuthToken 自建 client 泄漏连接池
            resultOf {
                VertexAiAuthToken(
                    serviceAccountEmail = specific.serviceAccountEmail,
                    privateKeyPem = specific.privateKey,
                    client = httpClient,
                )
            }.onError { msg, t ->
                Logger.w(TAG, "VertexAiAuthToken 初始化失败: $msg", t)
            }.getOrNull()
        } else null
    }

    private val sseFactory by lazy { EventSources.createFactory(httpClient) }

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> = callbackFlow {
        val scope = this
        val (system, contents) = splitSystem(request.messages)
        val body = buildRequestBody(
            system = system, contents = contents,
            temperature = request.temperature, maxTokens = request.maxTokens,
            reasoningLevel = request.reasoningLevel,
            supportsImageOutput = request.model.supportsImageOutput(),
            tools = request.tools,
        )
        // M-GEM2: buildUrl 可能抛 IllegalStateException(Vertex AI 配置缺失),包裹 try-catch 发 Error
        val url: String = try {
            buildUrl(model = request.model.id, stream = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trySend(ChatStreamEvent.Error(e.message ?: ErrorCode.SERVICE_UNAVAILABLE.toMessage("url_build")))
            close()
            return@callbackFlow
        }

        // M-GEM13: Vertex AI 服务账号配置无效(VertexAiAuthToken 构造失败)直接发 Error
        // M-GEM4: token 改为 var,重试时重新解析(避免旧 token 过期导致重试也失败)
        var token: String? = try {
            resolveToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trySend(ChatStreamEvent.Error(e.message ?: ErrorCode.VERTEX_AI_TOKEN_FAILED.toMessage()))
            close()
            return@callbackFlow
        }

        // M-GEM1: finished 标志,onClosed 时据此判断是否异常关闭
        val finished = AtomicBoolean(false)
        // H-GEM1: generation 计数器区分新旧流,替代原 retrying 标志的竞态判断。
        //  每次 startStream 递增 generation 并捕获 myGen,listener 回调校验 myGen == generation.get()
        //  即当前流;旧流的 onClosed/onFailure 在重试过渡期 generation 已推进,直接忽略,避免虚假 Error。
        val generation = AtomicInteger(0)
        val attempt = AtomicInteger(0)
        val currentEventSource = AtomicReference<EventSource?>(null)
        val currentCall = AtomicReference<Call?>(null)
        // M-GEM3: 为每个 functionCall 分配递增 index(原固定 0 导致多工具调用合并为一个)
        val toolCallIndex = AtomicInteger(0)
        // v1.0.1 (P1): 任何 ContentDelta/ImageDelta/ToolCallDelta 发出后置 true,
        //   重试前检查 !anyDeltaSent.get(),避免已流出内容被重发(与 OpenAI/Anthropic 对齐)。
        //   原 Gemini 仅用 generation 计数器区分新旧流,但未防止"重连后重新发送已发内容"。
        val anyDeltaSent = AtomicBoolean(false)

        /**
         * M-GEM2: 启动(或重启)SSE 流。可重试错误(UNAVAILABLE/5xx/429/网络异常)按指数退避重试,
         * 最多 [MAX_RETRIES] 次。
         */
        fun startStream() {
            val myGen = generation.incrementAndGet()
            // v1.0.1 (P0): 每次重连重新构建 URL(多 key 切换后 ?key= 参数需更新)
            val currentUrl = try {
                buildUrl(model = request.model.id, stream = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trySend(ChatStreamEvent.Error(e.message ?: ErrorCode.SERVICE_UNAVAILABLE.toMessage("url_build")))
                close()
                return
            }
            val httpRequest = Request.Builder()
                .url(currentUrl)
                .header("Accept", "text/event-stream")
                .apply {
                    // Phase 9.4: Vertex AI 服务账号鉴权用 Bearer token,优先于 API Key
                    if (token != null) {
                        header("Authorization", "Bearer $token")
                    }
                }
                // L-GEM8: 不手动设 Content-Type,由 toRequestBody 自动设置
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val call = httpClient.newCall(httpRequest)
            currentCall.set(call)
            val listener = object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    if (myGen != generation.get()) return  // H-GEM1: 旧流回调,忽略
                    if (!response.isSuccessful) {
                        val errText = ProviderHttpSupport.readBodyCapped(response)
                        val code = response.code
                        // v1.0.1 (P0): 429 限流时先尝试切换 key(多 key 场景),立即重试无需 backoff
                        if (code == 429 && !anyDeltaSent.get() && attempt.get() < MAX_RETRIES &&
                            !request.abortSignal.aborted && !scope.isClosedForSend &&
                            switchToNextKey()
                        ) {
                            attempt.incrementAndGet()
                            generation.incrementAndGet()
                            eventSource.cancel()
                            call.cancel()
                            Logger.i(TAG, "streamChat onOpen 429 限流,已切换到下一个 key,立即重试 " +
                                "(${attempt.get()}/$MAX_RETRIES)")
                            scope.launch {
                                if (request.abortSignal.aborted || scope.isClosedForSend) return@launch
                                startStream()
                            }
                            return
                        }
                        // v1.0.1 (P1): anyDeltaSent 保护 — 已流出内容则不重试(避免重复发送)
                        // M-GEM2: 可重试错误(5xx/429)重试
                        if (attempt.get() < MAX_RETRIES && !anyDeltaSent.get() && isRetryableError(code, null)) {
                            attempt.incrementAndGet()
                            // H-GEM1: 推进 generation 使旧流的后续 onClosed/onFailure 失效
                            generation.incrementAndGet()
                            eventSource.cancel()
                            call.cancel()
                            scope.launch {
                                delay(backoffMs(attempt.get()))
                                // M-GEM4: 重试时重新解析 token(Vertex AI 旧 token 可能已过期)
                                token = try {
                                    resolveToken()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    trySend(ChatStreamEvent.Error(e.message ?: ErrorCode.VERTEX_AI_TOKEN_FAILED.toMessage()))
                                    close()
                                    return@launch
                                }
                                startStream()
                            }
                            return
                        }
                        val msg = parseErrorMessage(code, errText)
                        // v1.0.1: 401/403 鉴权失败时标记当前 key 失败(多 key 场景)
                        if (code == 401 || code == 403) {
                            markKeyFailed(hardBlock = true)
                        }
                        finished.set(true)
                        trySend(ChatStreamEvent.Error(msg))
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
                    if (myGen != generation.get()) return  // H-GEM1: 旧流回调,忽略
                    if (data.isBlank()) return
                    // L-GEM1: 用 resultOf 替代 runCatching,正确传播 CancellationException
                    val chunk = resultOf {
                        AppJson.decodeFromString<GeminiResponse>(data)
                    }.getOrNull() ?: return

                    // H-GEM4: promptFeedback 提示级安全拦截
                    chunk.promptFeedback?.blockReason?.takeIf { it.isNotBlank() }?.let { reason ->
                            finished.set(true)
                            trySend(ChatStreamEvent.Error(ErrorCode.PERMISSION_DENIED.toMessage("safety", reason)))
                            close()
                        }

                    val candidate = chunk.candidates.firstOrNull() ?: return
                    // Phase 8.6: 遍历 parts,文本 → ContentDelta,图片 → ImageDelta
                    candidate.content?.parts?.forEach { part ->
                        if (part.text.isNotEmpty()) {
                            // v1.0.1 (P1): 标记已发出内容,防止重连后重发
                            anyDeltaSent.set(true)
                            trySend(ChatStreamEvent.ContentDelta(part.text))
                        }
                        // Phase 8.6: Gemini 绘图返回的 inlineData(base64)
                        val inline = part.inlineData
                        if (inline != null && inline.data.isNotEmpty()) {
                            anyDeltaSent.set(true)
                            trySend(ChatStreamEvent.ImageDelta(
                                imageBase64 = inline.data,
                                mimeType = inline.mimeType,
                            ))
                        }
                        // H-GEM2: 解析 functionCall → ToolCallDelta
                        // M-GEM3: 用递增 index 区分多个 functionCall(原固定 0 会合并为同一工具调用)
                        val fc = part.functionCall
                        if (fc != null) {
                            val argsJson = fc.args?.let { AppJson.encodeToString(it) } ?: "{}"
                            anyDeltaSent.set(true)
                            trySend(ChatStreamEvent.ToolCallDelta(
                                index = toolCallIndex.getAndIncrement(),
                                id = fc.name,
                                name = fc.name,
                                argumentsDelta = argsJson,
                            ))
                        }
                    }
                    if (candidate.finishReason != null) {
                        val reason = candidate.finishReason
                        // M-GEM4: 安全相关 finishReason 发 Error 而非 Done
                        if (reason in SAFETY_FINISH_REASONS) {
                            finished.set(true)
                        trySend(ChatStreamEvent.Error(ErrorCode.PERMISSION_DENIED.toMessage("safety", reason ?: "")))
                            close()
                        } else {
                            finished.set(true)
                            trySend(ChatStreamEvent.Done(reason))
                            close()
                        }
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    // H-GEM1: 旧流的 onClosed(generation 已推进)直接忽略,避免重试过渡期虚假 Error
                    if (myGen != generation.get()) return
                    // M-GEM1: 未正常结束(未收到 finishReason)即关闭,发 Error
                    if (!finished.get()) {
                        trySend(ChatStreamEvent.Error(ErrorCode.STREAM_INTERRUPTED.toMessage("gemini")))
                    }
                    close()
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?,
                ) {
                    // H-GEM1: 旧流的 onFailure(generation 已推进)直接忽略
                    if (myGen != generation.get()) return
                    if (request.abortSignal.aborted) {
                        finished.set(true)
                        close()
                        return
                    }
                    val code = response?.code
                    // v1.0.1 (P0): 429 限流时先尝试切换 key(多 key 场景),立即重试无需 backoff
                    if (code == 429 && !anyDeltaSent.get() && attempt.get() < MAX_RETRIES &&
                        !request.abortSignal.aborted && !scope.isClosedForSend &&
                        switchToNextKey()
                    ) {
                        attempt.incrementAndGet()
                        // H-GEM1: 推进 generation 使旧流的后续 onClosed 失效
                        generation.incrementAndGet()
                        Logger.i(TAG, "streamChat onFailure 429 限流,已切换到下一个 key,立即重试 " +
                            "(${attempt.get()}/$MAX_RETRIES)")
                        scope.launch {
                            if (request.abortSignal.aborted || scope.isClosedForSend) return@launch
                            startStream()
                        }
                        return
                    }
                    // v1.0.1 (P1): anyDeltaSent 保护 — 已流出内容则不重试(避免重复发送)
                    // M-GEM2: 可重试错误(网络异常/5xx/429/UNAVAILABLE)指数退避
                    if (attempt.get() < MAX_RETRIES && !anyDeltaSent.get() && isRetryableError(code, t)) {
                        attempt.incrementAndGet()
                        // H-GEM1: 推进 generation 使旧流的后续 onClosed 失效
                        generation.incrementAndGet()
                        scope.launch {
                            delay(backoffMs(attempt.get()))
                            // M-GEM4: 重试时重新解析 token
                            token = try {
                                resolveToken()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                trySend(ChatStreamEvent.Error(e.message ?: "Vertex AI 鉴权失败"))
                                close()
                                return@launch
                            }
                            startStream()
                        }
                        return
                    }
                    // v1.0.1: 401/403 鉴权失败时标记当前 key 失败(多 key 场景)
                    if (code == 401 || code == 403) {
                        markKeyFailed(hardBlock = true)
                    }
                    // v1.109 修复: SSE 已建立(2xx)后中断是连接断开,优先用 Throwable 信息
                    val msg = response?.let {
                        if (it.code in 200..299) {
                            t?.message?.takeIf { m -> m.isNotBlank() }
                                ?: ErrorCode.STREAM_INTERRUPTED.toMessage("gemini")
                        } else {
                            val respBody = ProviderHttpSupport.readBodyCapped(it)
                            parseErrorMessage(it.code, respBody)
                        }
                    } ?: (t?.message ?: ErrorCode.NETWORK_ERROR.toMessage())
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

            val eventSource = sseFactory.newEventSource(httpRequest, listener)
            currentEventSource.set(eventSource)
        }

        startStream()

        awaitClose {
            // L-GEM11: 不调 request.abortSignal.abort(),只 cancel eventSource + call
            currentEventSource.get()?.cancel()
            currentCall.get()?.cancel()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun completeText(request: ChatRequest): ChatCompletion = completeTextImpl(request, 0)

    private suspend fun completeTextImpl(request: ChatRequest, keySwitchDepth: Int = 0): ChatCompletion = withContext(Dispatchers.IO) {
        val (system, contents) = splitSystem(request.messages)
        val body = buildRequestBody(
            system = system, contents = contents,
            temperature = request.temperature, maxTokens = request.maxTokens,
            reasoningLevel = request.reasoningLevel,
            supportsImageOutput = request.model.supportsImageOutput(),
            tools = request.tools,
        )
        // M-GEM7: buildUrl/resolveToken 纳入 try-catch,原在 try 块外抛 IllegalStateException 会绕过错误处理
        // M-GEM13: Vertex AI 服务账号配置无效时 resolveToken 抛 RuntimeException
        val httpRequest: Request
        val call: Call
        try {
            val url = buildUrl(model = request.model.id, stream = false)
            val token: String? = resolveToken()
            httpRequest = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .apply {
                    if (token != null) {
                        header("Authorization", "Bearer $token")
                    }
                }
                // L-GEM8: 不手动设 Content-Type,由 toRequestBody 自动设置
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            call = httpClient.newCall(httpRequest)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // M-GEM7: buildUrl/resolveToken 异常统一抛 RuntimeException,由下方 catch 记录日志
            throw RuntimeException(e.message ?: ErrorCode.SERVICE_UNAVAILABLE.toMessage("request_build"), e)
        }
        try {
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val code = resp.code
                    // v1.0.1 (P0): 429 切换 key 重试(多 key 场景);buildUrl 内部用 effectiveApiKey(),
                    //   递归调用 completeText 会用新 key 重新构建 URL(?key= 参数更新)
                    if (code == 429 && keySwitchDepth < MAX_KEY_SWITCHES && switchToNextKey()) {
                        Logger.i(TAG, "completeText 429 限流,已切换到下一个 key,重试 ($keySwitchDepth/$MAX_KEY_SWITCHES)")
                        return@withContext completeTextImpl(request, keySwitchDepth + 1)
                    }
                    // v1.0.1: 401/403 鉴权失败时标记当前 key 失败(多 key 场景)
                    if (code == 401 || code == 403) {
                        markKeyFailed(hardBlock = true)
                    }
                    // M-GEM14: 用 readBodyCapped 替代 runCatching { resp.body.string() }
                    val errText = ProviderHttpSupport.readBodyCapped(resp)
                    throw RuntimeException(parseErrorMessage(code, errText))
                }
                val raw = resp.body.string()
                val parsed = AppJson.decodeFromString<GeminiResponse>(raw)
                // H-GEM4: promptFeedback 安全过滤
                parsed.promptFeedback?.blockReason?.takeIf { it.isNotBlank() }?.let { reason ->
                    throw RuntimeException(ErrorCode.PERMISSION_DENIED.toMessage("safety", reason))
                }
                val candidate = parsed.candidates.firstOrNull()
                    ?: throw RuntimeException(ErrorCode.INVALID_RESPONSE.toMessage("empty_candidates"))
                // H-GEM2: 收集 functionCall → toolCalls
                // M-GEM6: 用 takeIf { it.isNotEmpty() } 确保无工具调用时返回 null(原返回空列表)
                val toolCalls = candidate.content?.parts?.mapNotNull { it.functionCall }?.map { fc ->
                    ToolCall(
                        id = fc.name,
                        name = fc.name,
                        arguments = fc.args?.let { AppJson.encodeToString(it) } ?: "{}",
                    )
                }?.takeIf { it.isNotEmpty() }
                // Phase 8.6: 合并所有 text part(忽略图片 part,completeText 用于 memory 等后台任务)
                val text = candidate.content?.parts?.joinToString("") { it.text } ?: ""
                // M-GEM4: 安全相关 finishReason 抛错
                val reason = candidate.finishReason
                if (reason in SAFETY_FINISH_REASONS) {
                    throw RuntimeException(ErrorCode.PERMISSION_DENIED.toMessage("safety", reason))
                }
                if (text.isBlank() && toolCalls.isNullOrEmpty()) {
                    throw RuntimeException(ErrorCode.INVALID_RESPONSE.toMessage("empty_text"))
                }
                ChatCompletion(text = text, finishReason = reason, toolCalls = toolCalls)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            call.cancel()
            throw e
        } catch (t: Throwable) {
            if (request.abortSignal.aborted) {
                Logger.d(TAG, "completeText aborted by user")
            } else if (t is RuntimeException && (t.message?.startsWith("Gemini") == true || t.message?.contains("HTTP") == true || t.message?.contains("Gemini 安全过滤") == true || t.message?.contains("Vertex AI") == true)) {
                // 已记录的 HTTP/业务错误,不重复 log
            } else {
                Logger.e(TAG, "completeText 异常", t)
            }
            throw t
        } finally {
            if (request.abortSignal.aborted) call.cancel()
        }
    }

    /**
     * 拉取上游模型列表。
     *
     * GET {baseUrl}/models?pageSize=100&key={apiKey}&pageToken={token},解析 models[].name
     * (去掉 "models/" 前缀)和 displayName,按 supportedGenerationMethods 过滤
     * (仅保留支持 generateContent 的模型)。contextWindow 用 [ModelContextWindowRegistry] 兜底。
     *
     * 注:仅支持 generativelanguage API;Vertex AI 端点模型列表接口不同,此处不处理。
     *
     * v1.80 (M-GEM5): 支持分页,循环请求直到 nextPageToken 为 null,加安全页数上限防止异常响应无限循环。
     *
     * v1.132 优化(参考 rikkahub/kelivo/openhanako):
     *  - 短超时 client(30s),避免分页请求卡顿占用 chat 长连接
     *  - 按 id 去重 + 字母序排序
     *  - 过滤空 id
     */
    override suspend fun listModels(config: ProviderConfig): List<Model> = withContext(Dispatchers.IO) {
        val specific = config.resolvedSpecific()
        // M-GEM10: Vertex AI 不支持 listModels(端点协议不同)
        if (specific is ProviderSpecificConfig.Gemini && specific.useVertexAI) return@withContext emptyList()
        val base = config.resolvedBaseUrl().trimEnd('/')
        // H-GEM1: 日志不打印含 ?key= 的完整 URL,只打 base + "/models"
        Logger.i(TAG, "listModels: GET $base/models")
        // v1.132: 短超时 client
        val listClient = httpClient.newBuilder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val seen = HashSet<String>()
        val models = mutableListOf<Model>()
        var pageToken: String? = null
        var pageCount = 0
        val maxPages = 10  // M-GEM5: 安全上限,防止异常 nextPageToken 导致无限循环

        do {
            val urlBuilder = "$base/models".toHttpUrl().newBuilder()
                .addQueryParameter("pageSize", "100")
                .addQueryParameter("key", config.apiKey)
            // M-GEM5: 分页参数 pageToken
            if (pageToken != null) urlBuilder.addQueryParameter("pageToken", pageToken)
            val httpRequest = Request.Builder()
                .url(urlBuilder.build())
                .header("Accept", "application/json")
                .get()
                .build()
            listClient.newCall(httpRequest).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // M-GEM14: 用 readBodyCapped
                    val errText = ProviderHttpSupport.readBodyCapped(resp)
                    throw RuntimeException(
                        ErrorCode.INVALID_RESPONSE.toMessage("model_list_fetch", resp.code)
                            + errText.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                    )
                }
                val raw = resp.body.string()
                val parsed = AppJson.decodeFromString<GeminiModelsResponse>(raw)
                parsed.models
                    // L-GEM10: 过滤条件改为 == true
                    .filter { it.supportedGenerationMethods?.contains("generateContent") == true }
                    .map { m ->
                        val id = m.name.substringAfterLast("/").ifBlank { m.name }
                        Model(
                            id = id,
                            name = m.displayName ?: id,
                            providerId = config.id,
                            contextWindow = ModelContextWindowRegistry.lookup(id),
                        )
                    }
                    // v1.132: 过滤空 id + 去重
                    .filter { it.id.isNotBlank() && seen.add(it.id) }
                    .also { models.addAll(it) }
                // M-GEM5: 取下一页 token,null 表示无更多页
                pageToken = parsed.nextPageToken
            }
            pageCount++
        } while (!pageToken.isNullOrBlank() && pageCount < maxPages)

        // v1.132: 按 id 字母序排序,便于用户查找
        models.sortedBy { it.id.lowercase() }
    }

    /**
     * 把 SYSTEM 消息抽出为 systemInstruction,其余转 Gemini contents(assistant → model)。
     *
     * Phase 8.6: USER 消息若含 imageBase64List,转成 inlineData part,
     * 文本作为单独 text part,顺序:先图后文(Gemini 推荐顺序)。
     *
     * H-GEM3: 函数调用历史回放
     *  - ASSISTANT 携带 toolCalls 时,每个 toolCall 转 functionCall part(连同 args)
     *  - TOOL 消息转 functionResponse part(含 name + response JSON)
     */
    private fun splitSystem(messages: List<UIMessage>): Pair<String?, List<GeminiContent>> {
        val systemParts = messages.filter { it.role == MessageRole.SYSTEM }
            .map { it.content }
            .filter { it.isNotBlank() }
        val system = systemParts.joinToString("\n\n").ifBlank { null }
        val rest = messages
            .filter { it.role != MessageRole.SYSTEM }
            .map { msg ->
                val geminiRole = when (msg.role) {
                    MessageRole.ASSISTANT -> "model"
                    MessageRole.USER -> "user"
                    MessageRole.TOOL -> "user"  // Gemini 无 tool role,降级为 user
                    MessageRole.SYSTEM -> "user"  // 不会到达(SYSTEM 已过滤),编译完整性
                }
                val parts = buildList {
                    when {
                        // H-GEM3: ASSISTANT 携带 toolCalls → functionCall part
                        msg.role == MessageRole.ASSISTANT && !msg.toolCalls.isNullOrEmpty() -> {
                            msg.toolCalls.forEach { tc ->
                                // L-GEM1: 用 resultOf 替代 runCatching,正确传播 CancellationException
                                val args = resultOf { AppJson.parseToJsonElement(tc.arguments) }
                                    .getOrNull() ?: JsonNull
                                add(GeminiPart(functionCall = GeminiFunctionCall(name = tc.name, args = args)))
                            }
                            // 文本(若有)
                            if (msg.content.isNotEmpty()) add(GeminiPart(text = msg.content))
                        }
                        // H-GEM3: TOOL 消息 → functionResponse part(含 name + response JSON)
                        msg.role == MessageRole.TOOL -> {
                            // L-GEM1: 用 resultOf 替代 runCatching
                            val respJson = resultOf { AppJson.parseToJsonElement(msg.content) }
                                .getOrNull()
                                ?: JsonObject(mapOf("result" to JsonPrimitive(msg.content)))
                            // M-GEM8: toolCallId 为空时用 fallback,避免 functionResponse.name 为空导致 Gemini 拒绝请求
                            // (Gemini 要求 functionResponse.name 与前序 functionCall.name 匹配)
                            val fnName = msg.toolCallId?.takeIf { it.isNotBlank() } ?: "unknown"
                            add(GeminiPart(functionResponse = GeminiFunctionResponse(
                                name = fnName, response = respJson,
                            )))
                        }
                        else -> {
                            // 普通 USER/ASSISTANT:图片在前(若存在)+ 视频(若存在)+ 文本
                            msg.imageBase64List.forEach { b64 ->
                                if (b64.isNotEmpty()) {
                                    add(GeminiPart(inlineData = GeminiInlineData(
                                        // M-GEM11: mimeType 从 base64 头部 magic bytes 推断
                                        mimeType = inferMimeType(b64),
                                        data = b64,
                                    )))
                                }
                            }
                            // V-GEM1: 视频附件(已通过 uploadFile 上传到 Files API,这里仅引用 fileUri)
                            val vUri = msg.videoFileUri
                            val vMime = msg.videoMimeType
                            if (!vUri.isNullOrBlank() && !vMime.isNullOrBlank()) {
                                add(GeminiPart(fileData = GeminiFileData(
                                    mimeType = vMime,
                                    fileUri = vUri,
                                )))
                            }
                            // 文本(即使是空也添加,避免空 parts 数组)
                            add(GeminiPart(text = msg.content))
                        }
                    }
                }
                val finalParts = if (parts.isEmpty()) listOf(GeminiPart(text = msg.content)) else parts
                GeminiContent(role = geminiRole, parts = finalParts)
            }
        return system to rest
    }

    /**
     * Phase 9.4 (M10): URL 构造分支。
     *
     * - generativelanguage API: {baseUrl}/models/{model}:{action}?key={apiKey}[&alt=sse]
     * - Vertex AI: https://{location}-aiplanet.googleapis.com/v1/projects/{projectId}/
     *              locations/{location}/publishers/google/models/{model}:{action}[?alt=sse]
     *   (Vertex AI 用 Bearer token 鉴权,不加 ?key=)
     *
     * M-GEM8: action 从 specific.streamPath/generatePath 取值(去前导 `:`),不硬编码。
     * M-GEM9: Vertex AI + !useServiceAccount 直接 error,不走 ?key= 兜底。
     */
    private fun buildUrl(model: String, stream: Boolean): String {
        val specific = config.resolvedSpecific()
        val geminiSpecific = specific as? ProviderSpecificConfig.Gemini
        // M-GEM8: 从 specific 取端点路径(去前导 `:`),空则用默认
        val action = if (stream) {
            geminiSpecific?.streamPath?.removePrefix(":")?.takeIf { it.isNotBlank() }
                ?: "streamGenerateContent"
        } else {
            geminiSpecific?.generatePath?.removePrefix(":")?.takeIf { it.isNotBlank() }
                ?: "generateContent"
        }

        // Phase 9.4 (M10): Vertex AI 端点分支
        if (geminiSpecific != null && geminiSpecific.useVertexAI) {
            // M-GEM9: Vertex AI 需启用服务账号认证,不走 ?key= 兜底
            if (!geminiSpecific.useServiceAccount) {
                error(ErrorCode.VERTEX_AI_CONFIG_INVALID.toMessage("need_service_account"))
            }
            val location = geminiSpecific.location.ifBlank { "us-central1" }
            val projectId = geminiSpecific.projectId.ifBlank {
                error(ErrorCode.VERTEX_AI_CONFIG_INVALID.toMessage("missing_project_id"))
            }
            val base = "https://$location-aiplatform.googleapis.com/v1"
            val urlBuilder = ("$base/projects/$projectId/locations/$location/publishers/google/models/$model:$action")
                .toHttpUrl()
                .newBuilder()
            if (stream) urlBuilder.addQueryParameter("alt", "sse")
            return urlBuilder.build().toString()
        }

        // generativelanguage API(默认)
        // v1.0.1: 用 effectiveApiKey() 支持多 key 轮换(429 切换 key 后 URL ?key= 参数需更新)
        val base = config.resolvedBaseUrl().trimEnd('/')
        val urlBuilder = ("$base/models/$model:$action").toHttpUrl().newBuilder()
            .addQueryParameter("key", effectiveApiKey())
        if (stream) urlBuilder.addQueryParameter("alt", "sse")
        return urlBuilder.build().toString()
    }

    /**
     * Phase 8.6: buildRequestBody 增加 [supportsImageOutput] 参数。
     *
     * 当模型声明 supportsImageOutput()(Model.outputModalities 含 "image")时,
     * 在 generationConfig 中加 responseModalities = ["TEXT", "IMAGE"],
     * 让 Gemini 返回文本 + 图片混合内容(Gemini 2.5 Flash Image / Imagen via generateContent)。
     *
     * H-GEM2: [tools] 透传为 Gemini tools 格式(单个 tool 包裹 functionDeclarations 数组)。
     */
    private fun buildRequestBody(
        system: String?,
        contents: List<GeminiContent>,
        temperature: Float?,
        maxTokens: Int?,
        reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
        supportsImageOutput: Boolean = false,
        tools: List<ToolDefinition>? = null,
    ): String {
        // Phase 8.5 修复: Gemini 2.5 系列支持 thinkingConfig.thinkingBudget。
        // OFF → 不发(让模型用默认);其他等级用 level.budgetTokens 作为预算。
        val thinkingCfg = when (reasoningLevel) {
            ReasoningLevel.OFF -> null
            ReasoningLevel.AUTO -> null
            else -> reasoningLevel.budgetTokens?.let { GeminiThinkingConfig(thinkingBudget = it) }
        }
        // Phase 8.6 + M-GEM12: responseModalities 统一大写
        val responseModalities = if (supportsImageOutput) listOf("TEXT", "IMAGE") else null
        // H-GEM2: tools 透传(Gemini 用单个 tool 包裹 functionDeclarations 数组)
        // H-COMPAT1: compat.supportsToolCalling=false 时强制不发 tools
        //   (Gemini 默认 true,留此判断保持集中化派生,便于未来按 modelId 扩展)
        val compat: ProviderCompat = config.resolvedCompat()
        val geminiTools = if (compat.supportsToolCalling) {
            tools?.takeIf { it.isNotEmpty() }?.let {
                listOf(GeminiTool(functionDeclarations = it.map { td -> td.toGeminiDeclaration() }))
            }
        } else null
        val payload = GeminiRequest(
            contents = contents,
            systemInstruction = system?.takeIf { it.isNotBlank() }
                ?.let { GeminiContent(role = "user", parts = listOf(GeminiPart(text = it))) },
            generationConfig = GeminiGenerationConfig(
                temperature = temperature?.toDouble(),
                maxOutputTokens = maxTokens,
                thinkingConfig = thinkingCfg,
                responseModalities = responseModalities,
            ),
            tools = geminiTools,
        )
        return AppJson.encodeToString(payload)
    }

    /** H-GEM2: ToolDefinition → GeminiFunctionDeclaration(parameters 解析为 JsonElement)。 */
    private fun ToolDefinition.toGeminiDeclaration(): GeminiFunctionDeclaration = GeminiFunctionDeclaration(
        name = name,
        description = description,
        // L-GEM1: 用 resultOf 替代 runCatching,正确传播 CancellationException
        parameters = resultOf {
            AppJson.parseToJsonElement(parametersJsonSchema)
        }.getOrNull(),
    )

    /**
     * M-GEM11: 从 base64 头部 magic bytes 推断图片 MIME 类型。
     * 无法识别时降级为 image/jpeg(照片最常见)。
     */
    private fun inferMimeType(b64: String): String {
        val head = b64.take(16).uppercase()
        return when {
            head.startsWith("IVBORW0") -> "image/png"   // PNG: iVBORw0K
            head.startsWith("/9J/") -> "image/jpeg"      // JPEG: /9j/
            head.startsWith("UKLGR") -> "image/webp"     // WebP: UklGR
            head.startsWith("R0LGOD") -> "image/gif"     // GIF: R0lGOD
            else -> "image/jpeg"
        }
    }

    /**
     * M-GEM2: 判断错误是否可重试(UNAVAILABLE/5xx/429/网络异常)。
     * v1.80 (M-GEM1): 429(RESOURCE_EXHAUSTED)纳入可重试,原仅 5xx/IOException 导致限流直接失败。
     */
    private fun isRetryableError(code: Int?, t: Throwable?): Boolean {
        if (t != null && t is java.io.IOException) return true
        if (code != null && (code == 429 || code in 500..599)) return true
        return false
    }

    /**
     * M-GEM2: 指数退避(500ms / 1000ms / 2000ms)。
     * v1.80 (L-GEM2): 加随机 jitter(±20%),避免多客户端同步重试引发惊群。
     */
    private fun backoffMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 4)
        val base = 500L * (1L shl shift)
        // L-GEM2: jitter = base * (0.8 + random*0.4),范围 [0.8*base, 1.2*base]
        val jitter = base * (0.8 + Random.nextDouble() * 0.4)
        return jitter.toLong()
    }

    /**
     * M-GEM3: 按 status 区分错误类型,给出人类可读的中文提示。
     */
    private fun parseErrorMessage(code: Int, body: String): String {
        if (body.isBlank()) return "HTTP $code"
        // L-GEM1: 用 resultOf 替代 runCatching,正确传播 CancellationException
        val detail = resultOf {
            AppJson.decodeFromString<GeminiErrorBody>(body).error
        }.getOrNull()
        val status = detail?.status
        val statusHint = when (status) {
            "RESOURCE_EXHAUSTED" -> "resource exhausted"
            "UNAVAILABLE" -> "unavailable"
            "PERMISSION_DENIED" -> "permission denied"
            "INVALID_ARGUMENT" -> "invalid argument"
            "FAILED_PRECONDITION" -> "failed precondition"
            else -> null
        }
        return buildString {
            append("HTTP ").append(code)
            status?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(")") }
            statusHint?.let { append(" [").append(it).append("]") }
            detail?.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
        }
    }

    /**
     * M-GEM13: 解析 Vertex AI Bearer token(流式与completeText共用判断)。
     * - 非 Vertex AI 服务账号模式:返回 null(走 ?key= 鉴权)
     * - 认证器构造失败 / 取 token 异常:抛 RuntimeException(由调用方处理)
     */
    private suspend fun resolveToken(): String? {
        val specific = config.resolvedSpecific()
        val needToken = specific is ProviderSpecificConfig.Gemini &&
            specific.useVertexAI && specific.useServiceAccount
        if (!needToken) return null
        // by lazy 委托属性无法智能转换,用局部变量捕获
        val auth = vertexAiAuthToken
        if (auth == null) {
            throw RuntimeException(ErrorCode.VERTEX_AI_CONFIG_INVALID.toMessage())
        }
        return try {
            auth.getValidToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RuntimeException(ErrorCode.VERTEX_AI_TOKEN_FAILED.toMessage(e.message ?: ""))
        }
    }

    /**
     * V-GEM1: 上传视频(或其他大文件)到 Gemini Files API,返回可在 generateContent 中引用的 fileUri。
     *
     * 流程:
     *  1. POST /upload/v1beta/files?key=... (multipart: metadata JSON + 文件字节)
     *     响应含 file.name / file.uri / file.state(初始 PROCESSING)
     *  2. 轮询 GET /v1beta/files/{name}?key=... 直到 state=ACTIVE(失败则抛错)
     *  3. 返回 file.uri,调用方将其填入 [UIMessage.videoFileUri]
     *
     * 仅 generativelanguage API 支持 Files API;Vertex AI 应走 GCS uri(本方法不支持,
     * 调用方需在 Vertex 模式下走替代路径或抛错)。
     *
     * @param bytes 文件字节(调用方负责从 Android Uri 读取后传入,避免本模块依赖 android.net.Uri)
     * @param mimeType MIME 类型,如 "video/mp4"
     * @param displayName 文件名(可选,仅用于服务端展示)
     * @return fileUri(形如 "https://generativelanguage.googleapis.com/v1beta/files/abc123")
     */
    suspend fun uploadFile(
        bytes: ByteArray,
        mimeType: String,
        displayName: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val specific = config.resolvedSpecific()
        // V-GEM1: Vertex AI 不走 Files API,直接拒绝(调用方应走 GCS 或 inlineData)
        require(specific !is ProviderSpecificConfig.Gemini || !specific.useVertexAI) {
            "Files API 仅 generativelanguage API 支持,Vertex AI 请使用 GCS uri"
        }
        require(bytes.isNotEmpty()) { "上传数据为空" }
        require(mimeType.isNotBlank()) { "mimeType 不能为空" }

        val base = config.resolvedBaseUrl().trimEnd('/').toHttpUrl().run {
            // generativelanguage API 默认 baseUrl 形如 https://generativelanguage.googleapis.com/v1beta
            // Files API 端点 /upload/v1beta/files 在 host 根下,需去掉 baseUrl 的路径部分
            // 自定义端口(非 443/80)需保留,以支持反代/中转
            if (port == 443 || port == 80) "$scheme://$host" else "$scheme://$host:$port"
        }

        // 1. 上传文件(multipart/form-data)
        val uploadUrl = "$base/upload/v1beta/files".toHttpUrl().newBuilder()
            .addQueryParameter("key", config.apiKey)
            .build()
        val metadataJson = AppJson.encodeToString(
            GeminiFileMetadata(name = displayName ?: "files/video_${System.currentTimeMillis()}"),
        )
        val mediaType = mimeType.toMediaType()
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("metadata", metadataJson, metadataJson.toRequestBody(JSON_MEDIA_TYPE))
            .addFormDataPart("file", displayName ?: "video", bytes.toRequestBody(mediaType))
            .build()
        val uploadReq = Request.Builder()
            .url(uploadUrl)
            .header("Accept", "application/json")
            .header("X-Goog-Upload-Protocol", "multipart")
            .post(multipart)
            .build()
        val fileName: String = httpClient.newCall(uploadReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errText = ProviderHttpSupport.readBodyCapped(resp)
                throw RuntimeException("上传文件失败 HTTP ${resp.code}: $errText")
            }
            val raw = resp.body.string()
            val parsed = AppJson.decodeFromString<GeminiFileUploadResponse>(raw)
            val f = parsed.file ?: throw RuntimeException("上传响应缺少 file 字段: $raw")
            if (f.uri.isBlank() || f.name.isBlank()) {
                throw RuntimeException("上传响应 file.uri/file.name 为空: $raw")
            }
            // 上传成功但服务端已报错(如 mime 不支持),直接抛
            f.error?.let { e ->
                throw RuntimeException("上传文件被拒绝: ${e.message ?: "未知错误"}")
            }
            f.name
        }

        // 2. 轮询 GET /v1beta/files/{name} 直到 state=ACTIVE(上限 60s,间隔 1s)
        val pollUrl = "$base/$fileName".toHttpUrl().newBuilder()
            .addQueryParameter("key", config.apiKey)
            .build()
        val deadline = System.currentTimeMillis() + FILE_POLL_TIMEOUT_MS
        var lastState = "PROCESSING"
        while (System.currentTimeMillis() < deadline) {
            val pollReq = Request.Builder()
                .url(pollUrl)
                .header("Accept", "application/json")
                .get()
                .build()
            val fileUri = httpClient.newCall(pollReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errText = ProviderHttpSupport.readBodyCapped(resp)
                    throw RuntimeException("查询文件状态失败 HTTP ${resp.code}: $errText")
                }
                val raw = resp.body.string()
                val f = AppJson.decodeFromString<GeminiFileResource>(raw)
                lastState = f.state
                when (f.state) {
                    "ACTIVE" -> return@use f.uri // 上传完成,返回可用 uri
                    "FAILED" -> {
                        val errMsg = f.error?.message ?: "服务端处理失败"
                        throw RuntimeException("文件处理失败: $errMsg")
                    }
                    else -> null // PROCESSING 等中间态,继续轮询
                }
            }
            if (fileUri != null) return@withContext fileUri
            delay(FILE_POLL_INTERVAL_MS)
        }
        throw RuntimeException("文件处理超时(60s),最后状态: $lastState")
    }

    /** V-GEM1: uploadFile 的 metadata 子结构(仅含 name 字段,服务端忽略其他)。 */
    @kotlinx.serialization.Serializable
    private data class GeminiFileMetadata(
        val name: String,
    )

    private companion object {
        const val TAG = "GeminiProvider"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        /** M-GEM2: 流式断连最大重试次数。 */
        const val MAX_RETRIES = 3
        // completeText 429 切换 key 最大次数,防止无限递归
        const val MAX_KEY_SWITCHES = 3
        /** M-GEM4: 安全相关 finishReason,发 Error 而非 Done。 */
        val SAFETY_FINISH_REASONS = setOf("SAFETY", "RECITATION", "BLOCKLIST")
        /** V-GEM1: Files API 状态轮询总超时(60s,覆盖常见视频处理时长)。 */
        const val FILE_POLL_TIMEOUT_MS = 60_000L
        /** V-GEM1: Files API 状态轮询间隔(1s,平衡响应速度与服务端压力)。 */
        const val FILE_POLL_INTERVAL_MS = 1_000L
    }
}
