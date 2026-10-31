package io.zer0.muse.web

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpHeaders
import io.ktor.http.auth.AuthScheme
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.http.auth.parseAuthorizationHeader
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer
import io.zer0.common.Logger

/** JWT 的 subject：签发侧写它，校验侧比它。 */
internal const val HOST_AUTH_SUBJECT = "muse-web"

/** subject 的 claim 名。 */
internal const val HOST_AUTH_SUBJECT_CLAIM = "sub"

/** 登录后下发的 Cookie 名，浏览器会自动携带。 */
internal const val HOST_AUTH_COOKIE = "token"

/**
 * Host Mode 的 JWT 凭证闸门。
 *
 * 用 ktor-server-auth 自带的 `bearer` provider + `com.auth0.jwt` 手动校验，不用 ktor 的
 * `jwt()` provider：那个模块在取凭证/失败路径上引用 `com.auth0.jwk.JwkException`，而
 * `jwks-rsa` 已被排除（它拉 guava，与 AndroidX 的 listenablefuture 能力冲突）。
 * 结果是取凭证这一步就 `NoClassDefFoundError` —— 所有受保护路由变成 500 而不是 401，
 * `/ws` 握手表现为「连不上」。我们只用 HMAC-SHA256 对称签名，不需要 JWKS/RSA 轮换，
 * 自己校验只有一条明确路径。
 *
 * 语义与原来一致：没有凭证、签名不符、已过期、subject 不对，一律 401。
 */
internal fun AuthenticationConfig.installHostAuthProvider(
    providerName: String,
    realm: String,
    algorithm: Algorithm,
    cookieName: String = HOST_AUTH_COOKIE,
) {
    val verifier = JWT.require(algorithm).build()
    bearer(providerName) {
        this.realm = realm
        // 两种携带方式都认：Authorization 头优先，其次 httpOnly Cookie（浏览器自动带）。
        authHeader { call -> hostAuthHeader(call, cookieName) }
        authenticate { credential -> verifyHostToken(credential.token, verifier) }
    }
}

/**
 * 从 Authorization 头或 `token` Cookie 取出 bearer 凭证。
 *
 * 头能解析就用的；不是 Bearer 形状就当没带，交给 provider 走 401。
 */
internal fun hostAuthHeader(call: ApplicationCall, cookieName: String = HOST_AUTH_COOKIE): HttpAuthHeader? {
    val raw = call.request.headers[HttpHeaders.Authorization]
    if (raw != null) {
        (parseAuthorizationHeader(raw) as? HttpAuthHeader.Single)?.let { return it }
    }
    return call.request.cookies[cookieName]
        ?.takeIf { it.isNotBlank() }
        ?.let { HttpAuthHeader.Single(AuthScheme.Bearer, it) }
}

/**
 * 校验一枚 token。
 *
 * @return 通过时返回 principal；过期、签名不符、格式非法、subject 不对都返回 null，
 *   由 provider 统一回 401，不把异常漏成 500。返回值用 [Any] 接收是跟
 *   `bearer` provider 的 `authenticate` 签名对齐（[UserIdPrincipal] 是 java 侧 Principal）。
 */
internal fun verifyHostToken(token: String?, verifier: JWTVerifier, subject: String = HOST_AUTH_SUBJECT): Any? {
    if (token.isNullOrBlank()) return null
    val decoded = runCatching { verifier.verify(token) }.getOrElse { error ->
        Logger.d(TAG, "JWT 校验未通过: ${error.javaClass.simpleName}")
        return null
    }
    return if (decoded.getClaim(HOST_AUTH_SUBJECT_CLAIM).asString() == subject) UserIdPrincipal(subject) else null
}

private const val TAG = "HostAuth"
