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
            providerId = FreeModelConfig.FREE_PROVIDER_ID,
            baseUrl = "https://api.siliconflow.cn/v1",
            modelId = "THUDM/GLM-4-9B-0414",
            userApiKey = "sk-user",
        )
        assertNull(result)
    }

    @Test
    fun `resolveApiKey returns null for non siliconflow host`() {
        val result = FreeModelConfig.resolveApiKey(
            providerId = FreeModelConfig.FREE_PROVIDER_ID,
            baseUrl = "https://example.com/v1",
            modelId = "THUDM/GLM-4-9B-0414",
            userApiKey = "",
        )
        assertNull(result)
    }

    @Test
    fun `resolveApiKey returns null for non whitelisted model`() {
        val result = FreeModelConfig.resolveApiKey(
            providerId = FreeModelConfig.FREE_PROVIDER_ID,
            baseUrl = "https://api.siliconflow.cn/v1",
            modelId = "not-in-whitelist",
            userApiKey = "",
        )
        assertNull(result)
    }

    @Test
    fun `isFreeProvider only matches internal free provider with blank user key`() {
        assertTrue(
            FreeModelConfig.isFreeProvider(
                FreeModelConfig.FREE_PROVIDER_ID,
                "https://api.siliconflow.cn/v1",
                "",
            ),
        )
        assertFalse(
            FreeModelConfig.isFreeProvider(
                "preset_siliconflow",
                "https://api.siliconflow.cn/v1",
                "",
            ),
        )
        assertFalse(
            FreeModelConfig.isFreeProvider(
                FreeModelConfig.FREE_PROVIDER_ID,
                "https://api.siliconflow.cn/v1",
                "sk-user",
            ),
        )
        assertFalse(
            FreeModelConfig.isFreeProvider(
                FreeModelConfig.FREE_PROVIDER_ID,
                "https://example.com/v1",
                "",
            ),
        )
        assertTrue(
            FreeModelConfig.isFreeProvider(
                "legacy-provider",
                "https://api.siliconflow.cn/v1",
                "",
                hiddenFromSettings = true,
            ),
        )
    }

    @Test
    fun `fallback availability is consistent with whitelisted resolve`() {
        val resolved = FreeModelConfig.resolveApiKey(
            providerId = FreeModelConfig.FREE_PROVIDER_ID,
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

    @Test
    fun `regular siliconflow provider never receives free fallback`() {
        val result = FreeModelConfig.resolveApiKey(
            providerId = "preset_siliconflow",
            baseUrl = "https://api.siliconflow.cn/v1",
            modelId = "Qwen/Qwen3-8B",
            userApiKey = "",
        )
        assertNull(result)
    }

    @Test
    fun `hidden legacy provider can retain free fallback`() {
        val result = FreeModelConfig.resolveApiKey(
            providerId = "legacy-provider",
            baseUrl = "https://api.siliconflow.cn/v1",
            modelId = "Qwen/Qwen3-8B",
            userApiKey = "",
            hiddenFromSettings = true,
        )
        if (FreeModelConfig.isFallbackKeyAvailable()) {
            assertNotNull(result)
        } else {
            assertNull(result)
        }
    }
}
