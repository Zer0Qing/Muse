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
    /**
     * 上次执行时间的存储。
     *
     * 默认是内存实现（测试与"只跑一次"的场景够用）；生产注入落盘版本 ——
     * 进程被杀之后仍能算出"这个任务上次是什么时候跑的"，补跑才有依据。
     *
     * 放在 [now] 之前是刻意的：`now` 是最后一个参数，调用方可以继续用尾随 lambda 传时钟。
     */
    private val store: Store = InMemoryStore(),
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /** 上次执行时间的读写口子。 */
    interface Store {
        fun lastRunOf(key: String): Long?
        fun recordRun(key: String, atMillis: Long)
    }

    /** 内存实现：进程内有效，重启后清空。 */
    class InMemoryStore : Store {
        private val values = HashMap<String, Long>()

        @Synchronized
        override fun lastRunOf(key: String): Long? = values[key]

        @Synchronized
        override fun recordRun(key: String, atMillis: Long) {
            values[key] = atMillis
        }
    }

    /** 落盘实现：跨进程保留。 */
    class PersistentStore(private val store: PluginScheduleStore) : Store {
        override fun lastRunOf(key: String): Long? = store.load()[key]

        override fun recordRun(key: String, atMillis: Long) = store.record(key, atMillis)
    }

    /**
     * 跑一次检查：把到期的任务派发出去。返回本次派发的任务数。
     *
     * @param background 本次是否由**后台唤醒**触发（应用不在前台）。
     *        后台触发额外要求 `schedule.background` 授权 —— `schedule` 管"能不能按时间跑"，
     *        这条管"没人看着的时候能不能跑"，两者是不同强度的许可。
     */
    suspend fun tick(background: Boolean = false): Int {
        if (!settings.arePluginsGloballyEnabled()) return 0
        var dispatched = 0
        val plugins = pluginManager.list()
            .filter { it.enabled && it.kind == KIND_TOOL }
            .filter { ledger.isApproved(it.id, PluginCapabilityGateway.SCHEDULE) }
            .filter { !background || ledger.isApproved(it.id, PluginCapabilityGateway.SCHEDULE_BACKGROUND) }
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
            val last = store.lastRunOf(key)
            if (PluginScheduleDue.isDue(last, schedule.intervalMinutes, now())) {
                // 先记时间再执行：执行失败也不该在下一 tick 立刻重试
                store.recordRun(key, now())
                if (bridge.invokeScheduled(plugin.id, schedule.handler, schedule.name)) dispatched++
            }
        }
        return dispatched
    }


    private companion object {
        const val KIND_TOOL = "tool"
    }
}
