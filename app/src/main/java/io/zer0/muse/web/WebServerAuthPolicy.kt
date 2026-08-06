package io.zer0.muse.web

/**
 * R-TEST-12: WebServer 登录限流与 JWT 纯逻辑。
 */
internal object WebServerAuthPolicy {

    fun isWindowExpired(nowMs: Long, firstAttemptAt: Long, windowMs: Long): Boolean =
        nowMs - firstAttemptAt > windowMs

    fun isRateLimited(nowMs: Long, firstAttemptAt: Long, count: Int, windowMs: Long, maxFailures: Int): Boolean =
        !isWindowExpired(nowMs, firstAttemptAt, windowMs) && count >= maxFailures

    fun remainingSeconds(nowMs: Long, firstAttemptAt: Long, windowMs: Long): Long {
        if (isWindowExpired(nowMs, firstAttemptAt, windowMs)) return 0L
        return ((firstAttemptAt + windowMs - nowMs) / 1000).coerceAtLeast(1)
    }
}
