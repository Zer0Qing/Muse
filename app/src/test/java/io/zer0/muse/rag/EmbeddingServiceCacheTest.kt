package io.zer0.muse.rag

import io.zer0.ai.ProviderConfigStore
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.Logger
import io.zer0.muse.data.ProviderConfigChangeBus
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Embedding Provider 缓存的指纹失效与降级可见性。
 *
 * 覆盖三件事:
 *  1. 同一配置多次 getProvider 命中同一实例(缓存真的生效)
 *  2. Provider 的 apiKey / baseUrl / 模型 / id 任一变化都重建实例
 *  3. 云端 Provider 不可用时回落本地关键词实现,并留下 warn 日志与 degraded 状态
 */
class EmbeddingServiceCacheTest {

    private val client = OkHttpClient()

    @After
    fun tearDown() {
        Logger.sink = null
    }

    // ── 指纹 ────────────────────────────────────────────────

    @Test
    fun sameConfigReusesProviderInstance() = runBlocking {
        val store = FakeProviderStore(listOf(openAiProvider()))
        val service = EmbeddingService(store, client)
        val config = cloudConfig()

        val first = service.getProvider(config)
        val second = service.getProvider(config)

        assertSame("配置未变时必须复用同一个 Provider 实例", first, second)
        assertEquals("cloud-openai-main", first.id)
    }

    @Test
    fun changingApiKeyRebuildsProvider() = runBlocking {
        val store = FakeProviderStore(listOf(openAiProvider()))
        val service = EmbeddingService(store, client)
        val config = cloudConfig()
        val first = service.getProvider(config)

        store.providers = listOf(openAiProvider(apiKey = "sk-rotated-key"))

        assertNotSame("apiKey 变化后必须重建 Provider", first, service.getProvider(config))
    }

    @Test
    fun changingBaseUrlRebuildsProvider() = runBlocking {
        val store = FakeProviderStore(listOf(openAiProvider()))
        val service = EmbeddingService(store, client)
        val config = cloudConfig()
        val first = service.getProvider(config)

        store.providers = listOf(openAiProvider(baseUrl = "https://relay.example.com/v1"))

        assertNotSame("baseUrl 变化后必须重建 Provider", first, service.getProvider(config))
    }

    @Test
    fun changingModelRebuildsProvider() = runBlocking {
        val store = FakeProviderStore(listOf(openAiProvider()))
        val service = EmbeddingService(store, client)
        val first = service.getProvider(cloudConfig(cloudModel = "text-embedding-3-small"))

        val second = service.getProvider(cloudConfig(cloudModel = "text-embedding-3-large"))

        assertNotSame("embedding 模型变化后必须重建 Provider", first, second)
        assertEquals("text-embedding-3-large", second.modelName)
    }

    @Test
    fun providerConfigChangeNotificationInvalidatesCache() = runBlocking {
        val store = FakeProviderStore(listOf(openAiProvider()))
        val service = EmbeddingService(store, client)
        val config = cloudConfig()
        val first = service.getProvider(config)

        // 配置指纹完全不变,只能靠数据层广播让缓存失效
        ProviderConfigChangeBus.notifyChanged()

        val second = service.getProvider(config)
        assertNotSame("配置变更广播后必须重建 Provider", first, second)
    }

    // ── 降级可见性 ──────────────────────────────────────────

    @Test
    fun danglingCloudProviderIdFallsBackToKeywordAndWarns() = runBlocking {
        val store = FakeProviderStore(listOf(openAiProvider()))
        val service = EmbeddingService(store, client)
        val warnings = mutableListOf<String>()
        Logger.sink = { level, tag, message, _ ->
            if (level == "WARN" && tag == "EmbeddingService") warnings.add(message)
        }

        val provider = service.getProvider(cloudConfig(cloudProviderId = "provider-that-was-deleted"))

        assertTrue("悬空的 cloudProviderId 必须回落本地关键词实现", provider is LocalKeywordEmbeddingProvider)
        assertTrue(
            "降级必须留下可追溯的 warn 日志,实际:$warnings",
            warnings.any { it.contains("provider-that-was-deleted") && it.contains("不存在") },
        )
        val state = service.activeBackend.value
        assertNotNull(state)
        assertTrue("activeBackend 必须标记为降级", state!!.degraded)
        assertTrue("降级原因必须可展示", state.reason.orEmpty().contains("provider-that-was-deleted"))
        assertEquals("local-keyword", state.backendId)
    }

    @Test
    fun disabledCloudProviderFallsBackToKeywordAndWarns() = runBlocking {
        val store = FakeProviderStore(listOf(openAiProvider(enabled = false)))
        val service = EmbeddingService(store, client)
        val warnings = mutableListOf<String>()
        Logger.sink = { level, tag, message, _ ->
            if (level == "WARN" && tag == "EmbeddingService") warnings.add(message)
        }

        val provider = service.getProvider(cloudConfig(cloudProviderId = "openai-main"))

        assertTrue("停用的供应商不得继续用于 embedding", provider is LocalKeywordEmbeddingProvider)
        assertTrue("停用降级必须有 warn 日志,实际:$warnings", warnings.any { it.contains("已停用") })
    }

    @Test
    fun blankApiKeyFallsBackToKeywordAndWarns() = runBlocking {
        val store = FakeProviderStore(listOf(openAiProvider(apiKey = "")))
        val service = EmbeddingService(store, client)
        val warnings = mutableListOf<String>()
        Logger.sink = { level, tag, message, _ ->
            if (level == "WARN" && tag == "EmbeddingService") warnings.add(message)
        }

        val provider = service.getProvider(cloudConfig())

        assertTrue(provider is LocalKeywordEmbeddingProvider)
        assertTrue("API Key 为空导致的降级必须有 warn 日志,实际:$warnings", warnings.any { it.contains("API Key 为空") })
    }

    @Test
    fun localKeywordSourceIsNotReportedAsDegraded() = runBlocking {
        val store = FakeProviderStore(emptyList())
        val service = EmbeddingService(store, client)

        val provider = service.getProvider(RagConfig(embeddingSource = RagConfig.EmbeddingSource.LOCAL_KEYWORD))

        assertTrue(provider is LocalKeywordEmbeddingProvider)
        val state = service.activeBackend.value
        assertNotNull(state)
        assertFalse("用户显式选择本地关键词不算降级", state!!.degraded)
    }

    // ── fixtures ────────────────────────────────────────────

    private fun cloudConfig(
        cloudProviderId: String = "openai-main",
        cloudModel: String = "text-embedding-3-small",
    ): RagConfig = RagConfig(
        embeddingSource = RagConfig.EmbeddingSource.CLOUD,
        cloudProviderId = cloudProviderId,
        cloudModel = cloudModel,
    )

    private fun openAiProvider(
        apiKey: String = "sk-test-key",
        baseUrl: String = "https://api.openai.com/v1",
        enabled: Boolean = true,
    ): ProviderConfig = ProviderConfig(
        id = "openai-main",
        displayName = "OpenAI",
        type = ProviderType.OPENAI,
        baseUrl = baseUrl,
        apiKey = apiKey,
        enabled = enabled,
    )

    private class FakeProviderStore(var providers: List<ProviderConfig>) : ProviderConfigStore {
        override suspend fun get(): ProviderConfig? = providers.firstOrNull { it.enabled }

        override suspend fun getAllProviders(): List<ProviderConfig> = providers
    }
}
