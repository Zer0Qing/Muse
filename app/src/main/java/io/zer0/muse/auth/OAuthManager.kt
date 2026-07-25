package io.zer0.muse.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Base64
import io.zer0.ai.core.OAuthConfig
import io.zer0.common.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * P1-6: OAuth 登录管理器 — 为支持 OAuth 的供应商(如 xAI)自动获取 apiKey。
 *
 * 支持两种流程:
 *  1. **Device Flow**(RFC 8628):[OAuthConfig.deviceCodeUrl] 非空时优先走此流程。
 *     POST deviceCodeUrl 拿 device_code + user_code + verification_uri,
 *     通过 [stateFlow] (AWAITING_USER) 把 user_code 推给 UI 弹窗显示,
 *     然后轮询 tokenUrl 直到拿到 access_token。
 *  2. **Authorization Code Flow + PKCE**:[OAuthConfig.deviceCodeUrl] 为空时走此流程。
 *     用 Intent.ACTION_VIEW 打开系统浏览器到 [OAuthConfig.authorizeUrl],
 *     通过 Deep Link(`io.zer0.muse://oauth/callback`)接收 code,
 *     由 [OAuthCallbackActivity] 调用 [completeAuthorizationCodeFlow] 完成回调,
 *     最后用 code + code_verifier 换 access_token。
 *
 * 状态机(IDLE → AWAITING_USER/POLLING → SUCCESS/ERROR → IDLE)通过 [stateFlow] 暴露,
 * UI 可观察以驱动弹窗 / 进度条 / 错误提示。
 *
 * 线程模型:网络 IO 在 Dispatchers.IO;状态更新在 Main(由 StateFlow 解决并发)。
 *
 * P2-11: 凭证隔离 — 注入 [SecureCredentialStore] 后,登录成功自动把 TokenBundle
 * (access_token + refresh_token + expiresAt + scope)加密写入独立 SP(与普通 API Key 物理隔离),
 * 并提供 [getStoredToken] / [refreshTokenIfNeeded] 让 UI 在 token 未过期时直接复用、
 * 过期时自动刷新,无需用户每次重新登录。
 */
object OAuthManager {

    private const val TAG = "OAuthManager"

    /** P2-11: 安全凭证存储(由 [init] 注入,未注入时跳过持久化)。 */
    @Volatile
    private var secureStore: SecureCredentialStore? = null

    /**
     * P2-11: 注入 [SecureCredentialStore],由 MuseApp.onCreate 在 startKoin 后调用一次。
     *
     * 注入前调用的 launch* / getStoredToken / refreshTokenIfNeeded 会以「无持久化」模式工作
     * (登录成功但不落盘,刷新时返回 null/失败),不会崩溃 — 便于单元测试与早期启动场景。
     */
    fun init(secureStore: SecureCredentialStore) {
        this.secureStore = secureStore
    }

    /** P2-11: providerId → OAuthConfig 缓存,供 [refreshTokenIfNeeded] 查询 tokenUrl/clientId 等。 */
    private val providerConfigs = mutableMapOf<String, OAuthConfig>()

    /** token 即将过期的提前刷新窗口(秒),避免边界时间窗内的 401。 */
    private const val REFRESH_BUFFER_SECONDS = 60L

    /** OAuth 流程当前状态,UI 通过 [stateFlow] 观察。 */
    sealed class State {
        /** 空闲,无进行中的 OAuth 流程。 */
        object IDLE : State()
        /**
         * 等待用户操作。
         * - Device Flow:携带 [userCode] + [verificationUri],UI 应弹窗显示给用户。
         * - Auth Code Flow:[userCode] 为空,UI 应提示「浏览器已打开,请完成授权」。
         */
        data class AWAITING_USER(val userCode: String = "", val verificationUri: String = "") : State()
        /** Device Flow 正在轮询 token 端点。 */
        object POLLING : State()
        /** 成功,[apiKey] 为换到的 access_token。 */
        data class SUCCESS(val apiKey: String) : State()
        /** 失败,[message] 为错误描述。 */
        data class ERROR(val message: String) : State()
    }

    private val _stateFlow = MutableStateFlow<State>(State.IDLE)
    /** 状态流,UI 观察以驱动弹窗/进度/错误提示。 */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /** token 交换用的 OkHttp client(短超时,OAuth 流程不应长时间阻塞)。 */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 当前进行中的 Auth Code Flow(用于 [completeAuthorizationCodeFlow] 完成回调)。 */
    private val pendingAuthCodeFlow = MutableStateFlow<PendingAuthCode?>(null)

