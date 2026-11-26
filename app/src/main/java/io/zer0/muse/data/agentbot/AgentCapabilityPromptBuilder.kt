package io.zer0.muse.data.agentbot

import io.zer0.muse.automation.capability.DeviceCapabilityProfile
import io.zer0.muse.computenode.CloudComputerSummary
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.transformer.PromptTemplateLoader
import io.zer0.muse.transformer.SystemPromptAssembler
import kotlinx.coroutines.flow.first
import java.util.Locale

/**
 * 构建 Agent 前台大脑的系统提示词。
 *
 * 将「角色人设 + 本机真实电脑能力 + 当前活跃后台任务 + 协同工具契约 + 可选 Mood 规范」合为一份
 * 简洁、事实准确的上下文，避免模型盲目承诺设备不具备的能力。
 */
class AgentCapabilityPromptBuilder(
    private val runtimeReadyProvider: () -> Boolean = { false },
    private val deviceProfileProvider: (() -> DeviceCapabilityProfile?)? = null,
    private val activeChannelsProvider: (() -> List<String>)? = null,
    private val squadRosterProvider: (suspend () -> List<SquadMemberSummary>)? = null,
    private val cloudComputersProvider: (suspend () -> List<CloudComputerSummary>)? = null,
    private val promptLoader: PromptTemplateLoader? = null,
    private val settingsRepository: SettingsRepository? = null,
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

        val profile = runCatching { deviceProfileProvider?.invoke() }.getOrNull()
        val runtimeReady = runCatching { runtimeReadyProvider() }.getOrDefault(false)

        appendLine("## 行动规则")
        appendLine("- 轻量问答直接流式回复，不建后台任务。")
        appendLine("- 只调用当前请求 `tools` 列表里真实存在的工具；用户知识库默认不自动注入，问及个人文档/笔记时主动用 `knowledge_search` / `knowledge_list_docs` / `knowledge_read_doc`。")
        appendLine("- 单步可完成的查信息或轻量动作可在前台直接调工具完成；需长链路执行、脚本或写文件时简短告知已开始并调用 `launch_mission`（前台不要用 `subagent_run` / `subagent_task`）。")
        appendLine("- 收到任务或情绪表达时，可用 `react_message` 给用户消息贴单个 Emoji（接单 👌 / 查阅 👀 / 赞同 ❤️ / 疑问 🤔）。")
        appendLine("- 对运行中任务补充或纠偏，用 `steer_mission`，不要重复新建。")
        appendLine("- 遇阻需用户拍板时，说明原因并调用 `offer_choices` 给 2–4 个选项。")
        appendLine("- 周期性或事件触发事项用 `create_routine` 沉淀。")
        appendLine("- 只陈述下方真实环境具备的能力；缺失即如实告知。")
        appendLine()

        appendLine("## 本机环境")
        val arch = runCatching { android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty() }
            .getOrDefault("")
            .ifBlank { "linux" }
        if (runtimeReady) {
            appendLine("- 沙盒：Ubuntu ($arch)，Node.js 与 Python 3 就绪。")
        } else {
            appendLine("- 沙盒：未安装（脚本任务需先在「模型与运行环境 → 离线资源」安装）。")
        }

        if (profile != null) {
            appendLine("- 设备能力：${profile.renderOneLineSummary()}")
        }

        val channels = runCatching { activeChannelsProvider?.invoke().orEmpty() }.getOrDefault(emptyList())
        if (channels.isNotEmpty()) {
            appendLine("- 渠道：${channels.joinToString("、")}")
        }

        val squad = runCatching { squadRosterProvider?.invoke().orEmpty() }.getOrDefault(emptyList())
        if (squad.size > 1) {
            appendLine("- 可协作助手：${squad.joinToString("；") { "${it.name}(id=${it.id}${if (it.roleHint.isNotBlank()) ", ${it.roleHint}" else ""})" }}")
        }

        val cloudComputers = runCatching { cloudComputersProvider?.invoke().orEmpty() }.getOrDefault(emptyList())
        if (cloudComputers.isNotEmpty()) {
            appendLine()
            appendLine("## 云端电脑")
            cloudComputers.take(MAX_CLOUD_COMPUTERS).forEach { computer ->
                val resources = if (computer.memTotalMb > 0) {
                    "CPU " + String.format(Locale.US, "%.1f", computer.cpuPercent) + "%；内存 " +
                        computer.memUsedMb.toLong() + "/" + computer.memTotalMb + " MB"
                } else {
                    "CPU/内存待心跳上报"
                }
                val packs = if (computer.packs.isNotEmpty()) {
                    "；已装包 " + computer.packs.joinToString("、")
                } else {
                    ""
                }
                appendLine("- " + computer.name + "：" + computer.os.ifBlank { "OS 未知" } + "；" + resources + packs)
            }
        }

        if (activeMissions.isNotEmpty()) {
            appendLine()
            appendLine("## 运行中任务")
            activeMissions.take(6).forEach { mission ->
                appendLine("- [${mission.taskId}] (${mission.status}) ${mission.goal}")
            }
        }

        val chatPrefs = runCatching { settingsRepository?.getChatPreferences() }.getOrNull()
        val forceMood = runCatching { settingsRepository?.experimentsFlow?.first()?.forceMoodBlock == true }
            .getOrDefault(false)
        val includeMood = forceMood || (chatPrefs?.showMoodBlock == true)
        if (includeMood) {
            val locale = runCatching { settingsRepository?.getLanguageSync() }.getOrNull() ?: "zh"
            val moodSection = promptLoader?.render(
                name = "mood_format",
                locale = locale,
                fallback = SystemPromptAssembler.MOOD_FORMAT_SECTION,
            ) ?: SystemPromptAssembler.MOOD_FORMAT_SECTION
            if (moodSection.isNotBlank()) {
                appendLine()
                appendLine(moodSection.trim())
            }
        }
    }.trim()

    private companion object {
        private const val MAX_CLOUD_COMPUTERS = 4
    }
}
