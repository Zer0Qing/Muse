package io.zer0.muse.schedule

import android.content.Context
import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.emotion.MoodParser
import io.zer0.muse.data.experience.ExperienceRepository
import io.zer0.muse.data.lorebook.LorebookRepository
import io.zer0.muse.data.milestone.MilestoneDao
import io.zer0.muse.data.proactive.Mood
import io.zer0.muse.data.proactive.ProactiveScoreEngine
import io.zer0.muse.data.proactive.ScoreContext
import io.zer0.muse.data.session.SessionRepository
import io.zer0.memory.fact.FactStore
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 主动消息调度器(虚拟陪伴助手用)。
 *
 * 让助手像真人一样定时主动给用户发消息并弹通知,模拟"对方先找你聊天"的体验。
 * App 启动时调用 [start] 进入轮询,每 60 秒检查一次是否到达触发间隔;
 * 到期则进入"工作台巡检"流程,综合长期记忆/里程碑/经验/设定集构造上下文,
 * 用两阶段 LLM 调用(决策 → 生成)产出一条主动消息,写入当前会话并弹通知。
 *
 * v2.0 重构(参考 QingTian Heartbeat 模式):
 *  - 5.1: accountAgeDays 从 SharedPreferences `pref_proactive_first_launch` 计算真实账户年龄
 *  - 5.2: 接入 [MoodParser] 从最近 assistant 消息 `<mood>` 标签解析真实情绪
 *  - 5.3: 接入 [ExperienceRepository] / [MilestoneDao] 检测新经验/新里程碑
 *  - 5.4: 决策与生成分离(两阶段 LLM 调用,省 token)
 *  - 5.5: 工作台巡检 — 扫描会话差量,构造巡检上下文
 *  - 5.6: 事件触发器 [triggerByEvent](2 分钟手动冷却)
 *  - 5.7: content 长度按场景动态调整(提醒/故事/问候)
 *  - 5.8: prompt 强约束(系统巡检消息,非用户提问)
 *  - 5.10: 注入长期记忆/设定集/里程碑上下文
 *
 * 设计要点(参考 [ScheduledTaskRunner] 的轮询结构):
 *  - 用协程轮询而非 AlarmManager,避免 Android 后台限制
 *  - 单 Job 控制生命周期,[stop] 取消即可
 *  - [lastTriggeredAt] 持久化在 DataStore,App 重启后不会立即重发
 *  - 任一环节失败(LLM 调用 / 写库 / 通知)都不更新 lastTriggeredAt,下个 tick 会重试
 *
 * @param settings 读取/保存 [io.zer0.muse.data.ProactiveMessageConfig]
 * @param chatService 非流式调用 LLM 生成主动消息内容
 * @param sessionRepository 把生成的消息追加进当前会话
 * @param assistantRepository 取当前助手(人设 + 名字)
 * @param notificationManager 弹"主动消息"通知
 * @param scoreEngine 主动消息评分预筛选引擎
 * @param experienceRepository v2.0 5.3: 经验库,检测新经验作为 hasNewTopics
 * @param milestoneDao v2.0 5.3: 里程碑 DAO,检测新里程碑作为 hasNewMilestones
 * @param lorebookRepository v2.0 5.10: 设定集,匹配关键词注入巡检上下文
 * @param factStore v2.0 5.10: 长期记忆,检测新 fact 作为 hasNewMemories + 注入上下文
 * @param activityProfile v2.1: 用户活跃度画像,驱动自适应调度(替换随机偏移)
 * @param context 应用 Context
 * @param appScope App 全局协程作用域
 */
