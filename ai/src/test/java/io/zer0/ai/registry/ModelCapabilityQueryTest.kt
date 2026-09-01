package io.zer0.ai.registry

import io.zer0.ai.core.ProviderCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2.2/M2.8: 能力预检门面测试 — 三态区分与已知模型能力。
 */
class ModelCapabilityQueryTest {

    @Test
    fun `known vision tool reasoning model resolves all dimensions`() {
        val snapshot = ModelCapabilityQuery.snapshot(
            modelId = "gpt-4o",
            compat = ProviderCompat(supportsJsonMode = true),
            providerSupportsNonStreaming = true,
        )
        assertEquals(CapabilitySupport.SUPPORTED, snapshot.textInput)
        assertEquals(CapabilitySupport.SUPPORTED, snapshot.visionInput)
        assertEquals(CapabilitySupport.SUPPORTED, snapshot.toolCalling)
        assertEquals(CapabilitySupport.UNSUPPORTED, snapshot.reasoning)
        assertEquals(CapabilitySupport.SUPPORTED, snapshot.streaming)
        assertEquals(CapabilitySupport.SUPPORTED, snapshot.nonStreaming)
        assertEquals(CapabilitySupport.SUPPORTED, snapshot.structuredOutput)
        assertTrue(snapshot.known)
    }

    @Test
    fun `unknown model returns unknown states instead of guesses`() {
        val snapshot = ModelCapabilityQuery.snapshot(
            modelId = "totally-made-up-model-9000",
            compat = null,
            providerSupportsNonStreaming = null,
        )
        assertFalse(snapshot.known)
        assertEquals(CapabilitySupport.UNKNOWN, snapshot.textInput)
        assertEquals(CapabilitySupport.UNKNOWN, snapshot.visionInput)
        assertEquals(CapabilitySupport.UNKNOWN, snapshot.toolCalling)
        assertEquals(CapabilitySupport.UNKNOWN, snapshot.reasoning)
        assertEquals(CapabilitySupport.UNKNOWN, snapshot.streaming)
        assertEquals(CapabilitySupport.UNKNOWN, snapshot.nonStreaming)
        assertEquals(CapabilitySupport.UNKNOWN, snapshot.structuredOutput)
    }

    @Test
    fun `aggregator prefixed model id still resolves capabilities`() {
        val snapshot = ModelCapabilityQuery.snapshot(
            modelId = "opencode-go/deepseek-v4",
        )
        assertTrue(snapshot.known)
    }

    @Test
    fun `structured output follows provider compat json mode`() {
        val withoutJson = ModelCapabilityQuery.snapshot(
            modelId = "gpt-4o",
            compat = ProviderCompat(supportsJsonMode = false),
        )
        assertEquals(CapabilitySupport.UNSUPPORTED, withoutJson.structuredOutput)
        // compat 缺省时结构化输出 UNKNOWN,不猜测
        val noCompat = ModelCapabilityQuery.snapshot(modelId = "gpt-4o")
        assertEquals(CapabilitySupport.UNKNOWN, noCompat.structuredOutput)
    }
}
