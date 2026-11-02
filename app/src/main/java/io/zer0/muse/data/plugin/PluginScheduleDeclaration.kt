package io.zer0.muse.data.plugin

import kotlinx.serialization.Serializable

/**
 * 插件定时任务声明（插件计划：schedules）。
 *
 * ```json
 * "schedules": [
 *   { "name": "daily-digest", "handler": "onDailyDigest", "intervalMinutes": 720 }
 * ]
 * ```
 *
 * 为什么用"间隔"而不是 cron：cron 需要精确到分钟且隐含"到点一定跑"的承诺，
 * 而插件代码跑在应用进程里 —— 应用没活着就什么都跑不了。用间隔表达"至少隔多久跑一次"，
 * 与实际能力一致，不会给作者一个做不到的预期。
 *
 * @param name 任务名（同一插件内唯一，用于记录上次执行时间）
 * @param handler 插件入口里要调用的函数名
 * @param intervalMinutes 间隔（分钟），有下限见 [PluginScheduleDue.MIN_INTERVAL_MINUTES]
 */
@Serializable
data class PluginScheduleDeclaration(
    val name: String,
    val handler: String,
    val intervalMinutes: Int = 60,
)
