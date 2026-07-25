package io.zer0.muse.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.zer0.common.Logger
import io.zer0.common.resultOf
import org.koin.core.context.GlobalContext

/**
 * v1.132: 云备份的 WorkManager 兜底 Worker。
 *
 * [CloudBackupScheduler] 的协程轮询仅在 App 进程存活时有效,App 被杀后定时备份无法触发。
 * 本 Worker 通过 WorkManager 周期性调度(Android 最小周期 15 分钟),进程被杀也能由系统
 * 拉起执行,补齐"App 被杀后自动备份"的可靠性缺口。
 *
 * doWork 调用 [CloudBackupScheduler.checkAndSyncForWorker] 一次性检查是否到期:
 *  - autoSync=false 或 isConfigured=false 时直接跳过
 *  - 距上次同步 < intervalHours 时跳过(未到期)
 *  - 到期则触发 [BackupService.exportToCloud] 上传
 *
 * 设计取舍(与 [ScheduledTaskWorker] 对齐):
 *  - 不使用 setExpedited:备份任务可被 Doze 推迟,符合"省电"目标
 *  - 不要求网络约束:无网时 exportToCloud 内部失败被记录,下次 Worker 触发会重试
 *  - 返回 Result.success() 而非 retry:返回 retry 会让 WorkManager 短时间内反复重试
 *    (默认 30 秒)一个本就无解的网络问题,反而更费电
 *
 * Koin 拿依赖:WorkManager 默认跑在主进程,Worker 启动时 MuseApp.onCreate 已执行,
 * Koin 已初始化。若处于 Safe Mode(startKoin 失败),GlobalContext 为空,
 * 这里捕获异常返回 success 跳过本次,等下次启动恢复。
 */
class CloudBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val koin = resultOf { GlobalContext.get() }.getOrNull()
        if (koin == null) {
            Logger.w(TAG, "Koin 未初始化(Safe Mode?),跳过本次 Worker 执行")
            return Result.success()
        }
        val scheduler = resultOf { koin.get<CloudBackupScheduler>() }.getOrNull()
        if (scheduler == null) {
            Logger.w(TAG, "CloudBackupScheduler 解析失败,跳过本次 Worker 执行")
            return Result.success()
        }
        resultOf { scheduler.checkAndSyncForWorker() }
            .onError { msg, t -> Logger.w(TAG, "checkAndSyncForWorker failed: ${t?.message ?: msg}") }
        // 不论内部是否成功,都返回 success:autoSync 检查 + 到期判断都在 checkAndSync 内,
        // 返回 retry 会让 WorkManager 频繁重试(默认 30 秒),反而更费电
        return Result.success()
    }

    companion object {
        private const val TAG = "CloudBackupWorker"
        const val UNIQUE_WORK_NAME = "muse_cloud_backup_worker"
    }
}
