package io.zer0.muse.schedule

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactStore
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.ui.GreetingHelper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

/**
 * 每日总结的唯一生成入口。
 *
 * Worker 和首页前台补偿都通过这里执行，避免“只投递了 WorkManager 但首页一直没有内容”
 * 以及同一个时点同时触发两次 LLM 请求。时点抢占仍由 SettingsRepository 的 DataStore
 * 事务保证，Mutex 负责同一进程内的快速串行化。
 */
class DailySummaryService(
    private val settings: SettingsRepository,
    private val sessionRepository: SessionRepository,
    private val factStore: FactStore,
    private val chatService: ChatService?,
    private val notificationManager: MuseNotificationManager?,
) {

    private val mutex = Mutex()
    private val activeSlots = mutableSetOf<String>()

    /**
     * 前台打开首页时，为当前已经到点的时段立即生成一次总结。
     * 已经成功抢占过的时点不会重复生成；失败会释放抢占标记，允许下一次前台进入重试。
     */
    suspend fun generateCurrentSlotIfDue(nowMillis: Long = System.currentTimeMillis()): Boolean = mutex.withLock {
        val now = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
        val currentMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            now.get(java.util.Calendar.MINUTE)
        val due = DailySummaryWorker.SUMMARY_SLOTS
            .filter { (hour, minute) -> hour * 60 + minute <= currentMinutes }
            .maxByOrNull { (hour, minute) -> hour * 60 + minute }
            ?: return@withLock false
        val zone = ZoneId.systemDefault()
        val targetDate = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val targetDateText = targetDate.toString()
        val slotKey = DailySummaryWorker.slotKey(targetDateText, due.first, due.second)
        val summaryDate = DailySummaryWorker.summaryDateForTarget(targetDateText, due.first)
        val completed = settings.isDailySummarySlotCompleted(slotKey)
        if (!completed && settings.isDailySummarySlotClaimed(slotKey) && slotKey !in activeSlots) {
            // 进程可能在 claimed 后、保存前被杀；当前进程没有该时点的活跃任务时安全释放并重试。
            settings.releaseDailySummarySlot(slotKey)
        }
        val notificationsEnabled = try {
            settings.dailySummaryEnabledFlow.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "读取每日总结通知开关失败,按允许生成处理: ${e.message}", e)
            true
        }
        generateForSlotLocked(slotKey, summaryDate, notificationsEnabled)
    }

    /** Worker 和前台补偿共享的时点生成入口。 */
    suspend fun generateForSlot(
        slotKey: String,
        summaryDate: LocalDate,
        notificationsEnabled: Boolean,
    ): Boolean = mutex.withLock {
        generateForSlotLocked(slotKey, summaryDate, notificationsEnabled)
    }

    private suspend fun generateForSlotLocked(
        slotKey: String,
        summaryDate: LocalDate,
        notificationsEnabled: Boolean,
    ): Boolean {
        if (settings.isDailySummarySlotCompleted(slotKey)) {
            Logger.d(TAG, "每日总结时点已完成,跳过: $slotKey")
            return true
        }
        var claimed = resultOf { settings.claimDailySummarySlot(slotKey) }
            .onError { message, throwable ->
                Logger.w(TAG, "每日总结时点抢占失败($slotKey): ${throwable?.message ?: message}")
            }
            .getOrNull() ?: false
        if (!claimed && slotKey !in activeSlots && !settings.isDailySummarySlotCompleted(slotKey)) {
            // claimed 只代表“曾经开始过”；若进程在保存前退出，当前进程没有活跃任务，
            // 释放旧占位并重新抢占，避免一次崩溃永久吞掉该时段。
            settings.releaseDailySummarySlot(slotKey)
            claimed = resultOf { settings.claimDailySummarySlot(slotKey) }.getOrNull() ?: false
        }
        if (!claimed) {
            Logger.d(TAG, "每日总结时点正在执行或已抢占,跳过: $slotKey")
            return false
        }
        activeSlots += slotKey
        return try {
            val generated = resultOf {
                generateAndDeliver(slotKey, summaryDate, notificationsEnabled)
            }.onError { message, throwable ->
                Logger.w(TAG, "每日总结生成失败($slotKey): ${throwable?.message ?: message}")
            }.getOrNull() == true
            if (!generated) {
                // 只有未保存成功时才释放占位，避免一次瞬时错误永久吞掉该时点。
                resultOf { settings.releaseDailySummarySlot(slotKey) }
                    .onError { message, throwable ->
                        Logger.w(TAG, "释放每日总结时点失败($slotKey): ${throwable?.message ?: message}")
                    }
            }
            generated
        } finally {
            activeSlots -= slotKey
        }
    }

    private suspend fun generateAndDeliver(
        slotKey: String,
        summaryDate: LocalDate,
        notificationsEnabled: Boolean,
    ): Boolean {
        val zone = ZoneId.systemDefault()
        val dayStart = summaryDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = summaryDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        // 只取用户消息，避免把助手长回复重复灌入每日总结素材。
        val todayMessages = withContext(Dispatchers.IO) {
            resultOf { sessionRepository.getUserMessagesBetween(dayStart, dayEnd, 40) }
                .getOrNull()
                ?: emptyList()
        }
        val facts = resultOf { factStore.getAll("main") }.getOrNull() ?: emptyList()
        val todayFacts = facts
            .sortedByDescending { it.createdAt }
            .take(30)
            .mapNotNull { fact ->
                val timePart = fact.time?.takeIf { it.isNotBlank() }?.let { " (时间:$it)" } ?: ""
                "${fact.fact}$timePart"
            }

        val summary = if (chatService != null) {
            generateSummary(chatService, todayMessages, todayFacts)
        } else {
            Logger.w(TAG, "ChatService 未就绪,使用本地每日总结")
            buildLocalSummary(todayMessages, todayFacts)
        }
        if (summary.isNullOrBlank()) {
            Logger.w(TAG, "每日总结生成结果为空,跳过")
            return false
        }

        // 先保存，再根据前后台和用户开关决定是否发送通知；首页永远能复用这份结果。
        settings.saveDailySummary(summaryDate.toString(), summary)
        settings.completeDailySummarySlot(slotKey)
        val manager = notificationManager
        if (manager == null) {
            Logger.w(TAG, "MuseNotificationManager 未就绪,每日总结已保存但未发送通知")
            return true
        }
        if (manager.isAppForeground()) {
            Logger.d(TAG, "每日总结:应用在前台,跳过通知(内容已保存,首页问候语可见)")
            return true
        }
        if (!notificationsEnabled) {
            Logger.d(TAG, "每日总结已保存,推送开关关闭,首页问候语可见")
            return true
        }
        manager.notifyReminder(
            title = SUMMARY_TITLES.random(),
            message = summary,
            notificationId = DailySummaryWorker.NOTIF_ID_DAILY_SUMMARY,
            target = io.zer0.muse.notification.MuseNotificationTarget.Home,
        )
        Logger.i(TAG, "每日总结已保存并推送: ${summary.take(40)}...")
        return true
    }

    /** LLM 失败时的确定性本地总结，只使用真实素材。 */
    private suspend fun generateSummary(
        service: ChatService,
        todayMessages: List<UIMessage>,
        facts: List<String>,
    ): String? {
        val hasContent = todayMessages.isNotEmpty() || facts.isNotEmpty()
        val systemPrompt = if (hasContent) {
            """
你是用户的陪伴助手。请根据真实素材写一条晚间今日小结。
规则:口语、像朋友复盘;只挑 1 个最重要的重点;不编造未发生的事;输出 18-24 字;
只输出一行正文,不要标题、前缀、引号、MOOD、反思或换行。
            """.trimIndent()
        } else {
            """
你是用户的陪伴助手。用户今天没有留下可用素材,请写一句自然的晚间问候。
不要编造今天发生的事;可以问候或轻轻问一句过得怎么样;16 字以内;只输出一行正文。
            """.trimIndent()
        }
        val userContent = buildString {
            appendLine("<summary_material>")
            if (todayMessages.isNotEmpty()) {
                appendLine("今天用户说的话(按时间顺序):")
                todayMessages.forEach { message ->
                    appendLine("- ${message.content.replace('\n', ' ').take(120)}")
                }
            }
            if (facts.isNotEmpty()) {
                appendLine("用户近期事项(只有与今天相关或明确临近的事项才可提):")
                facts.take(10).forEach { appendLine("- $it") }
            }
            if (!hasContent) appendLine("没有可用素材。")
            appendLine("</summary_material>")
        }
        return resultOf {
            withTimeoutOrNull(GEN_TIMEOUT_MS) {
                GenerationGate.withPermit {
                    service.completeText(
                        messages = listOf(
                            UIMessage(
                                role = MessageRole.SYSTEM,
                                content = systemPrompt,
                                createdAt = System.currentTimeMillis(),
                            ),
                            UIMessage(
                                role = MessageRole.USER,
                                content = userContent,
                                createdAt = System.currentTimeMillis(),
                            ),
                        ),
                        temperature = 0.6f,
                        maxTokens = 48,
                    ).text.let { io.zer0.muse.transformer.stripThinkTags(it) }
                }
            }
        }.onError { message, throwable ->
            Logger.w(TAG, "每日总结 LLM 调用失败: ${throwable?.message ?: message}")
        }.getOrNull()?.let { raw ->
            GreetingHelper.compactGreetingText(raw, SUMMARY_MAX_CHARS)
        } ?: buildLocalSummary(todayMessages, facts)
    }

    private fun buildLocalSummary(todayMessages: List<UIMessage>, facts: List<String>): String {
        val messageCount = todayMessages.count { it.content.isNotBlank() }
        val firstFact = facts.firstOrNull()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(14)
        val raw = when {
            messageCount > 0 && firstFact != null -> "今天聊了${messageCount}次，记下：$firstFact"
            messageCount > 0 -> "今天聊了${messageCount}次，辛苦了。"
            firstFact != null -> "记下了：$firstFact"
            else -> "今天还没有新的对话。"
        }
        return GreetingHelper.compactGreetingText(raw, SUMMARY_MAX_CHARS) ?: "今天还没有新的对话。"
    }

    companion object {
        private const val TAG = "DailySummaryService"
        private const val GEN_TIMEOUT_MS = 60_000L
        private const val SUMMARY_MAX_CHARS = GreetingHelper.DAILY_SUMMARY_HINT_MAX_LENGTH
        private val SUMMARY_TITLES = listOf(
            "今日小结",
            "今天过得怎么样",
            "睡前复盘一下",
            "今天你聊了不少",
            "今日回顾",
        )
    }
}
