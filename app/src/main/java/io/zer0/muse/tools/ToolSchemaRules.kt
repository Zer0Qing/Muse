package io.zer0.muse.tools

import io.zer0.ai.core.ToolDefinition
import io.zer0.muse.data.skill.SkillEntity

/**
 * 「模型本轮真正能调用的工具」的唯一筛选口径。
 *
 * 同一套规则散落在三处时会出现三种数量：function schema 下发
 * （[io.zer0.muse.ui.chat.ChatStreamCoordinator]）、提示词工具索引
 * （[io.zer0.muse.transformer.SystemPromptAssembler]）、find_tools 检索目录
 * （[FindToolsTool]）。提示词与检索目录若各写一套，报出的数量就一定高于真实可调用数量，
 * 模型据此形成错误的能力认知。规则只在这里写一次，三处都调它。
 */
object ToolSchemaRules {

    /** 筛选条件：来自当前助手的工具/技能/MCP 绑定与用户的工具审批策略。 */
    data class Criteria(
        val configuredToolIds: Set<String> = emptySet(),
        val mcpServerIds: Set<String> = emptySet(),
        val configuredSkillIds: Set<String> = emptySet(),
        val deniedToolNames: Set<String> = emptySet(),
    )

    /**
     * 本地工具是否进入本轮 schema。
     *
     * - 空 [Criteria.configuredToolIds] 保留旧语义（全部内置工具可用）；
     * - find_tools 是分层收窄的补全通道，任何白名单下都保持可用；
     * - 用户显式禁用的工具（ALWAYS_DENY）在暴露层就拦下，不留到执行层再拒绝 ——
     *   否则模型每轮看到工具、调用、被拒，下一轮又调；
     * - 未绑定任何 MCP 服务时，`mcp_<server>__<tool>` 一律不下发。
     */
    fun localSelected(name: String, criteria: Criteria): Boolean {
        if (name in criteria.deniedToolNames) return false
        val selectedByTool =
            criteria.configuredToolIds.isEmpty() || name in criteria.configuredToolIds ||
                name == FindToolsTool.TOOL_NAME
        val isMcpTool = name.startsWith("mcp_") && name.contains("__")
        val selectedByMcp = !isMcpTool || criteria.mcpServerIds.any { name.startsWith("mcp_${it}__") }
        return selectedByTool && selectedByMcp
    }

    /**
     * 技能是否进入本轮 schema。
     *
     * 与本地工具同名的技能必须剔除：去重后定义保留本地版，若技能还留在路由里，就会
     * 「模型看到本地定义、实际执行技能实现」（参数集不一致、行为语义不一致）。
     * `channel_*` 群聊三件套只在群聊上下文由 ChannelToolFactory 注入，主会话必须剔除，
     * 否则可用技能版以任意 agent 身份向任意群聊发言，绕过身份绑定防护。
     */
    fun skillSelected(id: String, localToolNames: Set<String>, criteria: Criteria): Boolean {
        if (id in localToolNames || id.startsWith("channel_")) return false
        return criteria.configuredSkillIds.isEmpty() || id in criteria.configuredSkillIds
    }

    /** 过滤本地工具定义（下发侧与计数侧同一套规则）。 */
    fun selectLocalTools(all: List<ToolDefinition>, criteria: Criteria): List<ToolDefinition> =
        all.filter { localSelected(it.name, criteria) }

    /** 过滤技能（保序去重，再按规则筛选）。 */
    fun selectSkills(all: List<SkillEntity>, localToolNames: Set<String>, criteria: Criteria): List<SkillEntity> =
        all.distinctBy { it.id }.filter { skillSelected(it.id, localToolNames, criteria) }

    /** 本轮 schema 里真正下发的工具名：本地工具优先于同名技能。 */
    fun callableNames(localToolNames: Collection<String>, skillIds: Collection<String>): Set<String> {
        val locals = localToolNames.toSet()
        return locals + skillIds.filterNot { it in locals }
    }

    /** 会话可见目录：[visibleNames] 为 null（未登记，例如子代理等旧路径）时不作收窄。 */
    fun visibleSubset(tools: List<ToolRegistry.ToolDef>, visibleNames: Set<String>?): List<ToolRegistry.ToolDef> =
        if (visibleNames == null) tools else tools.filter { it.name in visibleNames }
}
