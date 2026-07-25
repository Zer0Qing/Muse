package io.zer0.muse.rag

import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.ProviderConfigStore
import io.zer0.common.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File

/**
 * v1.54: Embedding 服务统一入口。
 *
 * 根据 [RagConfig.embeddingSource] 选择 [EmbeddingProvider]:
 *  - CLOUD: 创建 [OpenAIEmbeddingProvider],用指定 Provider 或激活 Provider
 *  - LOCAL: v1.134 起优先尝试 [OnnxEmbeddingProvider](基于 [RagConfig.localModelPath]),
 *           模型文件不可用时降级到 [LocalKeywordEmbeddingProvider]
 *  - LOCAL_KEYWORD: 直接 [LocalKeywordEmbeddingProvider](离线关键词哈希,无 ONNX)
 *
 * Provider 实例缓存:config 变更时调 [invalidate] 重建。
 *
 * @param configStore Provider 配置仓库(SettingsRepository 实现)
 * @param client 复用 Koin 注入的 OkHttpClient(named("chat"))
 * @param filesDir 应用 filesDir,用于解析相对路径的 ONNX 模型(v1.134 新增)
 */
class EmbeddingService(
    private val configStore: ProviderConfigStore,
    private val client: OkHttpClient,
    private val filesDir: File? = null,
) {
    @Volatile
    private var cachedProvider: EmbeddingProvider? = null
    @Volatile
    private var cachedConfigHash: Int = 0
    private val mutex = Mutex()

    /** 获取当前 RagConfig 对应的 EmbeddingProvider。 */
    suspend fun getProvider(ragConfig: RagConfig): EmbeddingProvider {
        val hash = ragConfig.hashCode()
        cachedProvider?.let { if (hash == cachedConfigHash) return it }
        return mutex.withLock {
            // 双重检查,避免并发时重复创建 Provider
            cachedProvider?.let { if (hash == cachedConfigHash) return@withLock it }
            val provider = createProvider(ragConfig)
            cachedProvider = provider
            cachedConfigHash = hash
            provider
        }
    }

    /** 失效缓存(config 变更或 Provider 列表变更时调用)。 */
    fun invalidate() {
        cachedProvider = null
        cachedConfigHash = 0
    }

    private suspend fun createProvider(ragConfig: RagConfig): EmbeddingProvider {
        // v1.134: LOCAL 模式优先尝试 OnnxEmbeddingProvider,不可用时降级到关键词哈希
        if (ragConfig.embeddingSource == RagConfig.EmbeddingSource.LOCAL) {
            val onnxProvider = tryCreateOnnxProvider(ragConfig.localModelPath)
            if (onnxProvider != null) {
                Logger.i("EmbeddingService", "使用本地 ONNX embedding(${onnxProvider.modelName}, ${onnxProvider.dimension}d)")
                return onnxProvider
            }
            Logger.w("EmbeddingService", "ONNX embedding 不可用(未配置模型或文件缺失),降级到本地关键词 embedding")
            return LocalKeywordEmbeddingProvider()
        }

        // LOCAL_KEYWORD 模式:直接用关键词哈希(无 ONNX,完全离线)
        if (ragConfig.embeddingSource == RagConfig.EmbeddingSource.LOCAL_KEYWORD) {
            Logger.i("EmbeddingService", "使用本地关键词 embedding(离线模式)")
            return LocalKeywordEmbeddingProvider()
        }

        // 云端模式:获取 Provider 配置
        // v1.97: 云端 Provider 不可用时降级到本地关键词 embedding,而非抛异常导致向量检索完全失效。
        // 修复"用户未配置 embedding API 时知识库检索退化为纯 LIKE 子串匹配"的问题。
        val providerConfig = getCloudProviderConfig(ragConfig)
        if (providerConfig == null) {
            Logger.w("EmbeddingService", "未找到可用的云端 Provider,降级到本地关键词 embedding")
            return LocalKeywordEmbeddingProvider()
        }
        if (providerConfig.apiKey.isBlank()) {
            Logger.w("EmbeddingService", "Provider ${providerConfig.displayName} 的 API Key 为空,降级到本地关键词 embedding")
            return LocalKeywordEmbeddingProvider()
        }
        // v1.103: Anthropic/Gemini 不提供 OpenAI 兼容的 embedding API。
        // 之前 OpenAIEmbeddingProvider.defaultModel() 会在构造时抛 IllegalStateException,
        // 但该异常被 KnowledgeScreen 的 runCatching 吞掉,导致文档入库但 chunks 表为空,
        // 用户看到"知识库不生效、索引为空"。这里在创建 Provider 前做类型检查,
        // 不兼容类型 + 未指定 cloudModel 时直接降级到本地关键词 embedding。
        if (ragConfig.cloudModel.isBlank() &&
            providerConfig.type in setOf(io.zer0.ai.core.ProviderType.ANTHROPIC, io.zer0.ai.core.ProviderType.GEMINI)
        ) {
            Logger.w(
                "EmbeddingService",
                "Provider ${providerConfig.displayName}(${providerConfig.type}) 不兼容 OpenAI embedding API 且未指定 cloudModel,降级到本地关键词 embedding"
            )
            return LocalKeywordEmbeddingProvider()
        }
        return OpenAIEmbeddingProvider(
            config = providerConfig,
            model = ragConfig.cloudModel,
            client = client,
        )
    }

    /**
     * v1.134: 尝试创建 OnnxEmbeddingProvider。
     *
     * 路径解析:
     *  - [localModelPath] 为绝对路径(以 / 开头)→ 直接使用
     *  - 否则视为 filesDir 相对路径
     *  - 空串 → 默认 filesDir/muse_onnx/embedding.onnx
     *
     * @return 可用的 OnnxEmbeddingProvider;若模型文件不存在则返回 null
     */
    private fun tryCreateOnnxProvider(localModelPath: String): OnnxEmbeddingProvider? {
        val resolvedPath = resolveModelPath(localModelPath, "muse_onnx/embedding.onnx") ?: return null
        val provider = OnnxEmbeddingProvider(modelPath = resolvedPath)
        return if (provider.isAvailable()) provider else null
    }

    /** 解析模型路径:绝对路径直接用,相对路径拼接 filesDir,空串用 defaultRelative。 */
    private fun resolveModelPath(configuredPath: String, defaultRelative: String): String? {
        val path = configuredPath.trim()
        if (path.startsWith("/")) {
            return if (File(path).exists()) path else null
        }
        val files = filesDir ?: return null
        val candidate = if (path.isBlank()) {
            File(files, defaultRelative)
        } else {
            File(files, path)
        }
        return if (candidate.exists()) candidate.absolutePath else null
    }

    /** 获取云端 embedding 用的 ProviderConfig。 */
    private suspend fun getCloudProviderConfig(ragConfig: RagConfig): ProviderConfig? {
        // 优先用 RagConfig.cloudProviderId 指定的 Provider
        if (ragConfig.cloudProviderId.isNotBlank()) {
            return configStore.getAllProviders().firstOrNull { it.id == ragConfig.cloudProviderId }
        }
        // 否则用激活的 chat Provider
        return configStore.get()
    }
}
