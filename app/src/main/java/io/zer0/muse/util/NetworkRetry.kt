package io.zer0.muse.util

import java.io.IOException
import kotlin.random.Random

/**
 * v1.80 (L-NR1): 退避上限(毫秒),防止高 factor/多次重试时 delayMs 溢出 Long 范围。
 * 30 秒封顶 —— 超过此值的退避对用户体验已无意义,不如快速失败。
 */
private const val MAX_DELAY_MS = 30_000L

/**
 * v0.53: 网络请求重试(企业级容错)。
 *
 * 对可能因瞬时网络抖动失败的 IO 操作做指数退避重试:
 *  - 仅捕获 [IOException](网络层错误),业务异常不重试
 *  - 指数退避:initialDelayMs * factor^attempt,避免雪崩
 *  - 最后一次失败时抛出原始异常,保持调用方语义
 *
 * 用法:
 * ```
 * val resp = retryOnNetworkError(maxRetries = 3) {
 *     client.newCall(req).execute()
 * }
 * ```
 *
 * @param maxRetries 最大尝试次数(含首次,即 maxRetries=3 表示最多发 3 次请求)
 * @param initialDelayMs 首次失败后等待时长(毫秒)
 * @param factor 退避因子(每次重试间隔 = 上次间隔 * factor)
 * @param block 实际执行的网络/IO 操作
 * @return block 的返回值
 * @throws IOException 当所有重试均失败时抛出最后一次的 IOException
 */
suspend fun <T> retryOnNetworkError(
    maxRetries: Int = 3,
    initialDelayMs: Long = 500,
    factor: Double = 2.0,
    block: suspend () -> T,
): T {
    require(maxRetries >= 1) { "maxRetries must be >= 1" }
    var lastError: IOException? = null
    var delayMs = initialDelayMs
    repeat(maxRetries) { attempt ->
        try {
            return block()
        } catch (e: IOException) {
            lastError = e
            // 最后一次不等待
            if (attempt < maxRetries - 1) {
                // M4: 添加随机抖动(0-30%),避免多客户端同时重试造成惊群效应
                val jitter = (Random.nextFloat() * 0.3 * delayMs).toLong()
                kotlinx.coroutines.delay(delayMs + jitter)
                // v1.80 (L-NR1): 加 coerceAtMost 上限,防止高 factor/多次重试时 delayMs 溢出 Long 范围
                delayMs = (delayMs * factor).toLong().coerceAtMost(MAX_DELAY_MS)
            }
        }
    }
    throw lastError ?: IOException("retryOnNetworkError: all retries exhausted")
}
