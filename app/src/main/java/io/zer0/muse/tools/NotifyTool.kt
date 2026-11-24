package io.zer0.muse.tools

import io.zer0.muse.notification.MuseNotificationManager

/**
 * notify 工具。
 *
 * Lets the AI proactively send notifications to the user.
 * Only used when the user explicitly asks for reminders/notifications,
 * or when a scheduled task discovers something requiring attention.
 */
object NotifyTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "notify",
        description = "立即向用户发送一条系统通知（当前立刻弹出；定时/延后提醒请用 schedule_reminder）。" +
            "适用场景：用户要求立刻发一条通知，或后台巡检/异步任务发现需要用户立即注意的问题。" +
            "一切正常、没有异常时不要调用。",
        parameters = mapOf(
            "title" to "Required. Notification title (brief).",
            "body" to "Required. Notification content.",
            "priority" to "Optional. Priority: urgent/normal/low. Default: normal.",
        ),
        required = setOf("title", "body"),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    fun execute(args: Map<String, String>, notificationManager: MuseNotificationManager): String {
        val title = args["title"]?.trim()
            ?: return "Error: title parameter is required."
        val body = args["body"]?.trim()
            ?: return "Error: body parameter is required."
        if (title.isEmpty() || body.isEmpty()) {
            return "Error: title and body cannot be empty."
        }
        val priority = args["priority"]?.trim()?.lowercase() ?: "normal"
        return try {
            // 使用已有的通知渠道
            notificationManager.notifyChatCompleted(title, body)
            "Notification sent: '$title' (priority: $priority)."
        } catch (e: Exception) {
            "Failed to send notification: ${e.message}"
        }
    }
}
