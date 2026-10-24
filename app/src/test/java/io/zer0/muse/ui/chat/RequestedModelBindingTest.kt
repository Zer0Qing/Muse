package io.zer0.muse.ui.chat

import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.9: 模型绑定三态判定测试。
 *
 * 钉住的核心不变式:**空列表 ≠ 不含**。
 * 原实现 `provider.models.none { it.id == modelId }` 在空列表上恒为 true,
 * 把「模型列表还没拉过 / 拉取失败」误判成「模型不存在」并降级到兜底模型
 * (真机由此把会话降到 GLM-4-9B,该模型工具调用畸形,卡片生成直接失败)。
 */
class RequestedModelBindingTest {

    private fun provider(id: String, vararg modelIds: String) = ProviderConfig(
        id = id,
        displayName = id,
        models = modelIds.map { Model(id = it, name = it, providerId = id) },
    )

    // ── 1. 列表为空 → 不判缺失(本次修复的核心) ──────────────────────────────

    @Test
    fun `empty model list is not treated as missing`() {
        val binding = resolveRequestedModelBinding(
            // models 为空:从未拉取 / 拉取失败
            providers = listOf(provider("agnes")),
            providerId = "agnes",
            modelId = "claude-sonnet-4-5",
        )

        assertFalse("空列表不得判为模型缺失", binding.modelMissing)
        assertFalse("空列表不得触发降级", binding.missing)
        assertTrue("空列表应标记为列表未就绪", binding.listNotReady)
        // 保留请求绑定:按请求 id 构造占位 Model,交给上游判定,而不是换成别的模型。
        assertNotNull("列表未就绪时仍应保留请求的模型绑定", binding.model)
        assertEquals("claude-sonnet-4-5", binding.model?.id)
        assertEquals("agnes", binding.model?.providerId)
    }

    @Test
    fun `provider missing still reports missing`() {
        val binding = resolveRequestedModelBinding(
            providers = listOf(provider("agnes", "claude-sonnet-4-5")),
            providerId = "ghost",
            modelId = "claude-sonnet-4-5",
        )

        assertTrue(binding.providerMissing)
        assertTrue(binding.missing)
        assertNull(binding.model)
    }

    // ── 2. 列表非空且不含 → 真缺失,必须走兜底 ────────────────────────────────

    @Test
    fun `non-empty list without the id is a real miss and falls back`() {
        val binding = resolveRequestedModelBinding(
            providers = listOf(provider("agnes", "glm-4-9b", "gpt-4o-mini")),
            providerId = "agnes",
            modelId = "claude-sonnet-4-5",
        )

        assertTrue("列表非空且不含该 id 必须判缺失", binding.modelMissing)
        assertTrue("真缺失必须触发兜底", binding.missing)
        assertFalse(binding.listNotReady)
        assertNull("真缺失时不得保留请求绑定,应让兜底链接手", binding.model)
    }

    // ── 3. 列表非空且含 → 精确命中 ───────────────────────────────────────────

    @Test
    fun `non-empty list containing the id is not missing`() {
        val binding = resolveRequestedModelBinding(
            providers = listOf(provider("agnes", "glm-4-9b", "claude-sonnet-4-5")),
            providerId = "agnes",
            modelId = "claude-sonnet-4-5",
        )

        assertFalse(binding.modelMissing)
        assertFalse(binding.missing)
        assertFalse(binding.listNotReady)
        assertEquals("claude-sonnet-4-5", binding.model?.id)
    }

    // ── 4. 边界:调用方未指定 provider/model ─────────────────────────────────

    @Test
    fun `unspecified provider or model does not report missing`() {
        val noProvider = resolveRequestedModelBinding(listOf(provider("agnes")), null, "x")
        assertFalse(noProvider.missing)
        assertNull(noProvider.model)

        val noModel = resolveRequestedModelBinding(listOf(provider("agnes")), "agnes", null)
        assertFalse(noModel.missing)
        assertNull(noModel.model)
    }

    @Test
    fun `provider missing wins over unspecified model`() {
        // Provider 配置没了就是没了,即使调用方没带 modelId 也要报告(与原有行为一致)。
        val binding = resolveRequestedModelBinding(listOf(provider("agnes", "m")), "ghost", null)
        assertTrue(binding.providerMissing)
        assertTrue(binding.missing)
    }
}
