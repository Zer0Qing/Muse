package io.zer0.muse.web

import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * 搜索限速退避器（单例，进程级内存状态，不持久化）。
 *
 * 参考 openhanako 项目的 search-rate-limiter.ts 实现：
 *  - 内存维护 provider → nextAvailableAt 时间戳
 *  - HTTP 429（Too Many Requests）/ 402（Payment Required，部分搜索 API 用作配额耗尽）时
 *    标记该 provider 被限速，后续请求在退避期内直接被拒绝，避免反复撞墙被封禁
 *  - 支持解析 Retry-After 响应头覆盖默认退避时间
 *
 * 线程安全：使用 [ConcurrentHashMap] 保证多线程读写安全。
 *
 * 用法：
 *  - 调用搜索前先 [await] 检查是否被限速
 *  - HTTP 响应 429/402 时调用 [markRateLimited] 标记
 *  - 或直接用 [assertNotRateLimited] 一步完成检测+标记+抛异常
 */
object SearchRateLimiter {
    /** 默认退避时间：60 秒（无 Retry-After 头时使用）。 */
    private const val DEFAULT_BACKOFF_MS = 60_000L

    /** 最大退避时间：5 分钟（避免单次 Retry-After 过大导致长时间不可用）。 */
    private const val MAX_BACKOFF_MS = 5 * 60 * 1000L

    /** provider 标识 → 下次可调用的时间戳（System.currentTimeMillis()）。 */
    private val nextAvailableAt = ConcurrentHashMap<String, Long>()

    /**
     * 检查 provider 当前是否可调用。
     *
     * @param provider provider 标识（如 "Tavily"/"Bing"/"SearXNG"）
     * @return true=可调用；false=被限速，需等待 [getRetryAfterMs] 毫秒后重试
     */
    fun await(provider: String): Boolean {
        val now = System.currentTimeMillis()
        val until = nextAvailableAt[provider] ?: 0L
        return now >= until
    }

    /**
     * 标记 provider 被限速。
     *
     * @param provider provider 标识
     * @param retryAfterMs 退避时间（毫秒）；超出 [MAX_BACKOFF_MS] 会被截断，负值按 0 处理
     */
    fun markRateLimited(provider: String, retryAfterMs: Long) {
        val backoff = retryAfterMs.coerceIn(0L, MAX_BACKOFF_MS)
        nextAvailableAt[provider] = System.currentTimeMillis() + backoff
    }

    /**
     * 返回 provider 剩余退避时间（毫秒）。
     * 未被限速或退避期已过返回 0。
     */
    fun getRetryAfterMs(provider: String): Long {
        val now = System.currentTimeMillis()
        val until = nextAvailableAt[provider] ?: 0L
        return (until - now).coerceAtLeast(0L)
    }

    /**
     * 从 HTTP 响应头解析 Retry-After。
     *
     * 支持两种格式（RFC 7231）：
     *  1. 整数秒：如 "120"（最常见）
     *  2. HTTP-date：如 "Wed, 21 Oct 2026 07:28:00 GMT"
     *
     * 无该头或解析失败时返回默认 60s。
     *
     * @param headers 响应头 Map（key 大小写不敏感匹配 "Retry-After"）
     */
    fun extractRetryAfterMs(headers: Map<String, String>): Long {
        // 大小写不敏感查找 Retry-After
        val raw = headers.entries
            .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value
            ?: return DEFAULT_BACKOFF_MS

        // 1) 尝试解析为整数秒（HTTP 标准）
        raw.trim().toLongOrNull()?.let { seconds ->
            return (seconds * 1000L).coerceIn(0L, MAX_BACKOFF_MS)
        }

        // 2) 尝试解析为 HTTP-date（RFC 1123）
        // 非 suspend 上下文，runCatching 安全（不会吞 CancellationException）
        runCatching {
            val sdf = java.text.SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                java.util.Locale.US,
            ).apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
            val target = sdf.parse(raw.trim())?.time ?: return DEFAULT_BACKOFF_MS
            val diff = target - System.currentTimeMillis()
            return diff.coerceIn(0L, MAX_BACKOFF_MS)
        }

        return DEFAULT_BACKOFF_MS
    }

    /**
     * 便捷重载：直接从 OkHttp [Response] 提取 Retry-After。
     */
    fun extractRetryAfterMs(resp: Response): Long {
        val headers = resp.headers
        val map: Map<String, String> = headers.names().associateWith { headers[it] ?: "" }
        return extractRetryAfterMs(map)
    }

    /**
     * 检查 HTTP 响应是否为限速（429 / 402），若是则标记 provider 并抛 [SearchRateLimitException]。
     *
     * 供各 Provider 在 `resp.use { }` 块内、`if (!resp.isSuccessful)` 之前调用。
     * 非限速响应原样放行（不抛异常）。
     *
     * @param provider provider 标识
     * @param resp OkHttp 响应
     * @throws SearchRateLimitException 当响应码为 429 或 402 时
     */
    fun assertNotRateLimited(provider: String, resp: Response) {
        val code = resp.code
        if (code != 429 && code != 402) return
        val retryAfterMs = extractRetryAfterMs(resp)
        markRateLimited(provider, retryAfterMs)
        throw SearchRateLimitException(
            cause = "$provider HTTP $code",
            retryAfterMs = retryAfterMs,
        )
    }

    /** 清除指定 provider 的限速状态（仅供测试或手动重置用）。 */
    fun clear(provider: String) {
        nextAvailableAt.remove(provider)
    }

    /** 清除所有 provider 的限速状态。 */
    fun clearAll() {
        nextAvailableAt.clear()
    }
}

/**
 * 搜索被限速异常。
 *
 * 由 [SearchRateLimiter] 在以下场景抛出：
 *  1. [SearchRateLimiter.await] 返回 false（退避期内重复调用）
 *  2. HTTP 响应 429/402（[SearchRateLimiter.assertNotRateLimited] 检测到）
 *
 * UI 层捕获后向用户展示友好提示："搜索服务被限速，请 X 秒后重试"。
 *
 * @param cause 限速原因描述（如 provider 名 + HTTP 状态码），仅用于构造消息
 * @param retryAfterMs 建议重试等待时间（毫秒），UI 据此计算"X 秒后重试"
 */
class SearchRateLimitException(
    cause: String,
    val retryAfterMs: Long,
) : RuntimeException("Search rate limited: $cause, retry after ${retryAfterMs}ms")
