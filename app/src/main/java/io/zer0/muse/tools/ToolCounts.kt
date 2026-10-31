package io.zer0.muse.tools

import io.zer0.ai.core.ToolDefinition
import io.zer0.muse.data.skill.SkillEntity

/**
 * 工具数量的三档口径。
 *
 * 三个数本来就该不同，差别只应来自「问的是哪一层」，而不是来自实现各写一套：
 *  - [callableInSchema]：本轮 function schema 里真正下发的数量 —— 模型此刻能调的就是这些；
 *  - [sessionVisible]：本会话 find_tools 空查询能列出的数量（含可装载技能，扣掉 find_tools 自身）；
 *  - [registeredTotal]：本地注册表里的工具总数（启动日志报的就是这一档）。
 */
data class ToolCounts(
    val callableInSchema: Int,
    val sessionVisible: Int,
    val registeredTotal: Int,
)

/**
 * 三档计数的唯一计算入口（纯函数，不依赖 Android）。
 *
 * 下发、计数、检索都走 [ToolSchemaRules] 的同一套规则，所以这里的第一个数与
 * ChatStreamCoordinator 真正发给模型的 tools 数量一致，第二个数与 find_tools
 * 空查询的清单长度一致。
 */
object ToolCounter {

    /** 计算输入：把三个口径各自的来源数据集中传入。 */
    data class Input(
        val localToolDefs: List<ToolDefinition>,
        val registeredTools: List<ToolRegistry.ToolDef>,
        val sessionSkillDefs: List<ToolRegistry.ToolDef>,
        val sessionVisibleNames: Set<String>?,
        val enabledSkills: List<SkillEntity> = emptyList(),
        val criteria: ToolSchemaRules.Criteria = ToolSchemaRules.Criteria(),
    )

    fun compute(input: Input): ToolCounts {
        val localNames = input.registeredTools.map { it.name }.toSet()
        val callableLocal = ToolSchemaRules.selectLocalTools(input.localToolDefs, input.criteria)
        val callableSkills = ToolSchemaRules.selectSkills(input.enabledSkills, localNames, input.criteria)
        val sessionTools = input.registeredTools + input.sessionSkillDefs
        return ToolCounts(
            callableInSchema =
            ToolSchemaRules.callableNames(callableLocal.map { it.name }, callableSkills.map { it.id }).size,
            sessionVisible =
            ToolSchemaRules.visibleSubset(sessionTools, input.sessionVisibleNames)
                .count { it.name != FindToolsTool.TOOL_NAME },
            registeredTotal = input.registeredTools.size,
        )
    }
}
