package io.zer0.ai.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * v1.0.81: 流式 tool_call arguments 累积器。
 *
 * 问题背景：
 *  OpenAI 规范的流式 tool_call.arguments 是字符串增量（`{"lim` + `it":20}`），
 *  直接 append 即可拼成完整 JSON。但 DeepSeek V4 等模型/中转站会把同一 tool call 的
 *  arguments 以**多个完整 JSON 对象**形式分片发出：
 *
 *  ```
 *  {"limit":20}
 *  {"query":"天气"}
 *  ```
 *
 *  盲目 append 会拼成 `{"limit":20}{"query":"天气"}`，这是非法 JSON，下游 SkillExecutor
 *  的 parseArgs 靠拆段合并补锅，遇到未闭合片段就丢字段，最终触发"缺少参数: query"。
 *
 * 本累积器在 [append] 时区分两种情况：
 *  1. 字符串增量（最常见）：当前缓冲 + 新片段，整体能解析成 JsonObject 就保留。
 *  2. 完整对象分片：当前缓冲已经是一个完整 JsonObject，新片段 trim 后以 `{` 开头且自身
 *     也是完整 JsonObject，把两个对象**字段合并**（后到的同名字段覆盖先到的），产出单个
 *     合法 JsonObject。
 *
 * 截断/未闭合的片段照常 append，[isValidJson] 返回 false，由上层决定是等待后续片段
 * 还是报错，绝不静默丢字段。
 */
internal class ToolCallArgsAccumulator {

    private val buffer = StringBuilder()
    /** 是否发生过多个完整 JSON 对象分片合并。 */
    private var mergedObjects = false

    /** 当前累积的原始字符串。 */
    fun current(): String = buffer.toString()

    /** 当前累积字符数。 */
    val length: Int get() = buffer.length

    /** 是否已累积内容。 */
    fun isNotEmpty(): Boolean = buffer.isNotEmpty()

    /** 累积一个 arguments 增量片段。 */
    fun append(fragment: String?) {
        val piece = fragment ?: return
        if (piece.isBlank()) return

        // 情况 2：缓冲已经是完整对象，新片段也是完整对象 → 合并字段。
        val existingObj = parseObjectOrNull(buffer.toString())
        if (existingObj != null) {
            val newObj = parseObjectOrNull(piece.trim())
            if (newObj != null) {
                mergedObjects = true
                val merged = mergeObjects(existingObj, newObj)
                buffer.setLength(0)
                buffer.append(merged.toString())
                return
            }
        }

        // 情况 1：普通字符串增量。append 后若整体是拼接的多个对象（obj1}{obj2}），
        // 拆段合并；否则原样保留（单对象或未闭合片段都不重序列化，避免改动空白/格式）。
        buffer.append(piece)
        val combined = buffer.toString()
        if (parseObjectOrNull(combined) != null) return // 已经是合法单对象，原样保留
        val mergedFromConcat = tryMergeConcatenatedObjects(combined)
        if (mergedFromConcat != null) {
            mergedObjects = true
            buffer.setLength(0)
            buffer.append(mergedFromConcat.toString())
        }
    }

    /** 当前累积内容是否为合法 JSON 对象。 */
    fun isValidJson(): Boolean = parseObjectOrNull(buffer.toString()) != null

    /**
     * 是否发生过多个完整 JSON 对象分片合并(DeepSeek V4 等模型会把 arguments
     * 拆成 {"a":1}{"b":2} 多个对象)。
     *
     * 为 true 时,上游不能再把"原始增量片段"发给下游 append(那会重新拼成拼接串),
     * 而应发送 [current]()(合并后的完整 JSON)作为快照,下游用快照而非追加。
     */
    fun hasMergedObjects(): Boolean = mergedObjects

    private fun parseObjectOrNull(raw: String): JsonObject? {
        val text = raw.trim()
        if (!text.startsWith("{") || !text.endsWith("}")) return null
        return runCatching {
            val el = Json.parseToJsonElement(text)
            el as? JsonObject
        }.getOrNull()
    }

    /**
     * 检测 "obj1}{obj2}{obj3}" 这种拼接串，拆成多个 JsonObject 合并。
     * 拆不动时返回 null（走普通字符串增量路径）。
     */
    private fun tryMergeConcatenatedObjects(raw: String): JsonObject? {
        val text = raw.trim()
        if (!text.startsWith("{")) return null
        // 用括号深度切分顶层对象
        val objects = splitTopLevelObjects(text) ?: return null
        if (objects.size < 2) return null
        var merged: JsonObject? = null
        for (objText in objects) {
            val obj = parseObjectOrNull(objText) ?: return null
            merged = if (merged == null) obj else mergeObjects(merged, obj)
        }
        return merged
    }

    /** 按顶层花括号配对切分，返回每个对象的原文。不合法时返回 null。 */
    private fun splitTopLevelObjects(text: String): List<String>? {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escape = false
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        parts.add(text.substring(start, i + 1))
                        start = -1
                    } else if (depth < 0) {
                        return null // 括号不配对
                    }
                }
            }
        }
        if (depth != 0 || inString) return null // 未闭合
        return parts.takeIf { it.isNotEmpty() }
    }

    private fun mergeObjects(a: JsonObject, b: JsonObject): JsonObject = buildJsonObject {
        // 先放 a 的全部字段
        a.forEach { (k, v) -> put(k, v) }
        // b 覆盖同名字段；若两边都是对象，递归合并
        b.forEach { (k, v) ->
            val existing = a[k]
            if (existing is JsonObject && v is JsonObject) {
                put(k, mergeObjects(existing, v))
            } else {
                put(k, v)
            }
        }
    }
}
