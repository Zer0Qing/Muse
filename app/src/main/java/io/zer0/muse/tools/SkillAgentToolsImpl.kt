package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.R
import io.zer0.muse.data.groupchat.GroupChatRepository
import io.zer0.muse.schedule.GroupChatScheduler
import io.zer0.muse.ui.taskcard.AgentPlan
import io.zer0.muse.ui.taskcard.AgentPlanStep
import io.zer0.muse.ui.taskcard.AgentPlanStepStatus
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * P1-3e 拆域：SkillExecutor 的 Agent 工作流/群聊工具实现。
 *
 * 承载：
 * - task_plan / update_plan_step（内存计划缓存）
 * - channel_reply / channel_pass / channel_read_context（群聊决策工具）
 * - agent_phone（主 agent 触发群聊成员私聊）
 *
 * delegateAgent 仍留在 SkillExecutor（递归、暂停点、链路追踪与 Koin 依赖耦合高）。
 */
class SkillAgentToolsImpl(
    private val context: Context,
    private val assistantRepository: AssistantRepository,
    private val groupChatRepository: GroupChatRepository?,
    private val groupChatSchedulerProvider: (() -> GroupChatScheduler?)?,
) {

    /**
     * 当前活跃的计划内存缓存(planId → AgentPlan)。ChatViewModel 也会读取此缓存更新 UI。
     *
     * LRU 淘汰(上限 50 条)，避免长期运行累积计划导致内存泄漏。
     */
    private val activePlans: MutableMap<String, AgentPlan> =
        java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, AgentPlan>(
                64, 0.75f, true,
            ) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, AgentPlan>,
                ): Boolean = size > 50
            },
        )

    /** 读取全部活跃计划，保留给全局统计等旧调用方。 */
    fun getActivePlans(): Map<String, AgentPlan> = synchronized(activePlans) { activePlans.toMap() }

    /** 读取指定会话的活跃计划，避免并行会话串台。 */
    fun getActivePlans(sessionId: String): Map<String, AgentPlan> =
        synchronized(activePlans) {
            activePlans.toMap().filter { (_, plan) -> plan.sessionId == sessionId }
        }

    /**
     * 将消息历史重放出的计划回灌执行器缓存。
     *
     * 计划展示消息已经落库；回灌这一步让重新进入会话后继续调用 update_plan_step
     * 时不会因为进程内 activePlans 为空而返回“计划不存在”。
     */
    fun restoreActivePlans(plans: Map<String, AgentPlan>, sessionId: String = "default") {
        // 只替换当前会话的旧投影，保留其他会话正在执行的计划。
        synchronized(activePlans) {
            activePlans.entries.removeIf { it.value.sessionId == sessionId }
            plans.forEach { (id, plan) ->
                activePlans[id] = if (plan.sessionId == sessionId) plan else plan.copy(sessionId = sessionId)
            }
        }
    }

    suspend fun execTaskPlan(args: Map<String, String>, sessionId: String = "default"): String {
        val title = args["title"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_title)
        if (title.isBlank()) return context.getString(R.string.skill_title_blank)

        val stepsJson = args["steps"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_steps)

        val steps = resultOf {
            val jsonArray = io.zer0.common.AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.json.JsonElement.serializer()),
                stepsJson,
            )
            jsonArray.mapIndexed { idx, element ->
                val obj = element.jsonObject
                val stepTitle = obj["title"]?.jsonPrimitive?.content?.trim() ?: "步骤 ${idx + 1}"
                val stepDesc = obj["description"]?.jsonPrimitive?.content?.trim() ?: ""
                AgentPlanStep(
                    id = "step-$idx",
                    title = stepTitle,
                    description = stepDesc,
                )
            }
        }.onError { msg, _ ->
            Logger.w("SkillAgentTools", "task_plan steps 解析失败: $msg")
        }.getOrNull()
            ?: return context.getString(R.string.skill_steps_invalid)

        if (steps.isEmpty()) return context.getString(R.string.skill_steps_empty)

        val planId = "plan-${System.currentTimeMillis()}"
        val plan = AgentPlan(
            id = planId,
            title = title,
            steps = steps,
            sessionId = sessionId,
        )

        activePlans[planId] = plan

        val planSummary = buildString {
            appendLine("计划已创建。planId: $planId")
            appendLine("标题: $title")
            appendLine("步骤:")
            steps.forEachIndexed { idx, step ->
                appendLine("  $idx. ${step.title}${if (step.description.isNotBlank()) " — ${step.description.take(80)}" else ""}")
            }
            appendLine()
            appendLine("请按顺序执行各步骤。每完成一步,调用 update_plan_step(planId=\"$planId\", stepIndex=索引, status=\"done\") 更新进度。")
        }
        return planSummary
    }

    suspend fun execUpdatePlanStep(args: Map<String, String>, sessionId: String = "default"): String {
        val planId = args["planId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_plan_id)
        val stepIndex = args["stepIndex"]?.toIntOrNull()
            ?: return context.getString(R.string.skill_missing_param_step_index)
        val statusStr = args["status"]?.trim()?.lowercase()
            ?: return context.getString(R.string.skill_missing_param_status)
        val result = args["result"]?.trim() ?: ""

        val plan = activePlans[planId]
            ?.takeIf { it.sessionId == sessionId }
            ?: return context.getString(R.string.skill_plan_not_found, planId)

        if (stepIndex < 0 || stepIndex >= plan.steps.size) {
            return context.getString(R.string.skill_step_index_out_of_range, stepIndex, plan.steps.size)
        }

        val status = when (statusStr) {
            "done", "complete", "completed" -> AgentPlanStepStatus.DONE
            "failed", "error" -> AgentPlanStepStatus.FAILED
            "in_progress", "running" -> AgentPlanStepStatus.IN_PROGRESS
            "skipped", "skip" -> AgentPlanStepStatus.SKIPPED
            else -> return context.getString(R.string.skill_unknown_status, statusStr)
        }

        val updatedSteps = plan.steps.mapIndexed { idx, step ->
            if (idx == stepIndex) {
                step.copy(
                    status = status,
                    result = result,
                    startedAt = if (status == AgentPlanStepStatus.IN_PROGRESS) System.currentTimeMillis() else step.startedAt,
                    finishedAt = if (status == AgentPlanStepStatus.DONE || status == AgentPlanStepStatus.FAILED) System.currentTimeMillis() else step.finishedAt,
                )
            } else step
        }
        activePlans[planId] = plan.copy(steps = updatedSteps)

        val step = updatedSteps[stepIndex]
        val completedCount = updatedSteps.count { it.status == AgentPlanStepStatus.DONE }
        return "步骤 $stepIndex「${step.title}」已更新为: ${context.getString(status.labelRes)}。进度: $completedCount/${updatedSteps.size}"
    }

    suspend fun execChannelReply(args: Map<String, String>): String {
        val repo = groupChatRepository
            ?: return context.getString(R.string.skill_channel_reply_not_configured)
        val chatId = args["chatId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_chat_id)
        val assistantId = args["assistantId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_assistant_id)
        val body = args["body"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_body)
        if (body.isBlank()) return context.getString(R.string.skill_body_blank)

        val assistant = resultOf { assistantRepository.getById(assistantId) }
            .onError { msg, _ -> Logger.w("SkillAgentTools", "channel_reply getById 失败: $msg") }
            .getOrNull()
        val senderName = assistant?.name ?: context.getString(R.string.skill_unknown_agent)

        repo.sendMessage(
            chatId = chatId,
            senderType = "assistant",
            senderId = assistantId,
            senderName = senderName,
            body = body,
        )

        return context.getString(R.string.skill_message_sent, body.take(50))
    }

    suspend fun execChannelPass(args: Map<String, String>): String {
        val chatId = args["chatId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_chat_id)
        val assistantId = args["assistantId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_assistant_id)
        return context.getString(R.string.skill_skipped, chatId, assistantId)
    }

    suspend fun execChannelReadContext(args: Map<String, String>): String {
        val repo = groupChatRepository
            ?: return context.getString(R.string.skill_channel_read_not_configured)
        val chatId = args["chatId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_chat_id)
        val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20

        val messages = repo.getRecentMessages(chatId, limit)
        if (messages.isEmpty()) return context.getString(R.string.skill_chat_no_messages, chatId)

        val sb = StringBuilder(context.getString(R.string.skill_chat_messages_header))
        val timeFormatter = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        for (msg in messages) {
            val timeStr = timeFormatter.format(java.util.Date(msg.timestamp))
            sb.appendLine("[${msg.senderName} | $timeStr] ${msg.body}")
        }
        return sb.toString().trimEnd()
    }

    suspend fun execAgentPhone(args: Map<String, String>): String {
        val chatId = args["chatId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_chat_id)
        val targetAssistantId = args["targetAssistantId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_target_assistant_id)
        val message = args["message"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_message)
        if (message.isBlank()) return context.getString(R.string.skill_message_blank)

        val scheduler = groupChatSchedulerProvider?.invoke()
            ?: return context.getString(R.string.skill_agent_phone_not_configured)

        val target = resultOf { assistantRepository.getById(targetAssistantId) }
            .onError { msg, _ -> Logger.w("SkillAgentTools", "agent_phone getById 失败: $msg") }
            .getOrNull()
        if (target == null) return context.getString(R.string.skill_agent_phone_target_not_found)

        scheduler.launchWhisper(
            chatId = chatId,
            targetAssistantId = targetAssistantId,
            text = message,
        )
        return context.getString(R.string.skill_whisper_sent, target.name)
    }
}
