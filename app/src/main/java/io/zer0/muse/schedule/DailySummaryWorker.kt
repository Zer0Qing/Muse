package io.zer0.muse.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.zer0.ai.ChatService
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactStore
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.notification.MuseNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

/**
 * v1.0.72: 每日总结推送 Worker — 每天固定时间(默认 19:30)推送"今日小结"。
 *
 * 为什么做:用户希望主动消息里"今天的总结"至少每天固定时间推一次(而非概率触发),
 * 让助手像真正陪伴一样,晚上定时回顾一天聊了什么。
 *
 * 实现:
 *  - OneTimeWorkRequest 在目标时间触发,执行完自续期到下一天同一时间
 *    (WorkManager 的 PeriodicWork 最小周期 15 分钟,无法精确到小时级定点,
 *    用 OneTime + 自续期实现精确的每日定点)
 *  - 素材:今天(本地 0 点起)用户发的消息 + 主记忆近期事项(fact)
 *  - 生成:LLM 2-4 句话自然总结(风格:口语、不官方、像朋友晚上跟你复盘)
 *  - 无素材时跳过(今天没聊过也没记忆,不硬发)
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
        // 开关关闭时跳过(但保留自续期,开关重新打开后自动恢复)
        val enabled = resultOf { settings.dailySummaryEnabledFlow.first() }.getOrNull() ?: true
        if (!enabled) {
            Logger.i(TAG, "每日总结已关闭,跳过本次")
            scheduleNext(applicationContext)
            return Result.success()
        }

        resultOf { runGenerate(koin, settings) }
            .onError { msg, t -> Logger.w(TAG, "每日总结生成失败: ${t?.message ?: msg}") }

        // 无论成败都自续期到下一天
        scheduleNext(applicationContext)
        return Result.success()
    }

    /** 生成今日小结并发送通知。 */
    private suspend fun runGenerate(koin: org.koin.core.Koin, settings: SettingsRepository) {
        val sessionRepository = resultOf { koin.get<SessionRepository>() }.getOrNull() ?: return
        val chatService = resultOf { koin.get<ChatService>() }.getOrNull() ?: return
        val factStore = resultOf { koin.get<FactStore>() }.getOrNull() ?: return
        val notificationManager = resultOf { koin.get<MuseNotificationManager>() }.getOrNull() ?: return

        val dayStart = java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        // 素材1:今天用户发的消息(最多 40 条)
        val todayMessages = withContext(Dispatchers.IO) {
            resultOf { sessionRepository.getUserMessagesSince(dayStart, 40) }.getOrNull() ?: emptyList()
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
            return
        }

        // 先持久化再判断前台状态:前台不弹通知时,首页仍能在问候语后展示这次总结。
        val summaryDate = java.time.LocalDate.now().toString()
        settings.saveDailySummary(summaryDate, summary)

        // 审查修复 (2.0 B-34): 前台静默 — 用户正盯着 app 时不弹 HIGH 声音通知
        // (与主动消息 notifyProactiveMessage 口径一致;此前前台照发,高频打扰用户)
        if (notificationManager.isAppForeground()) {
            Logger.d(TAG, "每日总结:应用在前台,跳过通知(内容已保存,首页问候语可见)")
            return
        }

        notificationManager.notifyReminder(
            title = SUMMARY_TITLES.random(),
            message = summary,
            notificationId = NOTIF_ID_DAILY_SUMMARY,
        )
        Logger.i(TAG, "每日总结已保存并推送: ${summary.take(40)}...")
    }

    /** 调 LLM 生成今日小结(超时 60s,失败返回 null)。 */
    private suspend fun generateSummary(
        chatService: ChatService,
        todayMessages: List<UIMessage>,
        facts: List<String>,
    ): String? {
        val hasContent = todayMessages.isNotEmpty() || facts.isNotEmpty()
        val sb = StringBuilder()
        if (hasContent) {
            sb.appendLine("你是用户的日常陪伴助手。现在到了晚上总结时间,请回顾用户今天和你聊的内容,用 2-4 句话写一条自然的今日小结推送。")
            sb.appendLine("要求:")
            sb.appendLine("- 口语化,像朋友晚上跟你复盘,不要官方腔、不要'今天您'这类敬语")
            sb.appendLine("- 挑 2-3 个有意义的点:用户今天关心的事、完成的事、近期安排")
            sb.appendLine("- 语气温暖自然,可以带一点轻松调侃,长度控制在 80 字以内")
            sb.appendLine("- 直接输出小结正文,不要任何前缀、标题或引号")
        } else {
            // v1.0.72: 今天无对话也无记忆时的退化模式 — 发一条自然的晚间问候,
            // 不让用户觉得"它又没动静了"。
            sb.appendLine("你是用户的日常陪伴助手。现在是晚上,用户今天没有和你聊天。请写一条 1-2 句的晚间问候推送。")
            sb.appendLine("要求:")
            sb.appendLine("- 口语化,像朋友晚上打招呼,不要官方腔")
            sb.appendLine("- 自然轻松,可以问一句今天过得怎么样,但不追问具体事(你不知道)")
            sb.appendLine("- 长度控制在 40 字以内,直接输出正文,不要任何前缀")
        }
        sb.appendLine()
        if (todayMessages.isNotEmpty()) {
            sb.appendLine("今天用户说的话(按时间顺序):")
            todayMessages.forEach { msg ->
                val text = msg.content.replace('\n', ' ').take(120)
                sb.appendLine("- $text")
            }
        }
        if (facts.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("用户近期记忆(可能包含明天的安排,可以提一句):")
            facts.take(10).forEach { sb.appendLine("- $it") }
        }
        sb.appendLine()
        sb.appendLine("请输出推送正文:")

        return resultOf {
            withTimeoutOrNull(GEN_TIMEOUT_MS) {
                // A-13: 每日总结同样过限流闸 — 与主动消息/群聊共享并发上限,
                // 避免叠加触发 429(此前完全绕过 B-22 限流)
                GenerationGate.withPermit {
                    chatService.completeText(
                        messages = listOf(
                            io.zer0.ai.core.UIMessage(
                                role = io.zer0.ai.core.MessageRole.USER,
                                content = sb.toString(),
                                createdAt = System.currentTimeMillis(),
                            ),
                        ),
                        temperature = 0.8f,
                        maxTokens = 200,
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

        /** 默认推送时间(24 小时制)。 */
        const val DEFAULT_HOUR = 19
        const val DEFAULT_MINUTE = 30

        /**
         * v1.0.72: 计算距下次目标时间(DEFAULT_HOUR:DEFAULT_MINUTE)的延迟毫秒数。
         * 纯函数,供测试直接验证:已过今天目标时间则顺延到明天。
         *
         * @param nowMillis 当前时间戳
         * @return 距下次目标时间的毫秒数(> 0)
         */
        fun computeDelayToNextTarget(nowMillis: Long): Long {
            val now = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
            val target = java.util.Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(java.util.Calendar.HOUR_OF_DAY, DEFAULT_HOUR)
                set(java.util.Calendar.MINUTE, DEFAULT_MINUTE)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (!target.after(now)) {
                target.add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }

        /**
         * 注册下一次每日总结 OneTimeRequest(明天 DEFAULT_HOUR:DEFAULT_MINUTE 触发)。
         * 幂等:REPLACE 策略,重复调用只保留最新一个。
         */
        fun scheduleNext(context: Context) {
            val delayMillis = computeDelayToNextTarget(System.currentTimeMillis())
            Logger.i(TAG, "下次每日总结在 ${delayMillis / 3600000}h 后")

            val request = OneTimeWorkRequestBuilder<DailySummaryWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
            resultOf {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
            }.onError { msg, t -> Logger.w(TAG, "每日总结调度失败: ${t?.message ?: msg}") }
        }
    }
}
