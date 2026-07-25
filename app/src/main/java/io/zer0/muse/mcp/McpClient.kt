package io.zer0.muse.mcp

import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/**
 * Phase 9.5 (M3): MCP 协议数据类。
 *
 * 参考 MCP 规范:
 * - 2024-11-05: https://spec.modelcontextprotocol.io/spec/2024-11-05/
 * - 2025-03-26: https://spec.modelcontextprotocol.io/spec/2025-03-26/
 *
 * JSON-RPC 2.0 协议:
 * - 请求: {jsonrpc:"2.0", id, method, params?}
 * - 响应: {jsonrpc:"2.0", id, result? | error?}
 * - 通知: {jsonrpc:"2.0", method, params?}(无 id)
 */

@Serializable
data class McpTool(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonElement? = null,
)

@Serializable
data class McpToolCallResult(
    val content: List<JsonElement> = emptyList(),
    val isError: Boolean = false,
)

/**
 * Phase 11.1.2: MCP Resource(MCP 2025-03-26 spec § Resources)。
 *
 * Resource 是 server 暴露的被动资源(带 URI),由用户/客户端选择注入对话 context。
 * 与 Tool 的区别:Tool 是 LLM 主动调用,Resource 是用户被动选择。
 *
 * @param uri 资源唯一标识(如 file:///doc.md / db://schema/users)
 * @param name 资源名(用户可读)
 * @param description 描述(可选)
 * @param mimeType MIME 类型(可选,如 text/markdown / application/json)
 */
@Serializable
data class McpResource(
    val uri: String,
    val name: String? = null,
    val description: String? = null,
    val mimeType: String? = null,
)

/**
 * Phase 11.1.2: MCP Resource 内容(resources/read 响应)。
 *
 * @param uri 资源 URI
 * @param mimeType MIME 类型(可选)
 * @param text 文本内容(与 blob 二选一)
 * @param blob 二进制内容 base64 编码(与 text 二选一)
 */
@Serializable
data class McpResourceContent(
    val uri: String,
    val mimeType: String? = null,
    val text: String? = null,
    val blob: String? = null,
)

/**
 * Phase 11.1.2: MCP Prompt(MCP 2025-03-26 spec § Prompts)。
 *
 * Prompt 是 server 暴露的快捷指令模板,用户点击触发后返回一组 messages,
 * 作为新对话起点或插入当前对话。
 *
 * @param name prompt 唯一名(用作 prompts/get 的 key)
 * @param description 描述(可选)
 * @param arguments 参数列表(可选,用户填写后传入 prompts/get)
 */
@Serializable
data class McpPrompt(
    val name: String,
    val description: String? = null,
    val arguments: List<McpPromptArgument> = emptyList(),
)

/** Prompt 参数定义。 */
@Serializable
data class McpPromptArgument(
    val name: String,
    val description: String? = null,
    val required: Boolean = false,
)

/**
 * Phase 11.1.2: MCP Prompt 内容(prompts/get 响应)。
 *
 * @param description 描述(可选)
 * @param messages 消息列表(role + content),作为对话起点
 */
@Serializable
data class McpPromptResult(
    val description: String? = null,
    val messages: List<McpPromptMessage> = emptyList(),
)

/**
 * Prompt 消息(role + content)。
 *
 * @param role user / assistant / system
 * @param content 消息内容(JsonObject,可能为 {type:"text",text:"..."} 等多种形态)
 */
@Serializable
data class McpPromptMessage(
    val role: String,
    val content: JsonElement,
)

@Serializable
internal data class McpServerInfo(
    val name: String? = null,
    val version: String? = null,
)

/**
 * Phase 9.5 (M3): MCP Client。
 *
 * 职责:
 *  1. 建立 SSE / StreamableHTTP 连接,完成 initialize 握手
 *  2. 提供 [listTools] / [callTool] suspend API(对标 MCP tools/list、tools/call)
 *  3. 自动重连(指数退避:base * 2^attempt,上限 30s,达到 [McpServerConfig.maxReconnectAttempts] 转 FAILED)
 *  4. 暴露 [state] StateFlow 供 UI 观察连接状态
 *
 * 协议实现:
 *  - initialize 请求带 protocolVersion(2025-03-26 / 2024-11-05,server 自行选择兼容版本)
 *  - Initialized 通知在 initialize 响应后发送(MCP 要求的握手第二步)
 *  - tools/list 响应解析为 [McpTool] 列表
 *  - tools/call 请求带 name + arguments(JsonObject),响应解析为 [McpToolCallResult]
 *
 * 传输实现:
 *  - [SseTransport]: EventSource 接收 server→client 事件,POST 到 server 告知的 endpoint
 *  - [StreamableHttpTransport]: 单一 endpoint POST,响应可能是 JSON 或 SSE 流
 *
 * 线程模型:
 *  - 所有网络 IO 在 Dispatchers.IO
 *  - 状态变更通过 MutableStateFlow,线程安全
 *  - 请求-响应配对用 ConcurrentHashMap<id, Channel<JsonObject>>(支持并发请求)
 *
 * 独立编写(参考 MCP 官方规范),Apache 2.0。
 */
