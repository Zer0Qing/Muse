package io.zer0.muse.tools

import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.flow.first

/** 普通聊天里的“许愿”：允许当前助手之后主动联系用户。 */
object ProactiveWishTool {
    fun toolDef() = ToolRegistry.ToolDef(
        name = "proactive_message_wish",
        description = "Enable proactive messages for the current assistant when the user asks it to contact them later, check in, greet them, or send messages proactively. The host binds the wish to the current assistant; never ask the model to provide an assistant id.",
        parameters = mapOf(
            "schedule_hint" to "Optional. Natural-language timing preference, such as every evening or tomorrow morning. Store the wish; do not promise an exact delivery time.",
        ),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    suspend fun execute(
        args: Map<String, String>,
        settings: SettingsRepository,
        executionContext: ToolExecutionContext,
    ): String {
        val assistantId = executionContext.assistantId
            ?.takeIf { it.isNotBlank() }
            ?: executionContext.scope.takeIf { it.isNotBlank() && it != "main" }
            ?: "default"
        val current = settings.proactiveMessageConfigFlow.first()
        settings.saveProactiveMessageConfig(
            current.copy(
                enabled = true,
                agentId = assistantId,
                agentOnly = false,
                lastFailedAt = 0L,
                consecutiveFailures = 0,
                nextTriggerAt = 0L,
            ),
        )
        val hint = args["schedule_hint"]?.trim()?.takeIf { it.isNotBlank() }
        return if (hint == null) {
            "已记下这个愿望。之后我会在合适的时间主动联系你；主动消息可以发送到普通聊天，不只限于 Agent 会话。"
        } else {
            "已记下这个愿望（时间偏好：$hint）。我会结合你的主动消息设置和免打扰时段安排，不承诺精确到点。"
        }
    }
}