class ProactiveMessageRunner(
    private val settings: SettingsRepository,
    private val chatService: ChatService,
    private val sessionRepository: SessionRepository,
    private val assistantRepository: AssistantRepository,
    private val notificationManager: MuseNotificationManager,
    private val scoreEngine: ProactiveScoreEngine,
    private val experienceRepository: ExperienceRepository,
    private val milestoneDao: MilestoneDao,
    private val lorebookRepository: LorebookRepository,
    private val factStore: FactStore,
    private val activityProfile: UserActivityProfile,
    private val context: Context,
    private val appScope: CoroutineScope,
) {
    /** 解析 LLM 决策 JSON(忽略未知字段,兼容模型多返回字段的情况)。 */
    private val decisionJson = Json { ignoreUnknownKeys = true }
    private var job: Job? = null

    // 问题6.1: appScope 60s 轮询与 WorkManager 15 分钟兜底可能并发触发 checkAndTrigger,
    // 用 Mutex 保证同一时刻只有一个 checkAndTrigger 在执行,避免重复发主动消息。
    private val triggerMutex = Mutex()

    // 问题6.2: 当日已发送主动消息计数(持久化到 SharedPreferences),MAX_DAILY_MESSAGES 校验依赖此值。
    private val prefs = context.getSharedPreferences("proactive_msg", android.content.Context.MODE_PRIVATE)
    private var todaySentCount = 0
    private var todayDate = "" // yyyy-MM-dd,用于跨日重置计数

    // v2.0 5.1: 首次启动时间持久化(SharedPreferences key `pref_proactive_first_launch`),
    // 用于计算 accountAgeDays。复用 proactive_msg 文件避免散落多个 SP 文件。
    private val firstLaunchPrefs = prefs

    // v2.0 5.6: 事件触发器手动冷却时间戳(2 分钟),避免 onResume 频繁触发浪费 token。
    private var lastEventTriggerAt: Long = 0L

    fun start() {
        job?.cancel()
        job = appScope.launch(GlobalCoroutineExceptionHandler) {
            Logger.i(TAG, "ProactiveMessageRunner started")
            while (isActive) {
                try {
                    checkAndTrigger()
                } catch (e: Exception) {
                    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                    Logger.w(TAG, "Poll error: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * v1.134: 供 [ProactiveMessageWorker] 调用的一次性检查入口(Worker 进程被系统拉起时使用)。
     *
     * 与协程轮询共用 [executeProactiveCycle] 实现。Worker 进程内 Koin 已初始化(MuseApp.onCreate
     * 已执行),依赖解析正常。
     *
     * v1.134 P2-2: 冷启动防打扰 — 若距上次触发已超过 2 倍 interval,说明 App 长时间未运行,
     * 此时不应立即发送主动消息(可能打扰用户),仅更新 lastTriggeredAt 为当前时间,
     * 下次正常 interval 后再发送。时间窗口检查仍然生效。
     */
    suspend fun tickOnceForWorker() {
        executeProactiveCycle(triggerSource = TRIGGER_SOURCE_WORKER, suppressIfColdStart = true)
    }

    /**
     * v2.0 5.6: 事件触发器 — 由 App 生命周期事件或会话事件主动调用。
     *
     * 与定时轮询的区别:
     *  - 不受 [computeEffectiveIntervalMs] 间隔限制(事件本身就是触发信号)
     *  - 受 2 分钟手动冷却([EVENT_COOLDOWN_MS])限制,避免短时间内多次触发浪费 token
     *  - 仍受时间窗口 / 每日上限 / ScoreEngine 评分约束(在 [executeProactiveCycle] 内统一校验)
     *
     * 支持的事件类型(由调用方传入,仅用于日志与巡检上下文标注):
     *  - [TRIGGER_SOURCE_RESUME]:用户打开 App 时(onResume)
     *  - [TRIGGER_SOURCE_LONG_SILENCE]:长时间沉默后(超过 intervalMinutes 的 1.5 倍)
     *  - [TRIGGER_SOURCE_TASK_COMPLETE]:检测到会话最后一条消息是任务完成类内容
     */
    suspend fun triggerByEvent(eventType: String) {
        val now = System.currentTimeMillis()
        if (now - lastEventTriggerAt < EVENT_COOLDOWN_MS) {
            Logger.i(TAG, "事件触发冷却中(剩余 ${EVENT_COOLDOWN_MS - (now - lastEventTriggerAt)}ms),跳过 (eventType=$eventType)")
            return
        }
        lastEventTriggerAt = now
        Logger.i(TAG, "事件触发主动消息巡检: eventType=$eventType")
        executeProactiveCycle(triggerSource = eventType, suppressIfColdStart = false)
    }

    private suspend fun checkAndTrigger() {
        executeProactiveCycle(triggerSource = TRIGGER_SOURCE_POLL, suppressIfColdStart = false)
    }

    /**
     * v2.0 5.4/5.5: 统一的主动消息执行主流程(两阶段决策)。
     *
     * 阶段1(决策):用 ScoreEngine 预筛选 → 通过后用 LLM(maxTokens=[DECISION_MAX_TOKENS])
     *   只返回 {"shouldSend": bool, "reason": "...", "scenario": "..."}(省 token)
     * 阶段2(生成):shouldSend=true 时用 LLM(maxTokens=[CONTENT_MAX_TOKENS])生成正文
     *
     * [triggerSource] 影响:
     *  - "poll" / "worker":受 [computeNextTriggerTime] 自适应间隔限制
     *  - "app_resume" / "long_silence" / "task_complete":不受间隔限制(事件触发)
     *  - "worker" 路径额外检查冷启动防打扰
     */
    private suspend fun executeProactiveCycle(
        triggerSource: String,
        suppressIfColdStart: Boolean = false,
    ) = triggerMutex.withLock {
        // 问题6.2: 进入临界区先刷新当日计数(跨日重置 + 从 SP 读取持久化值)
        refreshDailyCount()

        val config = settings.proactiveMessageConfigFlow.first()
        if (!config.enabled) return@withLock

        // v1.95: 时间窗口检查 — 不在允许时段跳过发送(避免夜间打扰)
        val calendar = java.util.Calendar.getInstance()
        val currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val inWindow = if (config.allowedHourStart <= config.allowedHourEnd) {
            // 普通时段:如 8-22
            currentHour in config.allowedHourStart until config.allowedHourEnd
        } else {
            // 跨夜时段:如 22-8(22点到次日8点)
            currentHour >= config.allowedHourStart || currentHour < config.allowedHourEnd
        }
        if (!inWindow) {
            Logger.i(TAG, "当前 $currentHour:00 不在允许时段 ${config.allowedHourStart}:00-${config.allowedHourEnd}:00,跳过")
            return@withLock
        }

        val now = System.currentTimeMillis()
        // v2.1: 自适应调度 — 替换 v1.30 的 ±randomOffsetMinutes 随机偏移,
        // 改用"活跃度 + 对话连续性 + 情绪"三因子联合驱动(见 [computeNextTriggerTime])。
        val baseIntervalMs = computeBaseIntervalMs(config)
        val elapsed = now - config.lastTriggeredAt

        // 事件触发路径不受间隔限制;poll / worker 路径仍受自适应间隔约束
        val isEventTriggered = triggerSource != TRIGGER_SOURCE_POLL && triggerSource != TRIGGER_SOURCE_WORKER
        if (!isEventTriggered) {
            // 快速下限保护:elapsed < baseInterval × 0.3 时直接跳过(三因子最小乘积 ≈ 0.336),
            // 避免每次轮询都触发自适应计算与日志
            if (elapsed < baseIntervalMs * FAST_GUARD_RATIO) return@withLock
            val nextTriggerTime = computeNextTriggerTime(config, baseIntervalMs)
            if (now < nextTriggerTime) {
                Logger.d(TAG, "自适应间隔未到期: 剩余 ${(nextTriggerTime - now) / 60000}min")
                return@withLock
            }
        }

        // v1.134 P2-2: 冷启动防打扰 — Worker 路径检测到长时间未触发(> 2× baseInterval)
        // 时,不立即发送,仅更新 lastTriggeredAt 到当前时间,等下个 interval 再发。
        if (suppressIfColdStart && config.lastTriggeredAt > 0 && elapsed > baseIntervalMs * 2) {
            Logger.i(TAG, "冷启动检测:距上次触发 ${elapsed / 3600000}h,推迟到下个 interval 再发(避免打扰)")
            settings.saveProactiveMessageConfig(config.copy(lastTriggeredAt = now))
            return@withLock
        }

        // v2.0 5.6: 长时间沉默事件触发校验 — 仅当 elapsed > baseInterval × 1.5 时才允许 long_silence 事件触发
        if (triggerSource == TRIGGER_SOURCE_LONG_SILENCE && elapsed < baseIntervalMs * 1.5f) {
            Logger.i(TAG, "long_silence 事件未达 1.5× interval,跳过")
            return@withLock
        }

        // 获取指定 Agent 助手(v1.27):优先用 config.agentId,否则 default,再否则第一个
        val assistants = assistantRepository.observeAll.first()
        val assistant = assistants.firstOrNull { it.id == config.agentId.takeIf { id -> id.isNotBlank() } }
            ?: assistants.firstOrNull { it.id == "default" }
            ?: assistants.firstOrNull()
            ?: return@withLock

        // 取会话作为"当前会话"
        val sessions = sessionRepository.observeSessions().first()
        // v1.95: 仅Agent会话可发主动消息(agentOnly=true时)
        val targetSession = if (config.agentOnly) {
            sessionRepository.getLatestAgentSession() ?: sessions.firstOrNull()
        } else {
            sessions.firstOrNull()
        } ?: return@withLock

        // v0.44: 取最近 10 条消息,过滤出最近 5 条 user/assistant 消息作为上下文
        val allMessages = resultOf {
            sessionRepository.observeMessages(targetSession.id).first()
        }.getOrNull() ?: emptyList()
        val recentMessages = allMessages
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .takeLast(5)

        // v2.0 5.1: 真实账户年龄(从首次启动时间计算)
        val accountAgeDays = computeAccountAgeDays()

        // v2.0 5.2: 真实最近情绪(从 assistant 消息 <mood> 标签解析)
        val recentMood = computeRecentMood(recentMessages)
        // v2.1: 把最近情绪与对话结束类型持久化到活跃度画像,供下轮自适应调度读取
        activityProfile.setLastKnownMood(recentMood.name)
        val endType = detectConversationEndType(recentMessages)
        activityProfile.setConversationEndType(endType)

        // v2.0 5.3 & 5.10: 巡检数据采集 — 自上次触发后是否有新记忆/里程碑/经验
        val lastTriggeredAt = config.lastTriggeredAt
        val hasNewMemories = checkHasNewMemories(lastTriggeredAt)
        val hasNewMilestones = checkHasNewMilestones(lastTriggeredAt)
        val hasNewTopics = checkHasNewTopics(lastTriggeredAt)

        // v2.0 5.9: 用 config.maxDailyMessages 而非硬编码(预筛选前显式校验,
        // 避免 ScoreEngine 内部硬编码 MAX_DAILY_MESSAGES 与配置脱节)
        if (todaySentCount >= config.maxDailyMessages) {
            Logger.i(TAG, "已达每日上限 ${config.maxDailyMessages},跳过")
            return@withLock
        }

        // v1.0.4: ProactiveScoreEngine 预筛选 — 评分低于阈值直接跳过 LLM 调用(节省 token)
        val scoreCtx = ScoreContext(
            hoursSinceLastMessage = (elapsed / 3_600_000f).coerceAtLeast(0f),
            accountAgeDays = accountAgeDays,
            recentMood = recentMood,
            todaySentCount = this.todaySentCount,
            hasNewMilestones = hasNewMilestones,
            hasNewMemories = hasNewMemories,
            hasNewTopics = hasNewTopics,
            // v2.0 5.9: 传入可配置的每日上限,替代 ScoreEngine 硬编码
            maxDailyMessages = config.maxDailyMessages,
        )
        if (!scoreEngine.shouldSend(scoreCtx)) {
            Logger.i(TAG, "ScoreEngine 预筛选未通过,跳过 LLM 调用")
            settings.saveProactiveMessageConfig(config.copy(lastTriggeredAt = now))
            return@withLock
        }

        // v2.0 5.5: 构造工作台巡检上下文(Heartbeat 模式)
        val patrolContext = buildPatrolContext(
            triggerSource = triggerSource,
            recentMessages = recentMessages,
            allMessages = allMessages,
            lastTriggeredAt = lastTriggeredAt,
            recentMood = recentMood,
            hasNewMemories = hasNewMemories,
            hasNewMilestones = hasNewMilestones,
            hasNewTopics = hasNewTopics,
            accountAgeDays = accountAgeDays,
            elapsedHours = elapsed / 3_600_000f,
        )

        // ── 阶段1:决策(只返回 shouldSend + reason + scenario,maxTokens 小,省 token)──
        val decisionPrompt = buildDecisionPrompt(assistant, patrolContext)
        val decisionCompletion = resultOf {
            withTimeoutOrNull(LLM_TIMEOUT_MS) {
                chatService.completeText(
                    messages = decisionPrompt,
                    // v2.0 5.9: 决策阶段用 temperature × 0.5(决策需要确定性)
                    temperature = (config.temperature * 0.5f).coerceIn(0f, 2f),
                    maxTokens = DECISION_MAX_TOKENS,
                )
            }
        }.onError { msg, t ->
            Logger.w(TAG, "主动消息决策 LLM 调用失败: ${t?.message ?: msg}")
        }.getOrNull()
        if (decisionCompletion == null) {
            Logger.w(TAG, "主动消息决策 LLM 调用超时(${LLM_TIMEOUT_MS / 1000}s),跳过")
            return@withLock
        }

        val decision = parseDecision(decisionCompletion.text)

        if (!decision.shouldSend) {
            // shouldSend=false 也更新 lastTriggeredAt,避免频繁打扰 + 浪费 token
            settings.saveProactiveMessageConfig(config.copy(lastTriggeredAt = now))
            Logger.i(TAG, "Proactive message skipped (shouldSend=false), reason=${decision.reason}")
            return@withLock
        }

        // ── 阶段2:生成(用大 maxTokens 生成正文,场景驱动长度)──
        val contentPrompt = buildContentPrompt(assistant, patrolContext, decision)
        val contentCompletion = resultOf {
            withTimeoutOrNull(LLM_TIMEOUT_MS) {
                chatService.completeText(
                    messages = contentPrompt,
                    // v2.0 5.9: 生成阶段用配置的 temperature
                    temperature = config.temperature,
                    maxTokens = CONTENT_MAX_TOKENS,
                )
            }
        }.onError { msg, t ->
            Logger.w(TAG, "主动消息生成 LLM 调用失败: ${t?.message ?: msg}")
        }.getOrNull()
        if (contentCompletion == null) {
            Logger.w(TAG, "主动消息生成 LLM 调用超时(${LLM_TIMEOUT_MS / 1000}s),跳过")
            return@withLock
        }

        val proactiveContent = contentCompletion.text.trim()
        if (proactiveContent.isBlank()) {
            settings.saveProactiveMessageConfig(config.copy(lastTriggeredAt = now))
            Logger.i(TAG, "Proactive message skipped (empty content), reason=${decision.reason}")
            return@withLock
        }

        // 插入会话作为 assistant 消息
        sessionRepository.appendMessage(
            sessionId = targetSession.id,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                content = proactiveContent,
                createdAt = System.currentTimeMillis(),
            ),
        )
        // 更新 lastTriggeredAt(先更新再通知,即使通知失败也不影响下次间隔)
        settings.saveProactiveMessageConfig(config.copy(lastTriggeredAt = now))
        // 问题6.2: 成功发送后递增当日计数并持久化,MAX_DAILY_MESSAGES 校验下次生效
        incrementDailyCount()
        // 弹通知(像微信来消息一样,通知栏用助手头像)
        notificationManager.notifyProactiveMessage(assistant, proactiveContent)
        Logger.i(TAG, "Proactive message sent to session ${targetSession.id}, scenario=${decision.scenario}, reason=${decision.reason}")
    }

    // ══════════════════════════════════════════════════════════════════════
    // v2.0 5.1: accountAgeDays 从首次启动时间计算
    // ══════════════════════════════════════════════════════════════════════

    /**
     * v2.0 5.1: 计算账户年龄(天)。
     *
     * 首次调用时记录时间戳到 SharedPreferences `pref_proactive_first_launch`,
     * 后续调用计算 (now - firstLaunch) / 86400000。
     * 返回 0 表示今天首次启动(新用户)。
     */
    private fun computeAccountAgeDays(): Int {
        val now = System.currentTimeMillis()
        val firstLaunch = firstLaunchPrefs.getLong(KEY_FIRST_LAUNCH, 0L)
        return if (firstLaunch == 0L) {
            // 首次启动:记录时间戳,返回 0(今天就是第 0 天)
            firstLaunchPrefs.edit().putLong(KEY_FIRST_LAUNCH, now).apply()
            Logger.i(TAG, "首次启动,记录 firstLaunch 时间戳")
            0
        } else {
            ((now - firstLaunch) / (24 * 60 * 60 * 1000L)).toInt().coerceAtLeast(0)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // v2.0 5.2: 接入 EmotionTracking — 用 MoodParser 解析最近情绪
    // ══════════════════════════════════════════════════════════════════════

    /**
     * v2.0 5.2: 从最近 assistant 消息的 `<mood>` 标签解析情绪倾向。
     *
     * 复用现有 [MoodParser](从 `<mood value="0.5" label="positive">...</mood>` 解析)。
     * 同时兼容 [UIMessage.mood] 字段(已被 MoodTagTransformer 剥离出的纯文本)。
     *
     * 策略:取最近 N 条 assistant 消息,累加 mood value:
     *  - 正向(value > 0.3)→ POSITIVE 票
     *  - 负向(value < -0.3)→ NEGATIVE 票
     *  - 无 mood 标签 → UNKNOWN(默认)
     * 最终按多数票决定 [Mood],全无标签返回 UNKNOWN。
     */
    private fun computeRecentMood(recentMessages: List<UIMessage>): Mood {
        val recentAssistant = recentMessages.filter { it.role == MessageRole.ASSISTANT }
        if (recentAssistant.isEmpty()) return Mood.UNKNOWN

        var positiveScore = 0f
        var negativeScore = 0f
        var sampleCount = 0

        for (msg in recentAssistant) {
            // 优先从 content 解析 <mood> 标签(兼容历史消息未剥离的情况)
            val parsed = MoodParser.parse(msg.content)
            if (parsed != null) {
                sampleCount++
                when {
                    parsed.value > 0.3f -> positiveScore += parsed.value
                    parsed.value < -0.3f -> negativeScore += -parsed.value
                }
                continue
            }
            // 兜底:从 mood 字段(已剥离的纯文本)做关键词匹配
            val moodText = msg.mood
            if (!moodText.isNullOrBlank()) {
                val lower = moodText.lowercase()
                val positiveKeywords = listOf("positive", "happy", "开心", "高兴", "愉快", "兴奋")
                val negativeKeywords = listOf("negative", "sad", "难过", "伤心", "沮丧", "焦虑", "生气")
                when {
                    positiveKeywords.any { lower.contains(it) } -> {
                        sampleCount++
                        positiveScore += 1f
                    }
                    negativeKeywords.any { lower.contains(it) } -> {
                        sampleCount++
                        negativeScore += 1f
                    }
                }
            }
        }

        if (sampleCount == 0) return Mood.UNKNOWN
        return when {
            positiveScore > negativeScore -> Mood.POSITIVE
            negativeScore > positiveScore -> Mood.NEGATIVE
            else -> Mood.NEUTRAL
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // v2.0 5.3 & 5.10: 接入 ExperienceRepository / MilestoneDao / FactStore
    // ══════════════════════════════════════════════════════════════════════

    /**
     * v2.0 5.3: 检查自上次主动消息后是否有新的 fact(长期记忆)。
     *
     * FactStore.getAll 返回的 Fact.createdAt 是 ISO 8601 字符串,解析为时间戳比对。
     * 任何异常(解析失败/DB 错误)都视为"无新记忆",避免阻塞主动消息流程。
     */
    private suspend fun checkHasNewMemories(lastTriggeredAt: Long): Boolean {
        if (lastTriggeredAt <= 0L) return false
        return try {
            factStore.getAll("main").any { fact ->
                runCatching {
                    java.time.Instant.parse(fact.createdAt).toEpochMilli() > lastTriggeredAt
                }.getOrDefault(false)
            }
        } catch (e: Exception) {
            Logger.w(TAG, "checkHasNewMemories 失败: ${e.message}")
            false
        }
    }

    /**
     * v2.0 5.3: 检查自上次主动消息后是否有新的里程碑记录。
     */
    private suspend fun checkHasNewMilestones(lastTriggeredAt: Long): Boolean {
        if (lastTriggeredAt <= 0L) return false
        return try {
            milestoneDao.getAll().any { it.createdAt > lastTriggeredAt }
        } catch (e: Exception) {
            Logger.w(TAG, "checkHasNewMilestones 失败: ${e.message}")
            false
        }
    }

    /**
     * v2.0 5.3: 检查自上次主动消息后是否有新的经验条目(作为 hasNewTopics 信号)。
     */
    private suspend fun checkHasNewTopics(lastTriggeredAt: Long): Boolean {
        if (lastTriggeredAt <= 0L) return false
        return try {
            experienceRepository.getAll().any { it.updatedAt > lastTriggeredAt }
        } catch (e: Exception) {
            Logger.w(TAG, "checkHasNewTopics 失败: ${e.message}")
            false
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // v2.0 5.5: 工作台巡检上下文构造(Heartbeat 模式)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * v2.0 5.5: 构造工作台巡检上下文。
     *
     * 参考 QingTian Heartbeat 模式:工作台巡检 + 文件差量 + 巡检日志 + 隔离执行。
     * 这里"文件差量"映射为"会话差量"(自上次巡检后哪些会话有新消息/新记忆/新里程碑)。
     */
    private suspend fun buildPatrolContext(
        triggerSource: String,
        recentMessages: List<UIMessage>,
        allMessages: List<UIMessage>,
        lastTriggeredAt: Long,
        recentMood: Mood,
        hasNewMemories: Boolean,
        hasNewMilestones: Boolean,
        hasNewTopics: Boolean,
        accountAgeDays: Int,
        elapsedHours: Float,
    ): PatrolContext {
        // 差量统计:自上次巡检后新消息数
        val newMessagesSincePatrol = if (lastTriggeredAt > 0) {
            allMessages.count { it.createdAt > lastTriggeredAt }
        } else {
            allMessages.size
        }

        // 未完成话题:最近 user 消息中看似问题的内容(以 ?/?/?/!结尾或包含疑问词)
        val unfinishedTopics = recentMessages
            .filter { it.role == MessageRole.USER }
            .takeLast(3)
            .map { it.content }
            .filter { content ->
                content.endsWith("?") || content.endsWith("?") ||
                    content.endsWith("?") || content.endsWith("!") ||
                    content.contains("怎么") || content.contains("为什么") ||
                    content.contains("如何") || content.contains("什么") ||
                    content.contains("吗") || content.contains("呢")
            }

        // v2.0 5.10: 注入长期记忆(取最重要的 5 条)
        val recentMemories = try {
            factStore.getByScope("main")
                .sortedByDescending { it.importance }
                .take(5)
                .map { it.fact }
        } catch (e: Exception) {
            Logger.w(TAG, "拉取长期记忆失败: ${e.message}")
            emptyList()
        }

        // v2.0 5.10: 注入最近里程碑(取 3 条)
        val recentMilestones = try {
            milestoneDao.getAll().take(3).map { "${it.title}: ${it.message}" }
        } catch (e: Exception) {
            emptyList()
        }

        // v2.0 5.10: 匹配设定集(扫描最近 user 消息关键词)
        val matchedLorebooks = matchLorebooks(recentMessages)

        return PatrolContext(
            triggerSource = triggerSource,
            recentMessages = recentMessages,
            newMessagesSincePatrol = newMessagesSincePatrol,
            unfinishedTopics = unfinishedTopics,
            recentMood = recentMood,
            hasNewMemories = hasNewMemories,
            hasNewMilestones = hasNewMilestones,
            hasNewTopics = hasNewTopics,
            recentMemories = recentMemories,
            recentMilestones = recentMilestones,
            matchedLorebooks = matchedLorebooks,
            accountAgeDays = accountAgeDays,
            elapsedHours = elapsedHours,
        )
    }

    /**
     * v2.0 5.10: 扫描最近消息匹配 Lorebook 关键词,返回命中的设定集内容。
     */
    private suspend fun matchLorebooks(recentMessages: List<UIMessage>): List<String> {
        return try {
            val enabledLorebooks = lorebookRepository.observeEnabled().first()
            if (enabledLorebooks.isEmpty()) return emptyList()
            // 拼接最近消息文本作为扫描源
            val scanText = recentMessages.joinToString("\n") { it.content }
            enabledLorebooks
                .filter { lorebook ->
                    // keywordsJson 是 JSON 数组字符串,简单解析
                    val keywords = parseKeywords(lorebook.keywordsJson)
                    keywords.any { kw ->
                        if (lorebook.caseSensitive) scanText.contains(kw)
                        else scanText.lowercase().contains(kw.lowercase())
                    }
                }
                .take(3) // 最多注入 3 条避免上下文爆炸
                .map { it.content }
        } catch (e: Exception) {
            Logger.w(TAG, "matchLorebooks 失败: ${e.message}")
            emptyList()
        }
    }

    /** 简易解析 Lorebook keywordsJson(JSON 数组字符串 → List<String>)。 */
    private fun parseKeywords(keywordsJson: String): List<String> {
        if (keywordsJson.isBlank() || keywordsJson == "[]") return emptyList()
        return runCatching {
            decisionJson.decodeFromString<List<String>>(keywordsJson)
        }.getOrDefault(emptyList())
    }

    // ══════════════════════════════════════════════════════════════════════
    // v2.0 5.4 & 5.8: 两阶段 prompt 构造(强约束)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * v2.0 5.4 & 5.8: 阶段1 — 决策 prompt(只返回 shouldSend + reason + scenario)。
     *
     * 强约束(参考 QingTian Heartbeat):
     *  - 明确告知 LLM "这是系统自动触发的巡检消息,不是用户发来的"
     *  - 不要把巡检当作用户提问来回应
     *  - 独立判断是否需要主动发消息,不要向用户提问或等待回复
     */
    private fun buildDecisionPrompt(
        assistant: AssistantEntity,
        patrol: PatrolContext,
    ): List<UIMessage> {
        val systemMsg = UIMessage(
            role = MessageRole.SYSTEM,
            content = buildString {
                appendLine("注意:这是系统自动触发的巡检消息,不是用户发来的。")
                appendLine("用户目前没有在跟你对话,不要把巡检当作用户的提问来回应。")
                appendLine("你需要独立判断是否有需要主动处理的事项,如果有就直接执行,不要向用户提问或等待回复。")
                appendLine()
                appendLine("你是「${assistant.name.ifBlank { "muse" }}」,用户的虚拟陪伴助手。")
                if (assistant.systemPrompt.isNotBlank()) {
                    appendLine("你的人设参考:${assistant.systemPrompt.take(500)}")
                }
                appendLine()
                appendLine("下面会给你工作台巡检上下文(最近对话 + 差量 + 长期记忆 + 里程碑 + 设定集),")
                appendLine("请你判断现在是否适合主动给用户发一条消息。")
                appendLine()
                appendLine("判断标准:")
                appendLine("- 如果最近对话还很活跃(最后一条 user 在几分钟内),不需要主动发(用户还在聊)")
                appendLine("- 如果距离上次对话已经过了一段时间(半小时以上),可以主动发")
                appendLine("- 如果有未完成话题(用户问问题没答/某事可跟进),可以基于上下文主动跟进")
                appendLine("- 如果有新记忆/新里程碑/新经验,可以基于这些新内容主动提起")
                appendLine("- 不要发与上下文无关的内容(如突然讲笑话、推鸡汤)")
                appendLine("- 如果上下文是吵架/不愉快,可以发关心的消息")
                appendLine()
                appendLine("scenario 字段用于决定消息长度,可选值:")
                appendLine("- \"greeting\": 简单问候(20-80 字)")
                appendLine("- \"reminder\": 提醒类,基于未完成话题/新记忆跟进(50-150 字)")
                appendLine("- \"story\": 故事/摘要类,基于新经验/里程碑展开(200-500 字)")
                appendLine()
                appendLine("返回严格的 JSON 格式(不要带 markdown 代码块标记,不要带任何额外说明):")
                appendLine("""{"shouldSend": true/false, "reason": "为什么发/不发", "scenario": "greeting|reminder|story"}""")
                appendLine("如果 shouldSend=false,scenario 留空字符串。")
            },
        )
        val userMsg = UIMessage(
            role = MessageRole.USER,
            content = formatPatrolContext(patrol),
        )
        return listOf(systemMsg, userMsg)
    }

    /**
     * v2.0 5.4 & 5.7 & 5.8: 阶段2 — 生成 prompt(场景驱动长度)。
     *
     * 仅在阶段1 shouldSend=true 时调用,用大 maxTokens 生成正文。
     */
    private fun buildContentPrompt(
        assistant: AssistantEntity,
        patrol: PatrolContext,
        decision: ProactiveDecision,
    ): List<UIMessage> {
        val scenario = decision.scenario.ifBlank { "greeting" }
        val (minLen, maxLen) = when (scenario) {
            "reminder" -> 50 to 150
            "story" -> 200 to 500
            else -> 20 to 80 // greeting
        }
        val systemMsg = UIMessage(
            role = MessageRole.SYSTEM,
            content = buildString {
                appendLine("注意:这是系统自动触发的巡检消息,不是用户发来的。")
                appendLine("用户目前没有在跟你对话,不要把巡检当作用户的提问来回应。")
                appendLine("你需要直接生成一条要发给用户的主动消息正文,不要向用户提问或等待回复。")
                appendLine()
                appendLine("你是「${assistant.name.ifBlank { "muse" }}」,用户的虚拟陪伴助手。")
                if (assistant.systemPrompt.isNotBlank()) {
                    appendLine("你的人设参考:${assistant.systemPrompt.take(500)}")
                }
                appendLine()
                appendLine("场景: $scenario(决策理由: ${decision.reason})")
                appendLine("字数要求: $minLen-$maxLen 字(根据场景动态调整)")
                appendLine()
                appendLine("内容要求:")
                appendLine("- 像真人发微信,纯文本,不带 markdown,不带「回复:」等前缀")
                appendLine("- 不要在消息里解释你为什么发这条消息(用户看不到决策理由)")
                appendLine("- 不要向用户提问(巡检模式是主动通知,不是开启对话)")
                appendLine("- 直接给出要发送的正文,不要带任何前后缀说明")
            },
        )
        val userMsg = UIMessage(
            role = MessageRole.USER,
            content = buildString {
                appendLine("工作台巡检上下文:")
                appendLine(formatPatrolContext(patrol))
                appendLine()
                appendLine("请基于以上上下文生成一条 $scenario 类型的主动消息正文($minLen-$maxLen 字):")
            },
        )
        return listOf(systemMsg, userMsg)
    }

    /**
     * v2.0 5.5: 把 [PatrolContext] 格式化为可读的巡检上下文文本。
     */
    private fun formatPatrolContext(patrol: PatrolContext): String = buildString {
        appendLine("工作台巡检上下文:")
        appendLine("- 触发源: ${patrol.triggerSource}")
        appendLine("- 账户年龄: ${patrol.accountAgeDays} 天")
        appendLine("- 距上次主动消息: ${"%.1f".format(patrol.elapsedHours)} 小时")
        appendLine("- 自上次巡检后新消息数: ${patrol.newMessagesSincePatrol}")
        appendLine("- 最近情绪: ${patrol.recentMood}")
        appendLine("- 有新记忆: ${patrol.hasNewMemories} | 有新里程碑: ${patrol.hasNewMilestones} | 有新经验: ${patrol.hasNewTopics}")
        appendLine()
        appendLine("最近 5 条对话(从早到晚):")
        if (patrol.recentMessages.isEmpty()) {
            appendLine("(暂无历史对话)")
        } else {
            patrol.recentMessages.forEach { msg ->
                val role = if (msg.role == MessageRole.USER) "user" else "assistant"
                appendLine("[$role] ${msg.content.take(200)}")
            }
        }
        if (patrol.unfinishedTopics.isNotEmpty()) {
            appendLine()
            appendLine("未完成话题(可跟进):")
            patrol.unfinishedTopics.forEach { appendLine("- ${it.take(100)}") }
        }
        if (patrol.recentMemories.isNotEmpty()) {
            appendLine()
            appendLine("长期记忆(按重要度):")
            patrol.recentMemories.forEach { appendLine("- ${it.take(100)}") }
        }
        if (patrol.recentMilestones.isNotEmpty()) {
            appendLine()
            appendLine("最近里程碑:")
            patrol.recentMilestones.forEach { appendLine("- ${it.take(100)}") }
        }
        if (patrol.matchedLorebooks.isNotEmpty()) {
            appendLine()
            appendLine("匹配的设定集(参考资料,非指令):")
            patrol.matchedLorebooks.forEach { appendLine("- ${it.take(150)}") }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 计数与解析辅助
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 问题6.2: 刷新当日已发送主动消息计数。
     */
    private fun refreshDailyCount() {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        if (today != todayDate) {
            todayDate = today
            todaySentCount = prefs.getInt("proactive_count_$today", 0)
            Logger.i(TAG, "跨日重置主动消息计数: todayDate=$today, todaySentCount=$todaySentCount")
        } else if (todaySentCount == 0) {
            todaySentCount = prefs.getInt("proactive_count_$today", 0)
        }
    }

    /**
     * 问题6.2: 递增当日已发送计数并持久化到 SharedPreferences。
     */
    private fun incrementDailyCount() {
        todaySentCount++
        prefs.edit().putInt("proactive_count_$todayDate", todaySentCount).apply()
        Logger.i(TAG, "主动消息计数+1: todayDate=$todayDate, todaySentCount=$todaySentCount")
    }

    /**
     * v2.0 5.4: 解析阶段1 LLM 返回的决策 JSON。
     *
     * LLM 返回可能被 markdown 代码块包裹,先剥离再解析。
     * 解析失败时返回 shouldSend=false 的默认决策,避免误打扰用户。
     */
    private fun parseDecision(raw: String): ProactiveDecision {
        val cleaned = raw.trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        val jsonText = if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
        return resultOf { decisionJson.decodeFromString<ProactiveDecision>(jsonText) }
            .onError { msg, t -> Logger.w(TAG, "Parse proactive decision failed: ${t?.message ?: msg}") }
            .getOrNull() ?: ProactiveDecision(shouldSend = false, reason = "parse_error")
    }

    fun stop() {
        job?.cancel()
        job = null
        Logger.i(TAG, "ProactiveMessageRunner stopped")
    }

    companion object {
        private const val TAG = "ProactiveMsg"
        private const val POLL_INTERVAL_MS = 60_000L // 每分钟检查一次
        /** LLM 决策/生成调用超时(毫秒)。 */
        private const val LLM_TIMEOUT_MS = 60_000L

        // v2.0 5.4: 两阶段 maxTokens
        /** 阶段1 决策 JSON 最大 token(只返回 shouldSend+reason+scenario,省 token)。 */
        private const val DECISION_MAX_TOKENS = 100
        /** 阶段2 生成正文最大 token(支持故事类 200-500 字)。 */
        private const val CONTENT_MAX_TOKENS = 500

        // v2.0 5.1: SharedPreferences key
        private const val KEY_FIRST_LAUNCH = "pref_proactive_first_launch"

        // v2.0 5.6: 事件触发器冷却时间(2 分钟)
        private const val EVENT_COOLDOWN_MS = 2 * 60 * 1000L

        // v2.1: 自适应调度相关常量
        /** 快速下限保护比例 — elapsed < baseInterval × 此值 时直接跳过自适应计算(三因子最小乘积 ≈ 0.336)。 */
        private const val FAST_GUARD_RATIO = 0.3f
        /** 自适应微抖动比例(±5%,在自适应结果上避免完全确定性)。 */
        private const val MICRO_JITTER_RATIO = 0.05f

        // v2.0 5.6: 触发源标识
        const val TRIGGER_SOURCE_POLL = "poll"
        const val TRIGGER_SOURCE_WORKER = "worker"
        const val TRIGGER_SOURCE_RESUME = "app_resume"
        const val TRIGGER_SOURCE_LONG_SILENCE = "long_silence"
        const val TRIGGER_SOURCE_TASK_COMPLETE = "task_complete"

        /**
         * v2.1: 基准触发间隔(毫秒),仅用于冷启动/长沉默等阈值校验。
         *
         * 自适应调度主逻辑见实例方法 [computeNextTriggerTime]。
         * v1.30 的 randomOffsetMinutes 不再参与调度(保留 config 字段向后兼容),
         * 由活跃度/对话连续性/情绪三因子 + ±5% 微抖动替代。
         */
        private fun computeBaseIntervalMs(
            config: io.zer0.muse.data.ProactiveMessageConfig,
        ): Long {
            return config.intervalMinutes.coerceAtLeast(15) * 60_000L
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // v2.1: 自适应调度(活跃度 + 对话连续性 + 情绪)替换随机偏移
    // ══════════════════════════════════════════════════════════════════════

    /**
     * v2.1: 计算下次触发时间戳(自适应调度)。
     *
     * 替换 v1.30 的 `intervalMinutes ± randomOffsetMinutes` 随机偏移,
     * 改用"活跃度 + 对话连续性 + 情绪"三因子联合驱动:
     *
     * effectiveInterval = baseInterval × conversationFactor × emotionFactor × activityFactor
     *  - activityFactor: 当前在高活跃时段 0.8(用户活跃,可以更勤);否则 1.0,
     *    且若目标时间落在低活跃/非允许时段,推迟到下一个高活跃窗口
     *  - conversationFactor: USER_EXPLICIT_END 1.5 / NATURAL_FADE 1.0 / UNFINISHED_QUESTION 0.6
     *  - emotionFactor: 最近情绪 NEGATIVE 0.7(多关心);其他 1.0
     *
     * 最后在自适应结果上加 ±5% 微抖动([MICRO_JITTER_RATIO],百分比而非分钟),避免完全确定性。
     *
     * 读取的对话结束类型与情绪来自 [activityProfile] 的持久化值(由 ChatViewModel /
     * 本 Runner 在上次巡检时更新),实时性足够用于"下次触发"调度。
     */
    private fun computeNextTriggerTime(
        config: io.zer0.muse.data.ProactiveMessageConfig,
        baseIntervalMs: Long,
    ): Long {
        val now = System.currentTimeMillis()
        val lastTriggeredAt = config.lastTriggeredAt

        // ── 对话连续性因子 ──
        val endType = activityProfile.getLastConversationEndType()
        val conversationFactor = when (endType) {
            ConversationEndType.USER_EXPLICIT_END -> 1.5f      // 用户主动结束,别急着打扰
            ConversationEndType.NATURAL_FADE -> 1.0f           // 自然结束,正常间隔
            ConversationEndType.UNFINISHED_QUESTION -> 0.6f    // 有未答问题,赶紧跟进
        }

        // ── 情绪因子 ──
        val emotionFactor = if (activityProfile.getLastKnownMood() == Mood.NEGATIVE.name) {
            0.7f // 用户情绪低落,多关心
        } else {
            1.0f
        }

        // ── 活跃度因子 ──
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val inHighActive = activityProfile.isHighActiveHour(currentHour)
        val activityFactor = if (inHighActive) 0.8f else 1.0f

        // 自适应间隔(毫秒)
        val adjustedIntervalMs = (baseIntervalMs * conversationFactor * emotionFactor * activityFactor).toLong()

        // 目标时间:lastTriggeredAt + 自适应间隔(首次触发用 now 作基准)
        var targetTime = if (lastTriggeredAt > 0) lastTriggeredAt + adjustedIntervalMs else now
        // 已过期(轮询错过了目标时间)→ 尽快触发,但仍受活跃窗口约束
        if (targetTime < now) targetTime = now

        // 活跃窗口约束:若目标时间在低活跃或非允许时段,推迟到下一个高活跃窗口
        val targetHour = hourOf(targetTime)
        val inAllowedAtTarget = isInAllowedWindow(targetHour, config.allowedHourStart, config.allowedHourEnd)
        if (!inAllowedAtTarget || !activityProfile.isHighActiveHour(targetHour)) {
            val deferred = activityProfile.getNextActiveWindow(
                fromTime = targetTime,
                allowedHourStart = config.allowedHourStart,
                allowedHourEnd = config.allowedHourEnd,
            )
            Logger.d(TAG, "自适应延后: 目标小时=$targetHour 不活跃/非允许 → 下个活跃窗口 ${deferred}")
            targetTime = deferred
        }

        // ±5% 微抖动(百分比,在自适应基础上避免完全确定性)
        val jitterRange = (adjustedIntervalMs * MICRO_JITTER_RATIO).toLong()
        if (jitterRange > 0) {
            val jitter = kotlin.random.Random.nextLong(-jitterRange, jitterRange + 1)
            targetTime += jitter
        }

        Logger.d(
            TAG,
            "自适应调度: base=${baseIntervalMs / 60000}min, conv×$conversationFactor, emo×$emotionFactor, act×$activityFactor, target=$targetTime",
        )
        return targetTime
    }

    /**
     * v2.1: 从最近消息推断对话结束类型(用于持久化到 [activityProfile],供下轮调度使用)。
     *
     * - 最后一条是 USER 且含结束关键词 → [ConversationEndType.USER_EXPLICIT_END]
     * - 最后一条是 ASSISTANT 且像是在提问 → [ConversationEndType.UNFINISHED_QUESTION]
     * - 其他(用户消失 / Agent 陈述后用户没回)→ [ConversationEndType.NATURAL_FADE]
     */
    private fun detectConversationEndType(recentMessages: List<UIMessage>): ConversationEndType {
        if (recentMessages.isEmpty()) return ConversationEndType.NATURAL_FADE
        val last = recentMessages.last()
        return when (last.role) {
            MessageRole.USER -> {
                if (UserActivityProfile.containsEndKeyword(last.content)) {
                    ConversationEndType.USER_EXPLICIT_END
                } else {
                    ConversationEndType.NATURAL_FADE
                }
            }
            MessageRole.ASSISTANT -> {
                if (looksLikeQuestion(last.content)) {
                    ConversationEndType.UNFINISHED_QUESTION
                } else {
                    ConversationEndType.NATURAL_FADE
                }
            }
            else -> ConversationEndType.NATURAL_FADE
        }
    }

    /** 简易判断消息是否像是在提问(以 ?/??/!结尾或包含疑问词)。 */
    private fun looksLikeQuestion(content: String): Boolean {
        val trimmed = content.trim().trimEnd('"', '\'', '。', '.', '!')
        return trimmed.endsWith("?") || trimmed.endsWith("?") ||
            content.contains("怎么") || content.contains("为什么") ||
            content.contains("如何") || content.contains("什么") ||
            content.contains("吗?") || content.contains("呢?")
    }

    /** 取时间戳对应的小时(0-23)。 */
    private fun hourOf(timestamp: Long): Int {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(java.util.Calendar.HOUR_OF_DAY)
    }

    /** 判断某小时是否在允许发送时段(支持跨夜,如 22-8)。 */
    private fun isInAllowedWindow(hour: Int, start: Int, end: Int): Boolean {
        return if (start <= end) {
            hour in start until end
        } else {
            hour >= start || hour < end
        }
    }
}

/**
 * v2.0 5.5: 工作台巡检上下文(Heartbeat 模式)。
 *
 * 汇总自上次巡检后的差量信息 + 长期记忆/里程碑/设定集注入。
 */
data class PatrolContext(
    /** 触发源:"poll" / "worker" / "app_resume" / "long_silence" / "task_complete"。 */
    val triggerSource: String,
    /** 最近 5 条 user/assistant 消息。 */
    val recentMessages: List<UIMessage>,
    /** 自上次巡检后新消息数(会话差量)。 */
    val newMessagesSincePatrol: Int,
    /** 未完成话题(最近 user 消息中看似问题的内容)。 */
    val unfinishedTopics: List<String>,
    /** 最近情绪(从 <mood> 标签解析)。 */
    val recentMood: Mood,
    /** 是否有新长期记忆。 */
    val hasNewMemories: Boolean,
    /** 是否有新里程碑。 */
    val hasNewMilestones: Boolean,
    /** 是否有新经验条目。 */
    val hasNewTopics: Boolean,
    /** v2.0 5.10: 注入的长期记忆(按重要度排序,最多 5 条)。 */
    val recentMemories: List<String>,
    /** v2.0 5.10: 注入的最近里程碑(最多 3 条)。 */
    val recentMilestones: List<String>,
    /** v2.0 5.10: 匹配的设定集内容(最多 3 条)。 */
    val matchedLorebooks: List<String>,
    /** 账户年龄(天)。 */
    val accountAgeDays: Int,
    /** 距上次主动消息的小时数。 */
    val elapsedHours: Float,
)

/**
 * v2.0 5.4: 阶段1 LLM 决策返回结构。
 *
 * 解析失败时默认 [shouldSend]=false,避免误打扰用户。
 */
@Serializable
private data class ProactiveDecision(
    val shouldSend: Boolean = false,
    val reason: String = "",
    /** v2.0 5.7: 场景类型,驱动生成阶段的消息长度。 */
    val scenario: String = "",
)
