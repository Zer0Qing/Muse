package io.zer0.muse.web

import android.content.Context
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.AuthScheme
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.http.auth.parseAuthorizationHeader
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.BuildConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.notification.MuseNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import io.zer0.muse.R

/**
 * Phase 8.11: 嵌入式 Web 服务器(Ktor CIO + JWT + mDNS)。
 *
 * 功能:
 *  - 在局域网内暴露 REST API,允许 PC/其他设备浏览会话与设置
 *  - JWT 鉴权(密码 → HMAC-SHA256 签名 token,24h 有效期)
 *  - mDNS 注册(同 Wi-Fi 设备可通过 Bonjour/Avahi 发现)
 *  - 常驻通知显示运行状态(点击回到 App)
 *
 * P2-13:WebServer PIN 安全
 *  - 启动时生成 6 位数字 PIN(随机),用于 Web 端首次访问验证
 *  - Web 端通过 `POST /api/auth/pin-login` 提交 PIN,服务端校验后签发 JWT
 *  - JWT 仅通过 Cookie(`token`)与登录响应体(Authorization 头)下发,后续请求无需再次输入
 *  - C-29/C-11: 不再经 query param(`?token=`)新发 JWT(query 会进浏览器历史/Referer/访问日志);
 *    受保护路由的 JWT 鉴权仅读取 Authorization 头与 `token` Cookie,已彻底移除 `?token=` 读取路径
 *    (服务端 Web UI 完全基于 httpOnly Cookie,`?token=` 残留只会造成 token 泄漏面,已无兼容价值)
 *
 * 路由:
 *  - `GET  /api/health` — 健康检查(无需鉴权;LAN 对外时收敛 version/uptime 字段)
 *  - `POST /api/auth/login` — 密码换 JWT(无需鉴权)
 *  - `POST /api/auth/pin-login` — PIN 换 JWT(无需鉴权,设置 Cookie)
 *  - `POST /api/auth/logout` — 退出登录,过期 `token` Cookie(A-10,无需鉴权)
 *  - `GET  /api/sessions` — 会话列表(JWT 鉴权)
 *  - `GET  /api/sessions/{id}/messages` — 会话消息列表(JWT 鉴权)
 *  - `GET  /api/settings` — 设置摘要(Provider 名称/模型数,密钥脱敏)(JWT 鉴权)
 *
 * 引擎选择:
 *  - CIO(Coroutine I/O) — 纯 Kotlin 协程实现,无 JNI 依赖,适合 Android
 *  - 相比 Netty(~4MB)体积极小,功能足够(不支持 WebSocket,但本场景不需要)
 *
 * 安全:
 *  - 默认绑定 `127.0.0.1`;开启局域网访问后才绑定 `0.0.0.0`(所有网卡)
 *  - R-SEC-03: JWT 使用独立随机签名密钥,与用户密码分离
 *  - 所有受保护路由需 `Authorization: Bearer <token>` 头
 *  - 密码为空时自动生成 8 位随机密码并持久化(首次启用时)
 *  - PIN 为空时自动生成 6 位随机 PIN 并持久化(首次启用时)
 */
