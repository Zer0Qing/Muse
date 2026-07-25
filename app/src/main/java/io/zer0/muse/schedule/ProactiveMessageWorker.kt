package io.zer0.muse.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.zer0.common.Logger
import io.zer0.common.resultOf
import org.koin.core.context.GlobalContext

/**
 * v1.134: ProactiveMessageRunner 的 WorkManager 兜底。
 *
 * ProactiveMessageRunner 协程轮询仅在 App 进程存活时有效,App 被杀后主动消息无法触发。
 * 本 Worker 通过 WorkManager 周期性调度(Android 最小周期 15 分钟),进程被杀也能由系统拉起执行。
 *
 * doWork 调用 [ProactiveMessageRunner.tickOnceForWorker] 一次性检查是否到期:
 *  - 时间窗口检查(allowedHourStart/End)在 tickOnceForWorker 内部完成
 *  - LLM 决策调用 / 写库 / 通知任一失败都会被 resultOf 捕获
 *
 * 设计取舍(与 [ScheduledTaskWorker] 一致):
 *  - 不使用 setExpedited:主动消息非紧急,可被 Doze 推迟
 *  - 不要求网络约束:无网时 LLM 调用失败被记录,下次 Worker 触发会重试
 *  - 返回 Result.success() 而非 retry:主动消息更新 lastTriggeredAt 后本次就跳过,
 *    返回 retry 会让系统 30s 内反复重试一个本就无解的网络问题
 *
 * Koin 拿依赖:WorkManager 默认跑在主进程,Worker 启动时 MuseApp.onCreate 已执行,
 * Koin 已初始化。若处于 Safe Mode(startKoin 失败),GlobalContext 为空,
 * 这里捕获异常返回 success 跳过本次,等下次启动恢复。
 */
class ProactiveMessageWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val koin = resultOf { GlobalContext.get() }.getOrNull()
        if (koin == null) {
            Logger.w(TAG, "Koin 未初始化(Safe Mode?),跳过本次 Worker 执行")
            return Result.success()
        }
        val runner = resultOf { koin.get<ProactiveMessageRunner>() }.getOrNull()
        if (runner == null) {
            Logger.w(TAG, "ProactiveMessageRunner 解析失败,跳过本次 Worker 执行")
            return Result.success()
        }
        resultOf { runner.tickOnceForWorker() }
            .onError { msg, t -> Logger.w(TAG, "tickOnceForWorker failed: ${t?.message ?: msg}") }
        // 不论内部是否成功,都返回 success:避免 WorkManager 30s 内反复重试
        return Result.success()
    }

    companion object {
        private const val TAG = "ProactiveMsgWorker"
        const val UNIQUE_WORK_NAME = "muse_proactive_message_worker"
    }
}
