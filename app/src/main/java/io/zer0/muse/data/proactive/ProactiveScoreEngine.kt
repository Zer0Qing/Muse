package io.zer0.muse.data.proactive

import io.zer0.common.Logger
import kotlinx.serialization.Serializable
import java.util.Calendar
import kotlin.math.exp
import kotlin.math.min

/**
 * Phase 4 4D: 主动消息评分引擎 — 决定何时主动给用户发消息。
 *
 * 评分公式: score = timeWeight × silenceWeight × emotionWeight × noveltyWeight
 *
 * - timeWeight: 时间衰减(距离上次消息的时间)
 * - silenceWeight: 沉默期权重(新用户每3天,老用户每7天)
 * - emotionWeight: 情绪权重(根据最近消息的情绪倾向)
 * - noveltyWeight: 新鲜度权重(是否有新的话题/记忆可聊)
 *
 * 安静时段: 22:00-08:00,每天最多 3 条。
 */
class ProactiveScoreEngine {

    companion object {
        private const val TAG = "ProactiveScore"
        private const val MAX_DAILY_MESSAGES = 3
        private const val QUIET_HOUR_START = 22 // 22:00
        private const val QUIET_HOUR_END = 8    // 08:00
        private const val NEW_USER_INTERVAL_DAYS = 3
        private const val OLD_USER_INTERVAL_DAYS = 7
        private const val OLD_USER_THRESHOLD_DAYS = 30 // 使用30天以上算老用户

        // 评分阈值 — 高于此值才发送
        private const val SEND_THRESHOLD = 0.6f
    }

    /**
     * 计算主动消息评分。
     *
     * @param context 评分上下文(包含各种状态)
     * @return 0.0 ~ 1.0 之间的评分
     */
    fun calculateScore(context: ScoreContext): Float {
        val timeW = computeTimeWeight(context)
        val silenceW = computeSilenceWeight(context)
        val emotionW = computeEmotionWeight(context)
        val noveltyW = computeNoveltyWeight(context)

        val score = (timeW * silenceW * emotionW * noveltyW).coerceIn(0f, 1f)
        Logger.d(TAG, "Score: %.3f (time=%.2f silence=%.2f emotion=%.2f novelty=%.2f)".format(score, timeW, silenceW, emotionW, noveltyW))
        return score
    }

    /**
     * 是否应该发送主动消息(综合评分 + 安静时段 + 每日限制)。
     *
     * v2.0 5.9: 每日上限改用 [ScoreContext.maxDailyMessages](可配置),
     * 替代硬编码的 [MAX_DAILY_MESSAGES]。ScoreContext 默认值仍为 3,向后兼容。
     */
    fun shouldSend(context: ScoreContext): Boolean {
        // 安静时段检查
        if (isQuietHour()) {
            Logger.d(TAG, "Quiet hours, skipping")
            return false
        }
        // 每日限制(v2.0: 可配置,默认 3)
        val dailyLimit = context.maxDailyMessages.coerceAtLeast(1)
        if (context.todaySentCount >= dailyLimit) {
            Logger.d(TAG, "Daily limit reached ($dailyLimit)")
            return false
        }
        // 评分检查
        val score = calculateScore(context)
        return score >= SEND_THRESHOLD
    }

    /** 安静时段判断(22:00-08:00)。 */
    fun isQuietHour(currentHour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): Boolean {
        return currentHour >= QUIET_HOUR_START || currentHour < QUIET_HOUR_END
    }

    /**
     * 时间权重 — 距离上次消息越久,权重越高(指数增长,上限 1.0)。
     *
     * 公式: 1 - exp(-hours / 24), 24小时后接近 0.63, 48小时后接近 0.86
     */
    private fun computeTimeWeight(ctx: ScoreContext): Float {
        val hoursSinceLastMessage = ctx.hoursSinceLastMessage
        return (1f - exp(-hoursSinceLastMessage / 24.0)).toFloat().coerceIn(0f, 1f)
    }

    /**
     * 沉默期权重 — 根据用户年龄(新用户/老用户)设定理想间隔。
     *
     * 新用户(< 30天): 理想间隔 3 天
     * 老用户(>= 30天): 理想间隔 7 天
     *
     * 公式: min(daysSinceLastMessage / idealInterval, 1.0)
     */
    private fun computeSilenceWeight(ctx: ScoreContext): Float {
        val idealDays = if (ctx.accountAgeDays < OLD_USER_THRESHOLD_DAYS) {
            NEW_USER_INTERVAL_DAYS
        } else {
            OLD_USER_INTERVAL_DAYS
        }
        val daysSinceLast = ctx.hoursSinceLastMessage / 24f
        return min(daysSinceLast / idealDays, 1.0f)
    }

    /**
     * 情绪权重 — 基于最近消息的情绪标签。
     *
     * 积极情绪: 权重稍高(用户可能愿意继续交流)
     * 消极情绪: 权重稍高(关心用户状态)
     * 中性: 权重适中
     */
    private fun computeEmotionWeight(ctx: ScoreContext): Float {
        return when (ctx.recentMood) {
            Mood.POSITIVE -> 0.8f
            Mood.NEGATIVE -> 0.9f // 更关心用户
            Mood.NEUTRAL -> 0.6f
            Mood.UNKNOWN -> 0.5f
        }
    }

    /**
     * 新鲜度权重 — 是否有新话题/记忆可聊。
     *
     * 有新的里程碑/记忆/话题: 权重高
     * 无新内容: 权重低
     */
    private fun computeNoveltyWeight(ctx: ScoreContext): Float {
        var weight = 0.3f // 基础权重
        if (ctx.hasNewMilestones) weight += 0.3f
        if (ctx.hasNewMemories) weight += 0.2f
        if (ctx.hasNewTopics) weight += 0.2f
        return weight.coerceIn(0f, 1f)
    }
}

/** 评分上下文。 */
data class ScoreContext(
    /** 距离上次消息的小时数。 */
    val hoursSinceLastMessage: Float = 24f,
    /** 账户年龄(天)。 */
    val accountAgeDays: Int = 7,
    /** 最近消息的情绪倾向。 */
    val recentMood: Mood = Mood.UNKNOWN,
    /** 今天已发送的主动消息数。 */
    val todaySentCount: Int = 0,
    /** 是否有新的里程碑未读。 */
    val hasNewMilestones: Boolean = false,
    /** 是否有新的记忆。 */
    val hasNewMemories: Boolean = false,
    /** 是否有新的可聊话题。 */
    val hasNewTopics: Boolean = false,
    /**
     * v2.0 5.9: 每日主动消息上限(默认 3)。
     *
     * 由 ProactiveMessageRunner 从 ProactiveMessageConfig.maxDailyMessages 传入,
     * 替代 ScoreEngine 内部硬编码的 MAX_DAILY_MESSAGES。默认值 3 保持向后兼容。
     */
    val maxDailyMessages: Int = 3,
)

/** 情绪枚举。 */
enum class Mood {
    POSITIVE, NEGATIVE, NEUTRAL, UNKNOWN
}
