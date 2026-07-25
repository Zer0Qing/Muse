package io.zer0.muse.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.zer0.common.Logger
import io.zer0.common.resultOf
import org.koin.core.context.GlobalContext

/**
 * v1.134: 统计聚合缓存刷新 Worker。
 *
 * [StatsCacheManager] 类已存在(v1.107)但从未接入调度系统(孤儿组件)。
 * 本 Worker 通过 WorkManager 每日周期任务拉起,调用 [StatsCacheManager.refreshAll]
 * 全量刷新各聚合缓存(消息数/模型分布/小时分布/Top 会话等),避免统计页每次打开都
 * 触发全表 GROUP BY 扫描。
 *
 * 设计取舍(与 [ScheduledTaskWorker] 一致):
 *  - 周期 24 小时(每日 1 次,符合 StatsCacheManager 类注释约定)
 *  - 不使用 setExpedited:统计缓存非紧急
 *  - 不要求网络约束:本地数据库操作
 *  - 返回 Result.success():失败仅记录警告,下次 Worker 触发会重试
 *
 * Koin 拿依赖:WorkManager 默认跑在主进程,Worker 启动时 MuseApp.onCreate 已执行,
 * Koin 已初始化。若处于 Safe Mode(startKoin 失败),GlobalContext 为空,
 * 这里捕获异常返回 success 跳过本次,等下次启动恢复。
 */
class StatsCacheWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val koin = resultOf { GlobalContext.get() }.getOrNull()
        if (koin == null) {
            Logger.w(TAG, "Koin 未初始化(Safe Mode?),跳过本次 Worker 执行")
            return Result.success()
        }
        val manager = resultOf { koin.get<io.zer0.muse.data.stats.StatsCacheManager>() }.getOrNull()
        if (manager == null) {
            Logger.w(TAG, "StatsCacheManager 解析失败,跳过本次 Worker 执行")
            return Result.success()
        }
        resultOf { manager.refreshAll() }
            .onError { msg, t -> Logger.w(TAG, "refreshAll failed: ${t?.message ?: msg}") }
        // 不论内部是否成功,都返回 success:失败仅记录警告
        return Result.success()
    }

    companion object {
        private const val TAG = "StatsCacheWorker"
        const val UNIQUE_WORK_NAME = "muse_stats_cache_worker"
    }
}
