package io.zer0.ai.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端点候选归一化的边界：宁可少猜，也不能把用户本来正确的地址改坏。
 */
class OpenAiEndpointCandidatesTest {

    @Test
    fun `plain host gets a v1 variant but keeps the original first`() {
        assertEquals(
            listOf("https://relay.example.com", "https://relay.example.com/v1"),
            OpenAiEndpointCandidates.baseUrls("https://relay.example.com"),
        )
    }

    @Test
    fun `already versioned base url is not doubled`() {
        assertEquals(
            listOf("https://relay.example.com/v1"),
            OpenAiEndpointCandidates.baseUrls("https://relay.example.com/v1/"),
        )
    }

    @Test
    fun `pasted full endpoint is reduced back to a base url`() {
        assertEquals(
            listOf("https://relay.example.com/v1"),
            OpenAiEndpointCandidates.baseUrls("https://relay.example.com/v1/chat/completions"),
        )
        assertEquals(
            listOf("https://relay.example.com", "https://relay.example.com/v1"),
            OpenAiEndpointCandidates.baseUrls("https://relay.example.com/responses"),
        )
    }

    @Test
    fun `blank input yields no candidates`() {
        assertTrue(OpenAiEndpointCandidates.baseUrls("   ").isEmpty())
    }

    @Test
    fun `protocol probes cover four protocols and both v1 variants`() {
        val probes = OpenAiEndpointCandidates.protocolProbes("https://relay.example.com")
        // 4 协议 × 2 地址 = 8 个候选，去重后仍为 8
        assertEquals(8, probes.size)
        assertEquals(
            OpenAiEndpointCandidates.ProtocolKind.OPENAI_CHAT,
            probes.first().protocol,
        )
        assertTrue(
            "顺序按命中概率：OpenAI 兼容必须排在第一个",
            probes.take(2).all { it.protocol == OpenAiEndpointCandidates.ProtocolKind.OPENAI_CHAT },
        )
        assertEquals(
            setOf(
                OpenAiEndpointCandidates.ProtocolKind.OPENAI_CHAT,
                OpenAiEndpointCandidates.ProtocolKind.OPENAI_RESPONSES,
                OpenAiEndpointCandidates.ProtocolKind.ANTHROPIC,
                OpenAiEndpointCandidates.ProtocolKind.GEMINI,
            ),
            probes.map { it.protocol }.toSet(),
        )
        assertEquals(setOf("https://relay.example.com", "https://relay.example.com/v1"), probes.map { it.baseUrl }.toSet())
    }

    @Test
    fun `missing endpoint signals are narrow`() {
        assertTrue(OpenAiEndpointCandidates.looksLikeMissingEndpoint(404, null, null))
        assertTrue(OpenAiEndpointCandidates.looksLikeMissingEndpoint(200, "text/html; charset=utf-8", null))
        assertTrue(
            OpenAiEndpointCandidates.looksLikeMissingEndpoint(
                200,
                null,
                "Invalid content-type: text/html; charset=utf-8",
            ),
        )
        assertFalse("401 是账号问题，换端点没用", OpenAiEndpointCandidates.looksLikeMissingEndpoint(401, null, null))
        assertFalse("402 是余额问题", OpenAiEndpointCandidates.looksLikeMissingEndpoint(402, null, null))
        assertFalse(OpenAiEndpointCandidates.looksLikeMissingEndpoint(429, null, null))
    }
}
