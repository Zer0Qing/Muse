package io.zer0.ai.core

import io.zer0.common.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Tool call 参数清洗。
 *
 * 兼容部分 OpenAI 中转站返回的截断 JSON、多个 JSON 对象拼接、空 arguments。
 * 能修复的参数保留；完全不可恢复时保留工具调用并改成 `{}`，让工具执行层返回
 * 明确的缺参错误交给模型重试，不能静默删掉唯一的 tool call 造成空 UI。
 */
object ToolCallSanitizer {

    fun isValid(toolCall: ToolCall): Boolean = isValidName(toolCall.name) && toolCall.arguments.isNotBlank()

    /**
     * v2.6.7: 工具名是否真的是个工具名。
     *
     * 背景（用户实测日志）: 某些模型（GLM-4-9B 等）会把正文台词写进 function.name，
     * 于是出现 `name="好的，子奇！我会创建一个交互式图表…show_card"`。
     * 旧实现只检查 `name.isNotBlank()`，于是这串中文一路通过清洗、进审批、被当成工具执行，
     * 最终以 “未知工具” 失败，表现为“卡片根本生成不了”。
     *
     * 工具名在协议上永远是 `[A-Za-z0-9_-]` 的标识符，含有任何其它字符就不可能是真工具名。
     * 这类调用应整条丢弃，让编排器走“参数/调用异常”分支提示模型重试，
     * 而不是把垃圾当工具名去执行。
     */
    fun isValidName(name: String): Boolean {
        // 长度上限：真实工具名远短于此；超长说明混进了正文。
        val withinLimit = name.isNotBlank() && name.length <= 64
        return withinLimit && name.all { c ->
            (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '_' || c == '-'
        }
    }

    /**
     * 清洗 tool calls：
     *  - 空工具名丢弃
     *  - 工具名被正文污染（含中文/空格/标点）时先尝试还原末尾的工具名，
     *    还原不出且对不上 [knownToolNames] 才丢弃
     *  - 非法/空 arguments 尝试修复；无法修复时改成 `{}`，不丢调用
     *
     * @param knownToolNames 本轮真正要暴露给模型的工具名。只有能对上市名单才还原；
     *   传空集表示调用方没有名单，此时不猜，仍然丢弃非法调用。
     */
    fun sanitize(
        toolCalls: List<ToolCall>,
        knownToolNames: Set<String> = emptySet(),
    ): List<ToolCall> = toolCalls.mapNotNull { tc ->
        if (tc.name.isBlank()) {
            safeWarn("丢弃工具调用：name 为空, id=${tc.id}")
            return@mapNotNull null
        }
        val effectiveName =
            if (isValidName(tc.name)) {
                tc.name
            } else {
                val salvaged = salvageName(tc.name, knownToolNames)
                if (salvaged == null) {
                    // 不打印 name 原文（可能夹带用户内容），只给出长度与首字符类别。
                    safeWarn(
                        "丢弃工具调用：name 不是合法标识符(id=${tc.id}, len=${tc.name.length})" +
                            "，疑似模型把正文写进了函数名且无法还原",
                    )
                    return@mapNotNull null
                }
                safeWarn("工具名混入正文,已还原(id=${tc.id}, len=${tc.name.length} -> $salvaged)")
                salvaged
            }
        val repaired = repairArguments(tc.arguments)
        if (repaired == null) {
            safeWarn(
                "工具参数无法解析,保留调用并降级为 {}: " +
                    safeArgumentsForLog(effectiveName, tc.arguments),
            )
            tc.copy(name = effectiveName, arguments = "{}")
        } else {
            if (repaired != tc.arguments) {
                safeWarn(
                    "工具参数已修复: ${safeArgumentsForLog(effectiveName, tc.arguments)}" +
                        " repaired=[REDACTED]",
                )
            }
            tc.copy(name = effectiveName, arguments = repaired)
        }
    }

    /** 工具名结尾允许的标识符形态。 */
    private val TRAILING_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_-]*$")

    /**
     * 从被正文污染的 name 里把真正的工具名捞回来。
     *
     * 实测日志里模型会把台词和函数名连成一串提交：
     * `Sure, I can help with that. I'll create a card. Please hold on a moment.show_card`。
     * 整串含空格与标点，但**末尾那段**是货真价实的工具名。丢掉整条调用等于让用户
     * 反复重试同一个失败，所以这里把尾巴取出来复用。
     *
     * 只有能对上本轮暴露的工具才采用。拿不到名单时一律返回 null：没有名单就只能靠猜，
     * 而正文里随时会出现英文单词（"please hold on a moment"），猜错会把一个词当工具执行。
     */
    internal fun salvageName(rawName: String, knownToolNames: Set<String>): String? {
        if (knownToolNames.isEmpty()) return null
        val candidate = TRAILING_IDENTIFIER.find(rawName.trim())?.value ?: return null
        if (candidate.length > 64) return null
        if (candidate !in knownToolNames) return null
        return candidate
    }

    /**
     * Diagnostic-only representation. Tool arguments can contain passwords, clipboard
     * contents, or text injected into another app; never put the raw JSON in logs.
     */
    internal fun safeArgumentsForLog(toolName: String, arguments: String): String =
        "tool=$toolName args=[REDACTED] length=${arguments.length}"

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
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') inString = false
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
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') inString = false
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
