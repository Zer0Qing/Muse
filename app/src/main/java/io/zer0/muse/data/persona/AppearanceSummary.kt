package io.zer0.muse.data.persona

import kotlinx.serialization.Serializable

/**
 * Agent 外观摘要 (openhanako agent-appearance-summary.ts 移植)。
 *
 * 为助手生成外观描述 (基于头像/名称/设定)，注入 system prompt 增强角色一致性。
 * 在 Android 端简化为：基于 AssistantEntity 的 emoji/name/description 生成文本描述。
 */
@Serializable
data class AppearanceSummary(
    val name: String,
    val emoji: String,
    val visualDescription: String,
    val personalityHint: String,
) {
    companion object {
        /** 从助手基本信息生成外观摘要。 */
        fun fromAssistant(name: String, emoji: String = "🤖", description: String = ""): AppearanceSummary {
            return AppearanceSummary(
                name = name,
                emoji = emoji,
                visualDescription = if (description.isNotBlank()) description else "$emoji $name 是一位 AI 助手",
                personalityHint = "",
            )
        }
    }

    /** 渲染为 system prompt 段落。 */
    fun renderForPrompt(): String {
        if (visualDescription.isBlank()) return ""
        return buildString {
            appendLine("## Appearance")
            appendLine("$emoji $name: $visualDescription")
            if (personalityHint.isNotBlank()) appendLine("Personality hint: $personalityHint")
        }.trimEnd()
    }
}
