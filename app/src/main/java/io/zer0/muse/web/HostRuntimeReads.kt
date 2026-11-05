package io.zer0.muse.web

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.diagnostics.DiagnosticsSnapshot
import io.zer0.muse.diagnostics.MainThreadJankTracker
import io.zer0.muse.diagnostics.WebHostLatencyStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「运行」页三条只读命令(`runtime.logs` / `runtime.diagnostics` / `runtime.stats`)的载荷投影。
 *
 * 与 `system.status` 同一套做法:回执只做「数据源 → 协议 DTO」的搬运,不在网关里另测一次;
 * 三条命令都是读,不加焦点锁、不改变任何状态。
 */
internal object HostRuntimeReads {

    const val LOGS_TYPE = "runtime.logs"
    const val DIAGNOSTICS_TYPE = "runtime.diagnostics"
    const val STATS_TYPE = "runtime.stats"

    /** `runtime.logs` 不传 limit 时给多少行。 */
    const val DEFAULT_LOG_LIMIT = 200

    /** 行数上限:再多就不是「看一眼最近发生了什么」,而是把日志往网页端倾倒。 */
    const val MAX_LOG_LIMIT = 500

    /**
     * 单行字符上限。
     *
     * 日志行里最坏的是模型请求的整段 JSON,一行几十 KB 很常见;
     * 保留**开头**截断,因为行首是时间戳与标签,丢掉就认不出是哪条日志。
     */
    const val MAX_LINE_CHARS = 500

    /** limit 口径:缺省给 [DEFAULT_LOG_LIMIT],其余夹进 1..[MAX_LOG_LIMIT]。 */
    fun logLimit(raw: Int?): Int = raw?.coerceIn(1, MAX_LOG_LIMIT) ?: DEFAULT_LOG_LIMIT

    /** 单行截断,超长保留头部并以省略号收尾。 */
    fun truncateLine(line: String): String = if (line.length <= MAX_LINE_CHARS) line else line.take(MAX_LINE_CHARS - 1) + "…"

    fun logsEvent(logs: List<String>, requestId: String?): HostEvent = HostEvent(
        type = LOGS_TYPE,
        requestId = requestId,
        runtimeLogs = logs,
    )

    fun diagnosticsEvent(diagnosticsJson: String?, requestId: String?): HostEvent = HostEvent(
        type = DIAGNOSTICS_TYPE,
        requestId = requestId,
        runtimeDiagnostics = diagnosticsJson,
    )

    fun statsEvent(stats: HostRuntimeStats, requestId: String?): HostEvent = HostEvent(
        type = STATS_TYPE,
        requestId = requestId,
        runtimeStats = stats,
    )

    /**
     * 卡顿快照 + 延迟样本 → 协议统计。纯函数,不碰 Android 运行时,便于直接喂固定数字。
     *
     * `webP95Reliable` 在这里判定而不是让网页端复制阈值:样本不足时给一个看起来很精确的
     * p95,比不给数字更坏。
     */
    fun statsOf(jank: MainThreadJankTracker.Snapshot, webSamples: Int, webP95Ms: Long?): HostRuntimeStats = HostRuntimeStats(
        webSamples = webSamples,
        webP95Ms = webP95Ms,
        webP95Reliable = webSamples >= WebHostLatencyStats.MIN_RELIABLE_SAMPLES,
        jankCount = jank.jankCount,
        slowCount = jank.slowCount,
        blockCount = jank.blockCount,
        worstMs = jank.worstMs,
        windowSeconds = jank.windowSeconds,
    )
}

/**
 * 「运行」页三条命令的数据源。
 *
 * 抽成接口的原因与 [HostSystemStatusSource] 一样:没有 Android 运行时(测试、最小装配)时
 * 命令照常回执空值,而不是报错。
 */
interface HostRuntimeSource {

    /** 日志尾部最多 [limit] 行,已按单行上限截断;没有日志文件时为空列表。 */
    suspend fun logs(limit: Int): List<String>

    /** 诊断快照的脱敏 JSON;采集不出快照时为 null。 */
    suspend fun diagnosticsJson(): String?

    /** 当前窗口的运行统计。 */
    suspend fun stats(): HostRuntimeStats

    /** 未装配时的空实现:空日志、无诊断、全默认统计。 */
    object Empty : HostRuntimeSource {
        override suspend fun logs(limit: Int): List<String> = emptyList()
        override suspend fun diagnosticsJson(): String? = null
        override suspend fun stats(): HostRuntimeStats = HostRuntimeStats()
    }
}

/**
 * 直接读 Android 运行时的「运行」数据源。
 *
 * ## 日志口径
 *
 * - 只读 `Logger.getLogFile()` 指向的**当前**文件(`cacheDir/logs/muse.log`,单文件上限 1MB),
 *   不回读轮转出去的 `muse.log.1`:一次网页往返翻两个文件没有意义,想追溯历史该导出整份日志;
 * - 行数由 [HostRuntimeReads.logLimit] 夹住,单行由 [HostRuntimeReads.truncateLine] 截断;
 * - 内容本身已在写入侧过滤过 PII 与凭据(`Logger` 的三个出口共用 `PiiEngine.scrubForLogs`),
 *   这里不再二次过滤 —— 但也不要在日志文件缺失时改读别的文件来「凑够内容」。
 *
 * ## 诊断
 *
 * 复用 S4 的 [DiagnosticsSnapshot]:导出契约(只出体积/计数/版本/枚举)只有一份,
 * 网页端与 App 诊断卡看到的是同一份脱敏结果。采集失败回执 null,不编一份空快照出来。
 *
 * 文件与统计源都可注入:生产装配走默认值,测试给临时文件与固定数字。
 */
internal class AndroidHostRuntimeSource(
    private val context: Context,
    private val logFile: () -> File? = { Logger.getLogFile() },
    private val jankSnapshot: () -> MainThreadJankTracker.Snapshot = { MainThreadJankTracker.shared.snapshot() },
    private val latencySnapshot: () -> Pair<Int, Long?> = { WebHostLatencyStats.shared.snapshot() },
) : HostRuntimeSource {

    override suspend fun logs(limit: Int): List<String> = withContext(Dispatchers.IO) {
        val allowed = HostRuntimeReads.logLimit(limit)
        val file = logFile() ?: return@withContext emptyList()
        runCatching {
            val lines = file.readLines()
            val tail = if (lines.size <= allowed) lines else lines.subList(lines.size - allowed, lines.size)
            tail.map(HostRuntimeReads::truncateLine)
        }.getOrElse {
            Logger.w(TAG, "运行日志尾部读取失败", it)
            emptyList()
        }
    }

    override suspend fun diagnosticsJson(): String? = runCatching {
        DiagnosticsSnapshot.collect(context).sanitizedJson()
    }.getOrElse {
        Logger.w(TAG, "诊断快照采集失败", it)
        null
    }

    override suspend fun stats(): HostRuntimeStats {
        val (webSamples, webP95) = latencySnapshot()
        return HostRuntimeReads.statsOf(jankSnapshot(), webSamples, webP95)
    }

    private companion object {
        const val TAG = "HostRuntimeReads"
    }
}
