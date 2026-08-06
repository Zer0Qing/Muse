package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-SEC-07: FreeModelConfig fallback key 可用性与解析测试。
 */
class FreeModelConfigTest {

    @Test
    fun `resolveApiKey skips fallback when user key present`() {
        val result = FreeModelConfig.resolveApiKey(
            baseUrl = "https://api.siliconflow.cn/v1",
            modelId = "THUDM/GLM-4-9B-0414",
            userApiKey = "sk-user",
        )
        assertNull(result)
    }

    @Test
    fun `resolveApiKey returns null for non siliconflow host`() {
        val result = FreeModelConfig.resolveApiKey(
            baseUrl = "https://example.com/v1",
            modelId = "THUDM/GLM-4-9B-0414",
            userApiKey = "",
        )
        assertNull(result)
    }

    @Test
    fun `resolveApiKey returns null for non whitelisted model`() {
        val result = FreeModelConfig.resolveApiKey(
            baseUrl = "https://api.siliconflow.cn/v1",
            modelId = "not-in-whitelist",
            userApiKey = "",
        )
        assertNull(result)
    }

    @Test
    fun `isFreeProvider only matches siliconflow with blank user key`() {
        assertTrue(FreeModelConfig.isFreeProvider("https://api.siliconflow.cn/v1", ""))
        assertFalse(FreeModelConfig.isFreeProvider("https://api.siliconflow.cn/v1", "sk-user"))
        assertFalse(FreeModelConfig.isFreeProvider("https://example.com/v1", ""))
    }

    @Test
    fun `fallback availability is consistent with whitelisted resolve`() {
        val resolved = FreeModelConfig.resolveApiKey(
            baseUrl = "https://api.siliconflow.cn/v1",
            modelId = "Qwen/Qwen3-8B",
            userApiKey = "",
        )
        if (FreeModelConfig.isFallbackKeyAvailable()) {
            assertNotNull(resolved)
            assertEquals(FreeModelConfig.FALLBACK_API_KEY, resolved)
        } else {
            assertNull(resolved)
        }
    }
}
