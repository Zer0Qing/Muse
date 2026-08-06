package io.zer0.muse.auth

/**
 * R-TEST-03: PIN 失败退避与锁定策略纯逻辑。
 *
 * 前 5 次失败不锁定;从第 5 次起每次递增 30s × 2^n(指数退避),上限 20 级防溢出。
 */
internal object PinLockPolicy {

    const val MAX_FAILS_BEFORE_LOCK = 5
    private const val BASE_DELAY_MS = 30_000L
    private const val MAX_SHIFT = 20

    fun lockDelayMs(failCount: Int): Long {
        if (failCount < MAX_FAILS_BEFORE_LOCK) return 0L
        val shift = (failCount - MAX_FAILS_BEFORE_LOCK).coerceAtMost(MAX_SHIFT)
        return BASE_DELAY_MS * (1L shl shift)
    }

    fun isLocked(nowMs: Long, lockUntil: Long): Boolean = nowMs < lockUntil
}
