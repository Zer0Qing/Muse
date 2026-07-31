package io.zer0.ai.registry

import io.zer0.ai.core.ModelAbility
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.53: Kimi K2.6 多模态识别回归测试。
 *
 * 背景:KnownModels 曾把 kimi-k2.6 标记为纯文本(TEXT_IN),导致:
 *  - 中转站即使声明多模态,也被 v1.137 纠错逻辑覆盖为纯文本
 *  - 图片只能走视觉辅助,不能直发
 * 修复:ModelRegistry 新增 KIMI_K2_6 精确规则 + KnownModels 改 VISION_IN。
 */
class KimiK26VisionTest {

    @Test
    fun `kimi-k2_6 resolves vision input modality`() {
        val modalities = ModelRegistry.resolveInputModalities("kimi-k2.6")
        assertTrue("kimi-k2.6 应支持 image 输入,实际=$modalities", "image" in modalities)
    }

    @Test
    fun `kimi-k2_6 keeps tool and reasoning abilities`() {
        val abilities = ModelRegistry.resolveAbilities("kimi-k2.6")
        assertTrue("应含 TOOL", abilities.contains(ModelAbility.TOOL))
        assertTrue("应含 REASONING", abilities.contains(ModelAbility.REASONING))
    }

    @Test
    fun `kimi-k2 stays text-only`() {
        // kimi-k2(非 2.6)不含视觉 — 精确规则不应误伤
        val modalities = ModelRegistry.resolveInputModalities("kimi-k2")
        assertTrue("kimi-k2 应为纯文本,实际=$modalities", "image" !in modalities)
    }

    @Test
    fun `kimi-k2_5 stays text-only`() {
        val modalities = ModelRegistry.resolveInputModalities("kimi-k2.5")
        assertTrue("kimi-k2.5 应为纯文本,实际=$modalities", "image" !in modalities)
    }

    @Test
    fun `prefixed kimi-k2_6 still resolves vision`() {
        // 中转站/聚合站带前缀的 ID(如 opencode/kimi-k2.6)
        val modalities = ModelRegistry.resolveInputModalities("opencode/kimi-k2.6")
        assertTrue("带前缀 kimi-k2.6 应支持 image,实际=$modalities", "image" in modalities)
    }
}
