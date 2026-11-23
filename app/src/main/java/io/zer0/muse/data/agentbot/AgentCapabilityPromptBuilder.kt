package io.zer0.muse.data.agentbot

import io.zer0.muse.automation.capability.DeviceCapabilityProfile

/**
 * 构建 Agent 前台大脑的系统提示词。
 *
 * 将「角色人设 + 本机真实电脑能力 + 当前活跃后台任务 + 协同工具契约」合为一份
 * 简洁、事实准确的上下文，避免模型盲目承诺设备不具备的能力。
 */
class AgentCapabilityPromptBuilder(
    private val runtimeReadyProvider: () -> Boolean = { false },
    private val deviceProfileProvider: (() -> DeviceCapabilityProfile?)? = null,
    private val activeChannelsProvider: (() -> List<String>)? = null,
    private val squadRosterProvider: (suspend () -> List<SquadMemberSummary>)? = null,
) {

    data class SquadMemberSummary(
        val id: String,
        val name: String,
        val roleHint: String,
    )

    data class ActiveMissionSummary(
        val taskId: String,
        val goal: String,
        val status: String,
    )

    suspend fun build(
        assistantName: String,
        assistantSystemPrompt: String,
        activeMissions: List<ActiveMissionSummary>,
    ): String = buildString {
        if (assistantSystemPrompt.isNotBlank()) {
            appendLine(assistantSystemPrompt.trim())
            appendLine()
        } else {
            val displayName = assistantName.ifBlank { "Muse" }
            appendLine("你是个人智能体「$displayName」。回复直接、简洁、诚实，不用客服套话。")
            appendLine()
        }

        appendLine("## 行动规则")
        appendLine("- 轻量问答直接流式回复，不建后台任务。")
        appendLine("- 收到任务或情绪表达时，可用 `react_message` 给用户消息贴单个 Emoji（接单 👌 / 查阅 👀 / 赞同 ❤️ / 疑问 🤔）。")
        appendLine("- 需多步工具、脚本、文件或虚拟屏操作时：简短告知已开始，同时调用 `launch_mission`。")
        appendLine("- 对运行中任务补充或纠偏，用 `steer_mission`，不要重复新建。")
        appendLine("- 遇阻需用户拍板时，说明原因并调用 `offer_choices` 给 2–4 个选项。")
        appendLine("- 周期性或事件触发事项用 `create_routine` 沉淀。")
        appendLine("- 只陈述下方真实环境具备的能力；缺失即如实告知。")
        appendLine()

        appendLine("## 本机环境")
        val runtimeReady = runCatching { runtimeReadyProvider() }.getOrDefault(false)
        val arch = runCatching { android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty() }
            .getOrDefault("")
            .ifBlank { "linux" }
        if (runtimeReady) {
            appendLine("- 沙盒：Ubuntu ($arch)，Node.js 与 Python 3 就绪。")
        } else {
            appendLine("- 沙盒：未安装（脚本任务需先在「模型与运行环境 → 离线资源」安装）。")
        }

        val profile = runCatching { deviceProfileProvider?.invoke() }.getOrNull()
        if (profile != null) {
            appendLine("- 设备能力：${profile.renderForModel().lineSequence().take(4).joinToString("；")}")
        }

        val channels = runCatching { activeChannelsProvider?.invoke().orEmpty() }.getOrDefault(emptyList())
        if (channels.isNotEmpty()) {
            appendLine("- 渠道：${channels.joinToString("、")}")
        }

        val squad = runCatching { squadRosterProvider?.invoke().orEmpty() }.getOrDefault(emptyList())
        if (squad.size > 1) {
            appendLine("- 队友：${squad.joinToString("；") { "${it.name}(id=${it.id}${if (it.roleHint.isNotBlank()) ", ${it.roleHint}" else ""})" }}")
        }

        if (activeMissions.isNotEmpty()) {
            appendLine()
            appendLine("## 运行中任务")
            activeMissions.take(6).forEach { mission ->
                appendLine("- [${mission.taskId}] (${mission.status}) ${mission.goal}")
            }
        }
    }.trim()
}
