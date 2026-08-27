package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * v1.0.81: 工具参数 JSON 解析器。
 *
 * 把 LLM 传来的 tool_call.arguments JSON 字符串解析成 `Map<String, String>`，
 * 供 SkillExecutor / ToolRegistry 等执行层使用。
 *
 * 设计原则（对齐 Hana web-search 的 schema 直取思路）：
 *  - 只接受**单个完整 JSON 对象**。历史上有一层按 `}{` 拆段合并的容错，用来容忍
 *    DeepSeek V4 流式把 arguments 拼成 `{"a":1}{"b":2}` 的问题；但拆段在参数被截断
 *    （未闭合）时会静默丢字段，是 web_search "缺少参数: query" 的直接原因。
 *  - 拼接 JSON 的根因已在 `OpenAIProvider.ToolCallArgsAccumulator` 修复（智能合并多个
 *    JSON 对象分片）。到达这里的 arguments 永远是单个完整对象，这里不再拆段。
 *  - 解析失败返回空 map（不抛异常），并记日志。执行层据此返回明确的缺参提示，LLM
 *    下一轮重新生成完整参数——可观测、可重试，不静默丢字段。
 *  - 非字符串值（对象/数组/数字/布尔）统一序列化成字符串，保持与执行层 `Map<String,String>`
 *    的契约一致。
 */
object ToolArgsParser {

    private const val TAG = "ToolArgsParser"

    /**
     * 解析 tool_call.arguments JSON。
     *
     * @param json LLM 传来的 arguments 字符串
     * @param toolName 工具名（仅用于日志，定位是哪个工具解析失败）
     * @return 参数 map；解析失败或空入参返回空 map
     */
    fun parse(json: String?, toolName: String = "unknown"): Map<String, String> {
        if (json.isNullOrBlank()) {
            Logger.w(TAG, "$toolName arguments 为空")
            return emptyMap()
        }
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) {
            Logger.w(
                TAG,
                "$toolName arguments 不是 JSON 对象(首字符='${trimmed.firstOrNull()}'),返回空 map",
            )
            return emptyMap()
        }
        val parsed = resultOf {
            val obj = JSON.decodeFromString(JsonObject.serializer(), trimmed)
            obj.entries.associate { (k, v) -> k to stringifyValue(v) }
        }.onError { msg, _ ->
            Logger.w(
                TAG,
                "$toolName arguments 解析失败: $msg(前 200 字: ${trimmed.take(200)})",
            )
        }.getOrNull()
        return parsed ?: emptyMap()
    }

    /** 非字符串值（对象/数组/数字/布尔/null）序列化为字符串；字符串去掉外层引号。 */
    private fun stringifyValue(v: JsonElement): String = when (v) {
        is JsonPrimitive -> v.content
        else -> JSON.encodeToString(JsonElement.serializer(), v)
    }

    // 用独立 Json 实例，避免全局配置干扰；忽略未知键。
    private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}
