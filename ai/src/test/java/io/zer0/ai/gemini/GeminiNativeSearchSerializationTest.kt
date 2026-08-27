package io.zer0.ai.gemini

import io.zer0.common.AppJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiNativeSearchSerializationTest {
    @Test
    fun `google search tool serializes with empty google_search object`() {
        val json = AppJson.encodeToString(
            GeminiRequest(
                contents = emptyList(),
                tools = listOf(GeminiTool(googleSearch = GeminiGoogleSearch())),
            ),
        )
        val root = AppJson.parseToJsonElement(json).jsonObject
        val tool = root["tools"]!!.jsonArray.first().jsonObject
        assertTrue(tool["google_search"] is JsonObject)
        assertTrue(tool["functionDeclarations"] == null)
    }
}
