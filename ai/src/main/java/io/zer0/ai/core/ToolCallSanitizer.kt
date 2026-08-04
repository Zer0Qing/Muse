package io.zer0.ai.core

/**
 * v1.0.62: 非法 tool call 统一清洗。
 *
 * 部分模型/中转站会输出 name 为空或 arguments 为空的 tool call，
 * 直接进入请求体会触发 HTTP 400：
 *   invalid tool call function, function/name/arguments cannot be empty
 */
object ToolCallSanitizer {

    fun isValid(toolCall: ToolCall): Boolean =
        toolCall.name.isNotBlank() && toolCall.arguments.isNotBlank()

    fun sanitize(toolCalls: List<ToolCall>): List<ToolCall> =
        toolCalls.filter(::isValid)
}
