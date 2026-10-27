package io.zer0.muse.tools

import io.zer0.common.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Boundary for values that are safe to retain in tool cards, audit tables, and recovery files.
 *
 * The in-memory tool loop still receives the original arguments/results. Only persistence and
 * diagnostics use this policy, so a current request keeps its protocol semantics while a later
 * history view does not become a secret store.
 */
internal object ToolDataPrivacy {
    private const val REDACTED = "[REDACTED]"

    private val sensitiveArgumentTools = setOf(
        "browser_type",
        "clipboard_write",
        "input_inject",
        "screen_input",
        "virtual_screen_input",
        "settings_put",
        "send_sms",
        "send_email",
    )

    private val sensitiveResultTools = setOf(
        "clipboard_read",
        "generate_password",
        "settings_get",
        "get_recent_notifications",
        "get_contacts_list",
        "logcat_tail",
    )

    private val sensitiveKeyPattern = Regex(
        """(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|authorization|cookie|password|passwd|secret|private[_-]?key|jwt|pin|webhook|token)""",
    )
    private val sensitiveTextKeys = setOf("text", "content", "input", "body", "value")

    fun safeArgumentsForPersistence(toolName: String, arguments: String): String {
        val parsed = runCatching { AppJson.parseToJsonElement(arguments) }.getOrNull()
            ?: return "[REDACTED malformed arguments length=${arguments.length}]"
        return redactJson(toolName, parsed, parentKey = null).toString()
    }

    fun safeArgumentsForPreview(toolName: String, arguments: String, maxChars: Int = 200): String =
        safeArgumentsForPersistence(toolName, arguments).take(maxChars)

    fun safeResultForPersistence(toolName: String, result: String): String {
        if (toolName in sensitiveResultTools) {
            return "[tool result omitted from persisted history: ${result.length} chars]"
        }
        return redactText(result)
    }

    private fun redactJson(toolName: String, element: JsonElement, parentKey: String?): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) ->
                val normalized = key.lowercase().replace("-", "_")
                val redact = sensitiveKeyPattern.containsMatchIn(normalized) ||
                    (toolName in sensitiveArgumentTools && normalized in sensitiveTextKeys)
                put(key, if (redact) JsonPrimitive(REDACTED) else redactJson(toolName, value, key))
            }
        }
        is JsonArray -> buildJsonArray {
            element.forEach { add(redactJson(toolName, it, parentKey)) }
        }
        is JsonPrimitive -> {
            if (toolName in sensitiveArgumentTools && parentKey != null &&
                parentKey.lowercase().replace("-", "_") in sensitiveTextKeys
            ) {
                JsonPrimitive(REDACTED)
            } else {
                element
            }
        }
        else -> element
    }

    private fun redactText(text: String): String {
        var value = text
        value = Regex(
            """(?i)(api[\s_-]?key|access[\s_-]?token|refresh[\s_-]?token|authorization|cookie|password|passwd|secret|private[\s_-]?key|jwt|pin|webhook|token)\s*[:=]?\s*["']?[A-Za-z0-9._~+/=-]{6,}""",
        ).replace(value) { "${it.groupValues[1]}=$REDACTED" }
        value = Regex("""(?i)\b(?:sk|rk|pk|ak)-[A-Za-z0-9][A-Za-z0-9._-]{5,}\b""")
            .replace(value, REDACTED)
        value = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{8,}\b""")
            .replace(value, "Bearer $REDACTED")
        return value
    }
}
