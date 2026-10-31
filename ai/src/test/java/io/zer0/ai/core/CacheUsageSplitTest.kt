package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存读/写拆分：字段语义、计价倍数、旧数据兼容。
 */
class CacheUsageSplitTest {

    @Test
    fun `cache read and write are separate fields`() {
        val usage = UsageTokens(promptTokens = 100, completionTokens = 50, cachedTokens = 1_000, cacheWriteTokens = 200)

        assertEquals(1_000, usage.cacheReadTokens)
        assertEquals(200, usage.cacheWriteTokens)
        // 兼容视图：拆分前 cachedTokens 装的是读 + 写
        assertEquals(1_200, usage.cachedTokensTotal)
        // 既有口径不变：total 仍只看 prompt + completion
        assertEquals(150, usage.total)
    }

    @Test
    fun `legacy usage with only cachedTokens is treated as cache read`() {
        // 旧记录只有合并值（读 + 写）落在 cachedTokens 上，读取时按「读」解释，写入视为 0
        val legacy = UsageTokens(promptTokens = 1_000_000, cachedTokens = 400_000)

        assertEquals(400_000, legacy.cacheReadTokens)
        assertEquals(0, legacy.cacheWriteTokens)
        assertEquals(400_000, legacy.cachedTokensTotal)

        val info =
            KnownModels.KnownModelInfo(
                pricingPromptPer1M = 3.0,
                pricingCompletionPer1M = 15.0,
                pricingCachedInputPer1M = 0.3,
            )
        val cost = TokenCostCalculator.cost(TokenCostCalculator.fromUsageTokens(legacy), info)
        // 600k 普通输入 * 3.0 + 400k 缓存读 * 0.3 = 1.8 + 0.12
        assertTrue(cost.known)
        assertEquals(1.92, cost.usd, 1e-9)
    }

    @Test
    fun `cache write is priced at input multiplier for anthropic models`() {
        val usage = UsageTokens(cacheWriteTokens = 1_000_000)
        val cost = TokenCostCalculator.costFor("anthropic", "claude-sonnet-4", usage)

        assertTrue(cost.known)
        // claude-sonnet-4 输入价 3.0，缓存写入 1.25 倍 = 3.75
        assertEquals(3.75, cost.usd, 1e-9)
    }

    @Test
    fun `cache write multiplier applies after stripping aggregator prefix`() {
        val usage = UsageTokens(cacheWriteTokens = 1_000_000)
        val direct = TokenCostCalculator.costFor("anthropic", "claude-sonnet-4", usage)
        val prefixed = TokenCostCalculator.costFor("openrouter", "anthropic/claude-sonnet-4", usage)

        assertTrue(prefixed.known)
        assertEquals(direct.usd, prefixed.usd, 1e-9)
        assertEquals(3.75, prefixed.usd, 1e-9)
    }

    @Test
    fun `cache write price is not invented for non anthropic models`() {
        val info = KnownModels.lookupProvider("openrouter", "gpt-4o")

        assertNotNull(info)
        assertNotNull(info!!.pricingPromptPer1M)
        val priced = TokenCostCalculator.withEstimatedCacheWritePrice("gpt-4o", info)
        assertEquals(null, priced.pricingCacheWritePer1M)
    }

    @Test
    fun `anthropic prompt tokens are not clamped when cache is large`() {
        // Anthropic 的 input_tokens 是非缓存输入，缓存读/写单列；一旦按「prompt 含缓存」相减，
        // 5000+2000 的缓存量会把 1000 普通输入夹到 0，输入费直接消失。
        val anthropic =
            UsageTokens(
                promptTokens = 1_000,
                cachedTokens = 5_000,
                cacheWriteTokens = 2_000,
                promptTokensIncludeCache = false,
            )
        val cost = TokenCostCalculator.costFor("anthropic", "claude-sonnet-4", anthropic)

        assertTrue(cost.known)
        // 1000 输入 * 3.0 + 5000 缓存读 * 3.0（无缓存读单价时按输入价）+ 2000 写入 * 3.75
        val expected = 6_000 / 1_000_000.0 * 3.0 + 2_000 / 1_000_000.0 * 3.75
        assertEquals(expected, cost.usd, 1e-12)

        // 同样数字若按「prompt 含缓存」算（OpenAI/Gemini 口径），普通输入被夹成 0，金额更小
        val clamped = TokenCostCalculator.costFor(
            "anthropic",
            "claude-sonnet-4",
            anthropic.copy(promptTokensIncludeCache = true),
        )
        assertTrue("相减口径会漏掉输入费，金额应更小", clamped.usd < cost.usd)
    }

    @Test
    fun `openai style prompt still subtracts cache from prompt`() {
        val usage = UsageTokens(promptTokens = 1_000_000, cachedTokens = 1_000_000)
        val info =
            KnownModels.KnownModelInfo(
                pricingPromptPer1M = 3.0,
                pricingCompletionPer1M = 15.0,
                pricingCachedInputPer1M = 0.3,
            )
        // 默认 promptTokensIncludeCache = true：全部命中缓存时普通输入为 0
        val cost = TokenCostCalculator.cost(TokenCostCalculator.fromUsageTokens(usage), info)

        assertEquals(0.3, cost.usd, 1e-9)
    }

    @Test
    fun `explicit cache write price in table wins over multiplier`() {
        val info =
            KnownModels.KnownModelInfo(
                pricingPromptPer1M = 3.0,
                pricingCompletionPer1M = 15.0,
                pricingCacheWritePer1M = 9.0,
            )
        val priced = TokenCostCalculator.withEstimatedCacheWritePrice("claude-sonnet-4", info)

        assertEquals(9.0, priced.pricingCacheWritePer1M!!, 1e-9)
    }

    @Test
    fun `missing input price leaves cache write unpriced`() {
        val info = KnownModels.KnownModelInfo(pricingPromptPer1M = null, pricingCompletionPer1M = 15.0)
        val priced = TokenCostCalculator.withEstimatedCacheWritePrice("claude-sonnet-4", info)

        assertEquals(null, priced.pricingCacheWritePer1M)
    }
}
