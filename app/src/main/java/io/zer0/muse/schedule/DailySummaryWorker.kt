package io.zer0.muse.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

/**
 * v1.0.84: 每日总结 Worker — 每天 09:00、12:00、21:00、00:00 生成小结。
 *
 * 为什么做:用户希望主动消息里"今天的总结"至少每天固定时间推一次(而非概率触发),
 * 让助手像真正陪伴一样,晚上定时回顾一天聊了什么。
 *
 * 实现:
 *  - OneTimeWorkRequest 在目标时间触发,执行完自续期到下一天同一时间
 *    (WorkManager 的 PeriodicWork 最小周期 15 分钟,无法精确到小时级定点,
 *    用 OneTime + 自续期实现精确的每日定点)
 *  - 素材:目标总结日(00:00 时为前一天)用户的消息 + 主记忆近期事项(fact)
 *  - 生成:LLM 2-4 句话自然总结(风格:口语、不官方、像朋友晚上跟你复盘)
 *  - 无素材时生成自然问候，保证四个固定时点都有结果
 *  - 失败返回 success 不重试(明天自续期会再触发),避免 WorkManager 30s 反复重试
 *
 * 注意:Worker 触发时若 App 进程被杀,WorkManager 拉起新进程执行,
 * Koin 已由 MuseApp.onCreate 初始化(与 ProactiveMessageWorker 同理)。
 */
class DailySummaryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val koin = resultOf { GlobalContext.get() }.getOrNull()
        if (koin == null) {
            Logger.w(TAG, "Koin 未初始化,跳过本次执行")
            scheduleNext(applicationContext)
            return Result.success()
        }
        val settings = resultOf { koin.get<SettingsRepository>() }.getOrNull()
        if (settings == null) {
            Logger.w(TAG, "SettingsRepository 解析失败,跳过")
            scheduleNext(applicationContext)
            return Result.success()
        }
        // 开关只控制通知,不阻止总结生成与首页展示。
        // 首页问候语依赖 dailySummaryFlow;若这里整体跳过,用户即使不想要通知也永远看不到总结。
        val enabled = resultOf { settings.dailySummaryEnabledFlow.first() }.getOrNull() ?: true
        val slotHour = inputData.getInt(KEY_SLOT_HOUR, DEFAULT_HOUR)
        val slotMinute = inputData.getInt(KEY_SLOT_MINUTE, DEFAULT_MINUTE)
        val targetDate = inputData.getString(KEY_TARGET_DATE)
            ?: java.time.LocalDate.now().toString()
        val slotKey = inputData.getString(KEY_SLOT_KEY)
            ?: slotKey(targetDate, slotHour, slotMinute)

        // Worker 与首页前台补偿共用同一个服务；服务内部负责 DataStore 抢占、生成和失败释放。
        val summaryService = resultOf { koin.get<DailySummaryService>() }
            .onError { msg, t -> Logger.w(TAG, "DailySummaryService 解析失败: ${t?.message ?: msg}") }
            .getOrNull()
        if (summaryService == null) {
            Logger.w(TAG, "DailySummaryService 未就绪,跳过本次执行: $slotKey")
        } else {
            resultOf {
                summaryService.generateForSlot(
                    slotKey = slotKey,
                    summaryDate = summaryDateForTarget(targetDate, slotHour),
                    notificationsEnabled = enabled,
                )
            }.onError { msg, t -> Logger.w(TAG, "每日总结生成失败: ${t?.message ?: msg}") }
        }

        // 无论成败都自续期到下一次同一时点
        scheduleNextSlot(applicationContext, slotHour, slotMinute)
        return Result.success()
    }

    companion object {
        private const val TAG = "DailySummaryWorker"
        const val UNIQUE_WORK_NAME = "muse_daily_summary_worker"

        /** 每日总结通知 id(避开问候语 1010 / 主动消息等其他 id)。 */
        const val NOTIF_ID_DAILY_SUMMARY = 1012

        /** 总结时点(24 小时制)。 */
        val SUMMARY_SLOTS: List<Pair<Int, Int>> = listOf(
            9 to 0,
            12 to 0,
            21 to 0,
            0 to 0,
        )
        const val DEFAULT_HOUR = 9
        const val DEFAULT_MINUTE = 0
        const val KEY_SLOT_HOUR = "slot_hour"
        const val KEY_SLOT_MINUTE = "slot_minute"
        const val KEY_TARGET_DATE = "target_date"
        const val KEY_SLOT_KEY = "slot_key"

        /** 计算距指定时点的延迟；恰好到点时顺延到下一天，避免重复执行。 */
        fun computeDelayToNextTarget(
            nowMillis: Long,
            targetHour: Int = DEFAULT_HOUR,
            targetMinute: Int = DEFAULT_MINUTE,
        ): Long {
            require(targetHour in 0..23) { "targetHour out of range" }
            require(targetMinute in 0..59) { "targetMinute out of range" }
            val now = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
            val target = java.util.Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(java.util.Calendar.HOUR_OF_DAY, targetHour)
                set(java.util.Calendar.MINUTE, targetMinute)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (!target.after(now)) target.add(java.util.Calendar.DAY_OF_YEAR, 1)
            return target.timeInMillis - now.timeInMillis
        }

        /** 下一个目标时点对应的本地日期。 */
        fun nextTargetDate(
            nowMillis: Long,
            targetHour: Int,
            targetMinute: Int,
        ): java.time.LocalDate {
            val now = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
            val target = java.util.Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(java.util.Calendar.HOUR_OF_DAY, targetHour)
                set(java.util.Calendar.MINUTE, targetMinute)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (!target.after(now)) target.add(java.util.Calendar.DAY_OF_YEAR, 1)
            return target.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        }

        fun summaryDateForTarget(targetDate: String, targetHour: Int): java.time.LocalDate {
            val date = java.time.LocalDate.parse(targetDate)
            return if (targetHour == 0) date.minusDays(1) else date
        }

        fun slotKey(targetDate: String, targetHour: Int, targetMinute: Int): String =
            "$targetDate#${targetHour.toString().padStart(2, '0')}${targetMinute.toString().padStart(2, '0')}"

        /** 注册四个独立时点的下一次任务，避免 REPLACE 把其他时点覆盖掉。 */
        fun scheduleNext(context: Context) {
            val workManager = WorkManager.getInstance(context)
            resultOf { workManager.cancelUniqueWork(UNIQUE_WORK_NAME) }
                .onError { msg, t -> Logger.w(TAG, "清理旧版每日总结任务失败: ${t?.message ?: msg}") }
            SUMMARY_SLOTS.forEach { (hour, minute) ->
                // App 冷启动可能发生在 Worker 所在进程刚被拉起时，不能 REPLACE 正在执行的自身任务。
                scheduleNextSlot(context, hour, minute, ExistingWorkPolicy.KEEP)
            }
        }

        /**
         * 进程存活时的准点补触发器。
         * WorkManager 负责被杀后的兜底；这里在进程内等到四个时点，立即投递对应 Worker，
         * 这样前台或仍存活的进程不依赖 WorkManager 的周期调度精度。
         */
        fun startInProcess(
            context: Context,
            scope: CoroutineScope,
        ): Job = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val next = SUMMARY_SLOTS.minByOrNull { (hour, minute) ->
                    computeDelayToNextTarget(now, hour, minute)
                } ?: break
                val delayMillis = computeDelayToNextTarget(now, next.first, next.second)
                delay(delayMillis.coerceAtLeast(1_000L))
                enqueueDueSlot(context, next.first, next.second)
            }
        }

        private fun enqueueDueSlot(context: Context, targetHour: Int, targetMinute: Int) {
            val targetDate = java.time.LocalDate.now().toString()
            val key = slotKey(targetDate, targetHour, targetMinute)
            val request = OneTimeWorkRequestBuilder<DailySummaryWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SLOT_HOUR to targetHour,
                        KEY_SLOT_MINUTE to targetMinute,
                        KEY_TARGET_DATE to targetDate,
                        KEY_SLOT_KEY to key,
                    ),
                )
                .build()
            resultOf {
                // 到点时替换掉原先的延迟请求，立即执行本次时点。
                // 使用独立的到点任务名，不取消同一时点可能已经开始执行的延迟 Worker；
                // 最终由 claimDailySummarySlot 保证只生成一次。
                WorkManager.getInstance(context).enqueueUniqueWork(
                    dueWorkName(targetHour, targetMinute, targetDate),
                    ExistingWorkPolicy.KEEP,
                    request,
                )
            }.onError { msg, t -> Logger.w(TAG, "进程内每日总结触发失败($key): ${t?.message ?: msg}") }
        }

        /**
         * 前台打开首页时的补触发。
         *
         * WorkManager 在部分 ROM 上可能被省电策略延迟;如果当前日期已经经过一个
         * 总结时点,补投递最近的时点到期任务。Worker 内部仍由 claimDailySummarySlot
         * 做幂等抢占,因此不会因为首页重组或多次回到前台而重复调用 LLM。
         */
        suspend fun enqueueCatchUpIfDue(
            context: Context,
            settings: SettingsRepository,
            nowMillis: Long = System.currentTimeMillis(),
        ) {
            val now = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
            val currentMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                now.get(java.util.Calendar.MINUTE)
            val due = SUMMARY_SLOTS
                .filter { (hour, minute) -> hour * 60 + minute <= currentMinutes }
                .maxByOrNull { (hour, minute) -> hour * 60 + minute }
                ?: return
            val targetDate = java.time.Instant.ofEpochMilli(nowMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
                .toString()
            val key = slotKey(targetDate, due.first, due.second)
            if (!settings.isDailySummarySlotCompleted(key)) {
                Logger.i(TAG, "前台补触发每日总结: $key")
                enqueueDueSlot(context, due.first, due.second)
            } else {
                Logger.d(TAG, "前台补触发跳过已完成时点: $key")
            }
        }

        fun scheduleNextSlot(
            context: Context,
            targetHour: Int,
            targetMinute: Int,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
        ) {
            val now = System.currentTimeMillis()
            val targetDate = nextTargetDate(now, targetHour, targetMinute).toString()
            val key = slotKey(targetDate, targetHour, targetMinute)
            val delayMillis = computeDelayToNextTarget(now, targetHour, targetMinute)
            val request = OneTimeWorkRequestBuilder<DailySummaryWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SLOT_HOUR to targetHour,
                        KEY_SLOT_MINUTE to targetMinute,
                        KEY_TARGET_DATE to targetDate,
                        KEY_SLOT_KEY to key,
                    ),
                )
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
            resultOf {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    uniqueWorkName(targetHour, targetMinute),
                    policy,
                    request,
                )
            }.onError { msg, t -> Logger.w(TAG, "每日总结调度失败($key): ${t?.message ?: msg}") }
        }

        fun uniqueWorkName(targetHour: Int, targetMinute: Int): String =
            "${UNIQUE_WORK_NAME}_${targetHour.toString().padStart(2, '0')}${targetMinute.toString().padStart(2, '0')}"

        private fun dueWorkName(targetHour: Int, targetMinute: Int, targetDate: String): String =
            "${uniqueWorkName(targetHour, targetMinute)}_due_$targetDate"
    }
}
