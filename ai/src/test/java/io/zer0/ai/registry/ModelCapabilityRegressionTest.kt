package io.zer0.ai.registry

import io.zer0.ai.core.ModelAbility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCapabilityRegressionTest {

    @Test
    fun `runtime registry identifies Qwen 3 5 vision tools and reasoning`() {
        val modalities = ModelRegistry.resolveInputModalities("Qwen/Qwen3.5-4B")
        val abilities = ModelRegistry.resolveAbilities("Qwen/Qwen3.5-4B")

        assertTrue("image" in modalities)
        assertTrue(ModelAbility.TOOL in abilities)
        assertTrue(ModelAbility.REASONING in abilities)
    }

    @Test
    fun `both registries identify DeepSeek OCR as vision only`() {
        assertTrue("image" in ModelRegistry.resolveInputModalities("DeepSeek-OCR"))
        assertTrue("image" in io.zer0.ai.core.ModelRegistry.lookupInputModalities("DeepSeek-OCR"))
        assertFalse(ModelAbility.TOOL in ModelRegistry.resolveAbilities("DeepSeek-OCR"))
        assertFalse(ModelAbility.REASONING in ModelRegistry.resolveAbilities("DeepSeek-OCR"))
        assertFalse(ModelAbility.TOOL in io.zer0.ai.core.ModelRegistry.lookupAbilities("DeepSeek-OCR"))
        assertFalse(ModelAbility.REASONING in io.zer0.ai.core.ModelRegistry.lookupAbilities("DeepSeek-OCR"))
    }

    @Test
    fun `inconsistent output limit is marked suspicious`() {
        val model = io.zer0.ai.core.Model(
            id = "gpt-4o",
            providerId = "relay",
            contextWindow = 1_000,
            maxOutputTokens = 2_000,
        )

        val enhanced = ModelRegistry.enhanceModel(model)

        assertEquals(io.zer0.ai.core.ModelVerification.SUSPICIOUS, enhanced.verification)
        assertEquals(1_000, enhanced.contextWindow)
        assertEquals(2_000, enhanced.maxOutputTokens)
    }

    @Test
    fun `consistent known model metadata remains verified`() {
        val enhanced = ModelRegistry.enhanceModel(
            io.zer0.ai.core.Model(id = "gpt-4o", providerId = "openai"),
        )

        assertEquals(io.zer0.ai.core.ModelVerification.VERIFIED, enhanced.verification)
    }

    @Test
    fun `both registries keep GLM 4 6 text tool reasoning without vision`() {
        val runtimeModalities = ModelRegistry.resolveInputModalities("GLM-4.6")
        val coreModalities = io.zer0.ai.core.ModelRegistry.lookupInputModalities("GLM-4.6")

        assertFalse("image" in runtimeModalities)
        assertFalse("image" in coreModalities)
        assertTrue(ModelAbility.TOOL in ModelRegistry.resolveAbilities("GLM-4.6"))
        assertTrue(ModelAbility.REASONING in ModelRegistry.resolveAbilities("GLM-4.6"))
        assertTrue(ModelAbility.TOOL in io.zer0.ai.core.ModelRegistry.lookupAbilities("GLM-4.6"))
        assertTrue(ModelAbility.REASONING in io.zer0.ai.core.ModelRegistry.lookupAbilities("GLM-4.6"))
    }
}
