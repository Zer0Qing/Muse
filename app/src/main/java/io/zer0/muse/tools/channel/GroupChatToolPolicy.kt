package io.zer0.muse.tools.channel

import io.zer0.ai.core.ToolDefinition
import io.zer0.muse.tools.ToolPermissionResolver
import io.zer0.muse.tools.ToolRiskLevel

/** B8-03 方案 B: 群聊暂不支持媒体输出,由策略统一过滤。 */
object GroupChatToolPolicy {

    /** 群聊不可用的媒体生成工具,避免模型白调后无展示通道。 */
    val MEDIA_OUTPUT_TOOLS: Set<String> = setOf(
        "generate_image",
        "generate_video",
        "generate_qr_code",
    )

    /**
     * 过滤群聊常规工具列表。
     *
     * P1-11 风险白名单前置:除媒体输出工具外,高风险工具一律不进入群聊直执行通道。
     * 群聊常规工具无审批环节,只有低/中风险(SAFE/NORMAL)工具才允许直执行;
     * 风险判定走 [ToolPermissionResolver.riskLevelFor] 单一真源(P0-3)。
     */
    fun filterRegularTools(tools: List<ToolDefinition>): List<ToolDefinition> =
        tools.filterNot { it.name in MEDIA_OUTPUT_TOOLS || ToolPermissionResolver.riskLevelFor(it.name) == ToolRiskLevel.HIGH }
}
