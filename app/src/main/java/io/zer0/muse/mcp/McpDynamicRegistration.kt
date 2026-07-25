package io.zer0.muse.mcp

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Phase 11.1.3: MCP Dynamic Client Registration(RFC 7591 + RFC 9728 + RFC 8414)。
 *
 * MCP 规范(2025-03-26 Authorization)要求 OAuth 2.1,并推荐动态客户端注册用于
 * 无预注册 client_id 的场景。完整流程含三步:
 *
 * 1. **元数据发现**(RFC 9728 + RFC 8414):
 *    - GET `{server_url}/.well-known/oauth-protected-resource` → 返回 `authorization_servers` 数组
 *    - GET `{auth_server}/.well-known/oauth-authorization-server` → 返回
 *      `authorization_endpoint` / `token_endpoint` / `registration_endpoint`
 *
 * 2. **动态注册**(RFC 7591):
 *    - POST `registration_endpoint`(application/json)
 *    - 请求体:`client_name` / `redirect_uris` / `token_endpoint_auth_method="none"` /
 *      `grant_types=["authorization_code","refresh_token"]` / `response_types=["code"]` /
 *      `scope` / `code_challenge_method="S256"`
 *    - 响应(201 Created):`client_id`(公共客户端通常无 client_secret)
 *
 * 3. **PKCE 授权**(复用 Phase 10.4 的 [McpOAuthFlow]):
 *    - 用 Step 2 拿到的 `client_id` + PKCE pair 走授权码流程
 *
 * 与 Phase 10.4 的衔接:
 *  - 当前 [McpOAuthConfig] 要求用户手动填写 `clientId` / endpoints
 *  - 动态注册要解决的就是这个"手动配 client_id"痛点
 *  - 注册成功后,把 `clientId` / endpoints 回填到 [McpOAuthConfig],持久化到 DataStore
 *
 * 独立编写(参考 RFC 7591/9728/8414 + MCP 规范),Apache 2.0。
 */
object McpDynamicRegistration {

    private const val TAG = "McpDynReg"

