package io.zer0.muse.data.agentbot

/**
 * 后台任务的资源配额与排队策略。
 *
 * ## 为什么需要它
 *
 * 中枢允许"用户还在聊天时后台跑任务"。如果没有上限，用户连发五件事就会
 * 同时起五个子 Agent —— 共享的模型配额被吃光，前台回复开始转圈。
 * 那正是"做事时还能聊天"承诺失效的方式。
 *
 * ## 前台优先是什么意思
 *
 * 不是抢占（那会让后台任务永远跑不完），而是**排队顺序**：
 * 前台需要的模型调用先拿到配额，后台在配额紧张时留在队列里等。
 * 真正的并发控制在 [BotTaskScheduler] 的 Job 数与
 * [io.zer0.muse.tools.AgentConcurrencyLimiter] 里，这里只做"该不该现在跑"的判定。
 */
class BotResourceGovernor(
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    private val maxTaskDurationMs: Long = DEFAULT_MAX_TASK_DURATION_MS,
    private val maxTasksPerHour: Int = DEFAULT_MAX_TASKS_PER_HOUR,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * 最近一小时的派工时间戳。
     *
     * ## 为什么需要锁
     *
     * 审查 P2-14：原来是裸 `ArrayDeque`，而它会从两个线程被访问：
     *
     *  - 调度线程（`BotTaskScheduler.tick` → `admit` / `recordDispatch`）
     *  - UI 线程（`ForegroundCoordinator` 在 `viewModelScope` 里调）
     *
     * `ArrayDeque` 不是线程安全的，并发 `addLast` + 遍历会抛
     * `ConcurrentModificationException`，或者把配额算错。
     *
     * 用 `synchronized` 而不是换成 `ConcurrentLinkedDeque`：这里的操作是
     * "读大小 → 判断 → 追加"这种复合逻辑，单个线程安全容器挡不住竞态，
     * 必须让整段判定在同一个临界区里。
     */
    private val recentDispatch = ArrayDeque<Long>()
    private val dispatchLock = Any()

    /** 判定结果。 */
    sealed interface Verdict {
        /** 可以立刻跑。 */
        data object Go : Verdict

        /** 配额满，留在队列里等下一轮。 */
        data class Queue(val reason: String) : Verdict

        /** 该拒绝：不是"等一下"，而是"现在不该做这件事"。 */
        data class Reject(val reason: String) : Verdict
    }

    /**
     * 派工的种类。
     *
     * V16 要求"任务完成与审批不被可选额度吞掉"：可选闲聊（主动搭话）在配额紧张时
     * 应当让路，而用户真正交代的任务与等待中的审批永远优先。分开成两个入口，
     * 而不是在调用处各自判断 —— 那样迟早会漏一条路径。
     */
    enum class Kind {
        /** 用户交代的任务，或任务完成后的必要汇报。永远优先。 */
        REQUIRED,

        /** 可选闲聊（主动消息、气氛话）。配额紧张时可以让路。 */
        OPTIONAL,
    }

    /** 现在能不能再起一个后台任务。 */
    fun admit(runningCount: Int): Verdict = admit(runningCount, Kind.REQUIRED)

    /**
     * 按种类判定。
     *
     * 可选额度比必需额度更紧：只要有任务在跑，可选就不派 ——
     * 主动搭话晚一点无所谓，任务卡住是用户能感觉到的。
     */
    fun admit(runningCount: Int, kind: Kind): Verdict {
        val ts = now()
        pruneOldDispatches(ts)
        if (runningCount >= maxConcurrent) {
            return Verdict.Queue(
                "已经有 $runningCount 个任务在跑（上限 $maxConcurrent）",
            )
        }
        // V16: 有任务在跑时，可选闲聊让路。
        // 主动搭话晚一点无所谓，任务卡住是用户能感觉到的。
        if (kind == Kind.OPTIONAL && runningCount > 0) {
            return Verdict.Queue("有任务在跑，可选闲聊让路")
        }
        val dispatched = synchronized(dispatchLock) {
            pruneOldDispatches(now())
            recentDispatch.size
        }
        if (dispatched >= maxTasksPerHour) {
            // 这不是"等一会儿"，是一小时内不该再派了 —— 用户需要知道。
            return Verdict.Reject(
                "这一小时已经派了 $dispatched 个任务（上限 $maxTasksPerHour）",
            )
        }
        return Verdict.Go
    }

    /** 记录一次派工。 */
    fun recordDispatch() {
        // 顺带剪掉过期条目：不剪的话这队列只增不减，
        // 而 admit 里的"一小时上限"判断会永远看到旧记录。
        val ts = now()
        synchronized(dispatchLock) {
            pruneOldDispatches(ts)
            recentDispatch.addLast(ts)
        }
    }

    /**
     * 任务是否已经跑太久。
     *
     * 超时的任务不自动重跑 —— 它可能已经产生了副作用。标 UNKNOWN 让用户核验。
     */
    fun isOverdue(startedAt: Long): Boolean {
        if (startedAt <= 0) return false
        return now() - startedAt > maxTaskDurationMs
    }

    /** 当前配额状态（诊断与设置页展示用）。 */
    fun snapshot(runningCount: Int): Snapshot {
        val dispatched = synchronized(dispatchLock) {
            pruneOldDispatches(now())
            recentDispatch.size
        }
        return Snapshot(
            running = runningCount,
            maxConcurrent = maxConcurrent,
            dispatchedLastHour = dispatched,
            maxPerHour = maxTasksPerHour,
        )
    }

    data class Snapshot(
        val running: Int,
        val maxConcurrent: Int,
        val dispatchedLastHour: Int,
        val maxPerHour: Int,
    )

    /** 调用方必须持有 [dispatchLock]。 */
    private fun pruneOldDispatches(ts: Long) {
        val cutoff = ts - ONE_HOUR_MS
        while (recentDispatch.isNotEmpty() && recentDispatch.first() < cutoff) {
            recentDispatch.removeFirst()
        }
    }

    companion object {
        private const val ONE_HOUR_MS = 3600_000L

        /** 同时跑 2 个。手机端模型并发能力有限，再多会让前台明显变慢。 */
        const val DEFAULT_MAX_CONCURRENT = 2

        /** 单任务上限 30 分钟。超过就不是"后台跑一下"，是失控。 */
        const val DEFAULT_MAX_TASK_DURATION_MS = 30 * 60_000L

        /** 每小时最多派 20 个。正常使用远低于此，触到就说明有循环派工。 */
        const val DEFAULT_MAX_TASKS_PER_HOUR = 20
    }
}
