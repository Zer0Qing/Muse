package io.zer0.muse.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * v1.0.53: 子 agent 全局并发限流器。
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
        /** 上限 256;手机端保守取 8(本地模型并发能力有限)。 */
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
     * 后台任务正在占用的槽位数。
     *
     * V20 要求"前台优先，后台排队"。用**预留槽位**实现，而不是改排队顺序：
     * 排队顺序对用户是不可见的，而槽位预留是硬保证 —— 无论后台排队多久，
     * 前台永远至少有一个槽可用。
     */
    private val backgroundActive = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 给前台预留的槽位数。
     *
     * 取 1：手机端并发上限 8，留 1 个给前台就足以让"用户发一句话"不必等
     * 后台任务跑完。留多了会让后台几乎排不上队。
     */
    private val reservedForForeground: Int = 1

    /**
     * 以"后台"身份执行：只能占用非预留槽位。
     *
     * 中枢的后台任务走这里。前台（用户正在等的回复）走 [run]，
     * 因此前台永远有槽可用 —— 这正是"用户发 B 时 B 不被 A 挡住"的落点。
     */
    suspend fun <T> runAsBackground(block: suspend () -> T): T {
        mutex.withLock {
            totalSpawned++
            if (totalSpawned > maxTotal) {
                throw IllegalStateException(
                    "子 agent 累计派发超过上限 $maxTotal(防失控 backstop)",
                )
            }
        }
        acquireBackground()
        try {
            return block()
        } finally {
            backgroundActive.decrementAndGet()
            release()
        }
    }

    /**
     * 后台获取槽位：可用数必须严格小于 (上限 - 预留)。
     *
     * 前台活跃时不阻塞后台（那是前台自己的额度），但后台自己最多占到
     * 上限减去预留位，剩下的永远留给前台。
     */
    private suspend fun acquireBackground() {
        val backgroundCap = (maxConcurrent - reservedForForeground).coerceAtLeast(1)
        mutex.withLock {
            if (backgroundActive.get() < backgroundCap && active.get() < maxConcurrent) {
                backgroundActive.incrementAndGet()
                active.incrementAndGet()
                return
            }
        }
        while (true) {
            delay(POLL_INTERVAL_MS)
            mutex.withLock {
                if (backgroundActive.get() < backgroundCap && active.get() < maxConcurrent) {
                    backgroundActive.incrementAndGet()
                    active.incrementAndGet()
                    return
                }
            }
        }
    }

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
                    "子 agent 累计派发超过上限 $maxTotal(防失控 backstop)",
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
