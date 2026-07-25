package io.zer0.muse.perf

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.Perf
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 性能数据收集与上报器。
 *
 * 职责:
 *  - 通过 [Perf.sink] 订阅每次性能埋点(track / trackSuspend / log / Timer.end)
 *  - 按操作名称前缀判定类别与慢操作阈值:
 *      - 网络(net/http/network/web/api/stream):> [NETWORK_SLOW_MS] (10s)
 *      - 数据库(db/sql/dao/query/rag/index/insert/update/delete):> [DB_SLOW_MS] (2s)
 *      - UI 渲染(ui/render/draw/compose/frame/layout):> [UI_SLOW_MS] (500ms)
 *      - 其他:沿用 [Perf.SLOW_THRESHOLD_MS] (500ms)
 *  - 慢操作写入 filesDir/perf/perf_{date}.log(按天滚动,保留 [RETENTION_DAYS] 天)
 *  - 可选上报开关:[SettingsRepository.perfReportCache](默认 false,隐私优先)
 *      - 当前无远程上报通道,开启时仅 [Logger.d] 标记"待上报",本地日志照常写入
 *
 * 设计:
 *  - [Perf.sink] 回调在调用线程同步执行,必须轻量 — 仅判定阈值与入队,IO 写盘异步
 *  - 写盘用独立守护线程 + [LinkedBlockingQueue],避免阻塞埋点调用方(可能为主线程)
 *  - 启动时清理过期 perf 日志(> [RETENTION_DAYS] 天)
 *
 * @param appContext 应用上下文(用于定位 filesDir/perf)
 * @param settings 设置仓库(读取 enablePerfReport 开关)
 */
