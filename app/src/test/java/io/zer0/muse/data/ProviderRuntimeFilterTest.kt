package io.zer0.muse.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.data.routing.UtilityModelBinding
import io.zer0.muse.rag.RagConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 运行时读路径上的供应商/模型清理与停用过滤。
 *
 * 覆盖:
 *  - 停用供应商不参与 `get()` 的选择;全部停用时返回 null(不硬取第一个)
 *  - 删供应商后 RAG 的云端 embedding 引用被清(不再永久悬空)
 *  - 删单个模型后,指向它的 imageGenConfig / ragConfig / 辅助模型绑定被清
 *
 * 注:同一测试类的多个用例共享 DataStore 文件,每个用例使用各自的 provider id 前缀,
 * 并把被断言的全局配置写成该用例自己的基线,避免互相污染。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SettingsRepositoryTestApplication::class)
class ProviderRuntimeFilterTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newRepository(): SettingsRepository = SettingsRepository(context, mockk(relaxed = true))

    // ── enabled 过滤 ────────────────────────────────────────

    @Test
    fun get_skipsDisabledActiveProviderAndUsesEnabledOne() = runBlocking {
        val repo = newRepository()
        repo.addProvider(provider(id = "skip-disabled", enabled = false, modelIds = listOf("skip-m-off")))
        repo.addProvider(provider(id = "skip-enabled", enabled = true, modelIds = listOf("skip-m-on")))
        repo.setActiveProvider("skip-disabled")

        val active = repo.get()
        assertNotNull(active)
        assertTrue("停用的激活供应商必须被跳过", active!!.enabled)
        assertNull("停用供应商不得被按 id 取到", repo.getProviderById("skip-disabled"))
        assertNotNull(repo.getProviderById("skip-enabled"))
    }

    @Test
    fun get_returnsNullWhenEveryProviderIsDisabled() = runBlocking {
        val repo = newRepository()
        repo.addProvider(provider(id = "alloff-1", enabled = false, modelIds = listOf("alloff-m1")))
        repo.addProvider(provider(id = "alloff-2", enabled = false, modelIds = listOf("alloff-m2")))
        repo.setActiveProvider("alloff-1")
        // 仓库初始化会异步注入免费供应商,这里把它也停用,确保断言的是「全部停用」这个前提
        disableEveryProvider(repo)

        assertNull("全部停用时返回 null,不能硬取第一个", repo.get())
        assertNull(repo.providerConfigFlow.first())
    }

    @Test
    fun get_resolvesProviderWithDefaultConfiguration() = runBlocking {
        // 默认配置(没设激活项)下主对话仍必须取得到供应商
        val repo = newRepository()
        repo.addProvider(provider(id = "only-enabled", enabled = true, modelIds = listOf("only-model")))
        repo.setActiveProvider("only-enabled")

        val active = repo.get()
        assertNotNull("默认配置必须能取到供应商", active)
        assertEquals("only-enabled", active?.id)
    }

    // ── 删供应商后 RAG 引用清理 ────────────────────────────

    @Test
    fun deleteProvider_clearsRagEmbeddingReference() = runBlocking {
        val repo = newRepository()
        repo.addProvider(provider(id = "del-emb", enabled = true, modelIds = listOf("del-emb-model")))
        repo.addProvider(provider(id = "del-chat", enabled = true, modelIds = listOf("del-chat-model")))
        repo.saveRagConfig(
            RagConfig(
                embeddingSource = RagConfig.EmbeddingSource.CLOUD,
                cloudProviderId = "del-emb",
                cloudModel = "del-emb-model",
            ),
        )

        repo.deleteProvider("del-emb")

        val rag = repo.getRagConfig()
        assertEquals("删供应商后 cloudProviderId 必须清空", "", rag.cloudProviderId)
        assertEquals("随供应商生效的模型名也要一并清空", "", rag.cloudModel)
    }

    // ── 删单个模型 ─────────────────────────────────────────

    @Test
    fun updateProvider_clearsReferencesToRemovedModel() = runBlocking {
        val repo = newRepository()
        repo.addProvider(
            provider(
                id = "upd-model",
                enabled = true,
                modelIds = listOf("upd-text", "upd-image"),
            ),
        )
        repo.saveImageGenConfig(ImageGenConfig(providerId = "upd-model", modelId = "upd-image"))
        repo.saveUtilityModelBinding(UtilityModelBinding(providerId = "upd-model", modelId = "upd-image"))
        repo.saveRagConfig(
            RagConfig(
                embeddingSource = RagConfig.EmbeddingSource.CLOUD,
                cloudProviderId = "upd-model",
                cloudModel = "upd-image",
            ),
        )
        val before = repo.providersFlow.first().first { it.id == "upd-model" }
        assertEquals(listOf("upd-text", "upd-image"), before.models.map { it.id })

        repo.updateProvider(provider(id = "upd-model", enabled = true, modelIds = listOf("upd-text")))

        assertEquals("图片模型被删后 modelId 必须清空", "", repo.imageGenConfigFlow.first().modelId)
        assertEquals("upd-model", repo.imageGenConfigFlow.first().providerId)
        assertNull("辅助模型绑定指向被删模型时必须置空", repo.utilityModelBindingFlow.first())
        assertEquals("RAG 的云端 embedding 模型被删后必须清空", "", repo.getRagConfig().cloudModel)
    }

    @Test
    fun updateProvider_keepsReferencesWhenModelSurvivesElsewhere() = runBlocking {
        val repo = newRepository()
        repo.addProvider(provider(id = "shared-keep", enabled = true, modelIds = listOf("shared-model", "keep-other")))
        repo.addProvider(provider(id = "shared-other", enabled = true, modelIds = listOf("shared-model")))
        repo.saveImageGenConfig(ImageGenConfig(providerId = "shared-keep", modelId = "shared-model"))

        // shared-keep 删掉 shared-model,但 shared-other 仍提供同名模型
        repo.updateProvider(provider(id = "shared-keep", enabled = true, modelIds = listOf("keep-other")))

        assertEquals(
            "同名模型仍存在于其他供应商时保留选择,不要误删",
            "shared-model",
            repo.imageGenConfigFlow.first().modelId,
        )
    }

    private fun provider(
        id: String,
        enabled: Boolean,
        modelIds: List<String>,
    ): ProviderConfig = ProviderConfig(
        id = id,
        displayName = id,
        // 测试不需要真 key:空 apiKey 跳过 Keystore 加解密(Robolectric 无 AndroidKeyStore)
        apiKey = "",
        enabled = enabled,
        models = modelIds.map { Model(id = it, providerId = id) },
    )

    /** 把当前列表里所有启用的供应商都停用(含初始化时异步注入的内置供应商)。 */
    private suspend fun disableEveryProvider(repo: SettingsRepository) {
        repeat(5) {
            repo.providersFlow.first()
                .filter { it.enabled }
                .forEach { repo.updateProvider(it.copy(enabled = false)) }
        }
    }
}
