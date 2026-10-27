package io.zer0.muse.data.proactive

import kotlin.math.max

/**
 * 主动消息的时机判定引擎。
 *
 * 这个对象是整套机制里唯一「做判断」的地方，其余都是搬运。
 * 所以它被写成**纯函数**：不读配置、不碰数据库、不看系统时钟，
 * 所有输入靠参数进来，所有结论靠返回值出去。
 *
 * 判定只回答一个问题：**现在能不能说**。不回答「该不该说」。
 *
 * 「能不能」是可解释的：免打扰时段、正在生成、刚聊过、冷却、额度。
 * 「该不该」只能靠打分，而分数切在阈值下面就会输出一句话来解释为什么不说，
 * 那种解释对用户毫无意义 —— 他不知道你在算什么，只觉得被机器掂量了一遍。
 *
 * 判定顺序：
 *
 *  Q1 [Verdict.QUIET_HOUR] / [Verdict.USER_ACTIVE] / [Verdict.IN_CONVERSATION] / [Verdict.COOLDOWN]
 *      现在是不是打扰的好时机？—— 纯本地，零成本。
 *  Q2 [Verdict.NO_BUDGET]
 *      额度还有没有？—— 没有就是没有，明天再来。
 *
 * 内容层面的判断全部交给生成那一步：把最近对话给模型，由它决定有没有话头。
 * 模型说不出来就说 skip，这比用一堆权重去猜可靠得多。
 *
 * 判定写成一串 `?:` 与一个 `when` 表达式（单出口），
 * 不是风格洁癖：四问的先后顺序是这套机制的语义核心，
 * 散成多个 return 之后，改一处判断位置很容易看不到下游依赖它。
 */
object ProactiveTimingEngine {

    /** 用户最近 10 分钟内说过话，就不算「主动」，那是打断对话。 */
    const val USER_ACTIVE_WINDOW_MS = 10 * 60 * 1000L

    /**
     * 两条主动消息之间的最小间隔。
     *
     * 这个约束原来藏在评分函数里（刚发过就压低分数，压到阈值下就不发），
     * 评分删掉后必须独立成一条硬判定 —— 否则「接话」接得太勤，
     * 用户会觉得自己被一个不停找话题的机器缠住。
     */
    const val MIN_INTERVAL_MS = 3 * 60 * 60 * 1000L

    /**
     * 一次判定的全部输入。
     *
     * 刻意做成扁平的数据类而不是一堆参数：调用方必须显式准备每一件事，
     * 漏掉一个就编译不过，不会出现「忘了传用户活跃时间导致深夜打扰」。
     */
    data class Input(
        val now: Long,
        val relation: ProactiveRelationEntity,
        val allowedHourStart: Int,
        val allowedHourEnd: Int,
        val allDayAllowed: Boolean,
        /** 用户最后一次说话的时间戳，0 表示未知。 */
        val lastUserMessageAt: Long,
        /** 是否有正在进行的回复生成。 */
        val isGenerating: Boolean,
        /** 上一条主动消息的发出时间，0 表示从没有过。 */
        val lastProactiveSentAt: Long,
    )

    /** 判定结论。 */
    enum class Verdict {
        /** 判定全过，可以发。 */
        OK,

        /** 在免打扰时段内。 */
        QUIET_HOUR,

        /** 用户正在活跃（刚说过话），这时候「主动」等于打断。 */
        USER_ACTIVE,

        /** 正在生成回复，等它生成完再说。 */
        IN_CONVERSATION,

        /** 用户说过「最近别烦我」。 */
        COOLDOWN,

        /** 距上一条主动消息太近。 */
        TOO_SOON,

        /** 今天额度用完了。 */
        NO_BUDGET,
    }

    /** 判定结果。带出足够写日志的细节，便于排查「为什么没发」。 */
    data class Decision(
        val verdict: Verdict,
        val remainingBudget: Float,
        val hourOfDay: Int,
    ) {
        val shouldSend: Boolean get() = verdict == Verdict.OK
    }

