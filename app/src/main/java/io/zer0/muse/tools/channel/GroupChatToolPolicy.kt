package io.zer0.muse.tools.channel

import io.zer0.ai.core.ToolDefinition

/** B8-03 方案 B: 群聊暂不支持媒体输出,由策略统一过滤。 */
object GroupChatToolPolicy {

    /** 群聊不可用的媒体生成工具,避免模型白调后无展示通道。 */
    val MEDIA_OUTPUT_TOOLS: Set<String> = setOf(
        "generate_image",
        "generate_video",
        "generate_qr_code",
    )

    /** 过滤群聊常规工具列表。 */
    fun filterRegularTools(tools: List<ToolDefinition>): List<ToolDefinition> =
        tools.filterNot { it.name in MEDIA_OUTPUT_TOOLS }
}
