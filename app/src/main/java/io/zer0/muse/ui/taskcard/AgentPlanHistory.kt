@file:Suppress("ReturnCount", "CyclomaticComplexMethod")

package io.zer0.muse.ui.taskcard

import io.zer0.ai.core.ToolCallInfo
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val HISTORICAL_PLAN_ID_PATTERN = Regex(
    """(?i)(?:planId|plan_id)\s*["'`]?\s*[:=]\s*["'`]?([A-Za-z0-9_-]+)""",
)

/**
 * 从已落库的工具展示消息重放计划状态。
 *
 * 计划消息可能早于聊天首屏分页窗口,所以调用方应传入会话全量历史。
 * 没有有效步骤的记录不会进入结果,避免 UI 恢复成空计划卡。
 */
internal fun restoreAgentPlansFromHistory(messages: List<UIMessage>): Map<String, AgentPlan> {
    if (messages.none { it.toolCallInfo?.toolName == "task_plan" }) return emptyMap()

    val plans = linkedMapOf<String, AgentPlan>()
    messages.forEach { message ->
        val toolInfo = message.toolCallInfo ?: return@forEach
        when (toolInfo.toolName) {
            "task_plan" -> parseHistoricalPlan(message, toolInfo)?.let { plan ->
                plans[plan.id] = plan
            }
            "update_plan_step" -> applyHistoricalPlanUpdate(plans, message, toolInfo)
        }
    }
    return plans
}

private fun parseHistoricalPlan(message: UIMessage, toolInfo: ToolCallInfo): AgentPlan? {
    val arguments = parseHistoricalToolArguments(toolInfo.arguments) ?: return null
    val steps = parseHistoricalPlanSteps(arguments["steps"])
    if (steps.isEmpty()) return null

    val planId = HISTORICAL_PLAN_ID_PATTERN.find(toolInfo.result)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
        ?: "history-plan-${message.id}"
    val title = historicalJsonText(arguments["title"])
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "任务计划"

    return AgentPlan(
        id = planId,
        title = title,
        steps = steps,
        createdAt = message.createdAt,
        messageId = message.id.toString(),
    )
}

private fun applyHistoricalPlanUpdate(
    plans: MutableMap<String, AgentPlan>,
    message: UIMessage,
    toolInfo: ToolCallInfo,
) {
    val arguments = parseHistoricalToolArguments(toolInfo.arguments) ?: return
    val planId = historicalJsonText(arguments["planId"])
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return
    val planEntry = plans.entries.firstOrNull { it.key == planId || it.value.id == planId } ?: return
    val stepIndex = historicalJsonText(arguments["stepIndex"])
        ?.trim()
        ?.toIntOrNull()
        ?: return
    val status = parseHistoricalPlanStatus(historicalJsonText(arguments["status"])) ?: return
    if (stepIndex !in planEntry.value.steps.indices) return

    val result = historicalJsonText(arguments["result"]).orEmpty()
    val updatedSteps = planEntry.value.steps.mapIndexed { index, step ->
        if (index != stepIndex) {
            step
        } else {
            step.copy(
                status = status,
                result = result,
                startedAt = if (
                    status == AgentPlanStepStatus.IN_PROGRESS && step.startedAt == 0L
                ) {
                    message.createdAt
                } else {
                    step.startedAt
                },
                finishedAt = if (
                    status == AgentPlanStepStatus.DONE ||
                    status == AgentPlanStepStatus.FAILED ||
                    status == AgentPlanStepStatus.SKIPPED
                ) {
                    if (step.finishedAt == 0L) message.createdAt else step.finishedAt
                } else {
                    step.finishedAt
                },
            )
        }
    }
    plans[planEntry.key] = planEntry.value.copy(steps = updatedSteps)
}

private fun parseHistoricalToolArguments(arguments: String): JsonObject? = runCatching {
    AppJson.decodeFromString(JsonObject.serializer(), arguments)
}.getOrNull()

private fun parseHistoricalPlanSteps(value: JsonElement?): List<AgentPlanStep> {
    val array = when (value) {
        is JsonArray -> value
        is JsonPrimitive -> runCatching {
            AppJson.decodeFromString(JsonArray.serializer(), value.content)
        }.getOrNull()
        else -> null
    } ?: return emptyList()

    return array.mapIndexedNotNull { index, element ->
        val obj = element as? JsonObject ?: return@mapIndexedNotNull null
        AgentPlanStep(
            id = "step-$index",
            title = historicalJsonText(obj["title"])
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "步骤 ${index + 1}",
            description = historicalJsonText(obj["description"])
                ?.trim()
                .orEmpty(),
        )
    }
}

private fun historicalJsonText(value: JsonElement?): String? = when (value) {
    null -> null
    is JsonPrimitive -> value.content
    else -> AppJson.encodeToString(JsonElement.serializer(), value)
}

private fun parseHistoricalPlanStatus(raw: String?): AgentPlanStepStatus? = when (
    raw?.trim()?.lowercase()
) {
    "pending", "todo" -> AgentPlanStepStatus.PENDING
    "in_progress", "in-progress", "running" -> AgentPlanStepStatus.IN_PROGRESS
    "done", "complete", "completed", "success" -> AgentPlanStepStatus.DONE
    "failed", "error" -> AgentPlanStepStatus.FAILED
    "skipped", "skip" -> AgentPlanStepStatus.SKIPPED
    else -> null
}
