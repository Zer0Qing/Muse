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

    private val toolCallStopReasons = setOf(
        "tool_calls",
        "tool_call",
        "function_call",
        "function_calls",
    )

    /**
     * v2.6.1 (#5): 判断 Provider 返回的停止原因是否表示“模型停下是为了调用工具”。
     *
     * 这种情况下的输出属于工具协议而非用户可见正文：即使正文为空、工具调用又未解析出来，
     * 也不能把 reasoning 当作回复上屏（否则用户会看到思考过程/协议片段掺杂进正文）。
     */
    fun isToolCallStop(reason: String?): Boolean {
        val normalized = reason?.trim()?.lowercase()?.replace(' ', '_') ?: return false
        return normalized in toolCallStopReasons
    }
}
