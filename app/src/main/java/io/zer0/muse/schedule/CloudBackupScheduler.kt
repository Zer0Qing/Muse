package io.zer0.muse.schedule

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.zer0.common.Logger
import io.zer0.muse.backup.BackupService
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.notification.MuseNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * v1.98: 云备份自动定时上传调度器。
 *
 * 当 [CloudBackupConfig.autoSync] = true 且 [CloudBackupConfig.isConfigured] = true 时,
 * 每隔 [CloudBackupConfig.autoSyncIntervalHours] 小时自动调用 [BackupService.exportToCloud]。
 *
 * 设计与 [ScheduledTaskRunner] 一致:在 appScope 中轮询,每 10 分钟检查一次是否到期,
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
    private val notificationManager: MuseNotificationManager,
) {
    private var job: Job? = null

    /**
     * B-11: 进程内互斥 —— 10min 协程轮询与 15min Worker(同主进程)会并发调用
     * [checkAndSync] 触发重复 exportToCloud,用 Mutex 串行临界区。
     */
    private val syncMutex = Mutex()
    /**
     * B-11: 内存抢占标记 —— 触发上传前前置写入,防止临界区外的重复调用
     * 在同窗内再次触发。内存标记仅对同进程可见;跨进程(WorkManager 独立进程)
     * 仍有竞态窗口,见 [checkAndSync] 注释。
     */
    @Volatile
    private var syncStartedAt: Long = 0L

    companion object {
        private const val TAG = "CloudBackupSched"
        /** 轮询间隔:10 分钟检查一次是否到期(不需要更频繁,备份间隔最小 1 小时)。 */
        private const val POLL_INTERVAL_MS = 10 * 60 * 1000L
        /** 最小间隔:1 小时(防止用户设置过小导致频繁上传)。 */
        private const val MIN_INTERVAL_HOURS = 1
        /** B-11: 同窗口抢占保护时长(取轮询间隔,覆盖一轮轮询内可能的重复触发)。 */
        private const val SYNC_GUARD_MS = 10 * 60 * 1000L
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
     *
     * B-11: 使用 [syncMutex] 在进程内串行,保证 10min 协程轮询与 15min Worker
     * 不会同时进入上传临界区;触发前前置写入 [syncStartedAt] 作为抢占标记,
     * 对临界区外的并发调用(未取得锁的快速路径)也做同窗口拦截。
     * 跨进程窗口:WorkManager 若跑在独立进程,DataStore/内存标记分属不同进程,
     * 理论仍可并发触发一次重复上传,最终由 [BackupService.exportToCloud] 幂等写入。
     *
     * B-25: 后台调度总控 — 关闭时跳过执行体,周期调度本身仍保留,重新打开即恢复。
     */
    private suspend fun checkAndSync() {
        // B-25: 总控关闭时直接跳过(读 DataStore 缓存值,成本低)
        if (!settings.scheduleWorkEnabledFlow.first()) {
            Logger.i(TAG, "后台调度总控已关闭,跳过云备份检查")
            return
        }
        // B-11: 快速抢占检查(未加锁):同窗口已有上传在途则跳过,避免重复触发
        val now = System.currentTimeMillis()
        if (syncStartedAt > 0 && now - syncStartedAt < SYNC_GUARD_MS) {
            Logger.d(TAG, "云备份已在途(syncStartedAt 抢占),跳过本次检查")
            return
        }
        syncMutex.withLock {
            val config = settings.cloudBackupConfigFlow.first()
            if (!config.autoSync || !config.isConfigured) return@withLock

            val intervalHours = config.autoSyncIntervalHours.coerceAtLeast(MIN_INTERVAL_HOURS)
            val intervalMs = intervalHours * 60 * 60 * 1000L
            val lockNow = System.currentTimeMillis()
            if (lockNow - config.lastSyncAt < intervalMs) {
                Logger.d(TAG, "Not due yet: ${(lockNow - config.lastSyncAt) / 60000}min elapsed, need ${intervalMs / 60000}min")
                return@withLock
            }

            // B-11: 触发前前置写入同步开始标记,形成进程内抢占
            syncStartedAt = lockNow
            Logger.i(TAG, "Auto backup due (${(lockNow - config.lastSyncAt) / 3600000}h elapsed, interval=${intervalHours}h), uploading...")
            try {
                val outcome = backupService.exportToCloud()
                when (outcome) {
                    io.zer0.muse.backup.BackupService.CloudBackupOutcome.SUCCESS ->
                        Logger.i(TAG, "Auto backup succeeded")
                    io.zer0.muse.backup.BackupService.CloudBackupOutcome.WRITE_FAILED ->
                        Logger.w(TAG, "Auto backup failed: write failed")
                    io.zer0.muse.backup.BackupService.CloudBackupOutcome.VERIFY_FAILED ->
                        Logger.w(TAG, "Auto backup failed: read-back verification failed")
                    io.zer0.muse.backup.BackupService.CloudBackupOutcome.NOT_CONFIGURED ->
                        Logger.d(TAG, "Auto backup skipped: not configured")
                    // B-9: 备份密码失效(Keystore 丢失)时按失败处理并告警
                    io.zer0.muse.backup.BackupService.CloudBackupOutcome.PASSWORD_UNAVAILABLE ->
                        Logger.w(TAG, "Auto backup failed: backup password unavailable, please re-set it in cloud backup settings")
                }
                // v1.141 F1 + F-04: 备份提醒 — 定时自动备份完成/失败均发系统通知,
                // 失败按原因(写入/校验)分类提醒,让用户及时得知备份中断并检查云配置
                notificationManager.notifyAutoBackup(outcome)
            } finally {
                // 上传结束释放抢占标记,允许下一窗口的正常调度
                syncStartedAt = 0L
            }
        }
    }
}
