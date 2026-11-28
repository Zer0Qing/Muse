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

    // ── 中转站前缀 / 分组标签 / 改名场景 ──────────────────────────────────
    // 背景:用户反馈「同一个中转站里，带前缀的模型用不了」。
    // 根因是四份前缀剥离实现各自维护白名单，其中两份没有 `/` 兜底，
    // 导致自定义前缀（如 myrelay/gpt-4o）查不到上下文窗口与输出上限。

    @Test
    fun `自定义前缀不影响上下文窗口识别`() {
        val plain = io.zer0.ai.core.ModelContextWindowRegistry.lookup("gpt-4o")
        assertEquals(128_000, plain)
        // 已知白名单前缀
        assertEquals(plain, io.zer0.ai.core.ModelContextWindowRegistry.lookup("openai/gpt-4o"))
        // 白名单外的自定义前缀 —— 修复前返回 null
        assertEquals(plain, io.zer0.ai.core.ModelContextWindowRegistry.lookup("myrelay/gpt-4o"))
        // 前导分组标签 —— 修复前返回 null
        assertEquals(plain, io.zer0.ai.core.ModelContextWindowRegistry.lookup("[ais]gpt-4o"))
        // 多级分组标签
        assertEquals(plain, io.zer0.ai.core.ModelContextWindowRegistry.lookup("[vip][ais]gpt-4o"))
    }

    @Test
    fun `自定义前缀不影响 KnownModels 元信息`() {
        val plain = io.zer0.ai.core.KnownModels.lookup("gpt-4o")
        assertEquals(128_000, plain?.contextWindow)
        assertEquals(16_384, plain?.maxOutputTokens)

        val prefixed = io.zer0.ai.core.KnownModels.lookup("myrelay/gpt-4o")
        assertEquals(plain?.contextWindow, prefixed?.contextWindow)
        assertEquals(plain?.maxOutputTokens, prefixed?.maxOutputTokens)
    }

    @Test
    fun `自定义前缀不影响 enrich 补全结果`() {
        val plain = io.zer0.ai.core.ModelRegistry.enrich(
            io.zer0.ai.core.Model(id = "gpt-4o", providerId = "relay"),
        )
        val prefixed = io.zer0.ai.core.ModelRegistry.enrich(
            io.zer0.ai.core.Model(id = "myrelay/gpt-4o", providerId = "relay"),
        )
        assertEquals(plain.contextWindow, prefixed.contextWindow)
        assertEquals(plain.maxOutputTokens, prefixed.maxOutputTokens)
        assertEquals(plain.inputModalities, prefixed.inputModalities)
        assertEquals(plain.supportsVision, prefixed.supportsVision)
    }

    @Test
    fun `完全改名的模型不应被前缀逻辑误伤`() {
        // 不含斜杠也不含方括号，剥前缀后仍是自身 —— 查不到属预期行为，
        // 这类模型只能靠上游声明能力或用户在「编辑模型能力」里手动设置。
        assertEquals("gpt4o-vip", io.zer0.ai.core.normalizeModelIdForLookup("gpt4o-vip"))
        assertEquals(null, io.zer0.ai.core.KnownModels.lookup("gpt4o-vip"))
    }

    @Test
    fun `归一化函数自身的行为`() {
        // 大小写与空白
        assertEquals("gpt-4o", io.zer0.ai.core.normalizeModelIdForLookup("  GPT-4o  "))
        // 白名单精确剥
        assertEquals("gpt-4o", io.zer0.ai.core.normalizeModelIdForLookup("openai/gpt-4o"))
        // 多段前缀优先走白名单，不会被截成 "xxx"
        assertEquals(
            "llama-3-70b",
            io.zer0.ai.core.normalizeModelIdForLookup("accounts/fireworks/models/llama-3-70b"),
        )
        // 白名单外的单段前缀走 `/` 兜底
        assertEquals("gpt-4o", io.zer0.ai.core.normalizeModelIdForLookup("myrelay/gpt-4o"))
        // 空串安全
        assertEquals("", io.zer0.ai.core.normalizeModelIdForLookup(""))
    }

    @Test
    fun `白名单剥完仍残留厂商段时继续取最后一段`() {
        // openrouter/deepseek/deepseek-v4 → 先剥 openrouter/ 得 deepseek/deepseek-v4,
        // 再取最后一段得 deepseek-v4。旧实现（提前 return）会停在 deepseek/deepseek-v4,
        // 导致 ModelReasoningReplay 识别不出思考系模型。
        assertEquals(
            "deepseek-v4",
            io.zer0.ai.core.normalizeModelIdForLookup("openrouter/deepseek/deepseek-v4"),
        )
        assertEquals(
            "kimi-k2.5",
            io.zer0.ai.core.normalizeModelIdForLookup("siliconflow/kimi-k2.5"),
        )
        // 多段白名单剥净后无斜杠，第二步应为空操作
        assertEquals(
            "llama-3-70b",
            io.zer0.ai.core.normalizeModelIdForLookup("accounts/fireworks/models/llama-3-70b"),
        )
    }

    @Test
    fun `无前缀的常规模型 ID 必须原样返回`() {
        // 这是最常见的路径，统一归一化不能改变任何无前缀 ID 的行为
        for (id in listOf(
            "gpt-4o",
            "claude-3-5-sonnet",
            "deepseek-v3",
            "qwen-max",
            "gemini-2.5-pro",
            "glm-4.5",
            "kimi-k2",
            "o1-mini",
        )) {
            assertEquals(id, io.zer0.ai.core.normalizeModelIdForLookup(id))
        }
    }

    @Test
    fun `边界输入不应产生空模型名`() {
        // 只有标签时回退原值，避免请求发成空 model
        assertEquals("[ais]", io.zer0.ai.core.normalizeModelIdForLookup("[ais]"))
        // 尾斜杠剥完为空 → 回退原值
        assertEquals("myrelay/", io.zer0.ai.core.normalizeModelIdForLookup("myrelay/"))
        // 前导斜杠 → 取最后一段
        assertEquals("gpt-4o", io.zer0.ai.core.normalizeModelIdForLookup("/gpt-4o"))
        // 空与空白
        assertEquals("", io.zer0.ai.core.normalizeModelIdForLookup(""))
        assertEquals("", io.zer0.ai.core.normalizeModelIdForLookup("   "))
    }

    @Test
    fun `大小写与空白被规范化`() {
        assertEquals("gpt-4o", io.zer0.ai.core.normalizeModelIdForLookup("  GPT-4o  "))
        assertEquals("claude-3.5-sonnet", io.zer0.ai.core.normalizeModelIdForLookup("Claude-3.5-Sonnet"))
    }
}
