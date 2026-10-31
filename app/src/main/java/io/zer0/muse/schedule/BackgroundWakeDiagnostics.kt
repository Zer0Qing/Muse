package io.zer0.muse.schedule

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.BuildConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.proactive.ProactiveWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutionException

/**
 * 后台唤醒的可观测面。
 *
 * 定时任务「没跑」有三种完全不同的原因,以前只能靠猜:
 *  1. 应用自己的执行记录([io.zer0.muse.data.schedule.ScheduledTaskExecutionDao])里有 failed / skipped / missed → 跑过;
 *  2. 记录里什么都没有 → 系统根本没唤醒进程,或唤醒后各执行体读到的总控为 false;
 *  3. WorkManager 里那条周期任务压根没注册(装完没打开过应用) → 永远不会被唤醒。
 *
 * 1 有 UI(任务历史),2 和 3 没有任何入口。本文件补的就是 2 和 3:
 * 把 WorkManager 里在册的周期任务连同 [WorkInfo] 状态、下次运行时间、系统停跑原因一起枚举出来,
 * 并给出可复制的纯文本快照(用户粘给开发者即可,不需要 adb)。
 *
 * 不做的事:不注册、不取消、不修改任何调度配置,只读。
 */
enum class WakeWorkKind {
    SCHEDULED_TASK,
    PROACTIVE,
    DAILY_SUMMARY,
    CLOUD_BACKUP,
    AUTO_BACKUP,
    STATS_CACHE,
}

/** 任务在系统队列里的状态。`NOT_REGISTERED` 不是 [WorkInfo.State] 的值,是「系统里查不到这条任务」。 */
enum class WakeState {
    ENQUEUED,
    RUNNING,
    BLOCKED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    NOT_REGISTERED,
    UNKNOWN,
    ;

    /** 终态:不会再被推进(周期任务被取消/失败后需要重新注册才会回到 ENQUEUED)。 */
    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}

/** [WorkInfo.getStopReason] 的可读投影:系统为什么把这条任务停下来。 */
enum class WakeStopReason {
    NOT_STOPPED,
    UNKNOWN,
    CANCELLED_BY_APP,
    PREEMPT,
    TIMEOUT,
    DEVICE_STATE,
    CONSTRAINT_BATTERY,
    CONSTRAINT_CHARGING,
    CONSTRAINT_NETWORK,
    DEVICE_IDLE,
    CONSTRAINT_STORAGE,
    QUOTA,
    BACKGROUND_RESTRICTION,
    APP_STANDBY,
    USER,
    SYSTEM_PROCESSING,
    ESTIMATED_APP_LAUNCH_TIME_CHANGED,
    ;

    /** 是否为「被系统/环境限制住了」这一类原因(区别于用户主动取消)。 */
    val isExternalLimit: Boolean
        get() = this != NOT_STOPPED && this != CANCELLED_BY_APP && this != USER
}

/**
 * [WorkInfo] 的可读投影。
 *
 * 单独抽出来是为了让「状态枚举映射」和「条目组装」能在纯 JVM 单元测试里跑,
 * 不需要 Robolectric 或初始化 WorkManager。
 */
data class WakeWorkSnapshot(
    val uuid: String,
    val stateName: String,
    val nextRunAtMillis: Long?,
    val runAttemptCount: Int,
    val stopReason: WakeStopReason,
    val constrained: Boolean,
)

/** 一条在册的周期任务(同一个 unique work 取当前状态,历史条数另计)。 */
data class WakeWorkEntry(
    val kind: WakeWorkKind,
    val uniqueName: String,
    val state: WakeState,
    val nextRunAtMillis: Long?,
    val runAttemptCount: Int,
    val stopReason: WakeStopReason,
    val constrained: Boolean,
    val historyCount: Int,
)

/** 一次诊断采集的完整结果,同时也是可复制快照的数据源。 */
data class WakeDiagnostics(
    val entries: List<WakeWorkEntry>,
    val backgroundWorkEnabled: Boolean?,
    val batteryOptimizationExempt: Boolean,
    val exportedAtMillis: Long,
    val appVersion: String,
    val androidVersion: String,
    val device: String,
    val error: String? = null,
)

object BackgroundWakeDiagnostics {
    private const val TAG = "BackgroundWakeDiagnostics"