    /** Auth Code Flow 内部上下文(state + code_verifier + 完成信号)。 */
    private data class PendingAuthCode(
        val state: String,
        val codeVerifier: String,
        val deferred: CompletableDeferred<String>,
    )

    /** Device Flow 的 device_code 响应(RFC 8628 §3.2)。 */
    @Serializable
    private data class DeviceCodeResponse(
        val device_code: String = "",
        val user_code: String = "",
        val verification_uri: String = "",
        val verification_uri_complete: String? = null,
        val expires_in: Long = 1800L,
        val interval: Long = 5L,
        val error: String? = null,
        val error_description: String? = null,
    )

    /** Token 端点响应(标准 OAuth 2.0 token response)。 */
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

    /**
     * 启动 Device Flow(RFC 8628)。
     *
     * 步骤:
     *  1. POST [OAuthConfig.deviceCodeUrl] 拿 device_code + user_code + verification_uri
     *  2. 通过 [stateFlow] 推 AWAITING_USER(userCode, verificationUri),UI 弹窗显示
     *  3. 按 interval 轮询 [OAuthConfig.tokenUrl],直到拿到 access_token 或超时/失败
     *  4. 推 SUCCESS(apiKey),返回 Result.success(access_token)
     *
     * 失败时推 ERROR(message),返回 Result.failure。
     *
     * P2-11: 若 [providerId] 非空,登录成功后会自动把 TokenBundle 加密写入 [secureStore],
     *        供 [getStoredToken] / [refreshTokenIfNeeded] 后续复用。
     *
     * @param config OAuth 配置(必须含 deviceCodeUrl,否则返回失败)
     * @param providerId P2-11: 供应商 ID(空字符串表示不持久化,保留旧调用方兼容)
     * @return 成功时 Result.success(access_token);失败时 Result.failure(异常)
     */
    suspend fun launchDeviceFlow(config: OAuthConfig, providerId: String = ""): Result<String> {
        val deviceCodeUrl = config.deviceCodeUrl
        if (deviceCodeUrl.isNullOrBlank()) {
            val msg = "Device Flow 不可用:deviceCodeUrl 为空"
            _stateFlow.value = State.ERROR(msg)
            return Result.failure(IllegalStateException(msg))
        }
        if (config.clientId.isBlank()) {
            val msg = "Device Flow 不可用:clientId 为空"
            _stateFlow.value = State.ERROR(msg)
            return Result.failure(IllegalStateException(msg))
        }
        // P2-11: 缓存 config 供 refreshTokenIfNeeded 查询
        if (providerId.isNotBlank()) {
            synchronized(providerConfigs) { providerConfigs[providerId] = config }
        }

        // 1. 请求 device_code
        val deviceResp = withContext(Dispatchers.IO) {
            runCatching {
                val form = FormBody.Builder()
                    .add("client_id", config.clientId)
                    .apply { if (config.scope.isNotBlank()) add("scope", config.scope) }
                    .build()
                val request = Request.Builder().url(deviceCodeUrl).post(form).build()
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        error("device_code 请求失败: HTTP ${resp.code}, body=${body.take(200)}")
                    }
                    io.zer0.common.AppJson.decodeFromString(
                        DeviceCodeResponse.serializer(),
                        resp.body?.string().orEmpty(),
                    )
                }
            }
        }
        if (deviceResp.isFailure) {
            val msg = "device_code 请求异常: ${deviceResp.exceptionOrNull()?.message ?: "未知"}"
            Logger.w(TAG, msg)
            _stateFlow.value = State.ERROR(msg)
            return Result.failure(deviceResp.exceptionOrNull() ?: IllegalStateException(msg))
        }
        val dc = deviceResp.getOrThrow()
        if (dc.device_code.isBlank() || dc.user_code.isBlank()) {
            val msg = "device_code 响应缺字段: device_code/user_code 为空"
            Logger.w(TAG, msg)
            _stateFlow.value = State.ERROR(msg)
            return Result.failure(IllegalStateException(msg))
        }

        // 2. 推 AWAITING_USER,UI 弹窗显示 user_code
        _stateFlow.value = State.AWAITING_USER(
            userCode = dc.user_code,
            verificationUri = dc.verification_uri_complete ?: dc.verification_uri,
        )

        // 3. 轮询 token 端点
        _stateFlow.value = State.POLLING
        val intervalMs = (dc.interval.coerceAtLeast(1) * 1000L)
        val deadline = System.currentTimeMillis() + dc.expires_in.coerceAtLeast(1) * 1000L
        var currentInterval = intervalMs
        while (System.currentTimeMillis() < deadline) {
            delay(currentInterval)
            val tokenResp = pollToken(config, dc.device_code)
            // 拿到 access_token
            if (tokenResp.access_token.isNotBlank() && tokenResp.error == null) {
                // P2-11: 持久化 TokenBundle(refresh_token/expiresAt/scope 一并存储,
                // 供后续 refreshTokenIfNeeded 使用)
                if (providerId.isNotBlank()) {
                    persistTokenBundle(providerId, tokenResp)
                }
                _stateFlow.value = State.SUCCESS(tokenResp.access_token)
                return Result.success(tokenResp.access_token)
            }
            when (tokenResp.error) {
                "authorization_pending" -> {
                    // 用户尚未完成授权,继续轮询
                    currentInterval = intervalMs
                }
                "slow_down" -> {
                    // 服务端要求降速,interval 增加 5s
                    currentInterval = (currentInterval + 5000L).coerceAtMost(60_000L)
                }
                "expired_token" -> {
                    val msg = "device_code 已过期,请重新发起 OAuth 登录"
                    _stateFlow.value = State.ERROR(msg)
                    return Result.failure(IllegalStateException(msg))
                }
                "access_denied" -> {
                    val msg = "用户拒绝了授权请求"
                    _stateFlow.value = State.ERROR(msg)
                    return Result.failure(IllegalStateException(msg))
                }
                null -> {
                    // 无 error 但也无 access_token,继续轮询
                }
                else -> {
                    val msg = "OAuth token 轮询失败: ${tokenResp.error} — ${tokenResp.error_description ?: "未知"}"
                    _stateFlow.value = State.ERROR(msg)
                    return Result.failure(IllegalStateException(msg))
                }
            }
        }
        val msg = "OAuth 登录超时,请重试"
        _stateFlow.value = State.ERROR(msg)
        return Result.failure(IllegalStateException(msg))
    }

    /**
     * 启动 Authorization Code Flow + PKCE。
     *
     * 步骤:
     *  1. 生成 PKCE code_verifier + code_challenge + state(CSRF)
     *  2. 构造 authorizeUrl,用 Intent.ACTION_VIEW 打开系统浏览器
     *  3. 通过 [stateFlow] 推 AWAITING_USER(空 userCode),UI 提示「浏览器已打开」
     *  4. 等待 [completeAuthorizationCodeFlow] 被 [OAuthCallbackActivity] 调用,
     *     拿到 code,用 code + code_verifier 换 access_token
     *  5. 推 SUCCESS(apiKey),返回 Result.success(access_token)
     *
     * P2-11: 若 [providerId] 非空,登录成功后会自动把 TokenBundle 加密写入 [secureStore]。
     *
     * @param activity 用于 startActivity 的 Activity 上下文
     * @param config OAuth 配置
     * @param providerId P2-11: 供应商 ID(空字符串表示不持久化,保留旧调用方兼容)
     * @return 成功时 Result.success(access_token);失败时 Result.failure(异常)
     */
    suspend fun launchAuthorizationCodeFlow(
        activity: Activity,
        config: OAuthConfig,
        providerId: String = "",
    ): Result<String> {
        if (config.clientId.isBlank()) {
            val msg = "Auth Code Flow 不可用:clientId 为空"
            _stateFlow.value = State.ERROR(msg)
            return Result.failure(IllegalStateException(msg))
        }
        // P2-11: 缓存 config 供 refreshTokenIfNeeded 查询
        if (providerId.isNotBlank()) {
            synchronized(providerConfigs) { providerConfigs[providerId] = config }
        }
        // 1. 生成 PKCE 对 + state
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = computeCodeChallenge(codeVerifier)
        val state = generateRandomString(32)
        val deferred = CompletableDeferred<String>()
        pendingAuthCodeFlow.value = PendingAuthCode(state, codeVerifier, deferred)

        // 2. 构造 authorizeUrl
        val authUrl = buildAuthorizationUrl(config, codeChallenge, state)

        // 3. 打开浏览器
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(intent) }.onFailure {
            val msg = "无法打开浏览器: ${it.message ?: "未知"}"
            _stateFlow.value = State.ERROR(msg)
            pendingAuthCodeFlow.value = null
            return Result.failure(it)
        }

        // 4. 等待 OAuthCallbackActivity 调用 completeAuthorizationCodeFlow
        _stateFlow.value = State.AWAITING_USER()
        val code = try {
            deferred.await()
        } catch (e: Exception) {
            pendingAuthCodeFlow.value = null
            _stateFlow.value = State.ERROR(e.message ?: "授权回调被取消")
            return Result.failure(e)
        }

        // 5. 用 code + code_verifier 换 access_token
        _stateFlow.value = State.POLLING
        val tokenResp = exchangeCodeForToken(config, code, codeVerifier)
        pendingAuthCodeFlow.value = null
        if (tokenResp == null || tokenResp.access_token.isBlank()) {
            val msg = "OAuth token 交换失败"
            _stateFlow.value = State.ERROR(msg)
            return Result.failure(IllegalStateException(msg))
        }
        // P2-11: 持久化 TokenBundle
        if (providerId.isNotBlank()) {
            persistTokenBundle(providerId, tokenResp)
        }
        _stateFlow.value = State.SUCCESS(tokenResp.access_token)
        return Result.success(tokenResp.access_token)
    }

    /**
     * 由 [OAuthCallbackActivity] 在收到 Deep Link 回调时调用,完成 Auth Code Flow。
     *
     * 校验 state(CSRF 防护),匹配后解析 code 并 resolve 挂起的 Deferred。
     *
     * @param code OAuth 回调里的 code 参数
     * @param state OAuth 回调里的 state 参数
     * @return true 表示状态匹配,流程将继续换 token;false 表示无 pending 流程或 state 不匹配
     */
    fun completeAuthorizationCodeFlow(code: String, state: String): Boolean {
        val pending = pendingAuthCodeFlow.value ?: run {
            Logger.w(TAG, "completeAuthorizationCodeFlow 但无 pending Auth Code Flow")
            return false
        }
        if (state != pending.state) {
            Logger.w(TAG, "OAuth state 不匹配: expected=${pending.state.take(4)}***, got=${state.take(4)}***")
            return false
        }
        if (code.isBlank()) {
            Logger.w(TAG, "OAuth 回调 code 为空")
            return false
        }
        return pending.deferred.complete(code)
    }

    /** 取消当前进行中的 OAuth 流程(用户主动取消 / 页面退出时调用)。 */
    fun cancel() {
        pendingAuthCodeFlow.value?.deferred?.completeExceptionally(OAuthCancelledException())
        pendingAuthCodeFlow.value = null
        _stateFlow.value = State.IDLE
    }

    /** 重置状态为 IDLE(UI 关闭弹窗后调用)。 */
    fun resetState() {
        _stateFlow.value = State.IDLE
    }

    // ── P2-11: 凭证隔离相关 API ──────────────────────────────────────

    /**
     * P2-11: 读取已持久化的 OAuth token。
     *
     * UI 进入 ProviderEditPage 时调用,若返回非空且未过期,可直接复用 access_token 填入 apiKey 输入框,
     * 无需用户重新登录。
     *
     * @return 已存储的 [TokenBundle];未初始化 store / 无记录 / 解密失败均返回 null
     */
    suspend fun getStoredToken(providerId: String): TokenBundle? {
        val store = secureStore ?: return null
        if (providerId.isBlank()) return null
        return store.getOAuthToken(providerId)
    }

    /**
     * P2-11: 删除已持久化的 OAuth token(撤销访问)。
     *
     * UI「撤销访问」按钮调用,清空本地存储的 access/refresh token。
     * 注意:这仅撤销本地存储,不会通知服务端 invalidate token(需调用服务端的 revocation 端点,
     * 多数供应商未公开此端点)。撤销后 UI 应清空 apiKey 输入框。
     */
    suspend fun revokeStoredToken(providerId: String) {
        val store = secureStore ?: return
        if (providerId.isBlank()) return
        store.deleteOAuthToken(providerId)
        synchronized(providerConfigs) { providerConfigs.remove(providerId) }
    }

    /**
     * P2-11: 自动刷新 token(若已过期或即将过期)。
     *
     * 流程:
     *  1. 读取已存储的 [TokenBundle]
     *  2. 若 expiresAt 为 0(不过期/未知)或 expiresAt > now + [REFRESH_BUFFER_SECONDS],直接返回当前 access_token
     *  3. 若有 refresh_token,POST refresh_token grant 换新 token;成功后更新 [TokenBundle] 并返回新 access_token
     *  4. 无 refresh_token 或刷新失败,返回 Result.failure(用户需重新登录)
     *
     * @return 成功时 Result.success(有效的 access_token);失败时 Result.failure(异常)
     */
    suspend fun refreshTokenIfNeeded(providerId: String): Result<String> {
        val store = secureStore ?: run {
            return Result.failure(IllegalStateException("SecureCredentialStore 未初始化"))
        }
        if (providerId.isBlank()) {
            return Result.failure(IllegalArgumentException("providerId 为空"))
        }
        val bundle = store.getOAuthToken(providerId)
            ?: return Result.failure(IllegalStateException("无已存储的 OAuth token"))
        val now = System.currentTimeMillis()
        val bufferMs = REFRESH_BUFFER_SECONDS * 1000L
        // 未过期(含 expiresAt=0 未知)直接返回当前 access_token
        if (bundle.expiresAt == 0L || bundle.expiresAt > now + bufferMs) {
            return Result.success(bundle.accessToken)
        }
        // 过期但无 refresh_token,无法刷新
        val refreshToken = bundle.refreshToken
        if (refreshToken.isNullOrBlank()) {
            return Result.failure(IllegalStateException("token 已过期且无 refresh_token"))
        }
        // 取出 OAuthConfig(必须先 launch* 过一次才会缓存)
        val config = synchronized(providerConfigs) { providerConfigs[providerId] }
            ?: return Result.failure(IllegalStateException("无缓存的 OAuthConfig,无法刷新"))
        // 调用 refresh_token grant
        val refreshResult = refreshAccessToken(config, refreshToken)
        if (refreshResult == null || refreshResult.access_token.isBlank()) {
            // 刷新失败,清掉本地存储(强制用户重新登录)
            store.deleteOAuthToken(providerId)
            return Result.failure(IllegalStateException("refresh_token 已失效,请重新登录"))
        }
        // 更新 TokenBundle(refresh_token 部分供应商每次刷新都返回新的,部分只首次返回;
        // 若响应未带 refresh_token,保留旧值以备下次刷新)
        val newBundle = TokenBundle(
            accessToken = refreshResult.access_token,
            refreshToken = refreshResult.refresh_token ?: refreshToken,
            expiresAt = computeExpiresAt(refreshResult.expires_in),
            scope = refreshResult.scope ?: bundle.scope,
        )
        store.storeOAuthToken(providerId, newBundle)
        return Result.success(newBundle.accessToken)
    }

    /** P2-11: 把 [TokenResponse] 转 [TokenBundle] 并写入 [secureStore]。 */
    private suspend fun persistTokenBundle(providerId: String, resp: TokenResponse) {
        val store = secureStore ?: return
        val bundle = TokenBundle(
            accessToken = resp.access_token,
            refreshToken = resp.refresh_token,
            expiresAt = computeExpiresAt(resp.expires_in),
            scope = resp.scope.orEmpty(),
        )
        store.storeOAuthToken(providerId, bundle)
    }

    /** P2-11: 用 refresh_token 换新 access_token(refresh_token grant)。 */
    private suspend fun refreshAccessToken(config: OAuthConfig, refreshToken: String): TokenResponse? {
        return withContext(Dispatchers.IO) {
            runCatching {
                val form = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id", config.clientId)
                    .apply { config.clientSecret?.takeIf { it.isNotBlank() }?.let { add("client_secret", it) } }
                    .build()
                val request = Request.Builder().url(config.tokenUrl).post(form).build()
                httpClient.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Logger.w(TAG, "refresh_token 失败: HTTP ${resp.code}, body=${body.take(200)}")
                        return@use null
                    }
                    io.zer0.common.AppJson.decodeFromString(
                        TokenResponse.serializer(),
                        body,
                    )
                }
            }.getOrNull()
        }
    }

    /** P2-11: 根据 expires_in(秒)计算过期时间戳(epoch millis)。 */
    private fun computeExpiresAt(expiresInSeconds: Long): Long {
        if (expiresInSeconds <= 0L) return 0L
        return System.currentTimeMillis() + expiresInSeconds * 1000L
    }

    // ── 内部工具 ───────────────────────────────────────────────────────

    /** 轮询 token 端点(Device Flow)。 */
    private suspend fun pollToken(config: OAuthConfig, deviceCode: String): TokenResponse {
        return withContext(Dispatchers.IO) {
            runCatching {
                val form = FormBody.Builder()
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .add("device_code", deviceCode)
                    .add("client_id", config.clientId)
                    .apply { config.clientSecret?.takeIf { it.isNotBlank() }?.let { add("client_secret", it) } }
                    .build()
                val request = Request.Builder().url(config.tokenUrl).post(form).build()
                httpClient.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    // HTTP 4xx 也可能携带 error 字段(authorization_pending 等),按 body 解析
                    io.zer0.common.AppJson.decodeFromString(
                        TokenResponse.serializer(),
                        body,
                    )
                }
            }.getOrNull() ?: TokenResponse(error = "network_error", error_description = "网络请求失败")
        }
    }

    /**
     * 用 code + code_verifier 换 access_token。
     *
     * P2-11: 返回完整 [TokenResponse](含 refresh_token / expires_in / scope),
     * 供调用方构造 [TokenBundle] 持久化。
     */
    private suspend fun exchangeCodeForToken(
        config: OAuthConfig,
        code: String,
        codeVerifier: String,
    ): TokenResponse? {
        return withContext(Dispatchers.IO) {
            runCatching {
                val form = FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", config.redirectUri)
                    .add("client_id", config.clientId)
                    .add("code_verifier", codeVerifier)
                    .apply { config.clientSecret?.takeIf { it.isNotBlank() }?.let { add("client_secret", it) } }
                    .build()
                val request = Request.Builder().url(config.tokenUrl).post(form).build()
                httpClient.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Logger.w(TAG, "token 交换失败: HTTP ${resp.code}, body=${body.take(200)}")
                        return@use null
                    }
                    val token = io.zer0.common.AppJson.decodeFromString(
                        TokenResponse.serializer(),
                        body,
                    )
                    if (token.access_token.isBlank()) {
                        Logger.w(TAG, "token 响应无 access_token: error=${token.error}, desc=${token.error_description}")
                        return@use null
                    }
                    token
                }
            }.getOrNull()
        }
    }

    /** 构造 Authorization Code Flow 的授权 URL(含 PKCE + state)。 */
    private fun buildAuthorizationUrl(config: OAuthConfig, codeChallenge: String, state: String): String {
        val params = buildList {
            add("response_type" to "code")
            add("client_id" to config.clientId)
            add("redirect_uri" to config.redirectUri)
            add("code_challenge" to codeChallenge)
            add("code_challenge_method" to "S256")
            add("state" to state)
            if (config.scope.isNotBlank()) add("scope" to config.scope)
        }
        val query = params.joinToString("&") { (k, v) ->
            "$k=${Uri.encode(v)}"
        }
        val separator = if (config.authorizeUrl.contains("?")) "&" else "?"
        return config.authorizeUrl + separator + query
    }

    // ── PKCE 工具(RFC 7636,与 McpOAuthFlow 等价但独立实现) ───────────

    private const val VERIFIER_LENGTH = 64
    private val VERIFIER_CHARS: CharArray = (
        ('A'..'Z').toList() + ('a'..'z').toList() + ('0'..'9').toList() +
            listOf('-', '.', '_', '~')
        ).toCharArray()

    private fun generateCodeVerifier(): String {
        val random = SecureRandom()
        val sb = StringBuilder(VERIFIER_LENGTH)
        repeat(VERIFIER_LENGTH) { sb.append(VERIFIER_CHARS[random.nextInt(VERIFIER_CHARS.size)]) }
        return sb.toString()
    }

    private fun computeCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        // RFC 7636: BASE64URL 编码,去掉 = 填充
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateRandomString(length: Int): String {
        val random = SecureRandom()
        val sb = StringBuilder(length)
        repeat(length) { sb.append(VERIFIER_CHARS[random.nextInt(VERIFIER_CHARS.size)]) }
        return sb.toString()
    }
}

/** OAuth 流程被取消时用的异常(用户主动取消 / 页面退出)。 */
private class OAuthCancelledException : Exception("OAuth 流程已取消")
