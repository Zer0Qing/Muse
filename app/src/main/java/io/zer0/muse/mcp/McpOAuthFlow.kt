package io.zer0.muse.mcp

import android.util.Base64
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Phase 10.4 (M3): MCP OAuth 2.1 PKCE 流程实现。
 *
 * 实现 OAuth 2.1 授权码流程 + PKCE(RFC 7636),符合 MCP 规范的 Authorization 要求:
 *
 * 1. [generatePkcePair]: 生成 code_verifier + code_challenge(S256)
 * 2. [buildAuthorizationUrl]: 构造授权 URL(由 UI 用 Intent.ACTION_VIEW 打开浏览器)
 * 3. [exchangeCodeForToken]: 用授权码 + code_verifier 换 access_token
 * 4. [refreshAccessToken]: 用 refresh_token 续期 access_token
 *
 * PKCE 算法(RFC 7636 §4.2):
 * - code_verifier: 43-128 字符,字符集 [A-Z][a-z][0-9]-._~
 * - code_challenge = BASE64URL(SHA256(code_verifier)),去掉 = 填充
 * - code_challenge_method = "S256"
 *
 * 线程模型:所有网络 IO 在 Dispatchers.IO。
 *
 * 独立编写(参考 RFC 7636 + RFC 6749 + MCP 规范),Apache 2.0。
 */
object McpOAuthFlow {

    private const val TAG = "McpOAuthFlow"
    private const val VERIFIER_LENGTH = 64  // RFC 7636 推荐 43-128,取中间值 64
    private val VERIFIER_CHARS: CharArray = (
        ('A'..'Z').toList() + ('a'..'z').toList() + ('0'..'9').toList() +
            listOf('-', '.', '_', '~')
    ).toCharArray()

    /** token 交换用的 OkHttp client(短超时,OAuth 流程不应长时间阻塞)。 */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * PKCE 验证码对。
     *
     * @param verifier code_verifier(原值,token 交换时发送)
     * @param challenge code_challenge(SHA256 + BASE64URL,授权 URL 里发送)
     * @param state CSRF 防护随机串(回调时校验)
     */
    data class PkcePair(
        val verifier: String,
        val challenge: String,
        val state: String,
    )

    /**
     * Token 端点响应(标准 OAuth 2.0 token response)。
     */
    @Serializable
    private data class TokenResponse(
        val access_token: String = "",
        val token_type: String = "Bearer",
        val expires_in: Long = 0L,
        val refresh_token: String? = null,
        val scope: String? = null,
        val error: String? = null,
        val error_description: String? = null,
    )

    /** 生成 PKCE 验证码对(code_verifier + code_challenge + state)。 */
    fun generatePkcePair(): PkcePair {
        val verifier = generateCodeVerifier()
        val challenge = computeCodeChallenge(verifier)
        val state = generateRandomString(32)
        // L-MCP5: 日志中脱敏 state,仅记录前 4 位
        Logger.d(TAG, "PKCE pair generated: verifier length=${verifier.length}, state=${state.take(4)}***")
        return PkcePair(verifier, challenge, state)
    }

    /**
     * 构造授权 URL(由 UI 用 Intent.ACTION_VIEW 打开浏览器)。
     *
     * 参数顺序遵循 OAuth 2.1 规范,包含:
     * - response_type=code
     * - client_id
     * - redirect_uri
     * - code_challenge + code_challenge_method=S256
     * - state(CSRF 防护)
     * - scope(可选)
     */
    fun buildAuthorizationUrl(config: McpOAuthConfig, pkce: PkcePair): String {
        val params = buildList {
            add("response_type" to "code")
            add("client_id" to config.clientId)
            add("redirect_uri" to config.redirectUri)
            add("code_challenge" to pkce.challenge)
            add("code_challenge_method" to "S256")
            add("state" to pkce.state)
            if (config.scopes.isNotBlank()) add("scope" to config.scopes)
        }
        val query = params.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
        // 第一个参数用 ? 开头(若 endpoint 已含 ?,用 & 衔接)
        return buildString {
            append(config.authorizationEndpoint.trimEnd('?', '&'))
            append(if (config.authorizationEndpoint.contains("?")) "&" else "?")
            append(query)
        }.also {
            // L-MCP5: 日志中脱敏 state 参数,仅记录前 4 位
            val maskedUrl = it.replace("state=${pkce.state}", "state=${pkce.state.take(4)}***")
            Logger.d(TAG, "Authorization URL: $maskedUrl")
        }
    }

