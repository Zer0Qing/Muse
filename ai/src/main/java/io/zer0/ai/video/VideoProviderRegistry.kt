package io.zer0.ai.video

import io.zer0.ai.core.ProviderConfig
import io.zer0.common.Logger

/**
 * v1.137: Video Provider 注册中心(与 [io.zer0.ai.image.ImageProviderRegistry] 风格一致)。
 *
 * 参考 QingTian(openhanako)的 AdapterRegistry 模式 + 现有 ImageProviderRegistry:
 *  - 各 [VideoProvider] 实现类在启动时通过 [register] 注册到本中心;
 *  - 调用方通过 [selectFor] 按 [ProviderConfig] 自动选择合适的 provider;
 *  - 选择策略按 specId / baseUrl host 三层匹配,兜底 [GenericOpenAiVideoProvider]。
 *
 * 选择优先级:
 *  1. specId / id(去 preset_ 前缀)精确匹配(如 specId="agnes" → AgnesVideoProvider);
 *  2. baseUrl host 包含供应商关键字(如含 "agnes-ai" → AgnesVideoProvider);
 *  3. 兜底 [genericProvider](通用 OpenAI 兼容协议,对未知中转站友好)。
 *
 * 修复 v1.136 的 providerId 路由 bug:
 *  - 旧实现按 [ProviderConfig.id] 硬匹配 map key,导致 "preset_kling" ≠ "kling" 时路由失败
 *  - 新实现按 specId / id(去 preset_ 前缀)路由,与用户自定义 id 无关
 *
 * @param genericProvider 通用兜底 Provider(未命中时返回)
 */
class VideoProviderRegistry(
    private val genericProvider: GenericOpenAiVideoProvider,
) {

    private val providers = mutableMapOf<String, VideoProvider>()

    /** 注册一个 [VideoProvider](以其 [VideoProvider.providerId] 为 key,后者覆盖前者)。 */
    fun register(provider: VideoProvider) {
        providers[provider.providerId] = provider
        Logger.i(TAG, "registered video provider: ${provider.providerId}")
    }

    /** 按 providerId 取已注册的 [VideoProvider]。 */
    fun get(providerId: String): VideoProvider? = providers[providerId]

    /** 所有已注册的 provider(不含 genericProvider)。 */
    fun all(): Collection<VideoProvider> = providers.values

    /**
     * 根据 [ProviderConfig] 自动选择合适的 [VideoProvider]。
     *
     * 选择顺序见类注释。永不返回 null([genericProvider] 兜底)。
     */
    fun selectFor(config: ProviderConfig): VideoProvider {
        // 1. specId / id(去 preset_ 前缀)精确匹配
        //    对齐 ImageProviderRegistry:处理 preset_kling → kling 等历史 id
        val key = config.specId?.takeIf { it.isNotBlank() }
            ?: config.id.removePrefix("preset_").takeIf { it.isNotBlank() }
        if (key != null) {
            providers[key]?.let { return it }
        }

        // 2. baseUrl host 关键字匹配
        val host = runCatching {
            java.net.URI(config.resolvedBaseUrl()).host?.lowercase()
        }.getOrNull().orEmpty()
        if (host.isNotEmpty()) {
            for ((hostPattern, providerId) in HOST_PATTERNS) {
                if (host.contains(hostPattern, ignoreCase = true)) {
                    providers[providerId]?.let { return it }
                }
            }
        }

        // 3. 兜底通用 Provider(对未知中转站友好,走 OpenAI 兼容协议)
        return genericProvider
    }

    companion object {
        private const val TAG = "VideoProviderRegistry"

        /**
         * baseUrl host 模式 → providerId 映射(按优先级排序)。
         *
         * host 模糊匹配用于覆盖用户自定义 baseUrl 但 specId 留空的情况。
         */
        private val HOST_PATTERNS: List<Pair<String, String>> = listOf(
            // Agnes 优先匹配(避免被通用兜底拦截)
            "agnes-ai" to AgnesVideoProvider.PROVIDER_ID,
            // Kling
            "klingai" to KlingVideoProvider.PROVIDER_ID,
            "kuaishou" to KlingVideoProvider.PROVIDER_ID,
        )

        /**
         * 从 URL 中提取 host(小写),供日志诊断用。
         */
        internal fun extractHost(baseUrl: String): String {
            if (baseUrl.isBlank()) return ""
            return runCatching {
                java.net.URI(baseUrl).host?.lowercase() ?: ""
            }.getOrElse {
                val afterScheme = baseUrl.substringAfter("://", baseUrl)
                afterScheme.substringBefore('/').lowercase()
            }
        }
    }
}
