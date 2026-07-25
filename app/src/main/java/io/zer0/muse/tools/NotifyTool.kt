package io.zer0.muse.tools

import android.content.Context
import io.zer0.muse.notification.MuseNotificationManager

/**
 * notify 工具(openhanako notify-tool.ts 移植版)。
 *
 * Lets the AI proactively send notifications to the user.
 * Only used when the user explicitly asks for reminders/notifications,
 * or when a scheduled task discovers something requiring attention.
 */
object NotifyTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "notify",
        description = "Send a notification to the user. Use cases: " +
            "- The user says 'remind me about xxx' or 'notify me when...' " +
            "- A monitoring task discovers something requiring user attention. " +
            "If everything is normal with no issues, do not call this tool.",
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
