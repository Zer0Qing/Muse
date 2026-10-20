package io.zer0.muse.data.proactive

/**
 * 主动消息的「免打扰时段」判定（P1-2）。
 *
 * 为什么单独抽出来：
 * 主对话的 [io.zer0.muse.schedule.ProactiveMessageRunner] 里已有这套窗口逻辑
 * （allowedHourStart / allowedHourEnd / allDayAllowed，含跨夜与 24 小时两种边界），
 * 但它是内联在巡检流程里的私有代码。小手机的 AI 主动私信同样需要遵守同一套
 * 免打扰设置——再造一份不仅会漂移，还会让用户遇到"主对话安静、小手机半夜发私信"。
 *
 * 这里只做纯函数判定，不读配置、不发通知，调用方各自传入配置值。
 * 边界语义与 ProactiveMessageRunner 保持一致：
 *  - start <= end：普通时段，左闭右开 [start, end)
 *  - start > end：跨夜时段，[start, 24) ∪ [0, end)
 *  - allDayAllowed：直接放行（该开关的存在正是因为
 *    allowedHourStart/End 表达不出完整 24 小时）
 */
object QuietHoursPolicy {

    /**
     * 当前小时是否在允许发送窗口内。
     *
     * @param currentHour 0-23
     * @param allowedHourStart 允许起始小时（含）
     * @param allowedHourEnd 允许结束小时（不含）
     * @param allDayAllowed 全天允许
     */
    fun isWithinAllowedWindow(currentHour: Int, allowedHourStart: Int, allowedHourEnd: Int, allDayAllowed: Boolean): Boolean {
        if (allDayAllowed) return true
        val hour = currentHour.coerceIn(0, 23)
        return if (allowedHourStart <= allowedHourEnd) {
            hour in allowedHourStart until allowedHourEnd
        } else {
            hour >= allowedHourStart || hour < allowedHourEnd
        }
    }
}
