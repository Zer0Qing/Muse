package io.zer0.muse.data.proactive

/**
 * 允许时段与免打扰时段之间的映射与校验。
 *
 * 两套表达是同一件事的两种说法：用户填的是「允许哪几个小时打扰」，
 * 判定用的是「哪几个小时别打扰」。这两者必须严格互逆，
 * 而且用户可能填出一个整段落在静默时段里的窗口 —— 那等于永远发不出去，
 * 需要在保存时就能诊断出来，而不是等几天后发现一条消息都没发。
 */
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
