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
import okhttp3.HttpUrl.Companion.toHttpUrl
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
 * 独立编写(按 RFC 7591/9728/8414 + MCP 规范),Apache 2.0。
 */
object McpDynamicRegistration {

    private const val TAG = "McpDynReg"

    /** HTTP 客户端(短超时,避免 UI 卡顿)。 */
    private val httpClient: OkHttpClient by lazy {
        io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
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
    suspend fun discoverMetadata(serverUrl: String, protectedResourceMetadataUrl: String? = null): McpAuthMetadata? =
        McpMetadataDiscovery.discover(httpClient, serverUrl, protectedResourceMetadataUrl)

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
        resourceUrl: String = registrationEndpoint,
    ): RegisteredClient? = withContext(Dispatchers.IO) {
        resultOf {
            // M-DYNREG1: 用 buildJsonObject 构造请求体,避免手写 JSON 转义遗漏控制字符
            val requestBody = buildJsonObject {
                put("client_name", clientName)
                put("redirect_uris", JsonArray(listOf(JsonPrimitive(redirectUri))))
                put("token_endpoint_auth_method", "none")
                put(
                    "grant_types",
                    JsonArray(
                        listOf(
                            JsonPrimitive("authorization_code"),
                            JsonPrimitive("refresh_token"),
                        ),
                    ),
                )
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
            McpHttpSecurity.secureClient(httpClient, resourceUrl).newCall(req).execute().use { resp ->
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
        }.onError { _, _ -> Logger.w(TAG, "registerClient 失败") }
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
        protectedResourceMetadataUrl: String? = null,
        resource: String = serverUrl,
    ): McpOAuthConfig? = withContext(Dispatchers.IO) {
        val meta = discoverMetadata(serverUrl, protectedResourceMetadataUrl) ?: return@withContext null
        val regEndpoint = meta.registrationEndpoint ?: return@withContext null
        // Reject private targets and allow plain HTTP only when metadata stays on the same loopback origin.
        if (!isAllowedEndpoint(regEndpoint, serverUrl)) {
            Logger.w(TAG, "registrationEndpoint 无效或不安全,拒绝")
            return@withContext null
        }
        if (!isAllowedEndpoint(meta.authorizationEndpoint, serverUrl) || !isAllowedEndpoint(meta.tokenEndpoint, serverUrl)) {
            Logger.w(TAG, "OAuth endpoints 无效或不安全,拒绝")
            return@withContext null
        }
        val registered = registerClient(regEndpoint, redirectUri, scopes = scopes, resourceUrl = serverUrl)
            ?: return@withContext null
        McpOAuthConfig(
            clientId = registered.clientId,
            redirectUri = redirectUri,
            authorizationEndpoint = meta.authorizationEndpoint,
            tokenEndpoint = meta.tokenEndpoint,
            scopes = scopes,
            resource = safeResourceIdentifier(resource),
            enabled = true,
        )
    }

    /** Extract only the standard metadata URL parameter; never retain or log other challenge data. */
    fun resourceMetadataUrlFromChallenge(header: String): String? {
        val value = challengeParameter(header, "resource_metadata") ?: return null
        return runCatching { value.toHttpUrl() }.getOrNull()?.takeIf { url ->
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
                (url.isHttps || url.host in setOf("localhost", "127.0.0.1", "::1"))
        }?.toString()
    }

    fun scopeFromChallenge(header: String): String? = challengeParameter(header, "scope")

    private fun challengeParameter(header: String, name: String): String? {
        val match = Regex(
            "(?:^|[,\\s])${Regex.escape(name)}\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))",
            RegexOption.IGNORE_CASE,
        ).find(header) ?: return null
        return (
            match.groupValues.getOrNull(1)?.takeIf(String::isNotEmpty)
                ?: match.groupValues.getOrNull(2)
            )?.trim()?.takeIf(String::isNotBlank)
    }

    internal fun isAllowedEndpoint(value: String, resourceUrl: String): Boolean = runCatching {
        val url = value.toHttpUrl()
        val resource = resourceUrl.toHttpUrl()
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return@runCatching false
        val sameLocalOrigin = url.scheme == "http" && resource.scheme == "http" &&
            url.host == resource.host && url.port == resource.port &&
            url.host in setOf("localhost", "127.0.0.1", "::1")
        sameLocalOrigin || (url.isHttps && !io.zer0.muse.ui.common.SsrfGuard.isBlocked(value))
    }.getOrDefault(false)

    private fun safeResourceIdentifier(value: String): String = runCatching {
        value.toHttpUrl().newBuilder().username("").password("").query(null).fragment(null).build().toString()
    }.getOrDefault(value)

}

private object McpMetadataDiscovery {
    suspend fun discover(
        baseClient: OkHttpClient,
        serverUrl: String,
        protectedResourceMetadataUrl: String?,
    ): McpDynamicRegistration.McpAuthMetadata? = withContext(Dispatchers.IO) {
        val client = McpHttpSecurity.secureClient(baseClient, serverUrl)
        val urls = resourceMetadataUrls(serverUrl, protectedResourceMetadataUrl) ?: return@withContext null
        resultOf {
            val resource = urls.firstNotNullOfOrNull { fetchJson(client, it, serverUrl) } ?: return@resultOf null
            metadataFromResource(client, resource, serverUrl)
        }.onError { _, _ -> Logger.w("McpDynReg", "discoverMetadata 失败") }.getOrNull()
    }

    private fun resourceMetadataUrls(serverUrl: String, hint: String?): List<String>? {
        val base = baseUrl(serverUrl) ?: return null
        return buildList {
            hint?.takeIf { McpDynamicRegistration.isAllowedEndpoint(it, serverUrl) }?.let(::add)
            add("$base/.well-known/oauth-protected-resource")
            add("$base/.well-known/oauth-protected-resource${serverUrl.toHttpPath()}")
        }.distinct()
    }

    private suspend fun metadataFromResource(
        client: OkHttpClient,
        resource: JsonObject,
        resourceUrl: String,
    ): McpDynamicRegistration.McpAuthMetadata? {
        val authServers = resource["authorization_servers"]?.let(::parseStringArray).orEmpty()
        return if (authServers.isEmpty()) {
            directAuthMetadata(resource)
        } else {
            discoverFirstAuthServer(client, authServers, resourceUrl)
        }
    }

    private fun directAuthMetadata(resource: JsonObject): McpDynamicRegistration.McpAuthMetadata? {
        val authorizationEndpoint = resource["authorization_endpoint"]?.jsonPrimitive?.contentOrNull ?: return null
        val tokenEndpoint = resource["token_endpoint"]?.jsonPrimitive?.contentOrNull ?: return null
        return McpDynamicRegistration.McpAuthMetadata(
            authorizationEndpoint = authorizationEndpoint,
            tokenEndpoint = tokenEndpoint,
            registrationEndpoint = resource["registration_endpoint"]?.jsonPrimitive?.contentOrNull,
            scopesSupported = parseScopes(resource["scopes_supported"]),
        )
    }

    private suspend fun discoverFirstAuthServer(
        client: OkHttpClient,
        authServers: List<String>,
        resourceUrl: String,
    ): McpDynamicRegistration.McpAuthMetadata? {
        for (authServer in authServers) {
            val authMetadata = fetchAuthServerMetadata(client, authServer, resourceUrl) ?: continue
            parseAuthMetadata(authMetadata, authServer)?.let { return it }
        }
        return null
    }

    private suspend fun fetchAuthServerMetadata(client: OkHttpClient, authServer: String, resourceUrl: String): JsonObject? {
        val base = baseUrl(authServer) ?: return null
        val path = "/.well-known/oauth-authorization-server"
        return fetchJson(client, "$base$path", resourceUrl)
            ?: fetchJson(client, "$base$path${authServer.toHttpPath()}", resourceUrl)
    }

    private fun parseAuthMetadata(auth: JsonObject, authServer: String): McpDynamicRegistration.McpAuthMetadata? {
        val issuer = auth["issuer"]?.jsonPrimitive?.contentOrNull
        if (issuer != null && issuer.trimEnd('/') != authServer.trimEnd('/')) return null
        val authorizationEndpoint = auth["authorization_endpoint"]?.jsonPrimitive?.contentOrNull ?: return null
        val tokenEndpoint = auth["token_endpoint"]?.jsonPrimitive?.contentOrNull ?: return null
        return McpDynamicRegistration.McpAuthMetadata(
            authorizationEndpoint = authorizationEndpoint,
            tokenEndpoint = tokenEndpoint,
            registrationEndpoint = auth["registration_endpoint"]?.jsonPrimitive?.contentOrNull,
            scopesSupported = parseScopes(auth["scopes_supported"]),
        )
    }

    private fun fetchJson(client: OkHttpClient, url: String, resourceUrl: String): JsonObject? = runCatching {
        if (!McpDynamicRegistration.isAllowedEndpoint(url, resourceUrl)) return@runCatching null
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) AppJson.decodeFromString(JsonObject.serializer(), response.body.string()) else null
        }
    }.onFailure { Logger.w("McpDynReg", "获取 OAuth 元数据失败") }.getOrNull()

    private fun baseUrl(url: String): String? {
        val schemeEnd = url.indexOf("://").takeIf { it > 0 } ?: return null
        val afterScheme = url.substring(schemeEnd + 3)
        val pathStart = afterScheme.indexOf('/')
        val host = if (pathStart > 0) afterScheme.substring(0, pathStart) else afterScheme
        return url.substring(0, schemeEnd + 3) + host
    }

    private fun String.toHttpPath(): String = runCatching {
        toHttpUrl().encodedPath.let { path -> if (path == "/") "" else path }
    }.getOrDefault("")

    private fun parseScopes(element: kotlinx.serialization.json.JsonElement?): List<String> =
        if (element == null) emptyList() else parseStringArray(element)

    private fun parseStringArray(element: kotlinx.serialization.json.JsonElement): List<String> =
        (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
}
