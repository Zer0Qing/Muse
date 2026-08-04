package io.zer0.ai.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * B3-05: Provider 自定义请求模板与响应路径工具。
 *
 * - [renderRequestTemplate]: 把 `{{messages}}` / `{{model}}` 等占位符替换为 JSON 值。
 * - [extractByPath]: 提取非 OpenAI 标准响应结构中的字段,支持 `$.choices[0].message.content`。
 */
object ProviderTemplateEngine {

    private val PLACEHOLDER = Regex("""("?)\{\{\s*([\w.]+)\s*}}("?)""")

    fun renderRequestTemplate(template: String, variables: Map<String, JsonElement>): String {
        if (template.isBlank()) return template
        return PLACEHOLDER.replace(template) { match ->
            val leadingQuote = match.groupValues[1]
            val key = match.groupValues[2]
            val trailingQuote = match.groupValues[3]
            val value = variables[key] ?: return@replace match.value
            if (value is JsonPrimitive && value.isString &&
                (leadingQuote == "\"" || trailingQuote == "\"")
            ) {
                leadingQuote + value.content + trailingQuote
            } else {
                leadingQuote + value.toString() + trailingQuote
            }
        }
    }

    fun extractByPath(json: JsonElement, path: String): JsonElement? {
        if (path.isBlank()) return json
        var current: JsonElement? = json
        val normalized = path.removePrefix("$").removePrefix(".")
        val tokens = Regex("""[^.\[]+|\[\d+\]""").findAll(normalized).map { it.value }.toList()
        for (token in tokens) {
            current = when {
                token.startsWith("[") -> {
                    val index = token.removeSurrounding("[", "]").toIntOrNull() ?: return null
                    (current as? JsonArray)?.getOrNull(index)
                }
                else -> (current as? JsonObject)?.get(token)
            } ?: return null
        }
        return current
    }
}