    /**
     * 跑完四个问题，返回结论。
     *
     * 顺序不能调换。比如预算检查必须在 fitness 之后 ——
     * 否则预算为 0 时日志会写「没有可说的」，而真相是「有话但今天额度用完了」，
     * 这两种情况的排查方向完全相反。
     */
    fun evaluate(input: Input): Decision {
        val hour = hourOfDay(input.now)
        val remaining = remainingBudget(input.relation)

        // Q1：时机
        val timingBlock = checkTiming(input, hour)

        // Q2：额度。放在时机之后，是因为「现在不能打扰」和「没额度」的排查方向不同。
        val verdict = timingBlock ?: if (remaining < 1f) Verdict.NO_BUDGET else Verdict.OK

        return Decision(verdict = verdict, remainingBudget = remaining, hourOfDay = hour)
    }

    /** Q1 的四道闸。返回非空表示被拦下。 */
    private fun checkTiming(input: Input, hour: Int): Verdict? = when {
        !input.allDayAllowed &&
            isInQuietHours(hour, input.allowedHourStart, input.allowedHourEnd) -> Verdict.QUIET_HOUR

        input.relation.cooldownUntil > input.now -> Verdict.COOLDOWN

        input.lastProactiveSentAt > 0 &&
            input.now - input.lastProactiveSentAt < MIN_INTERVAL_MS -> Verdict.TOO_SOON

        input.isGenerating -> Verdict.IN_CONVERSATION

        input.lastUserMessageAt > 0 &&
            input.now - input.lastUserMessageAt < USER_ACTIVE_WINDOW_MS -> Verdict.USER_ACTIVE

        else -> null
    }

    // ══════════════════════════════════════════════════════════════
    // 预算
    // ══════════════════════════════════════════════════════════════

    /**
     * 当前剩余额度。
     *
     * ```
     * base = max(等级地板, 1 + intimacy × 4)
     * budget = clamp(base + 今日回补 − 今日消耗, 0, 等级天花板)
     * ```
     *
     * 地板和天花板来自用户选的等级（即时意志），中间那段来自 intimacy（长期学习）。
     * 用户选了「黏人」但关系还没攒够，就停在地板上；关系攒够了自然往上，
     * 但永远撞不破天花板。
     */
    fun remainingBudget(relation: ProactiveRelationEntity): Float {
        val level = relation.intensityEnum()
        val learned = 1f + relation.intimacy.coerceIn(0f, 1f) * 4f
        val base = max(level.floor.toFloat(), learned)
        val raw = base + relation.earnedToday - relation.spentToday
        return raw.coerceIn(0f, level.cap.toFloat())
    }

    /**
     * 跨日重置判定。
     *
     * true 表示调用方应当先把预算计数清零再继续。
     * 放在这里而不是 DAO 里，是因为「跨日」是业务语义，不是存储语义。
     */
    fun needsBudgetReset(relation: ProactiveRelationEntity, todayDate: String): Boolean =
        relation.budgetDate != todayDate

    // ══════════════════════════════════════════════════════════════
    // 工具
    // ══════════════════════════════════════════════════════════════

    /** 牵挂是否已过期。EVERGREEN 永不过期。 */
    fun isExpired(thought: PendingThoughtEntity, now: Long): Boolean {
        val expiresAt = thought.expiresAt ?: return false
        return now > expiresAt
    }

    /** 时间戳对应的小时（0-23）。 */
    fun hourOfDay(timestamp: Long): Int {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(java.util.Calendar.HOUR_OF_DAY)
    }

    /**
     * 是否在免打扰时段。
     *
     * 复用 [QuietHoursPolicy] 而不是自己再写一份：免打扰的边界语义
     * （左闭右开、跨夜）已经有单一实现，再造一份迟早漂移，
     * 而这种漂移的表现是「某些时段偶尔多发一条」，极难复现。
     */
    fun isInQuietHours(hour: Int, start: Int, end: Int, allDayAllowed: Boolean = false): Boolean =
        !QuietHoursPolicy.isWithinAllowedWindow(hour, start, end, allDayAllowed)

    /** 把判定结论翻译成能贴进设置页的一句话。 */
    fun describe(decision: Decision): String = when (decision.verdict) {
        Verdict.QUIET_HOUR -> "现在是免打扰时段（${decision.hourOfDay}点）"
        Verdict.USER_ACTIVE -> "刚聊过，等你安静一会儿"
        Verdict.IN_CONVERSATION -> "正在回复，不打断"
        Verdict.COOLDOWN -> "你让我最近别打扰"
        Verdict.TOO_SOON -> "刚说过话，过一会儿再说"
        Verdict.NO_BUDGET -> "今天说得够多了，明天再说"
        Verdict.OK -> "可以发"
    }
}
