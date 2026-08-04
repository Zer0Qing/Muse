package io.zer0.ai.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderTemplateEngineTest {

    @Test
    fun `renderRequestTemplate replaces quoted and unquoted placeholders`() {
        val variables = mapOf(
            "model" to JsonPrimitive("gpt-4"),
            "messages" to buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", "hi") })
            },
        )
        val rendered = ProviderTemplateEngine.renderRequestTemplate(
            """{"model":"{{model}}","messages":{{messages}}}""",
            variables,
        )
        assertEquals(
            """{"model":"gpt-4","messages":[{"role":"user","content":"hi"}]}""",
            rendered,
        )
        val unquoted = ProviderTemplateEngine.renderRequestTemplate(
            """{"model":{{model}},"stream":{{stream}}}""",
            variables + ("stream" to JsonPrimitive(false)),
        )
        assertEquals("""{"model":"gpt-4","stream":false}""", unquoted)
    }

    @Test
    fun `extractByPath supports nested object and array index`() {
        val json = buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject { put("message", buildJsonObject { put("content", "answer") }) })
            })
        }
        val extracted = ProviderTemplateEngine.extractByPath(json, "$.choices[0].message.content")
        assertEquals(JsonPrimitive("answer"), extracted)
    }

    @Test
    fun `extractByPath returns null for missing path`() {
        val json = buildJsonObject { put("a", JsonPrimitive(1)) }
        assertNull(ProviderTemplateEngine.extractByPath(json, "$.missing"))
    }
}
