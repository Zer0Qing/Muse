package io.zer0.ai.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelModalityCorrectionTest {

    @Test
    fun geminiModelsSupportVision() {
        assertTrue(ModelRegistry.supportsVisionInput("gemini-2.5-pro"))
        assertTrue(ModelRegistry.supportsVisionInput("gemini-2.5-flash"))
    }

    @Test
    fun deepseekTextModelsDoNotSupportVision() {
        assertFalse(ModelRegistry.supportsVisionInput("deepseek-chat"))
        assertFalse(ModelRegistry.supportsVisionInput("deepseek-reasoner"))
        assertFalse(ModelRegistry.supportsVisionInput("deepseek-v4-flash"))
    }
}
