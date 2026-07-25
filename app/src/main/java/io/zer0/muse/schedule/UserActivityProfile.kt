package io.zer0.muse.schedule

import android.content.Context
import io.zer0.common.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Calendar
import kotlin.math.pow

/**
 * 用户活跃度画像 — 记录用户最近 7 天每小时的活动频次,驱动自适应主动消息调度。
 *
 * 用 24 维数组记录每小时的活动计数,每日应用衰减因子([DECAY_FACTOR]=0.85),使数据
 * 近似反映"最近 7 天"的活跃分布(7 天后权重 ≈ 0.32,旧数据自然淡出)。
 *
 * 冷启动:新用户前 7 天数据不足,用通用默认画像(早 8-10 / 晚 20-22 高活跃,
 * 午 12-14 中活跃,其他低活跃)兜底。过渡期(0-7 天)默认画像与真实画像按
 * "天数/7"权重混合,满 7 天后完全用真实画像。详见 [getActiveProbability]。
 *
 * 同时持久化 [lastConversationEndType] 与 [lastKnownMood],供 [ProactiveMessageRunner]
 * 计算自适应触发间隔时读取(对话连续性因子 / 情绪因子)。
 *
 * 持久化到 SharedPreferences(JSON 序列化),App 重启后画像不丢失。
 *
 * 调用方:
 *  - [ProactiveMessageRunner] 读取 [isHighActiveHour] / [getNextActiveWindow] /
 *    [getLastConversationEndType] / [getLastKnownMood] 决定下次触发时间
 *  - [io.zer0.muse.ui.ChatViewModel] 在用户发消息时调用 [recordActivity] 更新画像,
 *    并根据消息内容更新 [lastConversationEndType]
 */
