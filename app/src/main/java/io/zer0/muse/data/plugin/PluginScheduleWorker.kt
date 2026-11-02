package io.zer0.muse.data.plugin

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.schedule.scheduleWorkEnabledOrFalse
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext

/**
 * 插件定时任务的**后台唤醒**（`schedule.background`）。
 *
 * 没有这条之前，插件任务只在应用进程存活时按间隔触发：用户把应用划掉之后，
 * "每天整理一次笔记"这类任务就再也不会跑，直到他下次打开应用 —— 而那一刻
 * 所有过期的任务会一起涌上来。
 *
 * 这个 Worker 由 WorkManager 每 15 分钟拉起一次（系统下限），做的事情很少：
 * 读出"上次执行时间"，把**到期的**任务补跑一次。判定逻辑与进程内那套完全一致
 * （[PluginScheduleDue]），只是触发源多了一路。
 *
 * 四条前置，任一不满足就直接返回：
 *  1. 安全模式总闸没关（[PluginKillSwitch]）；
 *  2. 后台调度总控开着；
 *  3. 插件全局开关开着；
 *  4. 至少有一个插件拿到了 `schedule.background` 授权 —— 没授权就没必要唤醒进程。
 *
 * 一律返回 success：定时任务不是必须送达的东西，retry 只会让系统在 30 秒后
 * 再拉起一次同样无解的唤醒。
 */
class PluginScheduleWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val koin = resultOf { GlobalContext.get() }.getOrNull()
        val workEnabled = koin != null && scheduleWorkEnabledOrFalse(
            resultOf { koin.get<io.zer0.muse.data.SettingsRepository>()?.scheduleWorkEnabledFlow?.first() }
                .getOrNull(),
        )
        val runner = koin?.let { resultOf { it.get<PluginScheduleRunner>() }.getOrNull() }
        when {
            koin == null -> Logger.w(TAG, "Koin 未初始化，跳过本次唤醒")
            PluginKillSwitch.isDisabled(applicationContext) ->
                Logger.i(TAG, "安全模式总闸已关，跳过本次唤醒")
            !workEnabled -> Logger.i(TAG, "后台调度总控已关闭，跳过本次唤醒")
            runner == null -> Logger.w(TAG, "PluginScheduleRunner 解析失败，跳过本次唤醒")
            else -> {
                val dispatched = resultOf { runner.tick(background = true) }
                    .onError { msg, t -> Logger.w(TAG, "插件定时任务执行失败: ${t?.message ?: msg}") }
                    .getOrNull()
                Logger.i(TAG, "后台唤醒完成，补跑 ${dispatched ?: 0} 个任务")
            }
        }
        // 一律 success：定时任务不是必须送达的东西，retry 只会让系统 30 秒后再拉起一次同样无解的唤醒
        return Result.success()
    }

    companion object {
        private const val TAG = "PluginScheduleWorker"

        /** 唯一任务名（KEEP 策略：已存在则保留旧排期）。 */
        const val UNIQUE_WORK_NAME = "muse_plugin_schedule_worker_v1"
    }
}
