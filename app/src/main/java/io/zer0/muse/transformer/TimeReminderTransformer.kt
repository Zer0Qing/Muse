package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.memory.time.TimeContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 时间提醒 Transformer(Phase 8.1 H1)。
 *
 * 在对话历史开头插入一条 SYSTEM 消息,告知 LLM 当前日期时间,
 * 让 LLM 能回答"现在几点"/"今天星期几"等问题。
 *
 * 由 [context.extra] 控制:
 *  - "time_reminder_enabled" (Boolean, 默认 true): 是否启用
 *  - "time_reminder_timezone" (String, 可选): 时区 id(如 "Asia/Shanghai"),
 *    缺省回退到 [TimeContext.DEFAULT_TIMEZONE]
 *
 * 时间取值使用 [TimeContext.resolveTimeZone] 解析的时区(与记忆系统一致),
 * 避免直接用系统默认时区导致与记忆模块时区不一致。格式带时区偏移与名称,
 * 让 LLM 能正确判定时区。
 *
 * 独立编写。
 */
class TimeReminderTransformer : Transformer {

    override val name: String = "TimeReminder"

    override suspend fun transform(
        messages: List<UIMessage>,
        context: TransformContext,
    ): List<UIMessage> {
        val enabled = (context.extra("time_reminder_enabled") as? Boolean) ?: true
        if (!enabled) return messages

        val zone = TimeContext.resolveTimeZone(context.extra("time_reminder_timezone") as? String)
        val now = ZonedDateTime.now(zone)
        val weekday = when (now.dayOfWeek) {
            java.time.DayOfWeek.MONDAY -> "星期一"
            java.time.DayOfWeek.TUESDAY -> "星期二"
            java.time.DayOfWeek.WEDNESDAY -> "星期三"
            java.time.DayOfWeek.THURSDAY -> "星期四"
            java.time.DayOfWeek.FRIDAY -> "星期五"
            java.time.DayOfWeek.SATURDAY -> "星期六"
            java.time.DayOfWeek.SUNDAY -> "星期日"
            // DayOfWeek 仅 7 个枚举值,此分支不可达;保留 else 仅为 Java 枚举 when 兼容,
            // 真正命中即说明运行时异常,直接抛出而非静默返回空串。
            else -> throw IllegalStateException("不可达: 未知 DayOfWeek ${now.dayOfWeek}")
        }
        val timeStr = now.format(TIME_FORMATTER)
        val timeMsg = UIMessage(
            role = MessageRole.SYSTEM,
            content = "当前时间: $timeStr $weekday (时区: ${zone.id})",
        )
        return listOf(timeMsg) + messages
    }

    companion object {
        // 含时区偏移(如 +08:00),让 LLM 能判定时区;formatter 提到 companion 避免每次调用新建
        private val TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")
    }
}