class McpClient(
    private val config: McpServerConfig,
    private val client: OkHttpClient = McpClient.defaultClient,
    /** Phase 10.4: SettingsRepository 注入(OAuth 启用时用于 token 持久化;null 走静态 token)。 */
    private val settings: io.zer0.muse.data.SettingsRepository? = null,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idCounter = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<Long, Channel<JsonObject>>()

    private val _state = MutableStateFlow(McpConnectionState.DISCONNECTED)
    val state: StateFlow<McpConnectionState> = _state.asStateFlow()

    /** 当前 SSE 连接(用于 close 时取消)。 */
    @Volatile
    private var eventSource: EventSource? = null

    /** SSE 传输模式下的 POST endpoint(server 通过 endpoint 事件告知)。 */
    @Volatile
    private var postEndpoint: String? = null

    /** 服务端信息(initialize 响应里返回)。 */
    @Volatile
    private var serverInfo: McpServerInfo? = null

    /** 重连次数(成功连接后重置)。 */
    @Volatile
    private var reconnectAttempts = 0

    /** 是否已主动 close(区分主动关闭 vs 网络断开,主动 close 不触发重连)。 */
    @Volatile
    private var closed = false

    /** Phase 10.4: OAuth token 内存缓存(从 settings 加载,refresh 后更新)。 */
    @Volatile
    private var oauthToken: McpTokenInfo? = null

    /** Phase 10.4: 当前 PKCE pair(startOAuthFlow 生成,completeOAuthFlow 消费)。 */
    @Volatile
    private var pendingPkce: McpOAuthFlow.PkcePair? = null

    /** Phase 10.4: 是否启用 OAuth。 */
    private val oauthEnabled: Boolean get() = config.oauthConfig.enabled && settings != null

    /**
     * 启动 MCP client。建立连接 + initialize 握手。
     * 失败则触发重连流程(若 [McpServerConfig.autoReconnect] 为 true)。
     */
    fun start() {
        // M-MCP4: synchronized 保护 check-then-launch,避免竞态导致重复连接
        synchronized(this) {
            if (closed) return
            if (_state.value != McpConnectionState.DISCONNECTED &&
                _state.value != McpConnectionState.FAILED &&
                _state.value != McpConnectionState.NEEDS_AUTH) return
            scope.launch { connect() }
        }
    }

    /**
     * 同步建立连接 + initialize 握手。
     * 在 IO 协程里调用。失败触发 [scheduleReconnect]。
     *
     * Phase 10.4: OAuth 启用时,先确保 token 有效(加载/refresh);无 token 或 refresh 失败
     * 转 NEEDS_AUTH 状态(等 UI 调 [startOAuthFlow] 引导用户授权)。
     */
    private suspend fun connect() {
        _state.value = McpConnectionState.CONNECTING
        Logger.d(TAG, "[${config.name}] 连接中 transport=${config.transportType} url=${config.url}")

        // Phase 10.4: OAuth 模式下先校验 token
        if (oauthEnabled && !ensureValidToken()) {
            Logger.w(TAG, "[${config.name}] OAuth token 无效或未授权,转 NEEDS_AUTH")
            _state.value = McpConnectionState.NEEDS_AUTH
            return
        }

        val connected = when (config.transportType) {
            McpTransportType.SSE -> connectSse()
            McpTransportType.STREAMABLE_HTTP -> connectStreamableHttp()
        }

        if (connected) {
            reconnectAttempts = 0
            _state.value = McpConnectionState.CONNECTED
            Logger.i(TAG, "[${config.name}] 已连接,server=${serverInfo?.name}:${serverInfo?.version}")
        } else {
            Logger.w(TAG, "[${config.name}] 连接失败")
            scheduleReconnect()
        }
    }

    // ── Phase 10.4: OAuth 2.1 PKCE ───────────────────────────────────────────

    /**
     * 确保 OAuth token 有效:加载缓存 → 检查过期 → refresh。
     * @return true 表示当前有有效 token;false 表示未授权 / refresh 失败(需重新授权)
     */
    private suspend fun ensureValidToken(forceRefresh: Boolean = false): Boolean {
        if (!oauthEnabled) return true
        // 首次加载:从 DataStore 读缓存
        if (oauthToken == null) {
            oauthToken = settings?.getMcpToken(config.id)
        }
        val token = oauthToken ?: return false  // 未授权
        // M-MCP2: forceRefresh=true 时强制 refresh(用于请求收到 401 后重试)
        if (!forceRefresh && token.isValid()) return true
        // 过期 → 尝试 refresh
        if (!token.canRefresh()) {
            settings?.clearMcpToken(config.id)
            oauthToken = null
            return false
        }
        Logger.d(TAG, "[${config.name}] OAuth token 过期,尝试 refresh")
        val newToken = McpOAuthFlow.refreshAccessToken(config.oauthConfig, token.refreshToken)
        if (newToken == null) {
            Logger.w(TAG, "[${config.name}] OAuth refresh 失败,需重新授权")
            settings?.clearMcpToken(config.id)
            oauthToken = null
            return false
        }
        oauthToken = newToken
        settings?.saveMcpToken(config.id, newToken)
        Logger.i(TAG, "[${config.name}] OAuth token refreshed,新有效期至 ${newToken.expiresAt}")
        return true
    }

    /**
     * 构造请求头:静态 headers + authToken + OAuth Bearer(OAuth 优先级最高)。
     * OAuth token 由 [ensureValidToken] 预先刷新到 [oauthToken],此方法仅读取。
     */
    private fun resolvedAuthHeaders(): Map<String, String> {
        val headers = config.resolvedHeaders().toMutableMap()
        oauthToken?.takeIf { it.accessToken.isNotBlank() }?.let { token ->
            headers["Authorization"] = "Bearer ${token.accessToken}"
        }
        return headers
    }

    /**
     * Phase 10.4: 启动 OAuth 授权流程(由 UI 调用)。
     *
     * @return 授权 URL(用 Intent.ACTION_VIEW 打开浏览器);null 表示 OAuth 未启用
     */
    fun startOAuthFlow(): String? {
        if (!oauthEnabled) return null
        val pkce = McpOAuthFlow.generatePkcePair()
        pendingPkce = pkce
        return McpOAuthFlow.buildAuthorizationUrl(config.oauthConfig, pkce)
    }

    /**
     * Phase 10.4: 完成 OAuth 授权流程(浏览器回调后调用)。
     *
     * @param redirectUri 回调 URI(含 code + state 参数)
     * @return true 成功(token 已持久化,可调 [start] 重连);false 失败(state 不匹配 / 交换失败)
     */
    suspend fun completeOAuthFlow(redirectUri: String): Boolean {
        val pkce = pendingPkce ?: run {
            Logger.w(TAG, "[${config.name}] completeOAuthFlow 但无 pending PKCE pair")
            return false
        }
        val parsed = McpOAuthFlow.parseRedirectCallback(redirectUri, config.oauthConfig.redirectUri)
        if (parsed == null) {
            Logger.w(TAG, "[${config.name}] OAuth 回调解析失败: $redirectUri")
            return false
        }
        val (code, state) = parsed
        if (state != pkce.state) {
            Logger.w(TAG, "[${config.name}] OAuth state 不匹配: expected=${pkce.state}, got=$state")
            return false
        }
        val token = McpOAuthFlow.exchangeCodeForToken(config.oauthConfig, code, pkce.verifier)
        if (token == null) {
            Logger.w(TAG, "[${config.name}] OAuth token 交换失败")
            return false
        }
        oauthToken = token
        settings?.saveMcpToken(config.id, token)
        pendingPkce = null
        Logger.i(TAG, "[${config.name}] OAuth 授权成功,token 有效期至 ${token.expiresAt}")
        return true
    }

    /** Phase 10.4: 撤销 OAuth 授权(清 token + PKCE,转 NEEDS_AUTH)。 */
    suspend fun revokeOAuth() {
        oauthToken = null
        pendingPkce = null
        settings?.clearMcpToken(config.id)
        _state.value = McpConnectionState.NEEDS_AUTH
        Logger.i(TAG, "[${config.name}] OAuth 授权已撤销")
    }

    /** Phase 10.4: 是否需要 OAuth 授权(NEEDS_AUTH 状态 + OAuth 启用)。 */
    fun needsOAuth(): Boolean = oauthEnabled && _state.value == McpConnectionState.NEEDS_AUTH

    // ── SSE 传输 ──────────────────────────────────────────────────────────────

    /**
     * SSE 传输连接(MCP 2024-11-05):
     *  1. EventSource 连到 [McpServerConfig.url],接收 server→client 事件
     *  2. 首个事件通常是 endpoint 事件(data: <POST endpoint URL>),
     *     告知 client 后续请求 POST 到哪个 URL
     *  3. initialize 请求通过 POST 发送,响应通过 SSE 事件返回
     */
    private suspend fun connectSse(): Boolean {
        // H-MCP1: 重连时先取消旧 eventSource,避免资源泄漏与响应错配
        eventSource?.cancel()
        eventSource = null

        val sseFactory = EventSources.createFactory(client)
        val firstEventChannel = Channel<String>(Channel.CONFLATED)
        var gotEndpoint = false

        val request = Request.Builder()
            .url(config.url.toHttpUrl())
            .header("Accept", "text/event-stream")
            .apply { resolvedAuthHeaders().forEach { (k, v) -> header(k, v) } }
            .get()
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                if (!response.isSuccessful) {
                    Logger.w(TAG, "[${config.name}] SSE 连接 HTTP ${response.code}")
                    firstEventChannel.close()
                    eventSource.cancel()
                }
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                // MCP 2024-11-05: 首个事件 type="endpoint",data 是 POST URL
                if (type == "endpoint" && !gotEndpoint) {
                    gotEndpoint = true
                    postEndpoint = resolvePostUrl(data)
                    Logger.d(TAG, "[${config.name}] SSE endpoint: $postEndpoint")
                    firstEventChannel.trySend("endpoint")
                } else {
                    // 普通事件: data 是 JSON-RPC 响应/通知
                    handleSseEvent(data)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                Logger.w(TAG, "[${config.name}] SSE 连接关闭")
                if (!closed) scheduleReconnect()
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?,
            ) {
                // v1.113: 区分"协程主动取消"与"真实网络故障"。
                // OkHttp 在 Call.cancel() 时抛 IOException("Canceled"),此时不应触发重连。
                if (closed || !scope.isActive) {
                    firstEventChannel.close()
                    return
                }
                if (t is java.io.IOException && t.message == "Canceled") {
                    Logger.d(TAG, "[${config.name}] SSE 被 Call.cancel() 取消,不重连")
                    firstEventChannel.close()
                    return
                }
                Logger.w(TAG, "[${config.name}] SSE 连接失败: ${t?.message ?: response?.code}")
                firstEventChannel.close()
                if (!closed) scheduleReconnect()
            }
        }

        eventSource = sseFactory.newEventSource(request, listener)

        // 等 endpoint 事件(L-MCP2: 超时提取为常量)
        val gotEp = withTimeoutOrNull(SSE_ENDPOINT_TIMEOUT_MS) { firstEventChannel.receive() }
        firstEventChannel.close()
        if (gotEp == null || postEndpoint == null) {
            eventSource?.cancel()
            return false
        }
        // M-MCP1: 校验 postEndpoint 与 config.url 同源,防止 Open Redirect
        val ep = postEndpoint ?: return false
        if (!isSameOrigin(ep, config.url)) {
            Logger.w(TAG, "[${config.name}] SSE endpoint 与 server 不同源: $ep vs ${config.url},拒绝连接")
            eventSource?.cancel()
            eventSource = null
            postEndpoint = null
            return false
        }

        // 发 initialize 请求
        return sendInitialize()
    }

    /** SSE 事件处理:解析 JSON-RPC 响应,匹配 pending 请求。 */
    private fun handleSseEvent(data: String) {
        if (data.isBlank()) return
        val obj = runCatching {
            AppJson.decodeFromString(JsonObject.serializer(), data)
        }.getOrNull() ?: return

        // 取 id(响应才有,通知无)
        val idEl = obj["id"] ?: return
        val id = (idEl as? JsonPrimitive)?.content?.toLongOrNull() ?: return

        val channel = pendingRequests.remove(id) ?: return
        channel.trySend(obj)
        channel.close()
    }

    /** SSE 传输模式下的 POST 请求(initialize / tools/list / tools/call 等)。 */
    private suspend fun postSseRequest(jsonRpc: String, retryOn401: Boolean = true): JsonObject? {
        val endpoint = postEndpoint ?: return null
        val request = Request.Builder()
            .url(endpoint.toHttpUrl())
            .header("Content-Type", "application/json")
            .apply { resolvedAuthHeaders().forEach { (k, v) -> header(k, v) } }
            .post(jsonRpc.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        // SSE 模式下,POST 请求通常返回 202(响应通过 SSE 事件回来)
        // 但有些 server 直接返回 JSON 响应(非标准但常见)
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { resp ->
                    // M-MCP2: 收到 401 时强制 refresh token 并重试一次
                    if (resp.code == 401 && retryOn401 && oauthEnabled) {
                        Logger.w(TAG, "[${config.name}] POST 收到 401,尝试 refresh token 后重试")
                        if (ensureValidToken(forceRefresh = true)) {
                            return@withContext postSseRequest(jsonRpc, retryOn401 = false)
                        }
                        return@withContext null
                    }
                    if (resp.isSuccessful) {
                        val body = resp.body.string()
                        if (body.isNotBlank() && body.trimStart().startsWith("{")) {
                            runCatching { AppJson.decodeFromString(JsonObject.serializer(), body) }.getOrNull()
                        } else null  // 202 Accepted,等 SSE 事件
                    } else null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                // v1.113: 协程取消导致的 IOException("Canceled") 不应触发重连逻辑
                if (closed || e.message == "Canceled") {
                    Logger.d(TAG, "[${config.name}] POST 请求被取消,不重连")
                    return@withContext null
                }
                Logger.w(TAG, "[${config.name}] POST 请求 IO 异常: ${e.message}")
                null
            }
        }
    }

    /** 解析 SSE endpoint 事件 data(可能是相对路径,如 /messages?session_id=xxx)。 */
    private fun resolvePostUrl(data: String): String {
        val trimmed = data.trim()
        return runCatching {
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                val base = config.url.toHttpUrl()
                base.resolve(trimmed)?.toString() ?: trimmed
            }
        }.getOrDefault(trimmed)
    }

    /** M-MCP1: 校验两个 URL 是否同源(scheme + host + port 一致)。 */
    private fun isSameOrigin(url1: String, url2: String): Boolean {
        return try {
            val u1 = url1.toHttpUrl()
            val u2 = url2.toHttpUrl()
            u1.scheme == u2.scheme && u1.host == u2.host && u1.port == u2.port
        } catch (e: Exception) {
            false
        }
    }

    // ── StreamableHTTP 传输 ───────────────────────────────────────────────────

    /**
     * StreamableHTTP 传输连接(MCP 2025-03-26):
     *  1. POST initialize 到 [McpServerConfig.url],Accept: application/json, text/event-stream
     *  2. 响应可能是单个 JSON(非流式)或 SSE 流(流式)
     *  3. 解析响应,完成握手
     *
     * 与 SSE 不同,StreamableHTTP 无需保持长连接 — 每个请求独立 POST。
     * 若 server 返回 SSE,则在 SSE 流结束前保持连接。
     */
    private suspend fun connectStreamableHttp(): Boolean {
        return sendInitialize()
    }

    /**
     * StreamableHTTP 请求:POST JSON-RPC,响应可能是 JSON 或 SSE。
     * 解析响应,返回 JsonObject(单次响应)或从 SSE 流聚合最后一个事件。
     */
    private suspend fun postStreamableRequest(jsonRpc: String, retryOn401: Boolean = true): JsonObject? {
        val request = Request.Builder()
            .url(config.url.toHttpUrl())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply { resolvedAuthHeaders().forEach { (k, v) -> header(k, v) } }
            .post(jsonRpc.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { resp ->
                    // M-MCP2: 收到 401 时强制 refresh token 并重试一次
                    if (resp.code == 401 && retryOn401 && oauthEnabled) {
                        Logger.w(TAG, "[${config.name}] StreamableHTTP 收到 401,尝试 refresh token 后重试")
                        if (ensureValidToken(forceRefresh = true)) {
                            return@withContext postStreamableRequest(jsonRpc, retryOn401 = false)
                        }
                        return@withContext null
                    }
                    if (!resp.isSuccessful) {
                        Logger.w(TAG, "[${config.name}] StreamableHTTP HTTP ${resp.code}")
                        return@use null
                    }
                    val contentType = resp.header("Content-Type") ?: ""
                    val body = resp.body.string()
                    when {
                        contentType.contains("text/event-stream") -> {
                            // SSE 流: 解析 data: 行,取最后一个 JSON 事件
                            parseSseResponse(body)
                        }
                        contentType.contains("application/json") -> {
                            runCatching { AppJson.decodeFromString(JsonObject.serializer(), body) }.getOrNull()
                        }
                        else -> {
                            // 兜底: 尝试当 JSON 解析
                            if (body.isNotBlank() && body.trimStart().startsWith("{")) {
                                runCatching { AppJson.decodeFromString(JsonObject.serializer(), body) }.getOrNull()
                            } else null
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                Logger.w(TAG, "[${config.name}] StreamableHTTP 请求 IO 异常: ${e.message}")
                null
            }
        }
    }

    /** 解析 SSE 响应体(纯文本 data: 行),返回最后一个 JSON 事件。 */
    private fun parseSseResponse(body: String): JsonObject? {
        var last: JsonObject? = null
        val sb = StringBuilder()
        for (line in body.lines()) {
            if (line.startsWith("data:")) {
                // L-MCP4: 按 SSE 规范,多行 data 用 \n 连接
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line.removePrefix("data:").trim())
            } else if (line.isBlank() && sb.isNotEmpty()) {
                val json = runCatching {
                    AppJson.decodeFromString(JsonObject.serializer(), sb.toString())
                }.getOrNull()
                if (json != null) last = json
                sb.clear()
            }
        }
        // 处理末尾未以空行结束的情况
        if (sb.isNotEmpty()) {
            val json = runCatching {
                AppJson.decodeFromString(JsonObject.serializer(), sb.toString())
            }.getOrNull()
            if (json != null) last = json
        }
        return last
    }

    // ── JSON-RPC 请求 ─────────────────────────────────────────────────────────

    /** 发送 initialize 请求 + initialized 通知,完成握手。 */
    private suspend fun sendInitialize(): Boolean {
        val id = idCounter.getAndIncrement()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "initialize")
            put("params", buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject {
                    put("name", "muse")
                    put("version", "1.0.0")
                })
            })
        }
        val response = sendRequest(id, request) ?: return false
        logJsonRpcError("initialize", response)

        // 解析 serverInfo
        val result = response["result"] as? JsonObject
        // L-MCP3: 读取 server 返回的 protocolVersion 并记录
        val serverProtocolVersion = (result?.get("protocolVersion") as? JsonPrimitive)?.content
        if (serverProtocolVersion != null) {
            Logger.i(TAG, "[${config.name}] server protocolVersion=$serverProtocolVersion (client=${PROTOCOL_VERSION})")
        }
        serverInfo = result?.let {
            val info = it["serverInfo"] as? JsonObject
            McpServerInfo(
                name = (info?.get("name") as? JsonPrimitive)?.content,
                version = (info?.get("version") as? JsonPrimitive)?.content,
            )
        }

        // 发 initialized 通知(无 id)
        sendNotification("notifications/initialized")
        return response["error"] == null
    }

    /**
     * tools/list:列出 server 暴露的所有工具。
     * @return 工具列表;失败返回空列表
     */
    suspend fun listTools(): List<McpTool> = withContext(Dispatchers.IO) {
        if (_state.value != McpConnectionState.CONNECTED) return@withContext emptyList()
        val id = idCounter.getAndIncrement()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "tools/list")
        }
        val response = sendRequest(id, request) ?: return@withContext emptyList()
        logJsonRpcError("tools/list", response)
        val result = response["result"] as? JsonObject ?: return@withContext emptyList()
        val toolsEl = result["tools"] ?: return@withContext emptyList()
        runCatching {
            AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(McpTool.serializer()),
                AppJson.encodeToString(toolsEl),
            )
        }.getOrDefault(emptyList())
    }

    /**
     * tools/call:调用指定工具。
     * @param name 工具名
     * @param arguments 参数(JsonObject)
     * @return 调用结果;失败返回 isError=true 的空结果
     */
    suspend fun callTool(name: String, arguments: JsonObject): McpToolCallResult = withContext(Dispatchers.IO) {
        if (_state.value != McpConnectionState.CONNECTED) {
            return@withContext McpToolCallResult(isError = true)
        }
        val id = idCounter.getAndIncrement()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            })
        }
        val response = sendRequest(id, request) ?: return@withContext McpToolCallResult(isError = true)
        logJsonRpcError("tools/call", response)
        val result = response["result"] ?: return@withContext McpToolCallResult(isError = true)
        runCatching {
            AppJson.decodeFromString(McpToolCallResult.serializer(), AppJson.encodeToString(result))
        }.getOrDefault(McpToolCallResult(isError = true))
    }

    // ── Phase 11.1.2: resources / prompts ───────────────────────────────────

    /**
     * Phase 11.1.2: resources/list — 列出 server 暴露的所有资源。
     *
     * @return 资源列表;失败返回空列表
     */
    suspend fun listResources(): List<McpResource> = withContext(Dispatchers.IO) {
        if (_state.value != McpConnectionState.CONNECTED) return@withContext emptyList()
        val id = idCounter.getAndIncrement()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "resources/list")
        }
        val response = sendRequest(id, request) ?: return@withContext emptyList()
        logJsonRpcError("resources/list", response)
        val result = response["result"] as? JsonObject ?: return@withContext emptyList()
        val resourcesEl = result["resources"] ?: return@withContext emptyList()
        runCatching {
            AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(McpResource.serializer()),
                AppJson.encodeToString(resourcesEl),
            )
        }.getOrDefault(emptyList())
    }

    /**
     * Phase 11.1.2: resources/read — 读取指定资源内容。
     *
     * @param uri 资源 URI(resources/list 返回的 uri 字段)
     * @return 资源内容列表(可能多个,如目录资源返回多个文件);失败返回空列表
     */
    suspend fun readResource(uri: String): List<McpResourceContent> = withContext(Dispatchers.IO) {
        if (_state.value != McpConnectionState.CONNECTED) return@withContext emptyList()
        val id = idCounter.getAndIncrement()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "resources/read")
            put("params", buildJsonObject {
                put("uri", uri)
            })
        }
        val response = sendRequest(id, request) ?: return@withContext emptyList()
        logJsonRpcError("resources/read", response)
        val result = response["result"] as? JsonObject ?: return@withContext emptyList()
        val contentsEl = result["contents"] ?: return@withContext emptyList()
        runCatching {
            AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(McpResourceContent.serializer()),
                AppJson.encodeToString(contentsEl),
            )
        }.getOrDefault(emptyList())
    }

    /**
     * Phase 11.1.2: prompts/list — 列出 server 暴露的所有 prompt 模板。
     *
     * @return prompt 列表;失败返回空列表
     */
    suspend fun listPrompts(): List<McpPrompt> = withContext(Dispatchers.IO) {
        if (_state.value != McpConnectionState.CONNECTED) return@withContext emptyList()
        val id = idCounter.getAndIncrement()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "prompts/list")
        }
        val response = sendRequest(id, request) ?: return@withContext emptyList()
        logJsonRpcError("prompts/list", response)
        val result = response["result"] as? JsonObject ?: return@withContext emptyList()
        val promptsEl = result["prompts"] ?: return@withContext emptyList()
        runCatching {
            AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(McpPrompt.serializer()),
                AppJson.encodeToString(promptsEl),
            )
        }.getOrDefault(emptyList())
    }

    /**
     * Phase 11.1.2: prompts/get — 获取指定 prompt 的消息内容。
     *
     * @param name prompt 名(prompts/list 返回的 name 字段)
     * @param arguments 参数(name→value),对应 [McpPrompt.arguments]
     * @return prompt 消息列表;失败返回空 messages
     */
    suspend fun getPrompt(
        name: String,
        arguments: Map<String, String> = emptyMap(),
    ): McpPromptResult = withContext(Dispatchers.IO) {
        if (_state.value != McpConnectionState.CONNECTED) {
            return@withContext McpPromptResult()
        }
        val id = idCounter.getAndIncrement()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "prompts/get")
            put("params", buildJsonObject {
                put("name", name)
                if (arguments.isNotEmpty()) {
                    put("arguments", buildJsonObject {
                        arguments.forEach { (k, v) -> put(k, v) }
                    })
                }
            })
        }
        val response = sendRequest(id, request) ?: return@withContext McpPromptResult()
        logJsonRpcError("prompts/get", response)
        val result = response["result"] ?: return@withContext McpPromptResult()
        runCatching {
            AppJson.decodeFromString(McpPromptResult.serializer(), AppJson.encodeToString(result))
        }.getOrDefault(McpPromptResult())
    }

    /** M-MCP5: 记录 JSON-RPC error 响应(code + message),避免静默丢弃。 */
    private fun logJsonRpcError(context: String, response: JsonObject?) {
        val error = response?.get("error") as? JsonObject ?: return
        val code = (error["code"] as? JsonPrimitive)?.content
        val message = (error["message"] as? JsonPrimitive)?.content
        Logger.w(TAG, "[${config.name}] $context JSON-RPC error: code=$code, message=$message")
    }

    /**
     * 发送 JSON-RPC 请求,等待响应(带超时)。
     * SSE 模式:POST 后等 SSE 事件;StreamableHTTP:直接拿响应。
     */
    private suspend fun sendRequest(id: Long, request: JsonObject): JsonObject? {
        val jsonStr = AppJson.encodeToString(request)
        val timeoutMs = config.requestTimeoutMs

        return when (config.transportType) {
            McpTransportType.STREAMABLE_HTTP -> {
                withTimeoutOrNull(timeoutMs) { postStreamableRequest(jsonStr) }
            }
            McpTransportType.SSE -> {
                // SSE 模式:注册 pending channel,POST 请求,等 SSE 事件回来
                val channel = Channel<JsonObject>(Channel.CONFLATED)
                pendingRequests[id] = channel
                try {
                    withTimeoutOrNull(timeoutMs) {
                        // POST 请求(server 可能直接返回响应,也可能通过 SSE 事件)
                        val directResponse = postSseRequest(jsonStr)
                        if (directResponse != null) {
                            directResponse
                        } else {
                            // 等 SSE 事件
                            channel.receive()
                        }
                    }
                } finally {
                    pendingRequests.remove(id)
                }
            }
        }
    }

    /** 发送 JSON-RPC 通知(无 id,无响应)。 */
    private suspend fun sendNotification(method: String) {
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
        }
        val jsonStr = AppJson.encodeToString(request)
        when (config.transportType) {
            McpTransportType.STREAMABLE_HTTP -> postStreamableRequest(jsonStr)
            McpTransportType.SSE -> postSseRequest(jsonStr)
        }
    }

    // ── 重连 ──────────────────────────────────────────────────────────────────

    /**
     * 指数退避重连:base * 2^attempt,上限 30s。
     * 达到 [McpServerConfig.maxReconnectAttempts] 转 FAILED 状态。
     */
    private fun scheduleReconnect() {
        if (closed) return
        if (!config.autoReconnect) {
            _state.value = McpConnectionState.FAILED
            return
        }
        if (reconnectAttempts >= config.maxReconnectAttempts) {
            Logger.w(TAG, "[${config.name}] 超过最大重连次数 ${config.maxReconnectAttempts},转 FAILED")
            _state.value = McpConnectionState.FAILED
            return
        }

        _state.value = McpConnectionState.RECONNECTING
        val delayMs = minOf(
            config.reconnectBaseMs * (1L shl reconnectAttempts),
            RECONNECT_MAX_DELAY_MS,
        )
        reconnectAttempts++
        Logger.d(TAG, "[${config.name}] 第 $reconnectAttempts 次重连,${delayMs}ms 后重试")

        scope.launch {
            delay(delayMs)
            if (!closed) connect()
        }
    }

    // ── 生命周期 ─────────────────────────────────────────────────────────────

    /** 主动关闭连接(不触发重连)。 */
    override fun close() {
        closed = true
        _state.value = McpConnectionState.DISCONNECTED
        eventSource?.cancel()
        eventSource = null
        scope.cancel()
        pendingRequests.clear()
    }

    private companion object {
        const val TAG = "McpClient"
        const val PROTOCOL_VERSION = "2025-03-26"
        // L-MCP2: 超时/重连魔法数字提取为常量
        const val SSE_ENDPOINT_TIMEOUT_MS = 5_000L
        const val RECONNECT_MAX_DELAY_MS = 30_000L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // v1.114 修复: SSE 长连接场景 readTimeout 60s 偏短(服务端长时间无数据会被误断),
            //   与 chat client 一致改为 120s
            .readTimeout(120, TimeUnit.SECONDS)
            // v1.114 修复: SSE 会话可能持续很久(MCP 工具调用 / 长推理),callTimeout 60s 会强制中断整个会话;
            //   0 表示无限制,由 readTimeout / pingInterval 控制活性
            .callTimeout(0, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
}
