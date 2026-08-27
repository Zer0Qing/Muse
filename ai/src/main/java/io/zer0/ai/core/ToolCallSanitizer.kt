package io.zer0.ai.core

import io.zer0.common.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Tool call 参数清洗。
 *
 * 兼容部分 OpenAI 中转站返回的截断 JSON、多个 JSON 对象拼接、空 arguments。
 * 能修复的参数保留；完全不可恢复时保留工具调用并改成 `{}`，让工具执行层返回
 * 明确的缺参错误交给模型重试，不能静默删掉唯一的 tool call 造成空 UI。
 */
object ToolCallSanitizer {

    fun isValid(toolCall: ToolCall): Boolean =
        toolCall.name.isNotBlank() && toolCall.arguments.isNotBlank()

    /**
     * 清洗 tool calls：
     *  - 空工具名仍丢弃
     *  - 非法/空 arguments 尝试修复；无法修复时改成 `{}`，不丢调用
     */
    fun sanitize(toolCalls: List<ToolCall>): List<ToolCall> =
        toolCalls.mapNotNull { tc ->
            if (tc.name.isBlank()) {
                safeWarn("丢弃工具调用：name 为空, id=${tc.id}")
                return@mapNotNull null
            }
            val repaired = repairArguments(tc.arguments)
            if (repaired == null) {
                safeWarn("工具参数无法解析,保留调用并降级为 {}: tool=${tc.name}, id=${tc.id}, raw=${tc.arguments.take(240)}")
                tc.copy(arguments = "{}")
            } else {
                if (repaired != tc.arguments) {
                    safeWarn("工具参数已修复: tool=${tc.name}, id=${tc.id}, before=${tc.arguments.take(160)}, after=${repaired.take(160)}")
                }
                tc.copy(arguments = repaired)
            }
        }

    private fun safeWarn(message: String) {
        runCatching { Logger.w("ToolCallSanitizer", message) }
    }

    /** arguments 是否为合法 JSON(object/array/标量均可)。 */
    fun isValidJson(arguments: String): Boolean = runCatching {
        Json.parseToJsonElement(arguments)
        true
    }.getOrDefault(false)

    /** 尝试修复中转站常见的截断/拼接 arguments。 */
    internal fun repairArguments(raw: String): String? {
        val text = raw.trim()
        if (text.isBlank()) return "{}"
        if (isValidJson(text)) return text

        // 先处理 {"a":1}{"b":2}：逐个取顶层对象并合并，后者覆盖同名键。
        splitTopLevelObjects(text)?.takeIf { it.size > 1 }?.let { pieces ->
            val merged = pieces.mapNotNull { piece ->
                runCatching { Json.parseToJsonElement(piece) as? JsonObject }.getOrNull()
            }
            if (merged.size == pieces.size) {
                return mergeObjects(merged).toString()
            }
        }

        // 模型在 arguments 末尾被截断时，补闭合字符串与容器。只对以对象/数组开始的值做。
        if (text.startsWith("{") || text.startsWith("[")) {
            val balanced = balanceJson(text)
            if (balanced != null && isValidJson(balanced)) return balanced
        }
        return null
    }

    private fun mergeObjects(objects: List<JsonObject>): JsonObject = buildJsonObject {
        objects.forEach { obj -> obj.forEach { (key, value) -> put(key, value) } }
    }

    private fun splitTopLevelObjects(text: String): List<String>? {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        text.forEachIndexed { index, c ->
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                return@forEachIndexed
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth < 0) return null
                    if (depth == 0 && start >= 0) {
                        parts += text.substring(start, index + 1)
                        start = -1
                    }
                }
            }
        }
        return if (depth == 0 && !inString && start < 0) parts else null
    }

    private fun balanceJson(text: String): String? {
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        text.forEach { c ->
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                return@forEach
            }
            when (c) {
                '"' -> inString = true
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}', ']' -> if (stack.isEmpty() || stack.removeLast() != c) return null
            }
        }
        val suffix = buildString {
            if (inString) append('"')
            while (stack.isNotEmpty()) append(stack.removeLast())
        }
        return text + suffix
    }
}
