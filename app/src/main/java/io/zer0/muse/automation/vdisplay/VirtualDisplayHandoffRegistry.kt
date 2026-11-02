package io.zer0.muse.automation.vdisplay

/**
 * 虚拟屏"交给用户"的台账。
 *
 * 为什么需要它：`ui_agent` 跑完在 `finally` 里直接销毁专属显示屏，用户**永远看不到**助手刚才
 * 在哪块屏上操作、也没法接手。有了这个台账，任务可以选择"跑完不销毁、把屏交出去"，
 * 由界面来预览/接管/关闭。
 *
 * 两条硬约束：
 *  1. **必须有过期时间**：没人来接手就自己失效 —— 否则一块看不见的屏会一直占着资源；
 *  2. **取出即转移**（[take] 会移除条目）：两个入口同时拿到同一块屏，迟早互相把对方的画面顶掉。
 */
object VirtualDisplayHandoffRegistry {

    /** 默认存活时间：足够用户切到预览页，又不至于让孤儿屏长期占资源。 */
    const val DEFAULT_TTL_MS = 5 * 60 * 1000L

    /** 一块已交出的屏。 */
    data class Handoff(
        val displayId: Int,
        val packageName: String,
        val createdAt: Long,
        val expiresAt: Long,
    ) {
        fun isExpired(now: Long): Boolean = now >= expiresAt
    }

    private val entries = LinkedHashMap<Int, Handoff>()

    /** 交出（或更新）一块屏。[now] 与 [ttlMs] 显式传入，便于测试不依赖真实时钟。 */
    @Synchronized
    fun offer(
        displayId: Int,
        packageName: String,
        now: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): Handoff {
        require(displayId >= 0) { "displayId 无效" }
        pruneExpired(now)
        val entry = Handoff(
            displayId = displayId,
            packageName = packageName,
            createdAt = now,
            expiresAt = now + ttlMs.coerceAtLeast(1),
        )
        entries[displayId] = entry
        return entry
    }

    /** 当前可交接的屏（已过期的会被清掉）。 */
    @Synchronized
    fun list(now: Long): List<Handoff> {
        pruneExpired(now)
        return entries.values.toList()
    }

    /** 取走一块屏：**取走即移除**，避免两个入口同时驱动同一块屏。 */
    @Synchronized
    fun take(displayId: Int, now: Long): Handoff? {
        pruneExpired(now)
        return entries.remove(displayId)
    }

    /** 主动放弃交接（界面关闭预览时调用）。 */
    @Synchronized
    fun release(displayId: Int) {
        entries.remove(displayId)
    }

    /** 过期清理：返回被清掉的 displayId，调用方据此销毁远端屏。 */
    @Synchronized
    fun pruneExpired(now: Long): List<Int> {
        val expired = entries.values.filter { it.isExpired(now) }.map { it.displayId }
        expired.forEach { entries.remove(it) }
        return expired
    }

    /** 仅测试用：清空台账，避免跨用例串状态。 */
    @Synchronized
    internal fun resetForTests() {
        entries.clear()
    }
}
