package io.zer0.muse.mcp

import io.zer0.muse.data.SecureKeyStore
import kotlinx.serialization.Serializable

/**
 * Phase 9.5 (M3): MCP 传输类型。
 *
 * - [SSE]: Server-Sent Events 传输(MCP 2024-11-05 规范)。
 *   服务端暴露 SSE endpoint(用于 server→client 推送)+ POST endpoint(用于 client→server 请求)。
 *   适用于旧版 MCP server(如 @modelcontextprotocol/server-everything v0.x)。
 * - [STREAMABLE_HTTP]: Streamable HTTP 传输(MCP 2025-03-26 规范)。
 *   单一 endpoint,POST 请求 + Accept: application/json, text/event-stream。
 *   响应可以是单个 JSON 或 SSE 流(取决于 server 是否需要流式推送)。
 *   适用于新版 MCP server(2025-03-26 之后)。
 *
 * Phase 11.1.1 平台限制说明(不做 stdio 实现):
 *  MCP 规范定义了第三种传输 stdio(子进程 stdin/stdout 通信),但 **Android 沙箱
 *  不支持此传输**:
 *   1. SELinux + seccomp 限制 exec 任意二进制(仅 /system/bin 极少数命令可启动)
 *   2. APK 内 .so 只能 System.loadLibrary 加载,不能作为独立进程启动
 *   3. Android 8+ 严格限制后台进程,长驻 stdio MCP server 子进程会被系统杀死
 *  参考 rikkahub 也仅实现 SSE + StreamableHTTP,印证 Android 平台限制。
 *  若需本地工具,建议用 app 内嵌 ToolRegistry(P5-H / P8.8 LocalTools)替代。
 */
@Serializable
enum class McpTransportType { SSE, STREAMABLE_HTTP }

/**
 * Phase 9.5 (M3): MCP server 连接状态。
 */
enum class McpConnectionState {
    /** 未连接(初始 / 已关闭)。 */
    DISCONNECTED,
    /** 正在连接(握手 initialize 中)。 */
    CONNECTING,
    /** 已连接(initialize 成功,可收发请求)。 */
    CONNECTED,
    /** 正在重连(指数退避中)。 */
    RECONNECTING,
    /** 连接失败(超过最大重试次数)。 */
    FAILED,
    /** Phase 10.4: 需要重新 OAuth 授权(token 过期且 refresh 失败)。 */
    NEEDS_AUTH,
}

/**
 * Phase 10.4 (M3): MCP OAuth 2.1 PKCE 配置。
 *
 * MCP 规范要求 OAuth 2.1(= OAuth 2.0 + 强制 PKCE + best practices)。
 * 授权码流程 + PKCE:
 *  1. client 生成 code_verifier(43-128 字符随机串)
 *  2. code_challenge = BASE64URL(SHA256(code_verifier))
 *  3. 打开浏览器到 authorization_endpoint,response_type=code&code_challenge=...
 *  4. 用户授权后,server 重定向到 redirect_uri,带 code 参数
 *  5. client 用 code + code_verifier 换 access_token + refresh_token
 *  6. 后续请求带 Bearer access_token;过期用 refresh_token 续期
 *
 * @param clientId OAuth client id(需在 server 预注册或 dynamic registration)
 * @param redirectUri 回调 URI(app deep link,如 io.zer0.muse://oauth/callback)
 * @param authorizationEndpoint 授权页 URL(如 https://server/oauth/authorize)
 * @param tokenEndpoint token 交换 URL(如 https://server/oauth/token)
 * @param scopes 空格分隔的 scope 列表(如 "tools:read tools:call")
 * @param enabled 是否启用 OAuth(关闭则用 [McpServerConfig.authToken] 静态 token)
 */
@Serializable
data class McpOAuthConfig(
    val clientId: String = "",
    val redirectUri: String = "io.zer0.muse://oauth/callback",
    val authorizationEndpoint: String = "",
    val tokenEndpoint: String = "",
    val scopes: String = "",
    val enabled: Boolean = false,
)

/**
 * Phase 10.4 (M3): OAuth token 缓存(持久化到 DataStore)。
 *
 * @param accessToken 访问令牌(请求时带 Bearer)
 * @param refreshToken 刷新令牌(access_token 过期时用)
 * @param expiresAt 过期时间戳(ms),0 表示无过期
 * @param scope 实际授权的 scope(可能不同于请求的 scope)
 */
