package io.zer0.muse.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Base64
import java.util.UUID

/** Converts protocol content blocks to bounded text that is safe for model context and chat history. */
internal object McpToolResultPresentation {
    private const val MAX_BASE64_CHARS_FOR_SIZE = 1_000_000
    private const val MAX_PREVIEW_CHARS = 175_000
    private const val MAX_PREVIEW_COUNT = 6
    private val previews = LinkedHashMap<String, ByteArray>()

    fun previewBytes(id: String): ByteArray? = synchronized(previews) { previews[id] }

    private fun rememberImage(mimeType: String, data: String): String? {
        if (data.length > MAX_PREVIEW_CHARS) return null
        val bytes = runCatching { Base64.getDecoder().decode(data) }.getOrNull() ?: return null
        val matchesFormat = when (mimeType) {
            "image/png" ->
                bytes.size >= 8 &&
                    bytes.copyOf(8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            "image/jpeg" -> bytes.size >= 3 && bytes[0].toInt() == -1 &&
                bytes[1].toInt() == -40 && bytes[2].toInt() == -1
            "image/webp" -> bytes.size >= 12 && bytes.decodeToString(0, 4) == "RIFF" &&
                bytes.decodeToString(8, 12) == "WEBP"
            else -> false
        }
        if (!matchesFormat) return null
        val id = UUID.randomUUID().toString()
        synchronized(previews) {
            if (previews.size == MAX_PREVIEW_COUNT) previews.remove(previews.keys.first())
            previews[id] = bytes
        }
        return id
    }

    fun formatToolResult(result: McpToolCallResult): String {
        val parts = buildList {
            result.content.mapNotNullTo(this) { formatContentBlock(it) }
            result.structuredContent?.let { add(it.toString()) }
        }
        return parts.joinToString("\n").ifBlank { "(MCP server returned no content)" }
    }

    private fun formatContentBlock(element: JsonElement): String {
        val block = element as? JsonObject ?: return element.toString()
        val type = block.string("type") ?: return element.toString()
        return when (type) {
            "text" -> block.string("text") ?: ""
            "image" -> formatBinaryBlock("image", block.string("mimeType"), block.string("data"))
            "audio" -> formatBinaryBlock("audio", block.string("mimeType"), block.string("data"))
            "resource_link" -> formatResourceLink(block)
            "resource" -> formatEmbeddedResource(block)
            else -> element.toString()
        }
    }

    private fun formatBinaryBlock(kind: String, rawMimeType: String?, data: String?): String {
        val mimeType = allowedMimeType(kind, rawMimeType)
        val size = data?.let(::base64ByteCount)
        val sizeText = size?.let { "about $it bytes" } ?: "size unavailable"
        val availability = when (kind) {
            "image" -> data?.let { rememberImage(mimeType, it) }?.let { "preview $it" } ?: "preview unavailable"
            "audio" -> "playback unavailable"
            else -> "binary omitted"
        }
        return "[MCP $kind: $mimeType, $sizeText; $availability]"
    }

    private fun formatResourceLink(block: JsonObject): String {
        val name = block.string("name")?.takeIf(String::isNotBlank)
        val mimeType = block.string("mimeType")?.takeIf(String::isNotBlank)
        val uri = block.string("uri")?.let(::portableResourceUri)
        val description = block.string("description")?.takeIf(String::isNotBlank)
        return buildList {
            add("[MCP resource${name?.let { ": $it" }.orEmpty()}]")
            mimeType?.let { add("Type: $it") }
            uri?.let { add("URI: $it") }
            description?.let(::add)
        }.joinToString("\n")
    }

    private fun formatEmbeddedResource(block: JsonObject): String {
        val resource = block["resource"] as? JsonObject ?: return block.toString()
        val uri = resource.string("uri")?.let(::portableResourceUri)
        val mimeType = resource.string("mimeType")?.takeIf(String::isNotBlank)
        val text = resource.string("text")?.takeIf(String::isNotBlank)
        val blob = resource.string("blob")
        val header = buildList {
            add("[MCP resource]")
            mimeType?.let { add("Type: $it") }
            uri?.let { add("URI: $it") }
        }
        return (header + listOfNotNull(text, blob?.let { formatBinaryBlock("resource", mimeType, it) }))
            .joinToString("\n")
    }

    private fun allowedMimeType(kind: String, value: String?): String {
        val normalized = value?.substringBefore(';')?.trim()?.lowercase()
        val allowed = when (kind) {
            "image" -> setOf("image/png", "image/jpeg", "image/webp")
            "audio" -> setOf("audio/wav", "audio/mpeg", "audio/ogg", "audio/webm", "audio/mp4")
            "resource" -> return normalized?.takeIf { it.matches(MIME_TYPE_REGEX) } ?: "unknown type"
            else -> emptySet()
        }
        return normalized?.takeIf { it in allowed } ?: "$kind/unknown"
    }

    private fun base64ByteCount(data: String): Int? {
        if (data.length > MAX_BASE64_CHARS_FOR_SIZE) return null
        return Base64SizeScanner().scan(data)
    }

    private class Base64SizeScanner {
        private var symbolCount = 0
        private var paddingCount = 0
        private var sawPadding = false

        fun scan(data: String): Int? {
            for (char in data) {
                if (!accept(char)) return null
            }
            val paddingIsValid = when (paddingCount) {
                0 -> symbolCount % 4 != 1
                1 -> symbolCount % 4 == 3
                2 -> symbolCount % 4 == 2
                else -> false
            }
            return if (paddingIsValid) symbolCount * 6 / 8 else null
        }

        private fun accept(char: Char): Boolean = when {
            char.isWhitespace() -> true
            char == '=' -> {
                sawPadding = true
                paddingCount++
                paddingCount <= 2
            }
            isBase64Symbol(char) && !sawPadding -> {
                symbolCount++
                true
            }
            else -> false
        }

        private fun isBase64Symbol(char: Char): Boolean =
            char in 'A'..'Z' ||
                char in 'a'..'z' ||
                char in '0'..'9' ||
                char == '+' ||
                char == '/' ||
                char == '-' ||
                char == '_'
    }

    private fun portableResourceUri(value: String): String? = runCatching {
        val uri = java.net.URI(value)
        if (uri.isOpaque) return "${uri.scheme}:"
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return "$scheme:"
        java.net.URI(scheme, null, host, uri.port, uri.path, null, null).toASCIIString()
    }.getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private val MIME_TYPE_REGEX = Regex("[a-z0-9.+-]+/[a-z0-9.+-]+")
}
