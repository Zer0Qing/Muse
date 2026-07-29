package io.zer0.ai.video

import io.zer0.ai.core.BaseProviderRegistry
import io.zer0.ai.core.ProviderConfig

/**
 * v1.137: Video Provider 注册中心。
 * v1.0.30 (P5-D): 继承 [BaseProviderRegistry],消除与 ImageProviderRegistry 的孪生代码。
 *
 * 选择优先级:
 *  1. specId / id(去 preset_ 前缀)精确匹配;
 *  2. baseUrl host 包含供应商关键字(如含 "agnes-ai" → AgnesVideoProvider);
 *  3. 兜底 [genericProvider](通用 OpenAI 兼容协议,对未知中转站友好)。
 *
 * @param genericProvider 通用兜底 Provider(未命中时返回)
 */
class VideoProviderRegistry(
    private val genericProvider: GenericOpenAiVideoProvider,
) : BaseProviderRegistry<VideoProvider>(TAG) {

    /** 注册一个 [VideoProvider]。 */
    fun register(provider: VideoProvider) = register(provider.providerId, provider)

    override val hostPatterns: List<Pair<String, String>> = listOf(
        // Agnes 优先匹配(避免被通用兜底拦截)
        "agnes-ai" to AgnesVideoProvider.PROVIDER_ID,
        // Kling
        "klingai" to KlingVideoProvider.PROVIDER_ID,
        "kuaishou" to KlingVideoProvider.PROVIDER_ID,
    )

    /**
     * 根据 [ProviderConfig] 自动选择合适的 [VideoProvider]。
     * 永不返回 null([genericProvider] 兜底)。
     */
    fun selectFor(config: ProviderConfig): VideoProvider =
        selectForInternal(config) { genericProvider } ?: genericProvider

    companion object {
        private const val TAG = "VideoProviderRegistry"
    }
}
