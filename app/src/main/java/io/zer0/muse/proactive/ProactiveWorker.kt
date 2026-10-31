package io.zer0.muse.proactive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.schedule.scheduleWorkEnabledOrFalse
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext

/**
 * 主动消息的 WorkManager 兜底（重建版）。
 *
 * 与旧 Worker 的关键区别是**职责变了**：旧 Worker 每次被拉起都可能触发两次 LLM 调用，
 * 所以「App 被杀 + 每 15 分钟唤醒」本身就是一笔持续开销。
 *
 * 新设计里 Worker 只是一只闹钟：它调 [ProactiveScheduler.runCycle]，
 * 而 runCycle 的前半段（总控 → 开关 → 选助手 → 关系 → 清理 → 记账 → 四问判定）
 * 全是本地操作。绝大多数唤醒会在「账本为空」或「时机不对」处直接返回，
 * 一次网络请求都不会发。
 *
 * 15 分钟是 WorkManager 的周期下限，绕不过。但既然唤醒是免费的，
 * 这个下限就不再是成本问题，只剩精度问题 —— 而精度由 IMMEDIATE 类牵挂的
 * 时效窗口兜底（窗口足够宽，不需要精确到分钟）。
 */
class ProactiveWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val koin = resultOf { GlobalContext.get() }.getOrNull()
        val workEnabled = koin != null && scheduleWorkEnabledOrFalse(
            resultOf { koin.get<io.zer0.muse.data.SettingsRepository>()?.scheduleWorkEnabledFlow?.first() }
                .getOrNull(),
        )
        val scheduler = koin?.takeIf { workEnabled }
            ?.let { resultOf { it.get<ProactiveScheduler>() }.getOrNull() }

        when {
            koin == null -> Logger.w(TAG, "Koin 未初始化(Safe Mode?)，跳过本次 Worker")
            !workEnabled -> Logger.i(TAG, "后台调度总控已关闭，跳过本次执行")
            scheduler == null -> Logger.w(TAG, "ProactiveScheduler 解析失败，跳过本次 Worker")
            // 进程内正好有一轮在跑时，这次唤醒没有意义：那一轮跑完就会重新判定时机，
            // 本次挤进去只会在「刚说过话」上再判一次，白读一次配置。
            scheduler.isCycleInFlight -> Logger.i(TAG, "进程内巡检正在进行，跳过本次 Worker")
            else -> resultOf { scheduler.runCycle(ProactiveScheduler.TRIGGER_WORKER) }
                .onError { msg, t -> Logger.w(TAG, "巡检失败: ${t?.message ?: msg}") }
        }

        // 一律返回 success：主动消息不是必须送达的任务，
        // 返回 retry 会让系统在 30 秒内反复重试一个本就无解的问题。
        return Result.success()
    }

    companion object {
        private const val TAG = "ProactiveWorker"
        const val UNIQUE_WORK_NAME = "muse_proactive_worker_v2"
    }
}