class WebServer(
    private val settings: SettingsRepository,
    private val sessionRepo: SessionRepository,
    private val notificationManager: MuseNotificationManager,
    private val mdnsService: MdnsService,
    private val context: Context,
) {
    @Volatile
    private var server: EmbeddedServer<*, *>? = null
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    @Volatile
    private var currentPort: Int = 0
    @Volatile
    private var currentPassword: String = ""
    @Volatile
    private var currentPin: String = ""

    /** 启动时间戳(用于 /api/health 的 uptime 计算)。 */
    @Volatile
    private var startedAt: Long = 0L

    /**
     * 启动 Web 服务器。
     * @return true 启动成功,false 启动失败(端口占用 / 配置异常)
     */
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (server != null) {
            Logger.w(TAG, "WebServer 已在运行中,跳过重复启动")
            return@withContext true
        }
        val config = settings.webServerConfigFlow.first()
        val port = config.port.coerceIn(MIN_PORT, MAX_PORT)
        var password = config.password
        // 首次启动: 生成随机密码并持久化,让用户在设置页可见
        if (password.isBlank()) {
            password = WebServerConfig.generateRandomPassword()
        }
        // P2-13: 首次启动生成 6 位 PIN,持久化后让用户在设置页可见
        var pin = config.pin
        if (pin.isBlank()) {
            pin = WebServerConfig.generateRandomPin()
        }
        // R-SEC-03: 独立 JWT 签名密钥,首次启动生成并持久化
        var jwtSecret = config.jwtSecret
        if (jwtSecret.isBlank()) {
            jwtSecret = WebServerConfig.generateRandomJwtSecret()
        }
        if (password != config.password || pin != config.pin || jwtSecret != config.jwtSecret) {
            settings.saveWebServerConfig(config.copy(password = password, pin = pin, jwtSecret = jwtSecret))
        }

        // M11: startedAt 在 it.start() 之前赋值,避免 museRoutes 捕获到 0 导致 uptime 计算错误
        startedAt = System.currentTimeMillis()
        // M4: runCatching 改为 resultOf;M14: withContext(Dispatchers.IO) 保证配置读取不在主线程
        resultOf {
            server = embeddedServer(CIO, port = port, host = WebServer.bindHost(config.allowLan)) {
                configureSecurity(jwtSecret)
                configureSerialization()
                configureCors(config.allowLan)
                configureStatusPages()
                museRoutes(password, jwtSecret, pin, sessionRepo, settings, config.allowLan)
            }.also { it.start(wait = false) }
            currentPort = port
            currentPassword = password
            currentPin = pin
            _isRunning.value = true
            mdnsService.register(port)
            notificationManager.updateWebServerStatus(port, true)
            // H4: 不再明文输出密码/PIN,避免日志泄露敏感凭据
            Logger.i(TAG, "WebServer 已启动: ${WebServer.bindHost(config.allowLan)}:$port")
            true
        }.onError { msg, t ->
            Logger.e(TAG, "WebServer 启动失败: $msg", t)
            server = null
            startedAt = 0L
        }.getOrNull() ?: false
    }

    /** 停止 Web 服务器(幂等,未运行时无操作)。M13: 改为 suspend,阻塞的 it.stop() 切到 IO 线程。 */
    suspend fun stop() {
        server?.let {
            // M5: runCatching 改为 resultOf;M13: it.stop() 是阻塞操作,用 withContext(Dispatchers.IO) 包裹
            resultOf { withContext(Dispatchers.IO) { it.stop(GRACE_PERIOD_MS, TIMEOUT_PERIOD_MS) } }
            server = null
        }
        if (_isRunning.value) {
            _isRunning.value = false
            mdnsService.unregister()
            notificationManager.updateWebServerStatus(currentPort, false)
            Logger.i(TAG, "WebServer 已停止")
        }
        currentPort = 0
        currentPassword = ""
        currentPin = ""
        startedAt = 0L
    }

    /** 获取当前运行状态信息(供 UI 显示)。M9: password/pin 脱敏,仅返回布尔 + 脱敏字符串。 */
    fun status(): Status {
        return Status(
            isRunning = _isRunning.value,
            port = currentPort,
            hasPassword = currentPassword.isNotBlank(),
            hasPin = currentPin.isNotBlank(),
            passwordMasked = maskSecret(currentPassword),
            pinMasked = maskSecret(currentPin),
            startedAt = startedAt,
        )
    }

    /** 脱敏处理:保留前 2 位,其余用 * 替换。空串原样返回。 */
    private fun maskSecret(secret: String): String {
        if (secret.isBlank()) return ""
        if (secret.length <= 2) return "*".repeat(secret.length)
        return secret.take(2) + "*".repeat(secret.length - 2)
    }

    // ── Ktor 配置(Application 扩展函数) ──────────────────────────────────

    /**
     * JWT 鉴权配置。
     * P2-13: 通过 `authHeader` 自定义 token 提取,支持两种来源:
     *  1. `Authorization: Bearer <token>` 头(原有行为)
     *  2. `token` Cookie(PIN/密码登录后由浏览器自动携带)
     *  C-11: 已移除对 `?token=` query param 的读取 — 服务端 Web UI 完全基于 httpOnly Cookie,
     *  `?token=` 残留只会让 JWT 进入浏览器历史/访问日志/Referer,不再作任何兼容读取。
     */
    private fun Application.configureSecurity(jwtSecret: String) {
        val algorithm = Algorithm.HMAC256(jwtSecret)
        install(Authentication) {
            jwt(AUTH_JWT_NAME) {
                realm = REALM
                verifier(JWT.require(algorithm).build())
                // 自定义 token 提取: Authorization 头 → Cookie(C-11: 不再读取 query param)
                authHeader { call ->
                    val rawAuthHeader = call.request.headers[io.ktor.http.HttpHeaders.Authorization]
                    if (rawAuthHeader != null) {
                        // M6: runCatching 改为 resultOf,避免吞没 CancellationException
                        resultOf { parseAuthorizationHeader(rawAuthHeader) }.getOrNull()
                    } else {
                        // C-11: Cookie 是唯一替代通道(httpOnly,由浏览器自动携带)
                        val token = call.request.cookies[TOKEN_COOKIE_NAME]
                        if (token != null) {
                            HttpAuthHeader.Single(AuthScheme.Bearer, token)
                        } else {
                            null
                        }
                    }
                }
                validate { credential ->
                    val sub = credential.payload.getClaim(CLAIM_SUB).asString()
                    if (sub == CLAIM_SUB_VALUE) {
                        JWTPrincipal(credential.payload)
                    } else {
                        null
                    }
                }
            }
        }
    }

    /** JSON 序列化配置(复用 AppJson,忽略未知字段)。 */
    private fun Application.configureSerialization() {
        install(ContentNegotiation) {
            json(AppJson)
        }
    }

    /**
     * CORS 配置。
     * R-SEC-03: 默认仅允许本机来源;开启局域网访问后才允许任意 Origin。
     */
    private fun Application.configureCors(allowLan: Boolean) {
        install(CORS) {
            // 安全边界：anyHost() 仅适用于局域网信任模型，公网暴露时需配置 Origin 白名单
            if (allowLan) {
                // 安全边界：anyHost() 仅适用于局域网信任模型
                anyHost()
            } else {
                allowHost("localhost", schemes = listOf("http", "https"))
                allowHost("127.0.0.1", schemes = listOf("http", "https"))
            }
            allowMethod(io.ktor.http.HttpMethod.Get)
            allowMethod(io.ktor.http.HttpMethod.Post)
            allowMethod(io.ktor.http.HttpMethod.Options)
            allowHeader(io.ktor.http.HttpHeaders.ContentType)
            allowHeader(io.ktor.http.HttpHeaders.Authorization)
        }
    }

    /** 全局异常处理(返回 JSON 错误响应,避免 Ktor 默认纯文本)。 */
    private fun Application.configureStatusPages() {
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                if (cause is kotlin.coroutines.cancellation.CancellationException) throw cause
                Logger.e(TAG, "WebServer 未捕获异常: ${cause.message}")
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("internal_error", cause.message ?: context.getString(R.string.webserver_unknown_error)),
                )
            }
        }
    }

    // ── 路由 ──────────────────────────────────────────────────────────────

    /**
     * 所有 API 路由定义。
     * P2-13: 新增 `POST /api/auth/pin-login`,接受 6 位 PIN,校验通过后签发 JWT,
     * 并通过 Cookie 下发,Web 端后续请求无需再带 PIN。
     */
    private fun Application.museRoutes(
        password: String,
        jwtSecret: String,
        pin: String,
        sessionRepo: SessionRepository,
        settings: SettingsRepository,
        allowLan: Boolean,
    ) {
        val algorithm = Algorithm.HMAC256(jwtSecret)

        routing {
            // 健康检查(无需鉴权,用于 mDNS 客户端探测)
            // C-18: 开启局域网访问(bind 0.0.0.0)时收敛返回字段 — 版本号/运行时长属于精确指纹信息,
            //   同时可能被局域网内第三方探测,仅在本机/非 LAN 调试模式下返回;健康状态本身始终可用。
            get("/api/health") {
                // M11: 直接引用 this@WebServer.startedAt,避免局部变量在 start() 前捕获到 0
                val health = if (allowLan) {
                    HealthResponse(status = "ok")
                } else {
                    HealthResponse(
                        status = "ok",
                        version = BuildConfig.VERSION_NAME,
                        uptime = System.currentTimeMillis() - this@WebServer.startedAt,
                    )
                }
                call.respond(health)
            }

            // 退出登录: A-10 前端 httpOnly Cookie 无法被 JS 删除,需经服务端过期 Set-Cookie 清理。
            // 无需鉴权(已登录态下浏览器自动带 Cookie,未登录调用亦幂等返回成功),不参与速率限制。
            post("/api/auth/logout") {
                call.expireTokenCookie()
                call.respond(LogoutResponse(loggedOut = true))
            }

            // 登录: 密码换 JWT
            post("/api/auth/login") {
                // H5: 速率限制 — 每 IP 每 30 秒最多 5 次失败尝试
                val clientIp = call.request.local.remoteHost
                if (!checkRateLimit(clientIp)) {
                    val remaining = getRateLimitRemainingSeconds(clientIp)
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate_limited", context.getString(R.string.webserver_rate_limited_remaining, remaining)))
                    return@post
                }
                val req = resultOf { call.receive<LoginRequest>() }
                    .onError { msg, _ ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", context.getString(R.string.webserver_bad_request, msg)))
                    }.getOrNull()
                if (req == null) return@post
                if (!MessageDigest.isEqual(req.password.toByteArray(Charsets.UTF_8), password.toByteArray(Charsets.UTF_8))) {
                    recordFailedAttempt(clientIp)
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("auth_failed", context.getString(R.string.webserver_auth_failed)))
                    return@post
                }
                clearAttempts(clientIp)
                val token = issueJwt(algorithm)
                // C-29: 密码登录同样通过 Set-Cookie 下发 JWT,与 PIN 登录一致,避免 token 进 query/URL。
                //   响应体仍返回 token 供非浏览器客户端(如脚本)取用;Web 端走 httpOnly Cookie。
                call.appendTokenCookie(token)
                call.respond(LoginResponse(token = token, expiresIn = TOKEN_TTL_MS / 1000))
            }

            // P2-13: PIN 登录 — 6 位 PIN 换 JWT,同时设置 Cookie
            post("/api/auth/pin-login") {
                // H5: 速率限制 — 每 IP 每 30 秒最多 5 次失败尝试
                val clientIp = call.request.local.remoteHost
                if (!checkRateLimit(clientIp)) {
                    val remaining = getRateLimitRemainingSeconds(clientIp)
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate_limited", context.getString(R.string.webserver_rate_limited_remaining, remaining)))
                    return@post
                }
                val req = resultOf { call.receive<PinLoginRequest>() }
                    .onError { msg, _ ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", context.getString(R.string.webserver_bad_request, msg)))
                    }.getOrNull()
                if (req == null) return@post
                if (!pin.matches(PIN_REGEX) || !MessageDigest.isEqual(req.pin.toByteArray(Charsets.UTF_8), pin.toByteArray(Charsets.UTF_8))) {
                    recordFailedAttempt(clientIp)
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("auth_failed", context.getString(R.string.webserver_pin_error)))
                    return@post
                }
                clearAttempts(clientIp)
                val token = issueJwt(algorithm)
                // C-29: token 仅经 Cookie 下发,不进 query/URL;Web 端浏览器后续请求自动携带。
                call.appendTokenCookie(token)
                call.respond(LoginResponse(token = token, expiresIn = TOKEN_TTL_MS / 1000))
            }

            // 以下路由需要 JWT 鉴权
            authenticate(AUTH_JWT_NAME) {

                // 会话列表
                get("/api/sessions") {
                    // M12: Flow.first() 加超时,避免长时间阻塞请求
                    val sessions = withTimeout(5_000L) { sessionRepo.observeSessions().first() }
                    call.respond(sessions.map { it.toDto() })
                }

                // 会话消息列表
                get("/api/sessions/{id}/messages") {
                    val id = call.parameters["id"]
                    if (id.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", context.getString(R.string.webserver_missing_session_id)))
                        return@get
                    }
                    // M12: Flow.first() 加超时
                    val messages = withTimeout(5_000L) { sessionRepo.observeMessages(id).first() }
                    call.respond(messages.map { it.toDto() })
                }

                // 设置摘要(Provider 名称 + 模型数,密钥脱敏)
                get("/api/settings") {
                    // M12: Flow.first() 加超时
                    val providers = withTimeout(5_000L) { settings.providersFlow.first() }
                    val activeId = withTimeout(5_000L) { settings.activeProviderIdFlow.first() }
                    val modelId = withTimeout(5_000L) { settings.selectedModelIdFlow.first() }
                    call.respond(
                        SettingsSummary(
                            providers = providers.map { it.toSummaryDto() },
                            activeProviderId = activeId,
                            selectedModelId = modelId,
                        )
                    )
                }

                // v1.120: POST /api/send 端点设计决策 — 不实现。
                //   WebServer 定位为只读浏览(会话/消息/设置),不支持从 Web 端发起新消息。
                //   原因:发送消息需调用 ChatViewModel.sendMessage 逻辑,涉及 LLM 流式响应 +
                //   SessionRepository 写库 + 工具调度,依赖链较重,且 Web 端缺少流式 SSE 转发方案,
                //   强行实现易引入新 bug。如需 Web 发消息,建议后续单独设计 WebSocket 通道。
            }

            // 根路径: 完整 Web UI(PIN 登录 → 会话列表 → 消息只读浏览)
            // P2-13: 单页内嵌 HTML/CSS/JS,无外部依赖;鉴权走 Cookie(httpOnly)
            get("/") {
                // L1: 添加 Referrer-Policy 头,防止 JWT query param 通过 Referer 头泄露到第三方
                call.response.headers.append("Referrer-Policy", "no-referrer")
                call.respondText(WebServerUi.INDEX_HTML, io.ktor.http.ContentType.Text.Html)
            }
        }
    }

    /** 签发 24h 有效期的 JWT,与密码登录共用同一签名密钥(password → HMAC-SHA256)。 */
    private fun issueJwt(algorithm: Algorithm): String {
        return JWT.create()
            .withSubject(CLAIM_SUB_VALUE)
            .withIssuedAt(Date())
            .withExpiresAt(Date(System.currentTimeMillis() + TOKEN_TTL_MS))
            .sign(algorithm)
    }

    /**
     * C-29: 通过 httpOnly Cookie 下发 JWT(不进 query/URL,避免进浏览器历史/Referer/访问日志)。
     * 当前为 HTTP 服务,Cookie 无法设 secure=true(JWT 仅限局域网信任模型);
     * A-10/C-11: Ktor 3 无独立 sameSite 参数,经 extensions 写入 `SameSite=Lax`,
     *   缓解 CSRF 场景下 Cookie 被跨站请求携带的风险(登录仅经浏览器同源请求);
     * TODO: HTTPS 启用时改为 secure=true。
     */
    private fun ApplicationCall.appendTokenCookie(token: String) {
        response.cookies.append(
            name = TOKEN_COOKIE_NAME,
            value = token,
            maxAge = TOKEN_TTL_MS / 1000,
            path = "/",
            httpOnly = true,
            secure = false,
            extensions = mapOf("SameSite" to "Lax"),
        )
    }

    /**
     * A-10: 退出登录时过期 `token` Cookie(Set-Cookie: token=; Max-Age=0; Path=/),
     * 因为该 Cookie httpOnly=true,浏览器 JS 无法读取/删除,必须由服务端下发过期指令。
     * SameSite 属性在过期 Cookie 上无实际意义,但保持与登录 Cookie 一致以防解析差异。
     */
    private fun ApplicationCall.expireTokenCookie() {
        response.cookies.append(
            name = TOKEN_COOKIE_NAME,
            value = "",
            maxAge = 0L,
            path = "/",
            httpOnly = true,
            secure = false,
            extensions = mapOf("SameSite" to "Lax"),
        )
    }

    // ── DTO 转换 ──────────────────────────────────────────────────────────

    private fun SessionEntity.toDto(): SessionDto = SessionDto(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastMessagePreview = lastMessagePreview,
        assistantId = assistantId,
        pinned = pinned,
    )

    private fun UIMessage.toDto(): MessageDto = MessageDto(
        id = id.toString(),
        role = role.name,
        content = content,
        reasoning = reasoning,
        modelId = modelId,
        createdAt = createdAt,
    )

    private fun io.zer0.ai.core.ProviderConfig.toSummaryDto(): ProviderSummaryDto =
        ProviderSummaryDto(
            id = id,
            name = displayName,
            type = type.name,
            modelCount = models.size,
            enabled = enabled,
        )

    // ── DTO 数据类 ────────────────────────────────────────────────────────

    data class Status(
        val isRunning: Boolean,
        val port: Int,
        val hasPassword: Boolean,
        val hasPin: Boolean,
        val passwordMasked: String,
        val pinMasked: String,
        val startedAt: Long,
    )

    @Serializable
    data class HealthResponse(val status: String, val version: String? = null, val uptime: Long? = null)

    @Serializable
    data class LoginRequest(val password: String)

    /** P2-13: PIN 登录请求体(6 位数字 PIN)。 */
    @Serializable
    data class PinLoginRequest(val pin: String)

    @Serializable
    data class LoginResponse(val token: String, val expiresIn: Long)

    /** A-10: 退出登录响应(前端据此确认 Cookie 已清除)。 */
    @Serializable
    data class LogoutResponse(val loggedOut: Boolean)

    @Serializable
    data class SessionDto(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val lastMessagePreview: String,
        val assistantId: String,
        val pinned: Boolean,
    )

    @Serializable
    data class MessageDto(
        val id: String,
        val role: String,
        val content: String,
        val reasoning: String?,
        val modelId: String?,
        val createdAt: Long,
    )

    @Serializable
    data class ProviderSummaryDto(
        val id: String,
        val name: String,
        val type: String,
        val modelCount: Int,
        val enabled: Boolean,
    )

    @Serializable
    data class SettingsSummary(
        val providers: List<ProviderSummaryDto>,
        val activeProviderId: String?,
        val selectedModelId: String?,
    )

    @Serializable
    data class ErrorResponse(val error: String, val message: String)

    companion object {
        private const val TAG = "WebServer"
        private const val LAN_BIND_HOST = "0.0.0.0"
        private const val LOCAL_BIND_HOST = "127.0.0.1"

        /** R-SEC-03: 默认仅本机,显式开启局域网访问后才绑定所有网卡。 */
        fun bindHost(allowLan: Boolean): String = if (allowLan) LAN_BIND_HOST else LOCAL_BIND_HOST
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535
        private const val GRACE_PERIOD_MS = 1000L
        private const val TIMEOUT_PERIOD_MS = 2000L
        private const val AUTH_JWT_NAME = "auth-jwt"
        private const val REALM = "muse-web"
        private const val CLAIM_SUB = "sub"
        private const val CLAIM_SUB_VALUE = "muse-web"
        private const val TOKEN_TTL_MS = 24L * 60 * 60 * 1000 // 24 小时

        // P2-13: Web 端 PIN 安全相关
        /** PIN/密码登录后下发的 Cookie 名,Web 端浏览器会自动携带。 */
        private const val TOKEN_COOKIE_NAME = "token"
        /** 6 位数字 PIN 校验正则。 */
        private val PIN_REGEX = Regex("^\\d{6}$")

        // H5: 登录速率限制 — 每 IP 每 30 秒最多 5 次失败尝试,超限返回 429
        private const val RATE_LIMIT_WINDOW_MS = 30_000L
        private const val RATE_LIMIT_MAX_FAILURES = 5
        private val loginAttempts = ConcurrentHashMap<String, AttemptTracker>()

        /** 速率限制跟踪器:记录失败次数与首次尝试时间。 */
        private class AttemptTracker(var count: Int, var firstAttemptAt: Long)

        /** 检查是否超过速率限制(返回 true 表示允许请求)。 */
        private fun checkRateLimit(ip: String): Boolean {
            val now = System.currentTimeMillis()
            // 清理超过 30s 窗口的过期 entry,避免从未成功登录的 IP 永久堆积导致内存泄漏
            loginAttempts.entries.removeIf { (_, attempt) ->
                now - attempt.firstAttemptAt > RATE_LIMIT_WINDOW_MS
            }
            val tracker = loginAttempts[ip] ?: return true
            synchronized(tracker) {
                if (WebServerAuthPolicy.isWindowExpired(now, tracker.firstAttemptAt, RATE_LIMIT_WINDOW_MS)) {
                    tracker.count = 0
                    tracker.firstAttemptAt = now
                    return true
                }
                return !WebServerAuthPolicy.isRateLimited(
                    now, tracker.firstAttemptAt, tracker.count, RATE_LIMIT_WINDOW_MS, RATE_LIMIT_MAX_FAILURES,
                )
            }
        }

        /** 记录一次失败尝试。 */
        private fun recordFailedAttempt(ip: String) {
            val now = System.currentTimeMillis()
            val tracker = loginAttempts.getOrPut(ip) { AttemptTracker(0, now) }
            synchronized(tracker) {
                if (WebServerAuthPolicy.isWindowExpired(now, tracker.firstAttemptAt, RATE_LIMIT_WINDOW_MS)) {
                    tracker.count = 1
                    tracker.firstAttemptAt = now
                } else {
                    tracker.count++
                }
            }
        }

        /** 清除指定 IP 的失败记录(登录成功时调用)。 */
        private fun clearAttempts(ip: String) {
            loginAttempts.remove(ip)
        }

        /** 返回当前 IP 剩余的锁定秒数(未锁定返回 0)。 */
        private fun getRateLimitRemainingSeconds(ip: String): Long {
            val now = System.currentTimeMillis()
            val tracker = loginAttempts[ip] ?: return 0L
            synchronized(tracker) {
                return WebServerAuthPolicy.remainingSeconds(now, tracker.firstAttemptAt, RATE_LIMIT_WINDOW_MS)
            }
        }
    }
}