    /** 每个任务类型在 WorkManager 里的 unique work 名。注册点在 MuseApp.onCreate,改注册点必须同步这里。 */
    internal fun candidateWorkNames(kind: WakeWorkKind): List<String> = when (kind) {
        WakeWorkKind.SCHEDULED_TASK -> listOf(ScheduledTaskWorker.UNIQUE_WORK_NAME)
        WakeWorkKind.PROACTIVE -> listOf(ProactiveWorker.UNIQUE_WORK_NAME)
        // 每日总结按配置时段逐个注册 unique work,时段可由用户改成任意整点,所以候选是 24 个小时。
        WakeWorkKind.DAILY_SUMMARY -> (0..23).map { hour -> DailySummaryWorker.uniqueWorkName(hour, 0) }
        WakeWorkKind.CLOUD_BACKUP -> listOf(CloudBackupWorker.UNIQUE_WORK_NAME)
        WakeWorkKind.AUTO_BACKUP -> listOf(AutoBackupWorker.UNIQUE_WORK_NAME)
        WakeWorkKind.STATS_CACHE -> listOf(StatsCacheWorker.UNIQUE_WORK_NAME)
    }

    /** [WorkInfo.State] 的名字 → [WakeState]。未知名字归到 [WakeState.UNKNOWN] 而不是崩掉。 */
    internal fun mapWakeState(stateName: String?): WakeState = when (stateName?.uppercase(Locale.ROOT)) {
        null -> WakeState.NOT_REGISTERED
        "ENQUEUED" -> WakeState.ENQUEUED
        "RUNNING" -> WakeState.RUNNING
        "BLOCKED" -> WakeState.BLOCKED
        "SUCCEEDED" -> WakeState.SUCCEEDED
        "FAILED" -> WakeState.FAILED
        "CANCELLED" -> WakeState.CANCELLED
        else -> WakeState.UNKNOWN
    }

    /**
     * 同一个 unique work 可能同时留着历史链:老的一条已 SUCCEEDED,新的一条还在 ENQUEUED。
     * 用户关心的是「现在这条会不会被唤醒」,所以优先取非终态,全都结束才取最后一条。
     */
    internal fun pickCurrent(snapshots: List<WakeWorkSnapshot>): WakeWorkSnapshot? {
        if (snapshots.isEmpty()) return null
        val live = snapshots.lastOrNull { snapshot -> !mapWakeState(snapshot.stateName).isTerminal }
        return live ?: snapshots.last()
    }

    /**
     * 组装一个任务类型的条目。
     *
     * @param candidates unique work 名 → 该系统名下的 [WorkInfo] 投影;值为 null 表示这次查询失败
     *  (WorkManager 未初始化 / 查询抛异常),与「查到了但为空(未注册)」区分开。
     */
    internal fun toEntries(
        kind: WakeWorkKind,
        candidates: Map<String, List<WakeWorkSnapshot>?>,
    ): List<WakeWorkEntry> {
        val present = candidates.filterValues { snapshots -> !snapshots.isNullOrEmpty() }
        if (present.isEmpty()) {
            val firstName = candidates.keys.firstOrNull() ?: return emptyList()
            val queryFailed = candidates.values.any { snapshots -> snapshots == null }
            return listOf(
                WakeWorkEntry(
                    kind = kind,
                    uniqueName = firstName,
                    state = if (queryFailed) WakeState.UNKNOWN else WakeState.NOT_REGISTERED,
                    nextRunAtMillis = null,
                    runAttemptCount = 0,
                    stopReason = WakeStopReason.NOT_STOPPED,
                    constrained = false,
                    historyCount = 0,
                ),
            )
        }
        return present.map { (uniqueName, snapshots) ->
            val list = snapshots.orEmpty()
            val current = pickCurrent(list)
            WakeWorkEntry(
                kind = kind,
                uniqueName = uniqueName,
                state = mapWakeState(current?.stateName),
                nextRunAtMillis = current?.nextRunAtMillis,
                runAttemptCount = current?.runAttemptCount ?: 0,
                stopReason = current?.stopReason ?: WakeStopReason.NOT_STOPPED,
                constrained = current?.constrained == true,
                historyCount = list.size,
            )
        }
    }

    /** 单个 [WorkInfo] → 投影。[WorkInfo.getNextScheduleTimeMillis] 对非 ENQUEUED 任务返回 Long.MAX_VALUE。 */
    internal fun toSnapshot(info: WorkInfo): WakeWorkSnapshot = WakeWorkSnapshot(
        uuid = info.id.toString(),
        stateName = info.state.name,
        nextRunAtMillis = info.nextScheduleTimeMillis.takeIf { at -> at in 1 until Long.MAX_VALUE },
        runAttemptCount = info.runAttemptCount,
        stopReason = mapStopReason(info.stopReason),
        constrained = hasConstraints(info),
    )