    /** HTTP 客户端(短超时,避免 UI 卡顿)。 */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 元数据发现结果。
     *
     * @param authorizationEndpoint 授权页 URL(如 https://server/oauth/authorize)
     * @param tokenEndpoint token 交换 URL(如 https://server/oauth/token)
     * @param registrationEndpoint 动态注册 URL(如 https://server/oauth/register);
     *        null 表示此 auth server 不支持动态注册
     * @param scopesSupported 支持的 scope 列表(可选,用于 UI 提示)
     */
    @Serializable
    data class McpAuthMetadata(
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val registrationEndpoint: String? = null,
        val scopesSupported: List<String> = emptyList(),
    )

    /**
     * 动态注册结果。
     *
     * @param clientId 注册得到的 client_id
     * @param clientIdIssuedAt 颁发时间戳(unix 秒,0 表示未返回)
     * @param clientSecret 客户端密钥(公共客户端通常为空)
     * @param clientSecretExpiresAt 过期时间戳(unix 秒,0 表示不过期)
     */
    @Serializable
    data class RegisteredClient(
        val clientId: String,
        val clientIdIssuedAt: Long = 0L,
        val clientSecret: String = "",
        val clientSecretExpiresAt: Long = 0L,
    )

    /**
     * Step 1: 元数据发现。
     *
     * 从 MCP server URL 出发,先查 Protected Resource Metadata(RFC 9728)找到
     * authorization_servers,再查首个 auth server 的 Authorization Server Metadata
     * (RFC 8414)拿到 endpoints。
     *
     * @param serverUrl MCP server URL(如 https://server.example.com/mcp)
     * @return 元数据;失败返回 null
     */
    suspend fun discoverMetadata(serverUrl: String): McpAuthMetadata? = withContext(Dispatchers.IO) {
        val base = baseUrl(serverUrl) ?: return@withContext null
        resultOf {
            // Step 1a: GET {base}/.well-known/oauth-protected-resource
            val resourceMeta = fetchJson("$base/.well-known/oauth-protected-resource") ?: return@resultOf null
            val authServers = resourceMeta["authorization_servers"]
                ?.let { parseStringArray(it) }
                ?: emptyList()
            if (authServers.isEmpty()) {
                // 兼容:部分 server 直接把 endpoints 放在 resource metadata 里
                val authEndpoint = resourceMeta["authorization_endpoint"]?.jsonPrimitive?.contentOrNull
                val tokenEndpoint = resourceMeta["token_endpoint"]?.jsonPrimitive?.contentOrNull
                if (authEndpoint != null && tokenEndpoint != null) {
                    return@resultOf McpAuthMetadata(
                        authorizationEndpoint = authEndpoint,
                        tokenEndpoint = tokenEndpoint,
                        registrationEndpoint = resourceMeta["registration_endpoint"]?.jsonPrimitive?.contentOrNull,
                        scopesSupported = parseScopes(resourceMeta["scopes_supported"]),
                    )
                }
                return@resultOf null
            }

            // 步骤 1b: GET {auth_server}/.well-known/oauth-authorization-server
            for (authServer in authServers) {
                val authMeta = fetchJson("$authServer/.well-known/oauth-authorization-server") ?: continue
                val authEndpoint = authMeta["authorization_endpoint"]?.jsonPrimitive?.contentOrNull ?: continue
                val tokenEndpoint = authMeta["token_endpoint"]?.jsonPrimitive?.contentOrNull ?: continue
                return@resultOf McpAuthMetadata(
                    authorizationEndpoint = authEndpoint,
                    tokenEndpoint = tokenEndpoint,
                    registrationEndpoint = authMeta["registration_endpoint"]?.jsonPrimitive?.contentOrNull,
                    scopesSupported = parseScopes(authMeta["scopes_supported"]),
                )
            }
            null
        }.onError { msg, t -> Logger.w(TAG, "discoverMetadata 失败: $msg", t) }
            .getOrNull()
    }

    /**
     * Step 2: 动态客户端注册(RFC 7591)。
     *
     * @param registrationEndpoint 注册端点(从 [discoverMetadata] 拿到)
     * @param redirectUri 回调 URI(必须与 MCP server 预期一致,如 io.zer0.muse://oauth/callback)
     * @param clientName 客户端名(默认 "muse")
     * @param scopes 空格分隔的 scope 列表(如 "tools:read tools:call")
     * @return 注册结果;失败返回 null
     */
    suspend fun registerClient(
        registrationEndpoint: String,
        redirectUri: String,
        clientName: String = "muse",
        scopes: String = "",
    ): RegisteredClient? = withContext(Dispatchers.IO) {
        resultOf {
            // M-DYNREG1: 用 buildJsonObject 构造请求体,避免手写 JSON 转义遗漏控制字符
            val requestBody = buildJsonObject {
                put("client_name", clientName)
                put("redirect_uris", JsonArray(listOf(JsonPrimitive(redirectUri))))
                put("token_endpoint_auth_method", "none")
                put("grant_types", JsonArray(listOf(
                    JsonPrimitive("authorization_code"),
                    JsonPrimitive("refresh_token"),
                )))
                put("response_types", JsonArray(listOf(JsonPrimitive("code"))))
                if (scopes.isNotBlank()) {
                    put("scope", scopes)
                }
                put("code_challenge_method", "S256")
            }
            val req = Request.Builder()
                .url(registrationEndpoint)
                .post(AppJson.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Logger.w(TAG, "registerClient 失败: HTTP ${resp.code}")
                    return@resultOf null
                }
                val body = resp.body.string()
                val obj = AppJson.decodeFromString(JsonObject.serializer(), body)
                val clientId = obj["client_id"]?.jsonPrimitive?.contentOrNull ?: return@resultOf null
                RegisteredClient(
                    clientId = clientId,
                    clientIdIssuedAt = obj["client_id_issued_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                    clientSecret = obj["client_secret"]?.jsonPrimitive?.contentOrNull ?: "",
                    clientSecretExpiresAt = obj["client_secret_expires_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                )
            }
        }.onError { msg, t -> Logger.w(TAG, "registerClient 失败: $msg", t) }
            .getOrNull()
    }

    /**
     * 一键完成"发现 + 注册"两步,返回填好的 [McpOAuthConfig](不含 token,token 由 PKCE 流程拿)。
     *
     * @param serverUrl MCP server URL
     * @param redirectUri 回调 URI
     * @param scopes 空格分隔的 scope
     * @return 填好 clientId + endpoints 的 [McpOAuthConfig];失败返回 null
     */
    suspend fun discoverAndRegister(
        serverUrl: String,
        redirectUri: String = "io.zer0.muse://oauth/callback",
        scopes: String = "",
    ): McpOAuthConfig? = withContext(Dispatchers.IO) {
        val meta = discoverMetadata(serverUrl) ?: return@withContext null
        val regEndpoint = meta.registrationEndpoint ?: return@withContext null
        // M-DYNREG2: 校验 registrationEndpoint 与 serverUrl 同源,防止 Open Redirect
        val regBase = baseUrl(regEndpoint)
        val serverBase = baseUrl(serverUrl)
        if (regBase == null || serverBase == null || regBase != serverBase) {
            Logger.w(TAG, "registrationEndpoint 与 serverUrl 不同源: $regEndpoint vs $serverUrl,拒绝")
            return@withContext null
        }
        val registered = registerClient(regEndpoint, redirectUri, scopes = scopes) ?: return@withContext null
        McpOAuthConfig(
            clientId = registered.clientId,
            redirectUri = redirectUri,
            authorizationEndpoint = meta.authorizationEndpoint,
            tokenEndpoint = meta.tokenEndpoint,
            scopes = scopes,
            enabled = true,
        )
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────

    /** 从完整 URL 提取 base(scheme://host[:port],不含 path)。 */
    private fun baseUrl(url: String): String? {
        val idx = url.indexOf("://").takeIf { it > 0 } ?: return null
        val afterScheme = url.substring(idx + 3)
        val slashIdx = afterScheme.indexOf('/')
        val base = if (slashIdx > 0) afterScheme.substring(0, slashIdx) else afterScheme
        return url.substring(0, idx + 3) + base
    }

    /** GET 请求并解析 JSON。失败返回 null。 */
    private fun fetchJson(url: String): JsonObject? {
        return runCatching {
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body.string()
                AppJson.decodeFromString(JsonObject.serializer(), body)
            }
        }.onFailure { Logger.w(TAG, "fetchJson($url) 失败: ${it.message}") }
            .getOrNull()
    }

    /** 解析 scopes_supported 字段(JSON 数组 of string)。 */
    private fun parseScopes(el: kotlinx.serialization.json.JsonElement?): List<String> {
        if (el == null) return emptyList()
        return parseStringArray(el)
    }

    /** 通用:解析 JSON 数组 of string。 */
    private fun parseStringArray(el: kotlinx.serialization.json.JsonElement): List<String> {
        val arr = el as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
    }
}
