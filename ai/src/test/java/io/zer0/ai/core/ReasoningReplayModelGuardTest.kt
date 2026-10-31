package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v2.6.14: 思考块回放的同模型守卫。
 *
 * 锁定三条契约：
 *  1. 历史思考块来自另一个模型时，一律剥离（不外发给当前模型）；
 *  2. 同模型时原样保留（回放链路不受影响）；
 *  3. 历史未记录产出模型（旧数据）时保持原样，不做无证据的剥离。
 */
class ReasoningReplayModelGuardTest {

    private fun assistant(
        modelId: String?,
        reasoning: String? = "thinking text",
        signature: String? = null,
        encrypted: String? = null,
    ): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        content = "answer",
        reasoning = reasoning,
        thinkingSignature = signature,
        thinkingEncryptedContent = encrypted,
        modelId = modelId,
    )

    private fun normalize(messages: List<UIMessage>, modelId: String): List<UIMessage> =
        ProviderPayloadNormalizer.normalizeMessages(messages, Model(id = modelId, providerId = "test"))

    @Test
    fun `cross model thinking is stripped`() {
        val history = assistant(modelId = "claude-sonnet-4-5", signature = "sig-claude")
        val result = normalize(listOf(history), "deepseek-v4").single()

        assertNull("跨模型时 reasoning 不应外发", result.reasoning)
        assertNull("跨模型时 signature 不应外发", result.thinkingSignature)
        assertNull("跨模型时 encrypted_content 不应外发", result.thinkingEncryptedContent)
        assertEquals("正文必须原样保留", "answer", result.content)
        assertEquals("产出模型记录保留（供 UI/排障）", "claude-sonnet-4-5", result.modelId)
    }

    @Test
    fun `same model thinking is kept for replay`() {
        val history = assistant(modelId = "claude-sonnet-4-5", signature = "sig-claude")
        val result = normalize(listOf(history), "claude-sonnet-4-5").single()

        assertEquals("thinking text", result.reasoning)
        assertEquals("sig-claude", result.thinkingSignature)
    }

    @Test
    fun `history without recorded model is left untouched`() {
        val history = assistant(modelId = null, signature = "sig-legacy")
        val result = normalize(listOf(history), "claude-sonnet-4-5").single()

        assertEquals("来源未知时不剥离", "thinking text", result.reasoning)
        assertEquals("sig-legacy", result.thinkingSignature)
    }

    @Test
    fun `model id comparison tolerates channel label and case`() {
        val history = assistant(modelId = "[ais]Claude-Sonnet-4-5")
        val result = normalize(listOf(history), "claude-sonnet-4-5").single()

        assertEquals("归一化后视为同模型", "thinking text", result.reasoning)
    }

    @Test
    fun `aggregator prefix still compares as same model`() {
        val history = assistant(modelId = "openrouter/anthropic/claude-sonnet-4-5")
        val result = normalize(listOf(history), "claude-sonnet-4-5").single()

        assertEquals("剥前缀后同模型，保留回放", "thinking text", result.reasoning)
    }

    @Test
    fun `cross model assistant without reasoning payload is untouched`() {
        val plain = UIMessage(role = MessageRole.ASSISTANT, content = "answer", modelId = "gpt-5")
        val result = normalize(listOf(plain), "deepseek-v4").single()

        assertEquals(plain, result)
    }

    @Test
    fun `input list is not mutated`() {
        val history = assistant(modelId = "claude-sonnet-4-5", signature = "sig-claude")
        val input = listOf(history)
        normalize(input, "deepseek-v4")

        assertEquals("thinking text", history.reasoning)
        assertEquals("sig-claude", history.thinkingSignature)
    }
}
