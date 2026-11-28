package io.zer0.muse.mcp

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolResultPresentationTest {
    @Test
    fun `plain text and structured content keep their text representation`() {
        val result = McpToolCallResult(
            content = listOf(Json.parseToJsonElement("""{"type":"text","text":"ready"}""")),
            structuredContent = Json.parseToJsonElement("""{"count":2}"""),
        )

        val formatted = McpToolResultPresentation.formatToolResult(result)

        assertTrue(formatted.startsWith("ready\n"))
        assertTrue(formatted.contains("{\"count\":2}"))
    }

    @Test
    fun `binary blocks become bounded typed summaries without payload data`() {
        val result = McpToolCallResult(
            content = listOf(
                Json.parseToJsonElement("""{"type":"image","mimeType":"image/png","data":"AQID"}"""),
                Json.parseToJsonElement("""{"type":"audio","mimeType":"audio/wav","data":"AQIDBA=="}"""),
            ),
        )

        val formatted = McpToolResultPresentation.formatToolResult(result)

        assertTrue(formatted.contains("[MCP image: image/png, about 3 bytes; preview unavailable]"))
        assertTrue(formatted.contains("[MCP audio: audio/wav, about 4 bytes; playback unavailable]"))
        assertFalse(formatted.contains("AQID"))
    }

    @Test
    fun `unsupported media mime and resource query credentials are not exposed`() {
        val result = McpToolCallResult(
            content = listOf(
                Json.parseToJsonElement("""{"type":"image","mimeType":"image/svg+xml","data":"AQID"}"""),
                Json.parseToJsonElement(
                    """{"type":"resource_link","name":"report","uri":"https://user:pass@example.invalid/r?token=private","mimeType":"text/plain"}""",
                ),
            ),
        )

        val formatted = McpToolResultPresentation.formatToolResult(result)

        assertTrue(formatted.contains("[MCP image: image/unknown"))
        assertTrue(formatted.contains("[MCP resource: report]"))
        assertTrue(formatted.contains("URI: https://example.invalid/r"))
        assertFalse(formatted.contains("user"))
        assertFalse(formatted.contains("private"))
        assertFalse(formatted.contains("pass"))
    }

    @Test
    fun `embedded resource keeps text and summarizes blob without embedding it`() {
        val result = McpToolCallResult(
            content = listOf(
                Json.parseToJsonElement(
                    """{"type":"resource","resource":{"uri":"db://schema/users","mimeType":"application/json","text":"{\"table\":\"users\"}","blob":"AQIDBA=="}}""",
                ),
            ),
        )

        val formatted = McpToolResultPresentation.formatToolResult(result)

        assertTrue(formatted.contains("{\"table\":\"users\"}"))
        assertTrue(formatted.contains("[MCP resource: application/json, about 4 bytes; binary omitted]"))
        assertFalse(formatted.contains("AQID"))
    }

    @Test
    fun `invalid base64 padding does not produce a byte size`() {
        val result = McpToolCallResult(
            content = listOf(
                Json.parseToJsonElement(
                    """{"type":"resource","resource":{"mimeType":"application/octet-stream","blob":"AQID=="}}""",
                ),
            ),
        )

        val formatted = McpToolResultPresentation.formatToolResult(result)

        assertTrue(formatted.contains("size unavailable"))
    }

    @Test
    fun `small image output exposes an opaque preview reference without making bytes part of tool text`() {
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aT1sAAAAASUVORK5CYII="
        val image = io.zer0.common.AppJson.parseToJsonElement(
            """{"type":"image","mimeType":"image/png","data":"$png"}""",
        )
        val formatted = McpToolResultPresentation.formatToolResult(McpToolCallResult(content = listOf(image)))
        assertFalse(formatted.contains(png))
        val id = formatted.substringAfter("; preview ").substringBefore(']')
        assertTrue(McpToolResultPresentation.previewBytes(id)?.isNotEmpty() == true)
    }
}
