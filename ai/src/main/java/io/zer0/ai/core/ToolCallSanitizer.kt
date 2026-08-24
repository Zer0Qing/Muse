package io.zer0.ai.core

import kotlinx.serialization.json.Json

/**
 * v1.0.62: 非法 tool call 统一清洗。
 *
 * 部分模型/中转站会输出 name 为空或 arguments 为空的 tool call，
 * 直接进入请求体会触发 HTTP 400：
 *   invalid tool call function, function/name/arguments cannot be empty
 *
 * v1.0.80: 新增 arguments 合法性校验 — 部分中转站(如 agnes)会输出
 * 非空但非法 JSON 的 arguments(未闭合/被截断),回传历史时 OpenAI 兼容
 * 服务端校验失败,报 400 "Assistant tool call arguments must be valid JSON",
 * 中断整轮。对这类调用把 arguments 修复为 "{}",工具执行时安全降级
 * (缺必填参数返回明确错误),回传时不再触发 400。
 */
object ToolCallSanitizer {

    fun isValid(toolCall: ToolCall): Boolean =
        toolCall.name.isNotBlank() && toolCall.arguments.isNotBlank()

    /**
     * 清洗 tool calls:
     *  - 丢弃 name 或 arguments 为空/空白的调用(旧行为)
     *  - arguments 非空白但非法 JSON 时,修复为 "{}"(空对象)
     */
    fun sanitize(toolCalls: List<ToolCall>): List<ToolCall> =
        toolCalls
            .filter(::isValid)
            .map { tc -> if (isValidJson(tc.arguments)) tc else tc.copy(arguments = "{}") }

    /** arguments 是否为合法 JSON(object/array/标量均可)。 */
    fun isValidJson(arguments: String): Boolean = runCatching {
        Json.parseToJsonElement(arguments)
        true
    }.getOrDefault(false)
}