    internal fun mapStopReason(code: Int): WakeStopReason = when (code) {
        WorkInfo.STOP_REASON_NOT_STOPPED -> WakeStopReason.NOT_STOPPED
        WorkInfo.STOP_REASON_CANCELLED_BY_APP -> WakeStopReason.CANCELLED_BY_APP
        WorkInfo.STOP_REASON_PREEMPT -> WakeStopReason.PREEMPT
        WorkInfo.STOP_REASON_TIMEOUT -> WakeStopReason.TIMEOUT
        WorkInfo.STOP_REASON_DEVICE_STATE -> WakeStopReason.DEVICE_STATE
        WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> WakeStopReason.CONSTRAINT_BATTERY
        WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> WakeStopReason.CONSTRAINT_CHARGING
        WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> WakeStopReason.CONSTRAINT_NETWORK
        WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> WakeStopReason.DEVICE_IDLE
        WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> WakeStopReason.CONSTRAINT_STORAGE
        WorkInfo.STOP_REASON_QUOTA -> WakeStopReason.QUOTA
        WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> WakeStopReason.BACKGROUND_RESTRICTION
        WorkInfo.STOP_REASON_APP_STANDBY -> WakeStopReason.APP_STANDBY
        WorkInfo.STOP_REASON_USER -> WakeStopReason.USER
        WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> WakeStopReason.SYSTEM_PROCESSING
        WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> WakeStopReason.ESTIMATED_APP_LAUNCH_TIME_CHANGED
        else -> WakeStopReason.UNKNOWN
    }

    internal fun hasConstraints(info: WorkInfo): Boolean {
        val constraints = info.constraints
        return constraints.requiresBatteryNotLow() ||
            constraints.requiresCharging() ||
            constraints.requiresDeviceIdle() ||
            constraints.requiresStorageNotLow() ||
            constraints.requiredNetworkType != androidx.work.NetworkType.NOT_REQUIRED
    }

