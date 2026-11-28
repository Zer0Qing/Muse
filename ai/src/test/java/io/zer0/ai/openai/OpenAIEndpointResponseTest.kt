package io.zer0.ai.openai

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIEndpointResponseTest {
    @Test
    fun `html content type is diagnosed as an api route error`() {
        val message = openAiHtmlEndpointHint("text/html; charset=utf-8", "<!doctype html><html><title>New API</title></html>")

        assertNotNull(message)
        assertTrue(message!!.contains("HTML page instead of OpenAI-compatible JSON"))
        assertTrue(message.contains("/v1"))
    }

    @Test
    fun `html payload is detected even when content type is missing`() {
        assertNotNull(openAiHtmlEndpointHint("", "  <html><body>login</body></html>"))
    }

    @Test
    fun `2xx html page gets an actionable sse hint`() {
        val hint = explainSseHtmlFailure(
            contentType = "text/html; charset=utf-8",
            body = "<!doctype html><html><title>Web protection</title></html>",
            throwableMessage = "Invalid content-type: text/html; charset=utf-8",
        )

        assertNotNull(hint)
        assertTrue(hint!!.contains("/v1"))
        assertTrue("要告诉用户这可能只是没实现 /responses", hint.contains("/chat/completions"))
    }

    @Test
    fun `json sse failure keeps the original error`() {
        assertNull(
            explainSseHtmlFailure(
                contentType = "application/json",
                body = "{\"error\":\"boom\"}",
                throwableMessage = "stream closed",
            ),
        )
    }

    @Test
    fun `json response is not classified as html`() {
        assertNull(openAiHtmlEndpointHint("application/json; charset=utf-8", "{\"data\":[]}"))
    }
}
