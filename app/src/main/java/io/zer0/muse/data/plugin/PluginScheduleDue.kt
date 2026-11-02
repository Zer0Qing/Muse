package io.zer0.muse.data.plugin

/**
 * 判断插件的定时任务**该不该现在跑**（插件计划：schedules）。
 *
 * 纯函数，因为这条判定最容易出两种错：
 *  1. **重复触发**：没有"上次执行时间"就会在每次 tick 都跑一遍；
 *  2. **时钟回拨后疯狂重跑**：用户改时间/跨时区后，`now - lastRun` 变成负数，
 *     天真的实现会认为"过期很久"而立刻跑，甚至连续跑。
 *
 * 所以除了间隔，还有两条兜底：间隔有下限、时间倒退按"刚跑过"处理。
 */
object PluginScheduleDue {

    /** 间隔下限：再短的定时任务在手机上没有意义，只会耗电。 */
    const val MIN_INTERVAL_MINUTES = 15

    /**
     * @param lastRunMillis 上次执行时间；null 表示从未执行过（**立刻到期**）
     * @param intervalMinutes 声明里的间隔（会被夹到下限以上）
     * @param nowMillis 当前时间
     */
    fun isDue(lastRunMillis: Long?, intervalMinutes: Int, nowMillis: Long): Boolean {
        if (lastRunMillis == null) return true
        val interval = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
        val elapsed = nowMillis - lastRunMillis
        // 时间倒退（改系统时间/跨时区）：当作刚跑过，别让它立刻重跑
        if (elapsed < 0) return false
        return elapsed >= interval * 60_000L
    }

    /** 声明的间隔是否被接受（用于安装时的校验提示）。 */
    fun intervalAccepted(intervalMinutes: Int): Boolean = intervalMinutes >= MIN_INTERVAL_MINUTES
}
