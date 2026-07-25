package io.zer0.muse.schedule

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.zer0.common.Logger
import io.zer0.muse.backup.BackupService
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * v1.98: 云备份自动定时上传调度器。
 *
 * 当 [CloudBackupConfig.autoSync] = true 且 [CloudBackupConfig.isConfigured] = true 时,
 * 每隔 [CloudBackupConfig.autoSyncIntervalHours] 小时自动调用 [BackupService.exportToCloud]。
 *
 * 设计参照 [ScheduledTaskRunner]:在 appScope 中轮询,每 10 分钟检查一次是否到期,
 * 到期则触发上传并更新 lastSyncAt。
 *
 * v1.132: 新增 WorkManager 兜底([registerWorkManagerFallback]) — App 被杀后由系统
 * 每 15 分钟拉起 [CloudBackupWorker] 执行 [checkAndSyncForWorker],与 [ScheduledTaskWorker]
 * 兜底机制保持一致。协程轮询负责"App 存活时"实时性,WorkManager 负责"App 被杀时"可靠性。
 *
 * 用户可在「设置 → 云备份 → 自动备份」中自定义间隔小时数(默认 24 小时)。
 */
class CloudBackupScheduler(
    private val backupService: BackupService,
    private val settings: SettingsRepository,
    private val appScope: CoroutineScope,
) {
    private var job: Job? = null

    companion object {
        private const val TAG = "CloudBackupSched"
        /** 轮询间隔:10 分钟检查一次是否到期(不需要更频繁,备份间隔最小 1 小时)。 */
        private const val POLL_INTERVAL_MS = 10 * 60 * 1000L
        /** 最小间隔:1 小时(防止用户设置过小导致频繁上传)。 */
        private const val MIN_INTERVAL_HOURS = 1
    }

    /** 启动定时轮询。在 Application.onCreate 中调用。 */
    fun start() {
        job?.cancel()
        job = appScope.launch {
            Logger.i(TAG, "Scheduler started")
            while (isActive) {
                try {
                    checkAndSync()
                } catch (e: Exception) {
                    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                    Logger.w(TAG, "Poll error: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** 停止定时轮询。 */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * v1.132: 注册 WorkManager 周期任务兜底。
     *
     * 在 [io.zer0.muse.MuseApp.onCreate] 中调用一次。KEEP 策略:已存在则保留旧 schedule,
     * 避免重复注册。15 分钟为 Android 系统最小周期。
     *
     * 不设 setExpedited / 网络约束:符合"省电"目标,无网时 exportToCloud 内部失败被记录,
     * 下次 Worker 触发会重试。与 [ScheduledTaskWorker] 兜底设计完全对齐。
     */
    fun registerWorkManagerFallback(context: Context) {
        try {
            val request = PeriodicWorkRequestBuilder<CloudBackupWorker>(
                15, TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                CloudBackupWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            Logger.i(TAG, "WorkManager fallback registered")
        } catch (e: Exception) {
            Logger.w(TAG, "registerWorkManagerFallback failed: ${e.message}")
        }
    }

    /**
     * v1.132: 供 [CloudBackupWorker] 调用的一次性检查入口(Worker 进程被系统拉起时使用)。
     *
     * 与协程轮询共用 [checkAndSync] 实现,Worker 进程内 Koin 已初始化(MuseApp.onCreate
     * 已执行),依赖解析正常。
     */
    suspend fun checkAndSyncForWorker() = checkAndSync()

    /**
     * 检查是否到期,到期则触发上传。
     * 条件:autoSync=true && isConfigured=true && 距上次同步 ≥ intervalHours。
     */
    private suspend fun checkAndSync() {
        val config = settings.cloudBackupConfigFlow.first()
        if (!config.autoSync || !config.isConfigured) return

        val intervalHours = config.autoSyncIntervalHours.coerceAtLeast(MIN_INTERVAL_HOURS)
        val intervalMs = intervalHours * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        val elapsed = now - config.lastSyncAt

        if (elapsed < intervalMs) {
            Logger.d(TAG, "Not due yet: ${elapsed / 60000}min elapsed, need ${intervalMs / 60000}min")
            return
        }

        Logger.i(TAG, "Auto backup due (${elapsed / 3600000}h elapsed, interval=${intervalHours}h), uploading...")
        val ok = backupService.exportToCloud()
        if (ok) {
            Logger.i(TAG, "Auto backup succeeded")
        } else {
            Logger.w(TAG, "Auto backup failed")
        }
    }
}
