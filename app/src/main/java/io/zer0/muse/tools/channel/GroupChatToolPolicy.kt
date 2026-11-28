package io.zer0.muse.tools.channel

import io.zer0.ai.core.ToolDefinition
import io.zer0.muse.tools.ToolPermissionResolver
import io.zer0.muse.tools.ToolRiskLevel

/** B8-03 方案 B / v2.x (B3): 群聊媒体工具进出策略。 */
object GroupChatToolPolicy {

    /**
     * v2.x (B3): 群聊已打通"生成结果 → 消息附件"展示通道的媒体工具 — 放开。
     *
     * 生成图的产物写入群聊消息 `imageBase64Json`(由 MessageImageGrid 渲染);
     * v2.x 遗留收尾起,生成视频的产物写入消息 `videoFileUri` 列(由 AssistantVideoCard
     * 渲染、点击调起播放器),不再是"白调后无展示通道"。风险等级经
     * [ToolPermissionResolver.riskLevelFor] 走 `generate_` 前缀 = SAFE,无需群聊审批。
     */
    val ENABLED_MEDIA_TOOLS: Set<String> = setOf(
        "generate_image",
        "generate_video",
        "generate_qr_code",
    )

    /**
     * v2.x (B3): 仍禁用的媒体工具。
     *
     * 视频通道补齐(消息 `videoFileUri` 列 + AssistantVideoCard 渲染)后已放开,
     * 当前为空集;保留此集合作为后续新增媒体工具的默认屏蔽位置。
     */
    val BLOCKED_MEDIA_TOOLS: Set<String> = emptySet()

    /**
     * 过滤群聊媒体工具:只保留已打通展示通道的 [ENABLED_MEDIA_TOOLS]。
     *
     * v2.x (B3): 与群聊常规工具开关(GroupChatScheduler.ENABLE_GROUP_CHAT_REGULAR_TOOLS)解耦 —
     * 常规工具默认关闭以免小模型空 tool call 风暴,但媒体工具已有展示通道,
     * 单独注入即可用(仍受成员 toolIdsJson 过滤)。
     */
    fun filterMediaTools(tools: List<ToolDefinition>): List<ToolDefinition> = tools.filter { it.name in ENABLED_MEDIA_TOOLS }

    /**
     * 过滤群聊常规工具列表。
     *
     * P1-11 风险白名单前置:除 [BLOCKED_MEDIA_TOOLS] 外,高风险工具一律不进入群聊直执行通道。
     * 群聊常规工具无审批环节,只有低/中风险(SAFE/NORMAL)工具才允许直执行;
     * 风险判定走 [ToolPermissionResolver.riskLevelFor] 单一真源(P0-3)。
     */
    fun filterRegularTools(tools: List<ToolDefinition>): List<ToolDefinition> =
        tools.filterNot { it.name in BLOCKED_MEDIA_TOOLS || ToolPermissionResolver.riskLevelFor(it.name) == ToolRiskLevel.HIGH }
}
