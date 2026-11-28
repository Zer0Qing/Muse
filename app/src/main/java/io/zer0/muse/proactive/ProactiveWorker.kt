package io.zer0.muse.proactive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
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
            resultOf { koin.get<io.zer0.muse.data.SettingsRepository>().scheduleWorkEnabledFlow.first() }
                .getOrNull(),
        )
        val scheduler = koin?.takeIf { workEnabled }
            ?.let { resultOf { it.get<ProactiveScheduler>() }.getOrNull() }
        val thoughtId = inputData.getString(THOUGHT_ID)

        when {
            koin == null -> Logger.w(TAG, "Koin 未初始化(Safe Mode?)，跳过本次 Worker")
            !workEnabled -> Logger.i(TAG, "后台调度总控已关闭，跳过本次执行")
            scheduler == null -> Logger.w(TAG, "ProactiveScheduler 解析失败，跳过本次 Worker")
            // 周期巡检可让出这一轮，具体预约仍需在单飞闸腾空后重试。
            scheduler.isCycleInFlight -> {
                Logger.i(TAG, "进程内巡检正在进行，跳过本次 Worker")
                if (thoughtId != null) return Result.retry()
            }
            else -> {
                val outcome = resultOf {
                    val thought = thoughtId?.let { id ->
                        scheduler.findScheduledThought(id, inputData.getLong(THOUGHT_TIME, -1L), System.currentTimeMillis())
                    }
                    if (thoughtId == null || thought != null) {
                        scheduler.runCycle(ProactiveScheduler.TRIGGER_WORKER, scheduledThought = thought)
                    } else {
                        null
                    }
                }.onError { msg, t -> Logger.w(TAG, "巡检失败: ${t?.message ?: msg}") }
                    .getOrNull()
                val retryableVerdict = outcome?.verdict?.let { it in RETRYABLE_VERDICTS } == true
                if (thoughtId != null && (outcome?.alreadyRunning == true || retryableVerdict)) {
                    return Result.retry()
                }
            }
        }

        // 只为可恢复的时机变化重试，避免对已关闭或已失效的预约反复唤醒。
        return Result.success()
    }

    companion object {
        private const val TAG = "ProactiveWorker"
        const val UNIQUE_WORK_NAME = "muse_proactive_worker_v2"
        const val UNIQUE_WAKEUP_WORK_NAME = "muse_proactive_thought_wakeup"
        private const val THOUGHT_ID = "thought_id"
        private const val THOUGHT_TIME = "trigger_at"
        private val RETRYABLE_VERDICTS = setOf(
            io.zer0.muse.data.proactive.ProactiveTimingEngine.Verdict.QUIET_HOUR,
            io.zer0.muse.data.proactive.ProactiveTimingEngine.Verdict.USER_ACTIVE,
            io.zer0.muse.data.proactive.ProactiveTimingEngine.Verdict.IN_CONVERSATION,
            io.zer0.muse.data.proactive.ProactiveTimingEngine.Verdict.TOO_SOON,
        )

        /**
         * 为带 `triggerAt` 的定时牵挂预约一次 WorkManager 一次性唤醒。
         *
         * 各预约独立排队，等待网络时保留原预约的执行机会。
         */
        internal fun scheduleOneShotWakeUp(context: Context, thoughtId: String, triggerAt: Long, delayMs: Long) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()
            val request = androidx.work.OneTimeWorkRequestBuilder<ProactiveWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(THOUGHT_ID to thoughtId, THOUGHT_TIME to triggerAt))
                .addTag(UNIQUE_WAKEUP_WORK_NAME)
                .setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
            androidx.work.WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    "$UNIQUE_WAKEUP_WORK_NAME:$thoughtId:$triggerAt",
                    androidx.work.ExistingWorkPolicy.KEEP,
                    request,
                )
        }
    }
}
