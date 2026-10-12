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

    @Test
    fun `sensenova declares an exclusive temperature upper bound`() {
        // 回归:商汤 SenseNova 的 temperature 为开区间 [0.0, 2.0),2.0 会被 400 拒绝。
        val compat = ProviderCompatRules.resolve(
            ProviderType.OPENAI,
            "https://token.sensenova.cn/v1",
            "deepseek-v4-flash",
        )

        assertEquals(2.0f, compat.maxTemperatureExclusive!!, 0f)
    }

    @Test
    fun `ordinary providers keep the generic temperature range`() {
        val compat = ProviderCompatRules.resolve(ProviderType.OPENAI, siliconFlow, "Qwen/Qwen3.5-4B")

        assertEquals(null, compat.maxTemperatureExclusive)
    }
}
