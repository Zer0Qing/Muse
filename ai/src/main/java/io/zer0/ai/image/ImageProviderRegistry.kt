package io.zer0.ai.image

import io.zer0.ai.core.BaseProviderRegistry
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType

/**
 * v1.0.18: 图片生成 Provider 注册中心。
 * v1.0.30 (P5-D): 继承 [BaseProviderRegistry],消除与 VideoProviderRegistry 的孪生代码。
 *
 * 选择优先级:
 *  1. specId / id 精确匹配(如 specId="agnes" → AgnesImageProvider);
 *  2. baseUrl host 包含供应商关键字(如含 "agnes" → AgnesImageProvider);
 *  3. type 匹配(OPENAI / OPENAI_RESPONSES → OpenAIImageProvider);
 *  4. 兜底 OpenAIImageProvider(对未知中转站友好,走 OpenAI 兼容协议)。
 */
class ImageProviderRegistry : BaseProviderRegistry<ImageProvider>(TAG) {

    /** 注册一个 [ImageProvider](以其 [ImageProvider.providerId] 为 key)。 */
    fun register(provider: ImageProvider) = register(provider.providerId, provider)

    override val hostPatterns: List<Pair<String, String>> = listOf(
        "agnes" to AgnesImageProvider.PROVIDER_ID,
    )

    /**
     * 根据 [ProviderConfig] 自动选择合适的 [ImageProvider]。
     * 返回 null 仅当注册中心为空(启动未注册任何 provider)。
     */
    fun selectFor(config: ProviderConfig): ImageProvider? = selectForInternal(config) {
        // 3. type 匹配:OPENAI / OPENAI_RESPONSES → OpenAIImageProvider
        if (config.type == ProviderType.OPENAI || config.type == ProviderType.OPENAI_RESPONSES) {
            get(OpenAIImageProvider.PROVIDER_ID)
        } else {
            // 4. 兜底 OpenAIImageProvider
            get(OpenAIImageProvider.PROVIDER_ID)
        }
    }

    companion object {
        private const val TAG = "ImageProviderRegistry"
    }
}