class PerformanceReporter(
    private val appContext: Context,
    private val settings: SettingsRepository,
) {
    // 待写盘的慢操作记录队列(线程安全:perf sink 线程 offer,写盘线程 poll)
    private val pendingQueue = LinkedBlockingQueue<SlowRecord>()

    // 是否已注册 sink(防止重复注册)
    private val installed = AtomicBoolean(false)

    // 写盘守护线程引用
    private var writerThread: Thread? = null

    // 运行标志
    @Volatile
    private var running: Boolean = false

    /** 慢操作记录(入队 + 写盘用)。 */
    private data class SlowRecord(
        val name: String,
        val elapsedMs: Long,
        val category: String,
        val timestamp: Long,
    )

    /**
     * 启动性能收集:注册 [Perf.sink],清理过期日志,启动后台写盘线程。
     *
     * 幂等:[installed] AtomicBoolean 保证 sink 只注册一次。
     */
    fun start() {
        if (!installed.compareAndSet(false, true)) return
        // 注册 Perf sink — 每次 track/trackSuspend/log/Timer.end 都会回调
        Perf.sink = { name, elapsedMs -> handlePerfRecord(name, elapsedMs) }
        running = true
        // 清理过期 perf 日志(>7 天)
        cleanupOldLogs()
        // 启动写盘守护线程(低优先级,减少对正常调度的影响)
        writerThread = Thread({ writeLoop() }, THREAD_NAME).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
        Logger.i(TAG, "PerformanceReporter 已启动(network>$NETWORK_SLOW_MS ms, db>$DB_SLOW_MS ms, ui>$UI_SLOW_MS ms)")
    }

    /**
     * 处理单条 Perf 埋点:判定类别与是否慢操作,慢操作入队等待写盘。
     *
     * 在 Perf.sink 回调中执行(可能是任意线程,包括主线程),必须轻量:
     * 仅做字符串前缀匹配 + 入队,IO 写盘交给后台线程。
     */
    private fun handlePerfRecord(name: String, elapsedMs: Long) {
        try {
            val category = categorize(name)
            val threshold = thresholdFor(category)
            // 未超阈值,跳过
            if (elapsedMs < threshold) return
            val record = SlowRecord(name, elapsedMs, category, System.currentTimeMillis())
            pendingQueue.offer(record)
            // enablePerfReport 开关:未来用于远程上报,当前仅标记日志(隐私优先,默认 false 不输出)
            if (settings.perfReportCache) {
                Logger.d(TAG, "perf_report 待上报: $name: ${elapsedMs}ms ($category)")
            }
        } catch (t: Throwable) {
            // sink 内任何异常都不能影响 Perf 调用方(否则可能改变业务行为)
            Logger.w(TAG, "handlePerfRecord 异常: ${t.message}", t)
        }
    }

    /** 根据操作名称前缀判定类别(忽略大小写)。 */
    private fun categorize(name: String): String {
        val lower = name.lowercase()
        return when {
            // 网络:net/http/network/web/api/stream
            lower.startsWith("net") || lower.startsWith("http") ||
                lower.startsWith("network") || lower.startsWith("web") ||
                lower.startsWith("api") || lower.startsWith("stream") -> CATEGORY_NETWORK
            // 数据库:db/sql/dao/query/rag/index/insert/update/delete
            lower.startsWith("db") || lower.startsWith("sql") ||
                lower.startsWith("dao") || lower.startsWith("query") ||
                lower.startsWith("rag") || lower.startsWith("index") ||
                lower.startsWith("insert") || lower.startsWith("update") ||
                lower.startsWith("delete") -> CATEGORY_DB
            // UI 渲染:ui/render/draw/compose/frame/layout
            lower.startsWith("ui") || lower.startsWith("render") ||
                lower.startsWith("draw") || lower.startsWith("compose") ||
                lower.startsWith("frame") || lower.startsWith("layout") -> CATEGORY_UI
            else -> CATEGORY_OTHER
        }
    }

    /** 各类别慢操作阈值(ms)。 */
    private fun thresholdFor(category: String): Long = when (category) {
        CATEGORY_NETWORK -> NETWORK_SLOW_MS
        CATEGORY_DB -> DB_SLOW_MS
        CATEGORY_UI -> UI_SLOW_MS
        else -> Perf.SLOW_THRESHOLD_MS
    }

    /**
     * 后台写盘循环:从队列批量取记录,追加写入当天 perf 日志文件。
     *
     * 阻塞等待首条(最多 5s),再非阻塞攒批(最多 [BATCH_SIZE] 条)后一次性写盘,
     * 减少 IO 次数。任何异常都不能让写盘线程退出(否则慢操作记录丢失)。
     */
    private fun writeLoop() {
        val batch = mutableListOf<SlowRecord>()
        while (running) {
            try {
                // 阻塞取首条(最多等 5s)
                val first = pendingQueue.poll(5, TimeUnit.SECONDS)
                if (first != null) {
                    batch.add(first)
                    // 非阻塞取剩余,攒批减少 IO 次数
                    while (batch.size < BATCH_SIZE) {
                        val r = pendingQueue.poll() ?: break
                        batch.add(r)
                    }
                    writeBatch(batch)
                    batch.clear()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (t: Throwable) {
                Logger.w(TAG, "写盘循环异常: ${t.message}", t)
                batch.clear()
                try {
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        // 退出前 flush 剩余记录(进程退出时尽量不丢数据)
        if (batch.isNotEmpty()) {
            resultOf { writeBatch(batch) }
            batch.clear()
        }
    }

    /** 把一批慢操作记录追加写入当天 perf 日志文件(perf_{date}.log)。 */
    private fun writeBatch(records: List<SlowRecord>) {
        if (records.isEmpty()) return
        val perfDir = File(appContext.filesDir, "perf").apply { mkdirs() }
        val dateStr = SimpleDateFormat(DATE_FMT, Locale.US).format(Date())
        val logFile = File(perfDir, "perf_$dateStr.log")
        val timeFmt = SimpleDateFormat(TIME_FMT, Locale.US)
        // appendText 追加写入(同一天共享一个文件)
        logFile.appendText(buildString {
            records.forEach { r ->
                append("[${timeFmt.format(Date(r.timestamp))}] ")
                append("(${r.category}) ")
                append("${r.name}: ${r.elapsedMs}ms")
                appendLine()
            }
        })
    }

    /** 清理超过 [RETENTION_DAYS] 天的 perf 日志(滚动保留 7 天)。 */
    private fun cleanupOldLogs() {
        resultOf {
            val perfDir = File(appContext.filesDir, "perf")
            if (perfDir.exists()) {
                val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24L * 60L * 60L * 1000L
                perfDir.listFiles { f -> f.name.startsWith("perf_") && f.lastModified() < cutoff }
                    ?.forEach { it.delete() }
            }
        }.onError { msg, t -> Logger.w(TAG, "清理过期 perf 日志失败: $msg", t) }
    }

    companion object {
        private const val TAG = "PerformanceReporter"
        private const val THREAD_NAME = "PerfWriter"
        // 慢操作阈值(ms):网络 >10s、DB >2s、UI 渲染 >500ms
        private const val NETWORK_SLOW_MS = 10_000L
        private const val DB_SLOW_MS = 2_000L
        private const val UI_SLOW_MS = 500L
        // 操作类别标识
        private const val CATEGORY_NETWORK = "network"
        private const val CATEGORY_DB = "db"
        private const val CATEGORY_UI = "ui"
        private const val CATEGORY_OTHER = "other"
        // 日志文件名日期格式(按天滚动)
        private const val DATE_FMT = "yyyyMMdd"
        // 记录内时间戳格式
        private const val TIME_FMT = "HH:mm:ss.SSS"
        // 日志保留天数(滚动清理)
        private const val RETENTION_DAYS = 7
        // 批量写盘最大条数
        private const val BATCH_SIZE = 50
    }
}
