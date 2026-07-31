package io.zer0.muse.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * v1.0.53: 子 agent 全局并发限流器(对标 Hana workflow 的 createLimiter)。
 *
 * - 信号量语义:maxConcurrent 同时执行,超出排队(轮询等待)
 * - backstop:累计派发总数超过 maxTotal 直接拒绝(防失控)
 * - 全局单例(经 Koin 注入),所有委派入口共享同一配额
 *   - TeamWorkflowExecutor.executeParallel(团队并行节点)
 *   - SkillExecutor.delegateAgent nonBlocking 路径(子助手异步委派)
 *   - SubagentRunner.run(被动子 agent 阻塞多轮工具循环)
 *
 * 实现说明:
 *  - 用 Mutex 保护 active / totalSpawned 计数,避免 lost update
 *  - 等待采用轮询(100ms),手机端足够;若需更低延迟可改 Channel<Unit> 槽位广播
 *  - release() 必须加锁递减 — 并发 release 时裸递减会丢失更新,导致 active 计数错误
 */
class AgentConcurrencyLimiter(
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    private val maxTotal: Int = DEFAULT_MAX_TOTAL,
) {
    companion object {
        /** 参考 Hana:256;手机端保守取 8(本地模型并发能力有限)。 */
        const val DEFAULT_MAX_CONCURRENT = 8
        /** 单次应用生命周期内累计派发上限,防失控。 */
        const val DEFAULT_MAX_TOTAL = 200
        /** 等待槽位时的轮询间隔。 */
        private const val POLL_INTERVAL_MS = 100L
    }

    private val mutex = Mutex()
    private val active = java.util.concurrent.atomic.AtomicInteger(0)
    private var totalSpawned = 0

    /** 当前活跃数(原子读,诊断用)。 */
    val activeCount: Int get() = active.get()

    /**
     * 执行 [block],若并发已满则挂起等待。
     *
     * @throws IllegalStateException 超出 maxTotal backstop 时
     */
    suspend fun <T> run(block: suspend () -> T): T {
        mutex.withLock {
            totalSpawned++
            if (totalSpawned > maxTotal) {
                throw IllegalStateException(
                    "子 agent 累计派发超过上限 $maxTotal(防失控 backstop)"
                )
            }
        }
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    private suspend fun acquire() {
        // 快速路径:首次即有空槽
        mutex.withLock {
            if (active.get() < maxConcurrent) {
                active.incrementAndGet()
                return
            }
        }
        // 慢速路径:轮询等待
        while (true) {
            delay(POLL_INTERVAL_MS)
            mutex.withLock {
                if (active.get() < maxConcurrent) {
                    active.incrementAndGet()
                    return
                }
            }
        }
    }

    /**
     * 必须加锁递减。
     *
     * 并发 release 时裸递减(active--)会丢失更新,导致 active 计数错误
     * (可能变为负数或永远卡在 maxConcurrent,使信号量失效)。
     * 复合操作(检查+增减)用 Mutex 互斥,读取用 AtomicInteger 保证可见性。
     */
    private suspend fun release() {
        mutex.withLock {
            if (active.get() > 0) active.decrementAndGet()
        }
    }
}
