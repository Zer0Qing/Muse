package io.zer0.ai.core

/**
 * Provider 无关的回复停止原因判定。
 *
 * 不同 API 对“输出达到上限”的命名不同：OpenAI 常见为 `length` / `max_tokens`，
 * Anthropic 为 `max_tokens`，Gemini 为 `MAX_TOKENS`。UI 和上层编排不应各自维护字符串表。
 */
object ChatStopReason {

    private val lengthLimitedReasons = setOf(
        "length",
        "max_tokens",
        "max_output_tokens",
        "max_output_token",
        "max-tokens",
        "max-output-tokens",
        "max_tokens_exceeded",
        "token_limit",
        "token_limit_exceeded",
        "max_token_limit",
    )

    /** 判断 Provider 返回的停止原因是否表示回复因输出长度达到上限而结束。 */
    fun isLengthLimited(reason: String?): Boolean {
        val normalized = reason?.trim()?.lowercase()?.replace(' ', '_') ?: return false
        return normalized in lengthLimitedReasons
    }
}
