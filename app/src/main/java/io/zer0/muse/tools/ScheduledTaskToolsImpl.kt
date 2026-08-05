package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.data.schedule.ScheduledTaskEntity
import io.zer0.muse.data.session.MuseDb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * P1-3b 拆域：定时任务工具实现（从 ToolRegistry.kt 原样迁移）。
 * 由 ScheduledTaskToolsRegistrar 注册到 ToolRegistry。
 */
class ScheduledTaskToolsImpl(private val context: Context) {
    private val FMT_DATETIME_MIN = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    // ── v1.0.17: 定时任务工具 ──────────────────────────────────────────

    /**
     * 计算下次执行时间(简化版,按 ScheduledTaskRunner.computeNextRun)。
     * - once: 立即(返回 now,由下一轮轮询执行)
     * - hourly/daily/weekly: now + 固定间隔
     * - cron: CronExpression 解析;空串或解析失败返回 0
     * - 未知: 默认按 daily
     */
    fun computeNextRun(interval: String, cronExpr: String, now: Long): Long {
        return when (interval) {
            "once" -> now
            "hourly" -> now + 3_600_000L
            "daily" -> now + 86_400_000L
            "weekly" -> now + 604_800_000L
            "cron" -> {
                if (cronExpr.isBlank()) return 0
                try {
                    io.zer0.muse.schedule.CronExpression.parse(cronExpr).nextRunAfter(now)
                } catch (e: Exception) {
                    Logger.w("ToolRegistry", "Invalid cron expr '$cronExpr': ${e.message}")
                    0
                }
            }
            else -> now + 86_400_000L
        }
    }

    /** 格式化时间戳为可读字符串(<=0 视为已禁用)。 */
    fun formatTimestamp(ts: Long): String {
        return if (ts > 0) FMT_DATETIME_MIN.get()?.format(Date(ts)) ?: "未知" else "已禁用"
    }

    /** 格式化定时任务为单条摘要。 */
    fun formatScheduledTask(t: ScheduledTaskEntity): String {
        val intervalDesc = when (t.interval) {
            "once" -> "单次"
            "hourly" -> "每小时"
            "daily" -> "每天"
            "weekly" -> "每周"
            "cron" -> "Cron: ${t.cronExpr}"
            else -> t.interval
        }
        return "• ${t.name}[id=${t.id}]\n  间隔: $intervalDesc | 下次: ${formatTimestamp(t.nextRunAt)} | ${if (t.enabled) "启用" else "禁用"}"
    }

