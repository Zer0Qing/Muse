package io.zer0.muse.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.zer0.common.Logger
import io.zer0.common.resultOf
import org.koin.core.context.GlobalContext

/**
 * v1.134: 自动本地数据库备份 Worker。
 *
 * [AutoBackupHelper] 类已存在(v1.107)但从未接入调度系统(孤儿组件)。
 * 本 Worker 通过 WorkManager 每日周期任务拉起,执行:
 *  1. [AutoBackupHelper.backupNow] — WAL checkpoint + 复制 muse.db 到 backups/
 *  2. [AutoBackupHelper.trimOldBackups] — 保留最近 7 份,清理更旧的
 *
 * 设计取舍(与 [ScheduledTaskWorker] 一致):
 *  - 周期 24 小时(每日 1 次,符合 AutoBackupHelper 类注释约定)
 *  - 不使用 setExpedited:备份非紧急
 *  - 不要求网络约束:本地操作,无需网络
 *  - 返回 Result.success():失败已记录到 auto_backup_log 表,无需 WorkManager 重试
 *
 * Koin 拿依赖:WorkManager 默认跑在主进程,Worker 启动时 MuseApp.onCreate 已执行,
 * Koin 已初始化。若处于 Safe Mode(startKoin 失败),GlobalContext 为空,
 * 这里捕获异常返回 success 跳过本次,等下次启动恢复。
 */
class AutoBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val koin = resultOf { GlobalContext.get() }.getOrNull()
        if (koin == null) {
            Logger.w(TAG, "Koin 未初始化(Safe Mode?),跳过本次 Worker 执行")
            return Result.success()
        }
        val helper = resultOf { koin.get<io.zer0.muse.data.stats.AutoBackupHelper>() }.getOrNull()
        if (helper == null) {
            Logger.w(TAG, "AutoBackupHelper 解析失败,跳过本次 Worker 执行")
            return Result.success()
        }
        resultOf {
            val ok = helper.backupNow()
            if (ok) helper.trimOldBackups()
        }.onError { msg, t -> Logger.w(TAG, "backupNow failed: ${t?.message ?: msg}") }
        // 不论内部是否成功,都返回 success:失败已记录到 auto_backup_log 表
        return Result.success()
    }

    companion object {
        private const val TAG = "AutoBackupWorker"
        const val UNIQUE_WORK_NAME = "muse_auto_backup_worker"
    }
}