class UserActivityProfile(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var state: ProfileState = load()

    // ══════════════════════════════════════════════════════════════════════
    // 活跃度记录
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 记录一次用户活动(通常在用户发消息时调用)。
     *
     * @param timestamp 活动时间戳,默认当前时间
     */
    @Synchronized
    fun recordActivity(timestamp: Long = System.currentTimeMillis()) {
        applyDecayIfNeeded(timestamp)
        val hour = hourOf(timestamp)
        val counts = state.hourlyCounts.toMutableList()
        counts[hour] = counts[hour] + 1
        // 冷启动:首次记录活动时,记录起始天数(用于计算"已积累多少天数据",
        // 驱动默认画像与真实画像的权重混合,满 [DEFAULT_PROFILE_DAYS] 天后完全用真实画像)
        val currentDay = (timestamp / (24 * 60 * 60 * 1000L)).toInt()
        val firstDay = if (state.firstRecordDay == 0) currentDay else state.firstRecordDay
        state = state.copy(
            hourlyCounts = counts,
            totalRecords = state.totalRecords + 1,
            lastRecordAt = timestamp,
            firstRecordDay = firstDay,
        )
        save()
    }

    /**
     * 返回某小时的活跃概率(归一化到 0-1)。
     *
     * 冷启动与过渡期(累计数据 < [DEFAULT_PROFILE_DAYS] 天):
     *  - 默认画像([DEFAULT_HOURLY_COUNTS])与真实画像按权重混合
     *  - 真实权重 = 已积累天数 / [DEFAULT_PROFILE_DAYS];默认权重 = 1 - 真实权重
     *  - 随真实数据逐步替代,满 [DEFAULT_PROFILE_DAYS] 天后完全用真实画像
     *
     * 若混合后总数为 0(长期未使用导致衰减殆尽),返回均匀分布(1/24 ≈ 0.042)。
     */
    @Synchronized
    fun getActiveProbability(hour: Int): Float {
        applyDecayIfNeeded(System.currentTimeMillis())
        val h = hour.coerceIn(0, 23)
        val daysCollected = getDaysCollected()
        // 满 7 天:完全用真实画像
        if (daysCollected >= DEFAULT_PROFILE_DAYS) {
            val counts = state.hourlyCounts
            val total = counts.sum().toFloat()
            if (total <= 0f) return DEFAULT_PROBABILITY
            return (counts[h] / total).coerceIn(0f, 1f)
        }
        // 冷启动/过渡期:默认画像 + 真实数据按权重混合
        val realWeight = daysCollected.toFloat() / DEFAULT_PROFILE_DAYS
        val defaultWeight = 1f - realWeight
        val realCounts = state.hourlyCounts
        var blendedTotal = 0f
        var blendedHour = 0f
        for (i in 0 until 24) {
            val blended = realWeight * realCounts[i] + defaultWeight * DEFAULT_HOURLY_COUNTS[i]
            blendedTotal += blended
            if (i == h) blendedHour = blended
        }
        if (blendedTotal <= 0f) return DEFAULT_PROBABILITY
        return (blendedHour / blendedTotal).coerceIn(0f, 1f)
    }

    /**
     * 判断某小时是否属于"高活跃时段"。
     *
     * 阈值 = 平均活跃概率 × [ACTIVE_THRESHOLD_FACTOR](1.5)。
     * 冷启动/过渡期通过 [getActiveProbability] 的默认画像混合保证新用户也能拿到
     * 合理的高活跃时段(早 8-10 / 晚 20-22),而非旧的"所有时段全活跃"策略。
     */
    @Synchronized
    fun isHighActiveHour(hour: Int): Boolean {
        applyDecayIfNeeded(System.currentTimeMillis())
        val prob = getActiveProbability(hour)
        val avgProb = 1f / 24f
        return prob >= avgProb * ACTIVE_THRESHOLD_FACTOR
    }

    /**
     * 返回从 [fromTime] 起下一个高活跃时段的开始时间(整点)。
     *
     * - 若 [fromTime] 当前就在高活跃且在允许时段,返回 [fromTime](立即触发)
     * - 否则扫描未来 48 小时,找下一个同时满足"高活跃 + 在允许时段"的整点
     * - 找不到时回退到 [fromTime] + 1 小时(兜底,避免无限延后)
     *
     * @param fromTime 起始时间戳
     * @param allowedHourStart 允许发送时段开始小时(0-23),与 ProactiveMessageConfig 对齐
     * @param allowedHourEnd 允许发送时段结束小时(0-23,支持跨夜,如 22-8 表示22点到次日8点)
     */
    @Synchronized
    fun getNextActiveWindow(
        fromTime: Long,
        allowedHourStart: Int = 8,
        allowedHourEnd: Int = 22,
    ): Long {
        applyDecayIfNeeded(fromTime)
        val currentHour = hourOf(fromTime)
        // 当前就在高活跃且在允许时段 → 立即返回
        if (isInAllowedWindow(currentHour, allowedHourStart, allowedHourEnd) &&
            isHighActiveHour(currentHour)
        ) {
            return fromTime
        }
        // 扫描未来 48 小时,找下一个高活跃整点
        for (offset in 1..48) {
            val hour = (currentHour + offset) % 24
            if (!isInAllowedWindow(hour, allowedHourStart, allowedHourEnd)) continue
            if (!isHighActiveHour(hour)) continue
            // 计算 hour 的下一个整点时间戳
            val candidate = Calendar.getInstance().apply {
                timeInMillis = fromTime
                add(Calendar.HOUR_OF_DAY, offset)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return candidate.timeInMillis
        }
        // 兜底:fromTime + 1 小时
        return fromTime + 60 * 60 * 1000L
    }

    // ══════════════════════════════════════════════════════════════════════
    // 对话连续性
    // ══════════════════════════════════════════════════════════════════════

    /** 返回最近记录的对话结束类型(持久化)。 */
    fun getLastConversationEndType(): ConversationEndType {
        return runCatching {
            ConversationEndType.valueOf(state.lastConversationEndType)
        }.getOrDefault(ConversationEndType.NATURAL_FADE)
    }

    /** 更新对话结束类型(持久化)。由 ChatViewModel / ProactiveMessageRunner 调用。 */
    @Synchronized
    fun setConversationEndType(type: ConversationEndType) {
        if (state.lastConversationEndType == type.name) return
        state = state.copy(lastConversationEndType = type.name)
        save()
    }

    // ══════════════════════════════════════════════════════════════════════
    // 情绪连续性
    // ══════════════════════════════════════════════════════════════════════

    /** 返回最近记录的用户情绪(持久化,由 ProactiveMessageRunner 在巡检时更新)。 */
    fun getLastKnownMood(): String = state.lastKnownMood

    /** 更新最近已知情绪(持久化)。 */
    @Synchronized
    fun setLastKnownMood(mood: String) {
        if (state.lastKnownMood == mood) return
        state = state.copy(lastKnownMood = mood)
        save()
    }

    // ══════════════════════════════════════════════════════════════════════
    // 内部:冷启动画像混合
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 计算已积累的真实数据天数(用于冷启动画像混合)。
     *
     * - [ProfileState.firstRecordDay] 为 0(从未记录过活动)→ 返回 0(纯默认画像)
     * - 衰减后 [ProfileState.totalRecords] ≤ 0(长期未使用导致数据衰减殆尽)→ 返回 0(回退默认画像)
     * - 否则返回 currentDay - firstRecordDay + 1(1-indexed,首日即 1)
     */
    private fun getDaysCollected(): Int {
        if (state.firstRecordDay == 0) return 0
        if (state.totalRecords <= 0) return 0
        val currentDay = (System.currentTimeMillis() / (24 * 60 * 60 * 1000L)).toInt()
        return (currentDay - state.firstRecordDay + 1).coerceAtLeast(1)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 内部:衰减与持久化
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 按日衰减(0.85/天)。每次读写时检查,若跨日则补算衰减。
     */
    private fun applyDecayIfNeeded(timestamp: Long) {
        val currentDay = (timestamp / (24 * 60 * 60 * 1000L)).toInt()
        val lastDay = state.lastDecayDay
        if (lastDay == 0) {
            // 首次:仅记录当天,不衰减
            state = state.copy(lastDecayDay = currentDay)
            return
        }
        if (currentDay <= lastDay) return
        val elapsedDays = (currentDay - lastDay).coerceAtMost(MAX_DECAY_DAYS)
        if (elapsedDays <= 0) return
        val factor = DECAY_FACTOR.pow(elapsedDays)
        val decayed = state.hourlyCounts.map { (it * factor).toInt() }
        val newTotal = decayed.sum()
        state = state.copy(
            hourlyCounts = decayed,
            totalRecords = newTotal,
            lastDecayDay = currentDay,
        )
        Logger.d(TAG, "活跃度画像衰减: elapsedDays=$elapsedDays, factor=$factor, total=$newTotal")
    }

    private fun load(): ProfileState {
        val raw = prefs.getString(KEY_STATE, null) ?: return ProfileState()
        return runCatching {
            json.decodeFromString<ProfileState>(raw)
        }.onFailure { t ->
            Logger.w(TAG, "加载活跃度画像失败,使用默认值: ${t.message}")
        }.getOrDefault(ProfileState())
    }

    @Synchronized
    private fun save() {
        prefs.edit().putString(KEY_STATE, json.encodeToString(ProfileState.serializer(), state)).apply()
    }

    private fun hourOf(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(Calendar.HOUR_OF_DAY)
    }

    /** 判断某小时是否在允许发送时段(支持跨夜,如 22-8)。 */
    private fun isInAllowedWindow(hour: Int, start: Int, end: Int): Boolean {
        return if (start <= end) {
            hour in start until end
        } else {
            hour >= start || hour < end
        }
    }

    companion object {
        private const val TAG = "UserActivityProfile"
        private const val PREFS_NAME = "user_activity_profile"
        private const val KEY_STATE = "profile_state"
        private const val DEFAULT_PROBABILITY = 1f / 24f
        /** 高活跃阈值因子:概率 ≥ 平均概率 × 此因子 才算高活跃。 */
        private const val ACTIVE_THRESHOLD_FACTOR = 1.5f
        /** 每日衰减因子(0.85 → 7 天后权重 ≈ 0.32)。 */
        private const val DECAY_FACTOR = 0.85f
        /** 单次最多补算的衰减天数(防止 App 长期未打开后衰减到 0)。 */
        private const val MAX_DECAY_DAYS = 30
        /**
         * 冷启动默认画像覆盖天数 — 累计真实数据达到此天数后完全用真实画像。
         * 不足此天数时,默认画像与真实画像按"天数/[DEFAULT_PROFILE_DAYS]"权重混合。
         */
        private const val DEFAULT_PROFILE_DAYS = 7

        /**
         * 冷启动默认画像 — 24 小时活动计数(相对权重,非绝对值)。
         *
         * 通用人群作息模板,新用户前 7 天调度依赖此画像,随真实数据积累逐步替代:
         *  - 早 8-10 点(8,9,10):高活跃(3.0)— 上班通勤/开始工作
         *  - 午 12-14 点(12,13,14):中活跃(1.5)— 午休
         *  - 晚 20-22 点(20,21,22):高活跃(3.0)— 晚间休闲
         *  - 其他时段:低活跃(0.3)— 凌晨/工作时段
         */
        private val DEFAULT_HOURLY_COUNTS: List<Float> = listOf(
            // 0-6: 凌晨低活跃
            0.3f, 0.3f, 0.3f, 0.3f, 0.3f, 0.3f, 0.3f,
            // 7: 起床过渡
            0.5f,
            // 8-10: 早高峰
            3.0f, 3.0f, 3.0f,
            // 11: 午餐前过渡
            0.5f,
            // 12-14: 午休中活跃
            1.5f, 1.5f, 1.5f,
            // 15-19: 下午工作低活跃
            0.3f, 0.3f, 0.3f, 0.3f, 0.3f,
            // 20-22: 晚高峰
            3.0f, 3.0f, 3.0f,
            // 23: 睡前过渡
            0.5f,
        )

        /**
         * 用户主动结束对话的关键词(用于 [ConversationEndType.USER_EXPLICIT_END] 判定)。
         */
        val END_KEYWORDS = listOf(
            "晚安", "拜拜", "先这样", "再见", "走了", "下次聊", "回见",
            "bye", "goodbye", "good night", "gn", "see you", "cya",
        )

        /**
         * 判断消息内容是否包含对话结束关键词。
         */
        fun containsEndKeyword(content: String): Boolean {
            val lower = content.lowercase().trim()
            return END_KEYWORDS.any { lower.contains(it) }
        }
    }
}

/**
 * 对话结束类型 — 影响 [ProactiveMessageRunner] 的自适应触发间隔。
 *
 * - [USER_EXPLICIT_END]:用户主动结束(说了"晚安"/"拜拜"等)→ 间隔 ×1.5,别急着打扰
 * - [NATURAL_FADE]:Agent 回复后用户没再说话(自然结束)→ 正常间隔
 * - [UNFINISHED_QUESTION]:Agent 问了问题但用户没答 → 间隔 ×0.6,赶紧跟进
 */
enum class ConversationEndType {
    USER_EXPLICIT_END,
    NATURAL_FADE,
    UNFINISHED_QUESTION,
}

/** 活跃度画像持久化状态。 */
@Serializable
private data class ProfileState(
    /** 24 小时活动计数(索引 0-23 对应小时)。 */
    val hourlyCounts: List<Int> = List(24) { 0 },
    /** 累计活动总数(衰减后)。 */
    val totalRecords: Int = 0,
    /** 最后一次活动时间戳。 */
    val lastRecordAt: Long = 0L,
    /** 距 1970-01-01 的天数,用于按日衰减。 */
    val lastDecayDay: Int = 0,
    /**
     * 首次记录活动的天数(距 1970-01-01),用于冷启动画像混合。
     * 0 表示尚未记录过任何活动;一旦设置后不再改变。
     * 配合当前天数计算"已积累多少天数据",驱动默认画像与真实画像的权重混合。
     */
    val firstRecordDay: Int = 0,
    /** 最近对话结束类型([ConversationEndType] 的 name)。 */
    val lastConversationEndType: String = "NATURAL_FADE",
    /** 最近已知情绪([io.zer0.muse.data.proactive.Mood] 的 name)。 */
    val lastKnownMood: String = "UNKNOWN",
)
