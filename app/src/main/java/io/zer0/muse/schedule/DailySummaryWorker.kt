package io.zer0.muse.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.zer0.ai.ChatService
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactStore
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.notification.MuseNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

        // 同一个时点可能由 WorkManager 和进程内调度同时触发，先抢占再生成，避免重复调用 LLM。
        val claimed = resultOf { settings.claimDailySummarySlot(slotKey) }.getOrNull() ?: false
        if (!claimed) {
            Logger.d(TAG, "每日总结时点已执行,跳过: $slotKey")
            scheduleNextSlot(applicationContext, slotHour, slotMinute)
            return Result.success()
        }

        val generated = resultOf {
            runGenerate(
                koin = koin,
                settings = settings,
                notificationsEnabled = enabled,
                summaryDate = summaryDateForTarget(targetDate, slotHour),
            )
        }
            .onError { msg, t -> Logger.w(TAG, "每日总结生成失败: ${t?.message ?: msg}") }
            .getOrNull() == true
        if (!generated) {
            // 只要本次没有成功保存总结，就释放占位，允许后续兜底任务重试。
            resultOf { settings.releaseDailySummarySlot(slotKey) }
                .onError { msg, t -> Logger.w(TAG, "释放每日总结时点失败: ${t?.message ?: msg}") }
        }

        // 无论成败都自续期到下一次同一时点
        scheduleNextSlot(applicationContext, slotHour, slotMinute)
        return Result.success()
    }

    /** 生成今日小结并发送通知。 */
    private suspend fun runGenerate(
        koin: org.koin.core.Koin,
        settings: SettingsRepository,
        notificationsEnabled: Boolean,
        summaryDate: java.time.LocalDate,
    ): Boolean {
        val sessionRepository = resultOf { koin.get<SessionRepository>() }.getOrNull() ?: return false
        val chatService = resultOf { koin.get<ChatService>() }.getOrNull() ?: return false
        val factStore = resultOf { koin.get<FactStore>() }.getOrNull() ?: return false
        val notificationManager = resultOf { koin.get<MuseNotificationManager>() }.getOrNull() ?: return false

        val zone = java.time.ZoneId.systemDefault()
        val dayStart = summaryDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = summaryDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        // 素材1:目标总结日用户发的消息(最多 40 条),00:00 总结前一天。
        val todayMessages = withContext(Dispatchers.IO) {
            resultOf { sessionRepository.getUserMessagesBetween(dayStart, dayEnd, 40) }.getOrNull() ?: emptyList()
        }
        // 素材2:主记忆近期事项(最近 30 条,含 time 字段指向未来的)
        val facts = resultOf { factStore.getAll("main") }.getOrNull() ?: emptyList()
        val todayFacts = facts
            .sortedByDescending { it.createdAt }
            .take(30)
            .mapNotNull { fact ->
                val timePart = fact.time?.takeIf { it.isNotBlank() }?.let { " (时间:$it)" } ?: ""
                "${fact.fact}$timePart"
            }

        // v1.0.72: 无素材不再跳过 — 每日总结固定必发。
        // 有对话 → 总结今天;无对话但有记忆 → 提醒近期事项;
        // 都没有 → 温暖问候(保证用户每天固定时间都能收到一条,兑现"固定推送"承诺)。
        val summary = generateSummary(chatService, todayMessages, todayFacts)
        if (summary.isNullOrBlank()) {
            Logger.w(TAG, "每日总结生成结果为空,跳过")
            return false
        }

        // 先持久化再判断前台状态:前台不弹通知时,首页仍能在问候语后展示这次总结。
        settings.saveDailySummary(summaryDate.toString(), summary)

        // 审查修复 (2.0 B-34): 前台静默 — 用户正盯着 app 时不弹 HIGH 声音通知
        // (与主动消息 notifyProactiveMessage 口径一致;此前前台照发,高频打扰用户)
        if (notificationManager.isAppForeground()) {
            Logger.d(TAG, "每日总结:应用在前台,跳过通知(内容已保存,首页问候语可见)")
            return true
        }

        if (!notificationsEnabled) {
            Logger.d(TAG, "每日总结已保存,推送开关关闭,首页问候语可见")
            return true
        }
        notificationManager.notifyReminder(
            title = SUMMARY_TITLES.random(),
            message = summary,
            notificationId = NOTIF_ID_DAILY_SUMMARY,
            target = io.zer0.muse.notification.MuseNotificationTarget.Home,
        )
        Logger.i(TAG, "每日总结已保存并推送: ${summary.take(40)}...")
        return true
    }

    /** 调 LLM 生成今日小结(超时 60s,失败返回 null)。 */
    private suspend fun generateSummary(
        chatService: ChatService,
        todayMessages: List<UIMessage>,
        facts: List<String>,
    ): String? {
        val hasContent = todayMessages.isNotEmpty() || facts.isNotEmpty()
        val systemPrompt = if (hasContent) {
            """
你是用户的陪伴助手。请根据真实素材写一条晚间今日小结。
规则:口语、像朋友复盘;只挑 2-3 个重点;不编造未发生的事;50-80 字;
只输出正文,不要标题、前缀、引号、MOOD 或反思。
            """.trimIndent()
        } else {
            // v1.0.72: 今天无对话也无记忆时的退化模式 — 发一条自然的晚间问候,
            // 不让用户觉得"它又没动静了"。
            """
你是用户的陪伴助手。用户今天没有留下可用素材,请写一句自然的晚间问候。
不要编造今天发生的事;可以问候或轻轻问一句过得怎么样;40 字以内;只输出正文。
            """.trimIndent()
        }
        val userContent = StringBuilder("<summary_material>\n")
        if (todayMessages.isNotEmpty()) {
            userContent.appendLine("今天用户说的话(按时间顺序):")
            todayMessages.forEach { msg ->
                val text = msg.content.replace('\n', ' ').take(120)
                userContent.appendLine("- $text")
            }
        }
        if (facts.isNotEmpty()) {
            userContent.appendLine("用户近期事项(只有与今天相关或明确临近的事项才可提):")
            facts.take(10).forEach { userContent.appendLine("- $it") }
        }
        if (!hasContent) userContent.appendLine("没有可用素材。")
        userContent.appendLine("</summary_material>")

        return resultOf {
            withTimeoutOrNull(GEN_TIMEOUT_MS) {
                // A-13: 每日总结同样过限流闸 — 与主动消息/群聊共享并发上限,
                // 避免叠加触发 429(此前完全绕过 B-22 限流)
                GenerationGate.withPermit {
                    chatService.completeText(
                        messages = listOf(
                            io.zer0.ai.core.UIMessage(
                                role = io.zer0.ai.core.MessageRole.SYSTEM,
                                content = systemPrompt,
                                createdAt = System.currentTimeMillis(),
                            ),
                            io.zer0.ai.core.UIMessage(
                                role = io.zer0.ai.core.MessageRole.USER,
                                content = userContent.toString(),
                                createdAt = System.currentTimeMillis(),
                            ),
                        ),
                        temperature = 0.6f,
                        maxTokens = 140,
                    // v1.0.74 fix: 剥离 <think> 推理标签,防止思考内容混入每日总结推送
                    ).text.let { io.zer0.muse.transformer.stripThinkTags(it) }
                }
            }
        }.onError { msg, t ->
            Logger.w(TAG, "每日总结 LLM 调用失败: ${t?.message ?: msg}")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val TAG = "DailySummaryWorker"
        const val UNIQUE_WORK_NAME = "muse_daily_summary_worker"

        /** 每日总结通知 id(避开问候语 1010 / 主动消息等其他 id)。 */
        const val NOTIF_ID_DAILY_SUMMARY = 1012

        /** 通知标题随机池。 */
        private val SUMMARY_TITLES = listOf(
            "今日小结",
            "今天过得怎么样",
            "睡前复盘一下",
            "今天你聊了不少",
            "今日回顾",
        )

        /** LLM 生成超时。 */
        private const val GEN_TIMEOUT_MS = 60_000L

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
