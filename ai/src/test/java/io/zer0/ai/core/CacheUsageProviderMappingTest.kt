package io.zer0.ai.core

import io.zer0.ai.anthropic.AnthropicUsage
import io.zer0.ai.anthropic.anthropicUsageTokens
import io.zer0.ai.gemini.GeminiUsageMetadata
import io.zer0.ai.gemini.toUsageTokens as geminiUsageTokens
import io.zer0.ai.openai.OpenAICompletionPromptTokensDetails
import io.zer0.ai.openai.OpenAICompletionUsage
import io.zer0.ai.openai.toUsageTokens as openAIUsageTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 各 provider 的 usage 字段落到 [UsageTokens] 的哪个分项。
 */
class CacheUsageProviderMappingTest {

    @Test
    fun `anthropic cache read and cache creation map to separate fields`() {
        val usage =
            anthropicUsageTokens(
                AnthropicUsage(
                    input_tokens = 100,
                    output_tokens = 50,
                    cache_read_input_tokens = 1_000,
                    cache_creation_input_tokens = 200,
                ),
                outputTokens = 50,
            )

        // input_tokens 本身不含缓存部分，口径保持原样
        assertEquals(100, usage.promptTokens)
        assertEquals(50, usage.completionTokens)
        assertEquals(1_000, usage.cachedTokens)
        assertEquals(1_000, usage.cacheReadTokens)
        assertEquals(200, usage.cacheWriteTokens)
        assertEquals(1_200, usage.cachedTokensTotal)
        assertEquals(150, usage.total)
        // Anthropic 的 input_tokens 不含缓存，计价时要声明口径
        assertFalse("Anthropic 的 prompt 不含缓存部分", usage.promptTokensIncludeCache)
    }

    @Test
    fun `anthropic missing usage maps to zeros`() {
        val usage = anthropicUsageTokens(null, outputTokens = 7)

        assertEquals(0, usage.promptTokens)
        assertEquals(7, usage.completionTokens)
        assertEquals(0, usage.cacheReadTokens)
        assertEquals(0, usage.cacheWriteTokens)
        assertFalse("即使 usage 缺失，Anthropic 的 prompt 口径仍是「不含缓存」", usage.promptTokensIncludeCache)
    }

    @Test
    fun `openai cached tokens are read only and write stays zero`() {
        val usage =
            OpenAICompletionUsage(
                promptTokens = 1_000,
                completionTokens = 10,
                promptTokensDetails = OpenAICompletionPromptTokensDetails(cachedTokens = 800),
            ).openAIUsageTokens()

        assertEquals(800, usage.cachedTokens)
        assertEquals(800, usage.cacheReadTokens)
        assertEquals(0, usage.cacheWriteTokens)
        // OpenAI 的 prompt_tokens 含缓存命中，计价时相减
        assertTrue(usage.promptTokensIncludeCache)
    }

    @Test
    fun `gemini cached content tokens are read only and write stays zero`() {
        val usage =
            GeminiUsageMetadata(
                promptTokenCount = 1_000,
                candidatesTokenCount = 20,
                cachedContentTokenCount = 600,
            ).geminiUsageTokens()

        assertEquals(600, usage.cachedTokens)
        assertEquals(600, usage.cacheReadTokens)
        assertEquals(0, usage.cacheWriteTokens)
        // Gemini 的 promptTokenCount 含缓存命中
        assertTrue(usage.promptTokensIncludeCache)
    }
}
