package io.zer0.ai.openai

import io.zer0.common.AppJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsesNativeSearchSerializationTest {
    @Test
    fun `responses request serializes web search preview alongside function tool`() {
        val request = ResponsesRequest(
            model = "gpt-5",
            input = emptyList(),
            tools = listOf(
                ResponsesTool(type = "web_search_preview"),
                ResponsesTool(
                    type = "function",
                    name = "echo",
                    description = "Echo input",
                ),
            ),
        )
        val root = AppJson.parseToJsonElement(AppJson.encodeToString(request)).jsonObject
        val tools = root["tools"]!!.jsonArray
        assertEquals("web_search_preview", tools[0].jsonObject["type"]!!.toString().trim('"'))
        assertEquals("function", tools[1].jsonObject["type"]!!.toString().trim('"'))
        assertEquals("echo", tools[1].jsonObject["name"]!!.toString().trim('"'))
        assertTrue(tools[0].jsonObject["name"] == null)
    }
}
