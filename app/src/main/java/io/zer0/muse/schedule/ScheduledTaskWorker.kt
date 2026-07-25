package io.zer0.muse.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.zer0.common.Logger
import io.zer0.common.resultOf
import org.koin.core.context.GlobalContext

/**
 * v1.104 P3: ScheduledTaskRunner 的 WorkManager 兜底。
 *
 * ScheduledTaskRunner 协程轮询仅在 App 进程存活时有效,App 被杀后定时任务无法触发。
 * 本 Worker 通过 WorkManager 周期性调度(Android 最小周期 15 分钟),进程被杀也能由系统拉起执行。
 *
 * doWork 调用 [ScheduledTaskRunner.tickOnceForWorker] 一次性扫描所有到期任务:
 *  - 通知渠道在 Worker 进程首次执行时幂等创建
 *  - executeTask 内部已用 resultOf 包裹 LLM 调用 / 写库 / 通知,任一失败都会记录 failed execution
 *
 * 设计取舍:
 *  - 不使用 setExpedited:任务可被 Doze 推迟,符合"省电"目标;定时任务本来就不是紧急实时
 *  - 不要求网络约束:无网时 LLM 调用会失败但被记录,下次 Worker 触发会重试到期任务
 *  - 返回 Result.success() 而非 retry:executeTask 内部已记录失败并推进 next_run_at,
 *    返回 retry 反而会让系统频繁重试一个本就无解的网络问题,更费电
 *
 * Koin 拿依赖:WorkManager 默认跑在主进程,Worker 启动时 MuseApp.onCreate 已执行,
 * Koin 已初始化。若处于 Safe Mode(startKoin 失败),GlobalContext 为空,
 * 这里捕获异常返回 success 跳过本次,等下次启动恢复。
 */
class ScheduledTaskWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val koin = resultOf { GlobalContext.get() }.getOrNull()
        if (koin == null) {
            Logger.w(TAG, "Koin 未初始化(Safe Mode?),跳过本次 Worker 执行")
            return Result.success()
        }
        val runner = resultOf { koin.get<ScheduledTaskRunner>() }.getOrNull()
        if (runner == null) {
            Logger.w(TAG, "ScheduledTaskRunner 解析失败,跳过本次 Worker 执行")
            return Result.success()
        }
        resultOf { runner.tickOnceForWorker() }
            .onError { msg, t -> Logger.w(TAG, "tickOnceForWorker failed: ${t?.message ?: msg}") }
        // 不论内部是否成功,都返回 success:executeTask 已原子记录失败 + 推进 next_run_at,
        // 返回 retry 会让 WorkManager 短时间内反复重试(默认 30 秒),反而更费电
        return Result.success()
    }

    companion object {
        private const val TAG = "ScheduledTaskWorker"
        const val UNIQUE_WORK_NAME = "muse_scheduled_task_worker"
    }
}