    /**
     * 采集全部在册周期任务的状态。
     *
     * 只读:每个 unique work 调一次 `getWorkInfosForUniqueWork`,不写入、不取消、不重排。
     * 全程 off-main(WorkManager 的 future 用阻塞 get 取结果,调用方在 IO 线程)。
     *
     * @param backgroundWorkEnabled 设置里「后台任务总控」的当前值;null 表示读不到
     *  (读取失败)。总控关闭时唤醒照旧发生,但各执行体读到 false 会直接返回 —— 这是
     *  「有唤醒、没执行」的常见原因,所以必须一起展示。
     */
    suspend fun collect(context: Context, backgroundWorkEnabled: Boolean?): WakeDiagnostics =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            // 面板不能因为一次意外异常就永久停在加载态:收集失败也交回一份带 error 的报告,
            // 让界面能把原因显式说出来。
            val collected = resultOf { collectOnce(appContext, backgroundWorkEnabled) }
                .onError { message, throwable ->
                    Logger.w(TAG, "收集后台唤醒状态失败: $message", throwable)
                }
            collected.getOrNull() ?: buildDiagnostics(
                appContext = appContext,
                entries = emptyList(),
                backgroundWorkEnabled = backgroundWorkEnabled,
                error = (collected as? io.zer0.common.Result.Error)?.message,
            )
        }

    /**
     * 从 Koin 读「后台任务总控」再采集。
     *
     * 设置仓库不可用(或读失败)时把总控标记为未知,而不是让整次采集失败 ——
     * 「总控读不到」本身就是需要被看到的一条诊断信息。
     */
    suspend fun collectWithSettings(context: Context): WakeDiagnostics {
        val backgroundWorkEnabled = resultOf {
            GlobalContext.get().get<SettingsRepository>().scheduleWorkEnabledFlow.first()
        }.onError { message, throwable ->
            Logger.w(TAG, "读取后台任务总控失败: $message", throwable)
        }.getOrNull()
        return collect(context, backgroundWorkEnabled)
    }

    /**
     * 遍历全部在册任务并拼出报告。
     *
     * 并发只省「我们这一侧」的往返等待:各 unique work 的查询是同时发起的,但 WorkManager
     * 内部用 serialTaskExecutor 串行执行这些查询,所以数据库层面并不会真的并行,
     * 整体耗时主要由「各次查询的串行总时长」决定,而不是最长那一次。
     * 将来觉得这里还可以更快时,先量设备上的实际耗时,不要因为写着 async 就认为已经并发跑了。
     */
    private suspend fun collectOnce(appContext: Context, backgroundWorkEnabled: Boolean?): WakeDiagnostics {
        var error: String? = null
        val workManager = try {
            WorkManager.getInstance(appContext)
        } catch (e: IllegalStateException) {
            error = e.message
            Logger.w(TAG, "WorkManager 未初始化,无法读取唤醒状态", e)
            null
        }
        val entries = coroutineScope {
            WakeWorkKind.values().flatMap { kind ->
                val names = candidateWorkNames(kind)
                val snapshots = names
                    .map { name -> async { if (workManager == null) null else queryUniqueWork(workManager, name) } }
                    .awaitAll()
                toEntries(kind, names.zip(snapshots).toMap())
            }
        }
        return buildDiagnostics(appContext, entries, backgroundWorkEnabled, error)
    }

    private fun buildDiagnostics(
        appContext: Context,
        entries: List<WakeWorkEntry>,
        backgroundWorkEnabled: Boolean?,
        error: String?,
    ): WakeDiagnostics = WakeDiagnostics(
        entries = entries,
        backgroundWorkEnabled = backgroundWorkEnabled,
        batteryOptimizationExempt = isIgnoringBatteryOptimizations(appContext),
        exportedAtMillis = System.currentTimeMillis(),
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        error = error,
    )

    /** 查询单个 unique work;失败返回 null(与「查到了、但是空」区分),不抛给调用方。 */
    private fun queryUniqueWork(workManager: WorkManager, uniqueName: String): List<WakeWorkSnapshot>? = try {
        workManager.getWorkInfosForUniqueWork(uniqueName).get().orEmpty().map { info -> toSnapshot(info) }
    } catch (e: InterruptedException) {
        // 保留中断标记:调用方在 IO 协程里,不该把中断吞成一次普通失败
        Thread.currentThread().interrupt()
        Logger.w(TAG, "读取唤醒状态被中断($uniqueName)", e)
        null
    } catch (e: ExecutionException) {
        // WorkManager 内部查询失败(数据库/初始化异常)以 ExecutionException 形式回到这里
        Logger.w(TAG, "读取唤醒状态失败($uniqueName)", e)
        null
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        // 读取失败不做区分:这个字段只是辅助信息,拿不到就当未豁免
        return resultOf { power.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull() ?: false
    }

    /** 时间戳 → 本地时间文本(快照是给人读的,固定格式便于对齐)。 */
    internal fun formatTime(millis: Long?): String {
        if (millis == null) return NONE_TEXT
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
    }

    /** 「没有这个值」在快照里的写法。缺值绝不能打印成 0 或 Long.MAX_VALUE:那两个都会被当成真时间戳。 */
    internal const val NONE_TEXT = "none"

    /**
     * 可复制的纯文本快照。
     *
     * 用 `key=value` 行长格式而不是本地化文案:这份文本是要粘给开发者看的,
     * 状态名、停跑原因名与 [WakeState]/[WakeStopReason] 枚举同名,跨语言不会歧义。
     */
    internal fun formatSnapshot(diagnostics: WakeDiagnostics): String {
        val builder = StringBuilder()
        builder.appendLine("# Muse 后台唤醒快照")
        builder.appendLine("exported_at=${formatTime(diagnostics.exportedAtMillis)}")
        builder.appendLine("app=${diagnostics.appVersion}")
        builder.appendLine("android=${diagnostics.androidVersion}")
        builder.appendLine("device=${diagnostics.device}")
        builder.appendLine("background_work_enabled=${diagnostics.backgroundWorkEnabled ?: "unknown"}")
        builder.appendLine("battery_optimization_exempt=${diagnostics.batteryOptimizationExempt}")
        diagnostics.error?.let { message ->
            builder.appendLine("work_manager_error=$message")
        }
        builder.appendLine("work_count=${diagnostics.entries.size}")
        diagnostics.entries.forEach { entry ->
            // 没有下次运行时间时写 next_run=none:非排队/已终态的任务没有这个值,
            // 不能拿哨兵或 0 充数,否则读的人会以为那是真的时间戳。
            builder.appendLine(
                "${entry.uniqueName} | kind=${entry.kind.name} | state=${entry.state.name} | " +
                    "next_run=${formatTime(entry.nextRunAtMillis)} | attempts=${entry.runAttemptCount} | " +
                    "constrained=${entry.constrained} | stop_reason=${entry.stopReason.name} | " +
                    "history=${entry.historyCount}",
            )
        }
        return builder.toString()
    }
}
