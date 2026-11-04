package io.zer0.common

/**
 * v1.56: 轻量级性能追踪工具。
 *
 * 设计目标:
 *  - 零依赖(仅依赖 Logger),可在所有模块使用
 *  - 零开销:关闭时 track() 块内的 lambda 仍执行,但不计时不记录
 *  - 简洁:track("name") { ... } 一行搞定
 *  - 可控:通过 [enabled] 开关全局控制(默认 true,release 可关)
 *
 * 使用示例:
 *  ```
 *  val result = Perf.track("rag-index") {
 *      ragService.indexDocument(docId, content, config)
 *  }
 *  ```
 *
 * 日志格式: `[Perf] rag-index: 1234ms`
 * 慢操作(>500ms)会用 WARN 等级记录,否则 DEBUG。
 */
object Perf {

    @Volatile
    var enabled: Boolean = true

    /** 慢操作阈值(ms),超过此值用 WARN 等级记录。 */
    const val SLOW_THRESHOLD_MS = 500L

    /**
     * 性能数据 sink,供外部(如 PerformanceReporter)订阅每次埋点。
     *
     * 回调在调用线程同步执行,必须轻量(仅判定/入队,IO 异步)。
     * 内部已 try-catch,异常不会影响埋点调用方。
     */
    @Volatile
    var sink: ((name: String, elapsedMs: Long) -> Unit)? = null

    /** 单条 Perf 埋点记录(供 [snapshotRecent] 输出,用于 ANR 诊断等场景)。 */
    data class PerfRecord(
        val name: String,
        val elapsedMs: Long,
        val timestamp: Long,
    )

    /** 最近埋点记录环形缓冲容量(供 ANR 诊断读取最近性能数据)。 */
    private const val MAX_RECENT = 50

    /** 最近埋点记录(线程安全,内部加锁)。 */
    private val recentRecords = mutableListOf<PerfRecord>()

    /** 获取最近 [MAX_RECENT] 条埋点记录的快照(线程安全拷贝,供 ANR 诊断使用)。 */
    fun snapshotRecent(): List<PerfRecord> = synchronized(recentRecords) { recentRecords.toList() }

    /**
     * 计时执行 [block] 并记录耗时。
     *
     * @param name 操作名称(如 "rag-index" / "stream-first-token")
     * @param block 要计时的代码块
     * @return block 的返回值
     */
    inline fun <T> track(name: String, block: () -> T): T {
        if (!enabled) return block()
        // 耗时走单调钟（见类注释）；inline 里只能用公开 API，所以直接除 1e6。
        val startNanos = System.nanoTime()
        return try {
            block()
        } finally {
            val elapsed = (System.nanoTime() - startNanos) / 1_000_000
            log(name, elapsed)
        }
    }

    /**
     * 计时执行 suspend [block] 并记录耗时。
     *
     * @param name 操作名称
     * @param block 要计时的 suspend 代码块
     * @return block 的返回值
     */
    suspend inline fun <T> trackSuspend(name: String, crossinline block: suspend () -> T): T {
        if (!enabled) return block()
        val startNanos = System.nanoTime()
        return try {
            block()
        } finally {
            val elapsed = (System.nanoTime() - startNanos) / 1_000_000
            log(name, elapsed)
        }
    }

    /**
     * 手动记录一个耗时点(用于不方便用 track 包裹的场景)。
     *
     * @param name 操作名称
     * @param elapsedMs 耗时(毫秒)
     */
    fun log(name: String, elapsedMs: Long) {
        if (!enabled) return
        // 写入最近记录环形缓冲(供 ANR 诊断等读取最近性能数据)
        synchronized(recentRecords) {
            recentRecords.add(PerfRecord(name, elapsedMs, System.currentTimeMillis()))
            if (recentRecords.size > MAX_RECENT) recentRecords.removeAt(0)
        }
        // 通知外部 sink(供 PerformanceReporter 订阅慢操作);try-catch 防止 sink 异常影响调用方
        sink?.let { cb ->
            try {
                cb(name, elapsedMs)
            } catch (t: Throwable) {
                Logger.w("Perf", "sink 回调异常: ${t.message}", t)
            }
        }
        if (elapsedMs >= SLOW_THRESHOLD_MS) {
            Logger.w("Perf", "$name: ${elapsedMs}ms (slow)")
        } else {
            Logger.d("Perf", "$name: ${elapsedMs}ms")
        }
    }

    /**
     * 创建一个计时器(用于需要分段的场景)。
     *
     * 示例:
     *  ```
     *  val t = Perf.start("stream-round")
     *  // ... 第一阶段 ...
     *  t.split("embedding")
     *  // ... 第二阶段 ...
     *  t.split("search")
     *  // ... 第三阶段 ...
     *  t.end()  // 记录总耗时 + 各分段
     *  ```
     */
    fun start(name: String): Timer = Timer(name)

    /**
     * 分段计时器。
     */
    class Timer internal constructor(private val name: String) {
        // 耗时一律用单调钟 `System.nanoTime()`：挂钟会被 NTP 校时、用户改时间、
        // 休眠唤醒拉动，`currentTimeMillis` 相减就会给出**负数或巨大的耗时**，
        // 而这些数字是要进 ANR 诊断与性能报表的（`PerfRecord.timestamp` 仍用挂钟，
        // 那个字段表达的是"什么时候发生"，本来就该是挂钟）。
        private val startNanos = System.nanoTime()
        private var lastSplitNanos = startNanos
        private val splits = mutableListOf<Pair<String, Long>>()

        /**
         * 附注：不是耗时，而是"这一轮发生了多少次/多少毫秒"之类的计数字段。
         *
         * 存在的理由：`Perf` 只有 ≥500ms 的分段才会以 WARN 进日志文件，
         * 而 `chat-first-token`（通常几百毫秒）这类埋点根本不会出现在日志里；
         * 把它挂到本来就会落盘的 `chat-turn` 行上，才查得到。
         */
        private val annotations = mutableListOf<String>()

        /** 追加一个非耗时计数字段（如 firstToken=312、uiFlushes=7）。 */
        fun annotate(key: String, value: Any?) {
            if (!enabled) return
            annotations.add("$key=$value")
        }

        /**
         * 记录一个分段点。
         * @param splitName 分段名称(如 "embedding" / "search")
         */
        fun split(splitName: String) {
            if (!enabled) return
            val now = System.nanoTime()
            splits.add(splitName to (now - lastSplitNanos) / 1_000_000)
            lastSplitNanos = now
        }

        /**
         * 结束计时,记录总耗时和各分段。
         */
        fun end() {
            if (!enabled) return
            val total = (System.nanoTime() - startNanos) / 1_000_000
            if (splits.isEmpty()) {
                log(name, total)
            } else {
                val splitStr = (
                    splits.joinToString(", ") { "${it.first}=${it.second}ms" } +
                        if (annotations.isEmpty()) "" else ", " + annotations.joinToString(", ")
                    ).trimStart(',', ' ')
                if (total >= SLOW_THRESHOLD_MS) {
                    Logger.w("Perf", "$name: ${total}ms (slow) [$splitStr]")
                } else {
                    Logger.d("Perf", "$name: ${total}ms [$splitStr]")
                }
            }
        }
    }
}
