package io.zer0.muse.data.preset

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelCatalog
import io.zer0.ai.core.ModelCatalogEntry
import io.zer0.ai.core.ToolUseSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.6.0：模型清单走云端目录 —— Codex 等“目录权威”供应商的模型不再在应用内预置。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelCatalogCloudBridgeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun codexCloudCatalog(): ModelCatalog = ModelCatalog(
        schemaVersion = 1,
        publishedAt = "2026-09-30T06:11:52",
        providers = mapOf(
            "openai-codex-oauth" to mapOf(
                "gpt-6.1-sol" to ModelCatalogEntry(
                    name = "GPT-6.1 Sol",
                    context = 400_000,
                    maxOutput = 128_000,
                    image = true,
                    reasoning = true,
                    toolUse = ToolUseSpec(supportsTools = true),
                ),
                "gpt-6-luna" to ModelCatalogEntry(
                    name = "GPT-6 Luna",
                    context = 400_000,
                    image = true,
                    reasoning = true,
                    toolUse = ToolUseSpec(supportsTools = true),
                ),
            ),
        ),
    )

    @Test
    fun codexPreset_hasNoBuiltInModels() {
        // 应用内不预置 Codex 模型（清单以云端目录为准）。
        val preset = PresetProviders(context).bySpecId("openai_codex")
        assertTrue(preset != null)
        assertEquals(0, preset!!.models.size)
    }

    @Test
    fun codexPreset_exposesCloudCatalogModels() {
        // 注入云端目录后，预设选择页应看到目录里的 Codex 模型。
        val store = ModelCatalogStore(context, cloudCatalog = { codexCloudCatalog() })
        val preset = PresetProviders(context, store).bySpecId("openai_codex")
        assertTrue(preset != null)
        val ids = preset!!.models.map { it.id }.toSet()
        assertTrue("应含 gpt-6.1-sol，实际=$ids", ids.contains("gpt-6.1-sol"))
        assertTrue("应含 gpt-6-luna，实际=$ids", ids.contains("gpt-6-luna"))
    }

    @Test
    fun codexMerge_discardsStalePersistedModels() {
        // 已登录用户的本地配置里可能残留已退役的旧模型；
        // 目录权威供应商的清单完全由云端目录决定，旧模型应被丢弃。
        val store = ModelCatalogStore(context, cloudCatalog = { codexCloudCatalog() })
        val stale = listOf(
            Model(id = "gpt-5.2-codex", name = "GPT-5.2 Codex", providerId = "preset_openai_codex"),
            Model(id = "gpt-5.2", name = "GPT-5.2", providerId = "preset_openai_codex"),
        )
        val projected = store.mergeIntoModels(
            providerId = "openai-codex-oauth",
            models = stale,
            runtimeProviderId = "preset_openai_codex",
        )
        val ids = projected.map { it.id }.toSet()
        assertFalse("已退役模型应被丢弃，实际=$ids", ids.contains("gpt-5.2-codex"))
        assertFalse(ids.contains("gpt-5.2"))
        assertTrue(ids.contains("gpt-6.1-sol"))
        // 运行时身份保留 preset_ 前缀，便于按 providerId 查找。
        assertEquals("preset_openai_codex", projected.first { it.id == "gpt-6.1-sol" }.providerId)
    }

    @Test
    fun nonAuthoritativeProvider_catalogOnlyOverridesFieldsNotMembership() {
        // 普通供应商：目录只覆盖字段，不凭目录新增模型（避免灌入全部历史模型）。
        val store = ModelCatalogStore(context, cloudCatalog = { codexCloudCatalog() })
        val own = listOf(Model(id = "gpt-4o", name = "GPT-4o", providerId = "preset_openai"))
        val projected = store.mergeIntoModels(
            providerId = "openai",
            models = own,
            runtimeProviderId = "preset_openai",
        )
        assertEquals(listOf("gpt-4o"), projected.map { it.id })
    }
}
