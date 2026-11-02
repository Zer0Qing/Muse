package io.zer0.muse.data.plugin

/**
 * 插件定时任务的**执行器**（插件计划：schedules）。
 *
 * 与 cron 的差别必须说清楚：这里只在**应用进程存活**时按间隔触发。
 * 应用被冻结或杀死后不会补跑 —— 与其假装支持后台唤醒，不如把边界写在脸上：
 * 作者会因此把任务设计成"晚一点跑也没关系"的那种，而不是"必须准点"。
 *
 * 三条约束：
 *  1. **按插件授权**：需要 `schedule` 能力（默认关闭）—— 无人值守地运行插件代码，
 *     属于要用户点头才做的事；
 *  2. **同一任务不重叠**：上次时间在执行**前**就记下，失败也不会立刻重试；
 *  3. **时钟回拨不重跑**：见 [PluginScheduleDue]。
 */
class PluginScheduleRunner(
    private val pluginManager: PluginManager,
    private val bridge: PluginActivationBridge,
    private val ledger: PluginCapabilityGateway,
    private val settings: io.zer0.muse.data.SettingsRepository,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /** 每个 (插件, 任务) 上次执行时间；进程级，重启后从头算。 */
    private val lastRuns = HashMap<String, Long>()

    /** 跑一次检查：把到期的任务派发出去。返回本次派发的任务数。 */
    suspend fun tick(): Int {
        if (!settings.arePluginsGloballyEnabled()) return 0
        var dispatched = 0
        val plugins = pluginManager.list()
            .filter { it.enabled && it.kind == KIND_TOOL }
            .filter { ledger.isApproved(it.id, PluginCapabilityGateway.SCHEDULE) }
        plugins.forEach { plugin ->
            dispatched += runPluginSchedules(plugin)
        }
        return dispatched
    }

    private suspend fun runPluginSchedules(plugin: PluginManager.InstalledPlugin): Int {
        val verified = runCatching { pluginManager.loadVerifiedPlugin(plugin.id) }.getOrNull() ?: return 0
        var dispatched = 0
        verified.manifest.schedules.forEach { schedule ->
            val key = "${plugin.id}#${schedule.name}"
            val last = synchronized(lastRuns) { lastRuns[key] }
            if (PluginScheduleDue.isDue(last, schedule.intervalMinutes, now())) {
                // 先记时间再执行：执行失败也不该在下一 tick 立刻重试
                synchronized(lastRuns) { lastRuns[key] = now() }
                if (bridge.invokeScheduled(plugin.id, schedule.handler, schedule.name)) dispatched++
            }
        }
        return dispatched
    }

    /** 仅测试用：清空上次执行时间。 */
    internal fun resetForTests() {
        synchronized(lastRuns) { lastRuns.clear() }
    }

    private companion object {
        const val KIND_TOOL = "tool"
    }
}