@Serializable
data class McpTokenInfo(
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0L,
    val scope: String = "",
) {
    /** 是否已过期(留 30s 提前量,避免请求时刚好过期)。 */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expiresAt in 1..(now + 30_000L)

    /** 是否有效(有 accessToken 且未过期)。 */
    fun isValid(now: Long = System.currentTimeMillis()): Boolean =
        accessToken.isNotBlank() && !isExpired(now)

    /** 是否可刷新(有 refreshToken)。 */
    fun canRefresh(): Boolean = refreshToken.isNotBlank()

    /**
     * M-05: 返回 accessToken/refreshToken 已加密(走 [SecureKeyStore.encrypt])的副本,供持久化前调用。
     * 空值原样保留(不加密空值)。参照 WebServerConfig.encrypted 模式。
     */
    suspend fun encrypted(): McpTokenInfo = copy(
        accessToken = SecureKeyStore.encrypt(accessToken),
        refreshToken = SecureKeyStore.encrypt(refreshToken),
    )

    /**
     * M-05: 返回 accessToken/refreshToken 已解密(走 [SecureKeyStore.decrypt])的副本,供从持久化层读出后调用。
     * 旧版明文由 decrypt 透传(兼容)。参照 WebServerConfig.decrypted 模式。
     */
    suspend fun decrypted(): McpTokenInfo = copy(
        accessToken = SecureKeyStore.decrypt(accessToken),
        refreshToken = SecureKeyStore.decrypt(refreshToken),
    )
}

/**
 * Phase 9.5 (M3): MCP server 配置。
 *
 * 一个 [McpServerConfig] 对应一个外部 MCP server。Muse 作为 MCP client 连接到它,
 * 把它的 tools 注册到本地 [io.zer0.muse.tools.ToolRegistry],供 LLM function calling 调用。
 *
 * @param id 唯一 id(用于 Room 持久化 + ToolRegistry 工具名前缀)
 * @param name 显示名(用户可读,如 "GitHub MCP")
 * @param transportType 传输类型 SSE / STREAMABLE_HTTP
 * @param url 端点 URL:
 *   - SSE: SSE endpoint(如 https://server/sse),client 从此 URL 接收事件,
 *          POST endpoint 从 SSE 事件里的 endpoint 字段动态获取
 *   - STREAMABLE_HTTP: 单一 endpoint(如 https://server/mcp),所有请求 POST 到此 URL
 * @param headers 自定义请求头(如 Authorization、X-API-Key)
 * @param authToken Bearer token(静态 token,OAuth 未启用时用;优先级低于 OAuth token)
 * @param enabled 是否启用(禁用的 server 不自动连接)
 * @param autoReconnect 是否自动重连(指数退避)
 * @param maxReconnectAttempts 最大重连次数(默认 5,达到后转 FAILED 状态)
 * @param reconnectBaseMs 重连基础间隔(默认 1000ms,指数退避:base * 2^attempt,上限 30s)
 * @param requestTimeoutMs 单次请求超时(默认 30s)
 * @param oauthConfig Phase 10.4: OAuth 2.1 PKCE 配置(enabled=true 时优先用 OAuth token)
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val transportType: McpTransportType = McpTransportType.STREAMABLE_HTTP,
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val authToken: String = "",
    val enabled: Boolean = true,
    val autoReconnect: Boolean = true,
    val maxReconnectAttempts: Int = 5,
    val reconnectBaseMs: Long = 1000L,
    val requestTimeoutMs: Long = 30_000L,
    val oauthConfig: McpOAuthConfig = McpOAuthConfig(),
) {
    /**
     * 合并 headers 和 authToken,Authorization 优先用 headers 里的。
     *
     * Phase 10.4: OAuth token 由 [McpClient] 在请求前动态注入(不在此方法处理),
     * 因为 OAuth token 可能过期需要 refresh,逻辑在 McpClient.ensureValidToken() 里。
     * 此方法只处理静态 authToken(非 OAuth 场景)。
     */
    fun resolvedHeaders(): Map<String, String> {
        val result = headers.toMutableMap()
        if (authToken.isNotBlank() && !result.containsKey("Authorization")) {
            result["Authorization"] = "Bearer $authToken"
        }
        return result
    }

    /**
     * M-05: 返回 authToken 已加密(走 [SecureKeyStore.encrypt])的副本,供持久化前调用。
     * 空值原样保留(不加密空值)。参照 WebServerConfig.encrypted 模式。
     */
    suspend fun encrypted(): McpServerConfig = copy(
        authToken = SecureKeyStore.encrypt(authToken),
    )

    /**
     * M-05: 返回 authToken 已解密(走 [SecureKeyStore.decrypt])的副本,供从持久化层读出后调用。
     * 旧版明文由 decrypt 透传(兼容)。参照 WebServerConfig.decrypted 模式。
     */
    suspend fun decrypted(): McpServerConfig = copy(
        authToken = SecureKeyStore.decrypt(authToken),
    )
}
