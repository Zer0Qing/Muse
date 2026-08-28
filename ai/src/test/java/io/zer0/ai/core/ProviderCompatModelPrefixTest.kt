package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProviderCompatModelPrefixTest {

    private val siliconFlow = "https://api.siliconflow.cn/v1"

    @Test
    fun `qwen prefix is removed before thinking format detection`() {
        val compat = ProviderCompatRules.resolve(
            ProviderType.OPENAI,
            siliconFlow,
            "Qwen/Qwen3.5-4B",
        )

        assertEquals(ThinkingFormat.QWEN, compat.thinkingFormat)
    }

    @Test
    fun `deepseek provider prefix is removed before model compatibility detection`() {
        val compat = ProviderCompatRules.resolve(
            ProviderType.OPENAI,
            siliconFlow,
            "deepseek-ai/DeepSeek-R1",
        )

        assertEquals(ThinkingFormat.DEEPSEEK, compat.thinkingFormat)
        assertFalse(compat.supportsToolCalling)
    }

    @Test
    fun `zhipu prefix is removed before glm thinking detection`() {
        val compat = ProviderCompatRules.resolve(
            ProviderType.OPENAI,
            siliconFlow,
            "zhipu/GLM-4-thinking",
        )

        assertEquals(ThinkingFormat.ZHIPU, compat.thinkingFormat)
    }
}
