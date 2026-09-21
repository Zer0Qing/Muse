package io.zer0.ai.core

/**
 * 协议注册表(照搬既有实现 的 `api` 字符串 → 实现映射)。
 *
 * 把"走哪套协议"从枚举硬分支收敛成 **字符串 + 映射**:将来接新协议
 * (Responses / 各家私有)只需加一个键,不必改枚举与所有 `when` 分支。
 * [ProviderType] 保留为兼容层,由这里与协议字符串互转。
 *
 * 已知协议(与上游目录一致):
 *  - `openai-completions` → [ProviderType.OPENAI](兼容所有 OpenAI 风格的直连/中转)
 *  - `openai-responses`   → [ProviderType.OPENAI_RESPONSES]
 *  - `anthropic-messages` → [ProviderType.ANTHROPIC]
 *  - `gemini`             → [ProviderType.GEMINI]
 */
object ProviderApiRegistry {

    const val OPENAI_COMPLETIONS = "openai-completions"
    const val OPENAI_RESPONSES = "openai-responses"
    const val ANTHROPIC_MESSAGES = "anthropic-messages"
    const val GEMINI = "gemini"

    /** 全部已知协议字符串。 */
    val known: Set<String> = setOf(OPENAI_COMPLETIONS, OPENAI_RESPONSES, ANTHROPIC_MESSAGES, GEMINI)

    /** 协议字符串 → 兼容层 [ProviderType];未识别返回 null。 */
    fun typeOf(api: String): ProviderType? = when (api.trim().lowercase()) {
        OPENAI_COMPLETIONS -> ProviderType.OPENAI
        OPENAI_RESPONSES -> ProviderType.OPENAI_RESPONSES
        ANTHROPIC_MESSAGES -> ProviderType.ANTHROPIC
        GEMINI -> ProviderType.GEMINI
        else -> null
    }

    /** [ProviderType] → 协议字符串。 */
    fun apiOf(type: ProviderType): String = when (type) {
        ProviderType.OPENAI -> OPENAI_COMPLETIONS
        ProviderType.OPENAI_RESPONSES -> OPENAI_RESPONSES
        ProviderType.ANTHROPIC -> ANTHROPIC_MESSAGES
        ProviderType.GEMINI -> GEMINI
    }
}