    /** 创建定时任务。 */
    suspend fun execScheduledTaskCreate(args: Map<String, String>): String {
        val name = args["name"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: name"
        val prompt = args["prompt"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: prompt"
        val interval = args["interval"]?.takeIf { it.isNotBlank() } ?: "daily"
        val cronExpr = args["cron_expr"] ?: ""
        val assistantId = args["assistant_id"]?.takeIf { it.isNotBlank() } ?: "default"
        val actionType = args["action_type"]?.takeIf { it.isNotBlank() } ?: "ai_prompt"
        val conditionType = args["condition_type"]?.takeIf { it.isNotBlank() } ?: "always"

        if (interval == "cron" && cronExpr.isBlank()) {
            return "interval=cron 时必须提供 cron_expr"
        }

        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val task = ScheduledTaskEntity(
            id = id,
            name = name,
            prompt = prompt,
            assistantId = assistantId,
            interval = interval,
            cronExpr = cronExpr,
            enabled = true,
            nextRunAt = computeNextRun(interval, cronExpr, now),
            createdAt = now,
            updatedAt = now,
            actionType = actionType,
            // always 用空串保持与旧版兼容,其他类型写入最小 JSON 供 Runner 解析
            conditionJson = if (conditionType == "always") "" else """{"type":"$conditionType"}""",
            createdBy = "assistant",
        )
        MuseDb.get(context).scheduledTaskDao().upsert(task)
        return "已创建定时任务: ${task.name}[id=$id] (间隔=$interval, 下次执行=${formatTimestamp(task.nextRunAt)})"
    }

    /** 列出定时任务。 */
    suspend fun execScheduledTaskList(args: Map<String, String>): String {
        val enabledOnly = args["enabled"]?.let { it.equals("true", ignoreCase = true) }
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 20
        val all = MuseDb.get(context).scheduledTaskDao().getAll()
        val filtered = when (enabledOnly) {
            true -> all.filter { it.enabled }
            false -> all.filter { !it.enabled }
            null -> all
        }.take(limit)
        if (filtered.isEmpty()) return "暂无定时任务"
        val sb = StringBuilder("定时任务 (${filtered.size}):\n")
        filtered.forEach { sb.appendLine(formatScheduledTask(it)) }
        return sb.toString().trimEnd()
    }

    /** 更新定时任务。仅传需要修改的字段,未传字段保留原值。 */
    suspend fun execScheduledTaskUpdate(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: id"
        val dao = MuseDb.get(context).scheduledTaskDao()
        val existing = dao.getById(id) ?: return "未找到任务: $id"
        val name = args["name"] ?: existing.name
        val prompt = args["prompt"] ?: existing.prompt
        val interval = args["interval"] ?: existing.interval
        val cronExpr = args["cron_expr"] ?: existing.cronExpr
        val enabled = args["enabled"]?.let { it.equals("true", ignoreCase = true) } ?: existing.enabled

        if (interval == "cron" && cronExpr.isBlank()) {
            return "interval=cron 时必须提供 cron_expr"
        }

        val now = System.currentTimeMillis()
        // 间隔或 cron 表达式变化时重新计算下次执行时间;仅切换 enabled 不重算
        val nextRunAt = if (interval != existing.interval || cronExpr != existing.cronExpr) {
            computeNextRun(interval, cronExpr, now)
        } else {
            existing.nextRunAt
        }
        val updated = existing.copy(
            name = name,
            prompt = prompt,
            interval = interval,
            cronExpr = cronExpr,
            enabled = enabled,
            nextRunAt = nextRunAt,
            updatedAt = now,
        )
        dao.upsert(updated)
        return "已更新定时任务: ${updated.name}[id=$id]"
    }

    /** 删除定时任务。 */
    suspend fun execScheduledTaskDelete(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: id"
        val dao = MuseDb.get(context).scheduledTaskDao()
        val existing = dao.getById(id) ?: return "未找到任务: $id"
        dao.delete(id)
        return "已删除定时任务: ${existing.name}[id=$id]"
    }

    /** 立即执行一次定时任务(把 next_run_at 设为现在,下一轮轮询执行)。 */
    suspend fun execScheduledTaskExecute(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: id"
        val dao = MuseDb.get(context).scheduledTaskDao()
        val existing = dao.getById(id) ?: return "未找到任务: $id"
        if (!existing.enabled) return "任务已禁用,请先启用后再执行: $id"
        // 触发下一轮轮询执行(最多 60 秒内由 ScheduledTaskRunner 拾取)
        dao.triggerNextTasks(listOf(id))
        return "已触发任务执行: ${existing.name}[id=$id],将在下一轮轮询中执行"
    }

    /** 查询定时任务执行历史。 */
    suspend fun execScheduledTaskGetHistory(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return "缺少必填参数: id"
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 10
        val dao = MuseDb.get(context).scheduledTaskDao()
        val existing = dao.getById(id) ?: return "未找到任务: $id"
        val records = MuseDb.get(context).scheduledTaskExecutionDao().queryByTaskId(id)
        if (records.isEmpty()) return "任务 ${existing.name}[id=$id] 暂无执行历史"
        val sb = StringBuilder("执行历史 (任务=${existing.name}):\n")
        records.take(limit).forEach { e ->
            val time = FMT_DATETIME_MIN.get()?.format(Date(e.executedAt)) ?: "未知"
            val detail = when (e.status) {
                "success" -> "成功: ${e.replySummary.take(80)}"
                "failed" -> "失败: ${e.errorMessage}"
                "skipped" -> "跳过(条件不满足)"
                else -> e.status
            }
            sb.appendLine("• [$time] $detail")
        }
        return sb.toString().trimEnd()
    }

}
