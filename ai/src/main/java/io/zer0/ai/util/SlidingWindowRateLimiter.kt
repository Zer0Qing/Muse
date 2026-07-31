package io.zer0.ai.util

import kotlinx.coroutines.delay

/**
 * P1-3: 滑动窗口限流器。
 *
 * 在 [windowMs] 时间窗口内最多允许 [maxRequests] 次请求。
 * [acquire] 在超限时挂起等待直到窗口滑出空位,而非直接拒绝 ——
 * 对用户对话路径更友好(用户多等几秒,而不是看到"限流"错误)。
 *
 * 实现:用 [ArrayDeque] 记录窗口内每次请求的时间戳。
 *  - acquire 时先清理过期时间戳(早于 now - windowMs)
 *  - 若队列长度 < [maxRequests],追加当前时间戳并返回
 *  - 否则计算最早时间戳滑出窗口所需等待时长,[delay] 后重试
 *
 * 线程安全:所有读写都在 [lock] 同步块内,ArrayDeque 操作极快(微秒级),
 * 锁竞争可忽略。适合 RPM 量级(每秒数十次 acquire)的场景。
 *
 * 与 [io.zer0.ai.util.KeyRoulette] 的关系:
 *  - KeyRoulette 是"反应式"策略:429 返回后切换到下一个 key
 *  - SlidingWindowRateLimiter 是"主动式"策略:请求发出前控制速率
 *  - 两者互补,可叠加使用
 *
 * @param maxRequests 窗口内最大请求数,必须 > 0
 * @param windowMs 窗口大小(毫秒),默认 60 秒(RPM)
 * @param clock 时钟源,可注入用于测试
 */
class SlidingWindowRateLimiter(
    private val maxRequests: Int,
    private val windowMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxRequests > 0) { "maxRequests must be > 0, got $maxRequests" }
        require(windowMs > 0) { "windowMs must be > 0, got $windowMs" }
    }

    /** 窗口内请求时间戳(单调递增,头部最旧)。 */
    private val timestamps = ArrayDeque<Long>()
    private val lock = Any()

    /**
     * 获取一个请求许可。超限时挂起等待直到有空位。
     *
     * 取消语义:在 [delay] 等待期间若协程被取消,会正常传播 CancellationException,
     * 不会消费许可(本次 acquire 未成功追加时间戳)。
     */
    suspend fun acquire() {
        while (true) {
            val waitMs = synchronized(lock) {
                val now = clock()
                val cutoff = now - windowMs
                // 清理过期时间戳(头部最旧)
                while (timestamps.isNotEmpty() && timestamps.first() <= cutoff) {
                    timestamps.removeFirst()
                }
                if (timestamps.size < maxRequests) {
                    // 有空位,追加并返回哨兵值表示已获取
                    timestamps.addLast(now)
                    ACQUIRED
                } else {
                    // 超限,计算最早时间戳滑出窗口的等待时长
                    val oldest = timestamps.first()
                    (oldest + windowMs - now).coerceAtLeast(1L)
                }
            }
            if (waitMs == ACQUIRED) return
            delay(waitMs)
        }
    }

    /** 当前窗口内已记录的请求数(主要用于测试与监控)。 */
    fun currentCount(): Int = synchronized(lock) {
        val now = clock()
        val cutoff = now - windowMs
        while (timestamps.isNotEmpty() && timestamps.first() <= cutoff) {
            timestamps.removeFirst()
        }
        timestamps.size
    }

    private companion object {
        /** acquire 成功时返回的哨兵值(负数不可能作为等待时长)。 */
        const val ACQUIRED = -1L
    }
}
