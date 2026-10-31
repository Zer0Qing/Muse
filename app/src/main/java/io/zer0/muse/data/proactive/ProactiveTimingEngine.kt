package io.zer0.muse.data.proactive

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
     * 两条主动消息之间的默认间隔（分钟）。
     *
     * 与 [io.zer0.muse.data.ProactiveMessageConfig.intervalMinutes] 的默认值一致：
     * 老配置里这个字段一直是 240，界面也一直这么写，判定必须按同一个数走。
     */
    const val DEFAULT_INTERVAL_MINUTES = 240

    /** 间隔下限（分钟）。再短就不是「主动」而是刷屏了。 */
    const val MIN_INTERVAL_MINUTES = 30

    /** 间隔上限（分钟，24 小时）。再长就变成「一年说几次话」。 */
    const val MAX_INTERVAL_MINUTES = 1440

    private const val MINUTE_MS = 60_000L

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
        /**
         * 上一轮巡检进入生成的时间，0 表示进程内还没有过。
         *
         * 与 [lastProactiveSentAt] 是两件事，不能互相替代：结果账要等消息真正落库才写，
         * 而生成那一步最长要几十秒 —— 这段时间里结果账上什么都没有，
         * 只看结果账的话，**正在生成的那一轮在数据层等于不存在**，
         * 于是又一轮巡检会认为「距上一条已经够久了」而放行。
         * 这个时间在调模型之前就登记，正好补上那段空窗。
         */
        val lastProactiveAttemptAt: Long = 0L,
        /**
         * 节奏参数：间隔、当日抖动与参与抖动的助手 id。
         *
         * 这三个是一组：改间隔必然要知道抖动上限和「是谁在发」，
         * 拆成三个平铺字段会让 [Input] 的构造参数超过可读范围，
         * 也会让调用方以为它们彼此无关。
         */
        val pacing: Pacing = Pacing(),
    )

    /**
     * 一组节奏参数。
     *
     * 基础间隔来自用户设置（「主动程度」档位或自定义滑块），
     * 抖动上限同样来自设置，两者叠加后的间隔才是真正的硬闸（见 [effectiveIntervalMinutes]）。
     */
    data class Pacing(
        /** 基础间隔（分钟）。判定时夹到 [MIN_INTERVAL_MINUTES]..[MAX_INTERVAL_MINUTES]。 */
        val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
        /** 当日偏移上限（分钟）。0 表示不抖动。 */
        val randomOffsetMinutes: Int = 0,
        /** 助手 id，参与「日期 + 助手」哈希，让不同助手的抖动落在不同分钟。 */
        val assistantId: String = "",
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
        /** 本次判定实际使用的间隔（含当日偏移），排查「为什么还没到点」时先看它。 */
        val effectiveIntervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
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
        val intervalMinutes = effectiveIntervalMinutes(input)

        // Q1：时机
        val timingBlock = checkTiming(input, hour, intervalMinutes * MINUTE_MS)

        // Q2：额度。放在时机之后，是因为「现在不能打扰」和「没额度」的排查方向不同。
        val verdict = timingBlock ?: if (remaining < 1f) Verdict.NO_BUDGET else Verdict.OK

        return Decision(
            verdict = verdict,
            remainingBudget = remaining,
            hourOfDay = hour,
            effectiveIntervalMinutes = intervalMinutes,
        )
    }

    /** Q1 的硬闸。返回非空表示被拦下。 */
    private fun checkTiming(input: Input, hour: Int, intervalMs: Long): Verdict? = when {
        !input.allDayAllowed &&
            isInQuietHours(hour, input.allowedHourStart, input.allowedHourEnd) -> Verdict.QUIET_HOUR

        input.relation.cooldownUntil > input.now -> Verdict.COOLDOWN

        tooSoon(input.now, input.lastProactiveSentAt, intervalMs) -> Verdict.TOO_SOON

        // 上一轮开跑得还不够久，也按「太近」处理。
        // 这一条才是生成窗口内的护栏：结果账还是空的，但尝试已经登记过了。
        tooSoon(input.now, input.lastProactiveAttemptAt, intervalMs) -> Verdict.TOO_SOON

        input.isGenerating -> Verdict.IN_CONVERSATION

        input.lastUserMessageAt > 0 &&
            input.now - input.lastUserMessageAt < USER_ACTIVE_WINDOW_MS -> Verdict.USER_ACTIVE

        else -> null
    }

    /** 距 [timestamp] 不足 [intervalMs] 就算太近；0 表示从未发生，不参与判定。 */
    private fun tooSoon(now: Long, timestamp: Long, intervalMs: Long): Boolean =
        timestamp > 0 && now - timestamp < intervalMs

    /**
     * 本次判定真正使用的间隔：用户配的基础间隔 + 当日偏移。
     *
     * 基础间隔夹到 [MIN_INTERVAL_MINUTES]..[MAX_INTERVAL_MINUTES]：
     * 设置页滑块的合法范围更宽，老配置里也可能存着 15 分钟或几天，
     * 这些值直接拿来判定会让助手变成闹钟或者哑巴。
     */
    fun effectiveIntervalMinutes(input: Input): Int {
        val base = input.pacing.intervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
        val offset = dailyOffsetMinutes(
            dayKey(input.now),
            input.pacing.assistantId,
            input.pacing.randomOffsetMinutes,
        )
        return base + offset
    }

    /**
     * 当天的抖动偏移（分钟）。
     *
     * 同一「日期 + 助手 id」得到同一个偏移，跨天才会变 —— 不用每次随机，
     * 是因为每次随机等于把间隔变成一个区间，用户调好的「4 小时」会忽早忽晚；
     * 按天固定的偏移既保留了「不是整点报时」的自然感，又让同一天内的节奏可预期。
     * 哈希只依赖字符串本身，不依赖随机源与时区以外的任何状态。
     */
    fun dailyOffsetMinutes(dateKey: String, assistantId: String, maxOffsetMinutes: Int): Int {
        val max = maxOffsetMinutes.coerceIn(0, MAX_INTERVAL_MINUTES)
        if (max == 0) return 0
        return "$dateKey|$assistantId".hashCode().mod(max + 1)
    }

    /** 时间戳所在日期（本地时区，`yyyy-MM-dd`）。跨天换偏移靠的就是它。 */
    fun dayKey(timestamp: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
        return buildString {
            append(cal.get(java.util.Calendar.YEAR))
            append('-')
            if (month < 10) append('0')
            append(month)
            append('-')
            if (day < 10) append('0')
            append(day)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 预算
    // ══════════════════════════════════════════════════════════════

    /**
     * 当前剩余额度。
     *
     * ```
     * 每日额度 = 等级上限（黏人档为不限）
     * budget   = max(0, 每日额度 − 今日已发)
     * ```
     *
     * 额度完全由用户选的档位决定：安静 3 / 克制 8 / 标准 15 / 主动 30 / 黏人不限。
     * 早期版本用「地板 + 亲密度学习值」拼额度，用户选了黏人每天也只能发 5 条，
     * 与界面承诺不符，已废弃。亲密度仍参与其它时机判定，但不再决定条数。
     */
    fun remainingBudget(relation: ProactiveRelationEntity): Float {
        val allowance = dailyAllowance(relation)
        return (allowance - relation.spentToday).coerceIn(0f, allowance)
    }

    /** 当天额度总量（黏人档为不限）。 */
    fun dailyAllowance(relation: ProactiveRelationEntity): Float =
        relation.intensityEnum().let { if (it.unlimited) Float.MAX_VALUE else it.cap.toFloat() }

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
