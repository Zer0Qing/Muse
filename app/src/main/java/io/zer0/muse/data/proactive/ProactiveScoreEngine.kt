package io.zer0.muse.data.proactive

import io.zer0.common.Logger
import java.util.Calendar
import kotlin.math.exp
import kotlin.math.min

/**
 * Phase 4 4D: 主动消息评分引擎 — 决定何时主动给用户发消息。
 *
 * v1.0.72 评分公式修正:
 * 旧公式 score = timeW × silenceW × emotionW × noveltyW(乘法)
 *  → 因子连乘后数值过小(典型场景 0.01~0.05),阈值 0.6 几乎永远无法触发,
 *    导致用户手机上主动消息"一直不发"(测试用 forceSend 直发所以看不出来)。
 * 新公式 score = (timeW + silenceW + emotionW + noveltyW) / 4(加权平均)
 *  → 各因子均衡参与,正常用户"隔几小时没聊"即可触发。
 * 阈值从 0.6 降到 0.2(用户决策)。
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

        /**
         * 硬编码静默时段(22:00-08:00)。
         *
         * internal 而非 private:它和用户的「可发送时段」是两套独立闸门,
         * 交集为空会造成结构性零发送,[ProactiveSendWindow] 需要这个值做诊断,
         * 两边各写一份数字迟早漂移。
         */
        internal const val QUIET_HOUR_START = 22 // 22:00

        /** 见 [QUIET_HOUR_START]。 */
        internal const val QUIET_HOUR_END = 8 // 08:00
        private const val NEW_USER_INTERVAL_DAYS = 3
        private const val OLD_USER_INTERVAL_DAYS = 7
        private const val OLD_USER_THRESHOLD_DAYS = 30 // 使用30天以上算老用户

        // 评分阈值 — 高于此值才发送。
        // B-10: 0.2 → 0.35 — 最小可能评分 = (time=0 + silence=0 + emotion=0.5 + novelty=0.3)/4 = 0.2,
        // 旧阈值与下界相撞导致 score >= 0.2 数学上恒真,"评分防骚扰"完全空转。
        // 0.35 保证"刚聊完立刻触发"的静止场景评分(0.2)不过线,防骚扰门槛重新生效。
        internal const val SEND_THRESHOLD = 0.35f
    }

    /**
     * 计算主动消息评分。
     *
     * v1.0.72: 乘法 → 加权平均,不设时间门槛。
     *  - 加权平均: 各因子均衡参与,正常用户"隔几小时没聊"即可触发。
     *  - 无硬性时间门槛: 发不发由评分 + 概率 + 发送间隔设置共同决定
     *    (用户决策: 某些用户喜欢频繁主动消息,不写死"几小时内不发")。
     *
     * @param context 评分上下文(包含各种状态)
     * @return 0.0 ~ 1.0 之间的评分
     */
    fun calculateScore(context: ScoreContext): Float {
        val timeW = computeTimeWeight(context)
        val silenceW = computeSilenceWeight(context)
        val emotionW = computeEmotionWeight(context)
        val noveltyW = computeNoveltyWeight(context)

        // v1.0.72: 加权平均替代连乘 — 避免因子连乘后数值过小无法触发
        val score = combine(timeW, silenceW, emotionW, noveltyW)
        Logger.d(TAG, "Score: %.3f (time=%.2f silence=%.2f emotion=%.2f novelty=%.2f)".format(score, timeW, silenceW, emotionW, noveltyW))
        return score
    }

    /** 四个因子 → 加权平均(单一实现处,避免 evaluate 与 calculateScore 各算一套)。 */
    private fun combine(timeW: Float, silenceW: Float, emotionW: Float, noveltyW: Float): Float =
        ((timeW + silenceW + emotionW + noveltyW) / 4f).coerceIn(0f, 1f)

    /**
     * 是否应该发送主动消息(综合评分 + 安静时段 + 每日限制)。
     *
     * v2.0 5.9: 每日上限改用 [ScoreContext.maxDailyMessages](可配置),
     * 替代旧版硬编码的每日上限。ScoreContext 默认值仍为 3,向后兼容。
     *
     * v1.0.72: [currentHour] 可注入(默认真实时钟),供单元测试避开安静时段,
     * 避免测试在 22:00-08:00 运行因时段拦截而失败。
     *
     * v2.6.2（用户反馈）: [allDayAllowed] 为 true 时跳过安静时段检查 ——
     * 用户在设置里开了「全天允许」后,评分引擎不得再因硬编码的 22:00-08:00 拦截。
     * 旧实现两套时段判断(设置页窗口 + 本硬编码窗口)互不相干,夜间活跃用户
     * 开了全天也一条不发。
     */
    fun shouldSend(
        context: ScoreContext,
        currentHour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        allDayAllowed: Boolean = false,
    ): Boolean = evaluate(context, currentHour, allDayAllowed).shouldSend

    /**
     * 带明细的评分判定 —— 与 [shouldSend] 同源,额外返回各因子与拦截原因。
     *
     * 为什么要它:旧实现只返回 Boolean,调用方只能打一句"预筛选未通过",
     * 用户反馈"一条都不发"时无法从日志复现到底是时段、日上限还是分数不够。
     * 这里把 verdict + 全部关键输入一次性摊开,调用方原样写日志即可。
     */
    fun evaluate(
        context: ScoreContext,
        currentHour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        allDayAllowed: Boolean = false,
    ): ScoreEvaluation {
        val timeW = computeTimeWeight(context)
        val silenceW = computeSilenceWeight(context)
        val emotionW = computeEmotionWeight(context)
        val noveltyW = computeNoveltyWeight(context)
        val score = combine(timeW, silenceW, emotionW, noveltyW)
        val dailyLimit = context.maxDailyMessages.coerceAtLeast(1)
        val verdict = when {
            !allDayAllowed && isQuietHour(currentHour) -> ScoreVerdict.QUIET_HOUR
            context.todaySentCount >= dailyLimit -> ScoreVerdict.DAILY_LIMIT
            score >= SEND_THRESHOLD -> ScoreVerdict.SEND
            else -> ScoreVerdict.BELOW_THRESHOLD
        }
        return ScoreEvaluation(
            verdict = verdict,
            timeWeight = timeW,
            silenceWeight = silenceW,
            emotionWeight = emotionW,
            noveltyWeight = noveltyW,
            score = score,
            threshold = SEND_THRESHOLD,
            todaySentCount = context.todaySentCount,
            dailyLimit = dailyLimit,
            currentHour = currentHour,
            allDayAllowed = allDayAllowed,
        )
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

/** 评分结论 —— 决定"没发"的真实原因。 */
enum class ScoreVerdict {
    /** 通过,可以进入 LLM 决策。 */
    SEND,

    /** 落在评分引擎硬编码的 22:00-08:00 静默时段。 */
    QUIET_HOUR,

    /** 已达每日上限。 */
    DAILY_LIMIT,

    /** 三项闸门都过,但加权评分低于阈值。 */
    BELOW_THRESHOLD,
}

/**
 * 评分明细(时间/沉默/情绪/新鲜度 + 总分 + 阈值 + 结论)。
 *
 * 供 [io.zer0.muse.schedule.ProactiveMessageRunner] 在拦截路径上原样落日志,
 * 让"为什么这条没发"可复现,而不是只剩一句"未通过"。
 */
data class ScoreEvaluation(
    val verdict: ScoreVerdict,
    val timeWeight: Float,
    val silenceWeight: Float,
    val emotionWeight: Float,
    val noveltyWeight: Float,
    val score: Float,
    val threshold: Float,
    val todaySentCount: Int,
    val dailyLimit: Int,
    val currentHour: Int,
    val allDayAllowed: Boolean,
) {
    val shouldSend: Boolean get() = verdict == ScoreVerdict.SEND

    /** 单行可读摘要,直接进日志/巡检日志。 */
    fun describe(): String = "verdict=$verdict hour=$currentHour allDay=$allDayAllowed " +
        "today=$todaySentCount/$dailyLimit time=%.3f silence=%.3f emotion=%.3f novelty=%.3f " +
        "score=%.3f threshold=%.3f".format(timeWeight, silenceWeight, emotionWeight, noveltyWeight, score, threshold)
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
    POSITIVE,
    NEGATIVE,
    NEUTRAL,
    UNKNOWN,
}

/**
 * 「免打扰时段」与存储字段「允许发送窗口」之间的唯一映射处。
 *
 * 背景（用户反馈「一条都不发」）:
 * 设置页的那个弹窗是 **免打扰** 语义（标题「免打扰时段」、提示「这段时间内不会发送主动消息」），
 * 而持久化字段 `allowedHourStart/End` 是 **允许** 语义 —— 两者互补，方向恰好相反。
 * 旧实现把这个互补关系直接写死在 UI 里（`startHour = allowedHourEnd`），
 * 每次都要在脑子里做一次取反，读代码的人极易误判为 bug（本次就是），
 * 也极易在改动时把其中一个方向改错（那才真的会导致保存后起止互换）。
 *
 * 现在把互补关系集中到这里，UI 只读不推导：滑块改的是「免打扰开始」，
 * 写回去的仍然是「免打扰开始」，换算全在此处一次性完成。
 */
object QuietHoursWindow {
    /** 免打扰时段（0-23 整点，左闭右开 [start, end)，start > end 表示跨夜）。 */
    data class QuietHours(val startHour: Int, val endHour: Int) {
        /** 对应的允许发送窗口。 */
        fun toAllowed(): AllowedWindow = QuietHoursWindow.toAllowed(this)

        /** 免打扰起止相同 = 允许窗口为空 = 永远不会发送。 */
        val isEmptyAllowed: Boolean get() = startHour == endHour

        /** 形如 22:00 – 08:00。 */
        fun label(): String = "%02d:00 – %02d:00".format(startHour, endHour)
    }

    /** 允许发送窗口（0-23 整点，左闭右开 [start, end)，start > end 表示跨夜）。 */
    data class AllowedWindow(val startHour: Int, val endHour: Int) {
        /** 对应的免打扰时段。 */
        fun toQuietHours(): QuietHours = QuietHoursWindow.fromAllowed(this)

        /** 允许窗口为空（start == end）—— 结构性的零发送。 */
        val isEmpty: Boolean get() = startHour == endHour

        /** 允许窗口里的全部整点（0-23），空窗口返回空集。 */
        fun hours(): Set<Int> = if (isEmpty) {
            emptySet()
        } else if (startHour < endHour) {
            (startHour until endHour).toSet()
        } else {
            ((startHour until 24) + (0 until endHour)).toSet()
        }
    }

    /** 允许窗口 → 免打扰时段（取补集边界）：允许结束的那一刻就是免打扰开始。 */
    fun fromAllowed(allowed: AllowedWindow): QuietHours = QuietHours(
        startHour = allowed.endHour.coerceIn(0, 23),
        endHour = allowed.startHour.coerceIn(0, 23),
    )

    /** 免打扰时段 → 允许窗口（与 [fromAllowed] 严格互逆）。 */
    fun toAllowed(quiet: QuietHours): AllowedWindow = AllowedWindow(
        startHour = quiet.endHour.coerceIn(0, 23),
        endHour = quiet.startHour.coerceIn(0, 23),
    )

    /**
     * 允许窗口是否与评分引擎硬编码的静默时段（22:00-08:00）**完全重合**。
     *
     * 完全重合时，外层时段闸放行的每一小时都会被引擎的静默时段闸再拦一次 ——
     * 两道闸相乘的结果是“永远不发”，且日志里只会看到 Quiet hours，看不到用户的
     * 窗口设置在里面扮演了什么角色。这种组合必须显式报出来。
     */
    fun fullyShadowedByEngineQuietHours(allowed: AllowedWindow, engineQuietStart: Int, engineQuietEnd: Int): Boolean {
        val allowedHours = allowed.hours()
        if (allowedHours.isEmpty()) return false
        return allowedHours.all { it >= engineQuietStart || it < engineQuietEnd }
    }
}