    /**
     * 用授权码换 access_token。
     *
     * 以 POST application/x-www-form-urlencoded 方式发送:
     * - grant_type=authorization_code
     * - code=...
     * - redirect_uri=...(必须与授权请求一致)
     * - client_id=...
     * - code_verifier=...(PKCE 校验)
     *
     * @return 成功返回 [McpTokenInfo],失败返回 null(error 已记录日志)
     */
    suspend fun exchangeCodeForToken(
        config: McpOAuthConfig,
        code: String,
        verifier: String,
    ): McpTokenInfo? = withContext(Dispatchers.IO) {
        if (config.tokenEndpoint.isBlank()) {
            Logger.w(TAG, "tokenEndpoint is blank, cannot exchange code")
            return@withContext null
        }
        // M-OAUTH1: 校验 tokenEndpoint 使用 HTTPS,防止 token 明文传输
        if (!config.tokenEndpoint.startsWith("https://")) {
            Logger.w(TAG, "tokenEndpoint 不是 HTTPS,拒绝交换 token: ${config.tokenEndpoint}")
            return@withContext null
        }
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", config.redirectUri)
            .add("client_id", config.clientId)
            .add("code_verifier", verifier)
            .build()

        val request = Request.Builder()
            .url(config.tokenEndpoint)
            .header("Accept", "application/json")
            .post(form)
            .build()

        resultOf {
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) {
                    Logger.w(TAG, "Token exchange failed: HTTP ${resp.code}, body=${body.take(200)}")
                    return@resultOf null
                }
                parseTokenResponse(body)
            }
        }.onError { msg, t ->
            Logger.w(TAG, "Token exchange exception: $msg", t)
        }.getOrNull()
    }

    /**
     * 用 refresh_token 续期 access_token。
     *
     * 以 POST application/x-www-form-urlencoded 方式发送:
     * - grant_type=refresh_token
     * - refresh_token=...
     * - client_id=...
     *
     * @return 成功返回新的 [McpTokenInfo](refresh_token 可能不变,沿用旧值),失败返回 null
     */
    suspend fun refreshAccessToken(
        config: McpOAuthConfig,
        refreshToken: String,
    ): McpTokenInfo? = withContext(Dispatchers.IO) {
        if (config.tokenEndpoint.isBlank()) {
            Logger.w(TAG, "tokenEndpoint is blank, cannot refresh")
            return@withContext null
        }
        // M-OAUTH1: 校验 tokenEndpoint 使用 HTTPS,防止 token 明文传输
        if (!config.tokenEndpoint.startsWith("https://")) {
            Logger.w(TAG, "tokenEndpoint 不是 HTTPS,拒绝 refresh token: ${config.tokenEndpoint}")
            return@withContext null
        }
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", config.clientId)
            .build()

        val request = Request.Builder()
            .url(config.tokenEndpoint)
            .header("Accept", "application/json")
            .post(form)
            .build()

        resultOf {
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) {
                    Logger.w(TAG, "Token refresh failed: HTTP ${resp.code}, body=${body.take(200)}")
                    return@resultOf null
                }
                // refresh 响应可能不含新 refresh_token,沿用旧的;server 轮换 refresh_token 时用新的
                parseTokenResponse(body)?.let { token ->
                    token.copy(
                        refreshToken = token.refreshToken.ifBlank { refreshToken }
                    )
                }
            }
        }.onError { msg, t ->
            Logger.w(TAG, "Token refresh exception: $msg", t)
        }.getOrNull()
    }

    /**
     * 解析 redirect_uri 回调的 query 参数,提取 code 和 state。
     *
     * 回调形如:io.zer0.muse://oauth/callback?code=xxx&state=yyy
     * 失败时(如 error=access_denied)返回 null。
     */
    fun parseRedirectCallback(redirectUri: String, expectedPrefix: String? = null): Pair<String, String>? {
        // L-OAUTH1: 校验回调 URI 前缀(去掉 query 部分),防止恶意回调
        if (expectedPrefix != null) {
            val prefix = expectedPrefix.substringBefore('?')
            if (!redirectUri.startsWith(prefix)) {
                Logger.w(TAG, "OAuth 回调 URI 前缀不匹配: expected=$prefix, got=${redirectUri.substringBefore('?')}")
                return null
            }
        }
        val query = redirectUri.substringAfter('?', "")
        if (query.isBlank()) return null
        val params = query.split('&').mapNotNull {
            val idx = it.indexOf('=')
            if (idx < 0) null else it.substring(0, idx) to urlDecode(it.substring(idx + 1))
        }.toMap()

        // OAuth 2.1 错误响应:error=...&error_description=...
        params["error"]?.let { err ->
            Logger.w(TAG, "OAuth callback error: $err, desc=${params["error_description"]}")
            return null
        }

        val code = params["code"] ?: return null
        val state = params["state"] ?: return null
        return code to state
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    /** 生成 code_verifier(43-128 字符,[A-Z][a-z][0-9]-._~)。 */
    private fun generateCodeVerifier(): String {
        val random = SecureRandom()
        val sb = StringBuilder(VERIFIER_LENGTH)
        repeat(VERIFIER_LENGTH) {
            sb.append(VERIFIER_CHARS[random.nextInt(VERIFIER_CHARS.size)])
        }
        return sb.toString()
    }

    /**
     * 计算 code_challenge = BASE64URL(SHA256(verifier))。
     * BASE64URL: 标准 BASE64 的 + → -,/ → _,去掉 = 填充。
     */
    private fun computeCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** 生成随机字符串(state / nonce 用)。 */
    private fun generateRandomString(length: Int): String {
        val random = SecureRandom()
        val sb = StringBuilder(length)
        repeat(length) {
            sb.append(VERIFIER_CHARS[random.nextInt(VERIFIER_CHARS.size)])
        }
        return sb.toString()
    }

    /** 解析 token 端点 JSON 响应为 [McpTokenInfo]。 */
    private fun parseTokenResponse(body: String): McpTokenInfo? {
        val resp = runCatching {
            AppJson.decodeFromString(TokenResponse.serializer(), body)
        }.getOrNull() ?: run {
            // 某些 server 返回非标 JSON,尝试用 JsonObject 兜底解析
            tryParseLooseTokenResponse(body)
        }
        if (resp == null) {
            // v1.114 安全:解析失败的 body 可能含凭据,截断前 200 字符;Logger.w 会自动 sanitizePii
            Logger.w(TAG, "Failed to parse token response: ${body.take(200)}")
            return null
        }
        if (!resp.error.isNullOrEmpty()) {
            Logger.w(TAG, "Token response error: ${resp.error}, desc=${resp.error_description}")
            return null
        }
        if (resp.access_token.isBlank()) {
            Logger.w(TAG, "Token response missing access_token")
            return null
        }
        val expiresAt = if (resp.expires_in > 0) {
            System.currentTimeMillis() + resp.expires_in * 1000L
        } else 0L
        return McpTokenInfo(
            accessToken = resp.access_token,
            refreshToken = resp.refresh_token.orEmpty(),
            expiresAt = expiresAt,
            scope = resp.scope.orEmpty(),
        )
    }

    /** 兜底解析:某些 server 字段名不规范(如 access-token 而非 access_token)。 */
    private fun tryParseLooseTokenResponse(body: String): TokenResponse? {
        val obj = runCatching {
            AppJson.decodeFromString(JsonObject.serializer(), body)
        }.getOrNull() ?: return null
        fun getStr(key: String): String =
            (obj[key] as? JsonPrimitive)?.content ?: ""
        fun getLong(key: String): Long =
            (obj[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        return TokenResponse(
            access_token = getStr("access_token").ifBlank { getStr("accessToken") },
            token_type = getStr("token_type").ifBlank { "Bearer" },
            expires_in = getLong("expires_in").takeIf { it > 0 } ?: getLong("expiresIn"),
            refresh_token = getStr("refresh_token").ifBlank { getStr("refreshToken") }.ifBlank { null },
            scope = getStr("scope").ifBlank { null },
            error = getStr("error").ifBlank { null },
            error_description = getStr("error_description").ifBlank { getStr("errorDescription") }.ifBlank { null },
        )
    }

    /** URL 编码(用 java.net.URLEncoder,UTF-8)。 */
    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    /** URL 解码。 */
    private fun urlDecode(s: String): String =
        java.net.URLDecoder.decode(s, "UTF-8")
}
