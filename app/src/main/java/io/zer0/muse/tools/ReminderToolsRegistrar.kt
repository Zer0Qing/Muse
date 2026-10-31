package io.zer0.muse.tools

import android.content.Context
import io.zer0.muse.R
import io.zer0.muse.tools.reminder.ReminderAlarmScheduler
import io.zer0.muse.tools.reminder.ReminderAlarmReceiver
import io.zer0.muse.tools.reminder.ReminderEntry
import io.zer0.muse.tools.reminder.ReminderStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P1-3b 拆域：定时提醒工具注册器。
 *
 * 从 ToolRegistry.kt 抽出的 schedule_reminder / cancel_reminder / list_reminders，
 * 持有 ReminderStore 与 AlarmManager 调度逻辑。
 */
class ReminderToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
    private val reminderStore: ReminderStore = ReminderStore(context),
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "schedule_reminder",
                // v1.0.75 fix (工具审查 02): 与 set_alarm/scheduled_task_create 区分 + 返回说明
                description = "创建本地定时提醒,到点弹出通知。用户说'提醒我 X 时间做 Y'时调用。" +
                    "区别于: set_alarm=系统闹钟(响铃), scheduled_task_create=周期 AI 任务(自动执行 prompt)。" +
                    "time 支持 '2026-08-16 15:30' 或 ISO 8601,必须是未来时间。返回提醒 id,后续可用 cancel_reminder 取消。",
                parameters = mapOf(
                    "title" to "必填,提醒标题",
                    "message" to "必填,提醒正文",
                    // B-08: 示例必须是实现可解析的格式 — "2026-07-21T15:30:00"(无时区偏移)
                    // 过不了 Instant.parse;本地时间格式或带 Z/偏移的 ISO 才可解析。
                    "time" to "必填,触发时间,如 2026-07-21 15:30 或 2026-07-21T15:30:00Z,必须是未来时间",
                ),
                required = setOf("title", "message", "time"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            val title = args["title"]?.takeIf { it.isNotBlank() }
                ?: return@register context.getString(R.string.tool_reminder_missing_title)
            val message = args["message"]?.takeIf { it.isNotBlank() }
                ?: return@register context.getString(R.string.tool_reminder_missing_message)
            val timeStr = args["time"]?.takeIf { it.isNotBlank() }
                ?: return@register context.getString(R.string.tool_reminder_missing_time)
            val triggerAt = parseReminderTime(timeStr)
                ?: return@register context.getString(R.string.tool_reminder_invalid_time, timeStr)
            if (triggerAt <= System.currentTimeMillis()) {
                return@register context.getString(R.string.tool_reminder_past_time)
            }
            val id = reminderStore.add(
                title = title,
                message = message,
                triggerAtMillis = triggerAt,
                targetType = ReminderAlarmReceiver.TARGET_HOME,
                targetId = "",
            )
            val result = ReminderAlarmScheduler.schedule(
                context = context,
                id = id,
                title = title,
                message = message,
                triggerAtMillis = triggerAt,
                targetType = ReminderAlarmReceiver.TARGET_HOME,
                targetId = "",
            )
            val whenText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(triggerAt))
            if (!result.scheduled) {
                // 排期彻底失败：保留条目（而非悄悄删除），并记录原因，让 list_reminders 能如实标注。
                reminderStore.markScheduleFailed(id, result.error ?: "alarm_schedule_failed")
                return@register context.getString(R.string.tool_reminder_schedule_failed)
            }
            if (result.needsExactPermission) {
                // 已降级为非精确闹钟：先保证「会响」，再引导用户开启「闹钟和提醒」权限以获得准时。
                reminderStore.markScheduled(id, exact = false, atMillis = System.currentTimeMillis())
                ReminderAlarmScheduler.requestExactAlarmPermission(context)
                return@register context.getString(R.string.tool_reminder_scheduled_inexact, id, whenText)
            }
            reminderStore.markScheduled(id, exact = true, atMillis = System.currentTimeMillis())
            context.getString(R.string.tool_reminder_scheduled, id, whenText)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "cancel_reminder",
                description = "取消一个已创建的定时提醒。",
                parameters = mapOf("id" to "必填,提醒 id(由 schedule_reminder 返回)"),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            val id = args["id"]?.takeIf { it.isNotBlank() }
                ?: return@register context.getString(R.string.tool_reminder_missing_id)
            ReminderAlarmScheduler.cancel(context, id)
            if (reminderStore.remove(id)) {
                context.getString(R.string.tool_reminder_cancelled, id)
            } else {
                context.getString(R.string.tool_reminder_not_found, id)
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "list_reminders",
                description = "列出当前所有未触发的定时提醒。",
                parameters = mapOf("limit" to "可选,最多返回数量,默认 20"),
                required = emptySet(),
                category = "built-in",
                parameterTypes = mapOf("limit" to "integer"),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            // 提醒列表文件损坏时，先把「列表坏了、已备份」这件事转述给用户，而不是假装列表为空。
            reminderStore.consumeError()?.let { return@register it }
            val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 20
            val list = reminderStore.list().sortedBy { it.triggerAtMillis }.take(limit)
            if (list.isEmpty()) return@register context.getString(R.string.tool_reminder_list_empty)
            val sb = StringBuilder(context.getString(R.string.tool_reminder_list_header, list.size))
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            list.forEach {
                sb.appendLine(
                    context.getString(
                        R.string.tool_reminder_list_item,
                        it.id,
                        sdf.format(Date(it.triggerAtMillis)),
                        it.title,
                        statusText(it),
                    ),
                )
            }
            sb.toString().trimEnd()
        }
    }

    /**
     * 一条提醒的可读状态：让用户分得清「排上了会准时」「只能不准时地响」「可能根本不会响」。
     */
    private fun statusText(entry: ReminderEntry): String = when {
        entry.scheduleError != null -> context.getString(R.string.tool_reminder_status_unscheduled)
        entry.alarmLive && entry.scheduledExact -> context.getString(R.string.tool_reminder_status_scheduled)
        entry.alarmLive -> context.getString(R.string.tool_reminder_status_inexact)
        else -> context.getString(R.string.tool_reminder_status_lost)
    }

    private fun parseReminderTime(input: String): Long? {
        val trimmed = input.trim()
        val patterns = listOf(
            "yyyy-MM-dd HH:mm" to SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()),
            "yyyy-MM-dd HH:mm:ss" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()),
            "yyyy/MM/dd HH:mm" to SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()),
        )
        patterns.forEach { (_, sdf) ->
            try {
                return sdf.parse(trimmed)?.time
            } catch (_: Exception) { /* ignore */ }
        }
        return try {
            java.time.Instant.parse(trimmed).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}
