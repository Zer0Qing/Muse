package io.zer0.muse.rag

import io.zer0.ai.ProviderConfigStore
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.Logger
import io.zer0.muse.data.ProviderConfigChangeBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest

/**
 * v1.54: Embedding 服务统一入口。
 *
 * 根据 [RagConfig.embeddingSource] 选择 [EmbeddingProvider]:
 *  - CLOUD: 创建 [OpenAIEmbeddingProvider],用指定 Provider 或激活 Provider
 *  - LOCAL: v1.134 起优先尝试 [OnnxEmbeddingProvider](基于 [RagConfig.localModelPath]),
 *           模型文件不可用时降级到 [LocalKeywordEmbeddingProvider]
 *  - LOCAL_KEYWORD: 直接 [LocalKeywordEmbeddingProvider](离线关键词哈希,无 ONNX)
 *
 * Provider 实例缓存:缓存键是「配置指纹」——[RagConfig.hashCode] 加上真正参与建 Provider 的字段
 * (Provider id、实际 baseUrl、apiKey 的 SHA-256 摘要、模型名、ONNX 模型文件是否就位)。
 * 任一项变化即重建,避免改完 key/端点后继续用旧快照。数据层写入 Provider 配置后也会通过
 * [ProviderConfigChangeBus] 触发 [invalidate] 兜底。
 *
 * 降级不再静默:回落 [LocalKeywordEmbeddingProvider] 时记 warn 日志,并把原因写进
 * [activeBackend],供上层展示「当前实际在用哪个 embedding 后端」。
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
    /**
     * 当前实际生效的 embedding 后端快照。
     *
     * @param degraded true 表示本次并非用户所选后端(例如云端配置不可用,回落本地关键词)
     * @param reason 降级原因([degraded]=true 时非空),可直接用于提示文案
     */
    data class BackendState(
        val backendId: String,
        val displayName: String,
        val modelName: String,
        val degraded: Boolean,
        val reason: String? = null,
    )

    /** 缓存项:指纹 + 实例 + 该实例对应的后端快照。 */
    private data class CacheEntry(
        val fingerprint: String,
        val provider: EmbeddingProvider,
        val state: BackendState,
    )

    /** 云端 embedding 的解析结果;[provider] 为 null 表示不可用,[reason] 说明原因。 */
    private data class CloudTarget(
        val provider: ProviderConfig?,
        val reason: String? = null,
    )

    @Volatile
    private var cached: CacheEntry? = null
    private val mutex = Mutex()

    private val _activeBackend = MutableStateFlow<BackendState?>(null)

    /** 当前实际生效的 embedding 后端(null = 尚未解析过)。 */
    val activeBackend: StateFlow<BackendState?> = _activeBackend.asStateFlow()

    private val changeListener: () -> Unit = { invalidate() }

    init {
        ProviderConfigChangeBus.register(changeListener)
    }

    /** 获取当前 RagConfig 对应的 EmbeddingProvider。 */
    suspend fun getProvider(ragConfig: RagConfig): EmbeddingProvider {
        val target = resolveCloudTarget(ragConfig)
        val fingerprint = fingerprintOf(ragConfig, target)
        cached?.let { if (it.fingerprint == fingerprint) return it.provider }
        return mutex.withLock {
            // 双重检查,避免并发时重复创建 Provider
            cached?.let { if (it.fingerprint == fingerprint) return@withLock it.provider }
            val entry = buildEntry(ragConfig, target, fingerprint)
            cached = entry
            _activeBackend.value = entry.state
            entry.provider
        }
    }

    /** 失效缓存(config 变更或 Provider 列表变更时调用)。 */
    fun invalidate() {
        cached = null
        _activeBackend.value = null
    }

    // ── 缓存指纹 ────────────────────────────────────────────────

    /**
     * 计算决定「Provider 实例行为」的指纹。
     *
     * 只放真正参与建 Provider 的字段:[RagConfig.hashCode] 覆盖配置面,
     * 再加上解析后的 Provider 身份(baseUrl/apiKey/模型名)—— 这些改了但 RagConfig 没变时,
     * 光靠配置 hash 是察觉不到的。
     */
    private fun fingerprintOf(ragConfig: RagConfig, target: CloudTarget): String = buildString {
        append(ragConfig.hashCode()).append('|')
        append(ragConfig.embeddingSource.name).append('|')
        append(ragConfig.localModelPath.trim()).append('|')
        when (ragConfig.embeddingSource) {
            RagConfig.EmbeddingSource.LOCAL_KEYWORD -> append("keyword")
            RagConfig.EmbeddingSource.LOCAL ->
                append(resolveModelPath(ragConfig.localModelPath, DEFAULT_ONNX_MODEL) ?: "onnx-missing")
            RagConfig.EmbeddingSource.CLOUD -> {
                val provider = target.provider
                if (provider == null) {
                    append("cloud-unavailable|").append(target.reason.orEmpty())
                } else {
                    append(provider.id).append('|')
                    append(provider.resolvedBaseUrl()).append('|')
                    append(provider.type.name).append('|')
                    append(apiKeyDigest(provider.apiKey)).append('|')
                    append(ragConfig.cloudModel.trim())
                }
            }
        }
    }

    /** apiKey 只以摘要参与指纹,明文不落到任何字符串里。 */
    private fun apiKeyDigest(apiKey: String): String {
        if (apiKey.isEmpty()) return "none"
        val digest = MessageDigest.getInstance("SHA-256").digest(apiKey.toByteArray(Charsets.UTF_8))
        return digest.take(DIGEST_BYTES).joinToString("") { byte -> "%02x".format(byte) }
    }

    // ── Provider 构建 ──────────────────────────────────────────

    private fun buildEntry(ragConfig: RagConfig, target: CloudTarget, fingerprint: String): CacheEntry {
        val (provider, state) = when (ragConfig.embeddingSource) {
            RagConfig.EmbeddingSource.LOCAL -> createLocalProvider(ragConfig)
            RagConfig.EmbeddingSource.LOCAL_KEYWORD -> keywordProvider(degraded = false, reason = null)
            RagConfig.EmbeddingSource.CLOUD -> createCloudProvider(ragConfig, target)
        }
        return CacheEntry(fingerprint = fingerprint, provider = provider, state = state)
    }

    private fun createLocalProvider(ragConfig: RagConfig): Pair<EmbeddingProvider, BackendState> {
        // v1.134: LOCAL 模式优先尝试 OnnxEmbeddingProvider,不可用时降级到关键词哈希
        val onnxProvider = tryCreateOnnxProvider(ragConfig.localModelPath)
            ?: return keywordProvider(degraded = true, reason = "本地 ONNX embedding 不可用(未配置模型或文件缺失)")
        Logger.i("EmbeddingService", "使用本地 ONNX embedding(${onnxProvider.modelName}, ${onnxProvider.dimension}d)")
        return onnxProvider to stateOf(onnxProvider, degraded = false)
    }

    private fun createCloudProvider(ragConfig: RagConfig, target: CloudTarget): Pair<EmbeddingProvider, BackendState> {
        val providerConfig = target.provider
            ?: return keywordProvider(degraded = true, reason = target.reason ?: "云端 Provider 不可用")
        // v1.103: Anthropic/Gemini 不提供 OpenAI 兼容的 embedding API,未指定 cloudModel 时
        // OpenAIEmbeddingProvider 构造会抛 IllegalStateException,因此在建实例前就拦下来。
        val provider = OpenAIEmbeddingProvider(
            config = providerConfig,
            model = ragConfig.cloudModel,
            client = client,
        )
        Logger.i(
            "EmbeddingService",
            "使用云端 embedding(provider=${providerConfig.id}, model=${provider.modelName})",
        )
        return provider to stateOf(provider, degraded = false)
    }

    /** 回落本地关键词实现:记 warn(原因写清楚)并打上 degraded 标记。 */
    private fun keywordProvider(degraded: Boolean, reason: String?): Pair<EmbeddingProvider, BackendState> {
        if (degraded) {
            Logger.w("EmbeddingService", "${reason ?: "云端 embedding 不可用"},降级到本地关键词 embedding,语义检索能力下降")
        } else {
            Logger.i("EmbeddingService", "使用本地关键词 embedding(离线模式)")
        }
        val provider = LocalKeywordEmbeddingProvider()
        return provider to stateOf(provider, degraded = degraded, reason = reason)
    }

    private fun stateOf(
        provider: EmbeddingProvider,
        degraded: Boolean,
        reason: String? = null,
    ): BackendState = BackendState(
        backendId = provider.id,
        displayName = provider.displayName,
        modelName = provider.modelName,
        degraded = degraded,
        reason = reason,
    )

    // ── 云端 Provider 解析 ────────────────────────────────────

    /**
     * 解析云端 embedding 实际可用的 Provider。
     *
     * 三种不可用情形(找不到 Provider / Provider 已停用 / 能力或密钥不满足)都返回带原因的
     * [CloudTarget],由 [createCloudProvider] 统一降级并记日志,避免用户以为在用向量检索。
     */
    private suspend fun resolveCloudTarget(ragConfig: RagConfig): CloudTarget {
        if (ragConfig.embeddingSource != RagConfig.EmbeddingSource.CLOUD) return CloudTarget(provider = null)
        val providerConfig = getCloudProviderConfig(ragConfig)
        return when {
            providerConfig == null -> CloudTarget(
                provider = null,
                reason = if (ragConfig.cloudProviderId.isNotBlank()) {
                    "RAG 配置指定的 Provider(${ragConfig.cloudProviderId}) 不存在"
                } else {
                    "未找到可用的云端 Provider"
                },
            )
            !providerConfig.enabled ->
                CloudTarget(provider = null, reason = "Provider ${providerConfig.displayName} 已停用")
            providerConfig.apiKey.isBlank() ->
                CloudTarget(provider = null, reason = "Provider ${providerConfig.displayName} 的 API Key 为空")
            ragConfig.cloudModel.isBlank() && providerConfig.type !in OPENAI_EMBEDDING_TYPES -> CloudTarget(
                provider = null,
                reason = "Provider ${providerConfig.displayName}(${providerConfig.type}) 不兼容 OpenAI embedding API 且未指定模型",
            )
            else -> CloudTarget(provider = providerConfig)
        }
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
        val resolvedPath = resolveModelPath(localModelPath, DEFAULT_ONNX_MODEL) ?: return null
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
        // 否则用激活的 chat Provider(ProviderConfigStore.get() 已跳过停用供应商)
        return configStore.get()
    }

    private companion object {
        /** 默认 ONNX 模型在 filesDir 下的相对路径。 */
        const val DEFAULT_ONNX_MODEL = "muse_onnx/embedding.onnx"

        /** apiKey 摘要保留的字节数(16 个十六进制字符,碰撞概率对变更检测足够低)。 */
        const val DIGEST_BYTES = 8

        /** 支持 OpenAI 兼容 embedding 接口的 Provider 类型。 */
        val OPENAI_EMBEDDING_TYPES = setOf(ProviderType.OPENAI, ProviderType.OPENAI_RESPONSES)
    }
}
