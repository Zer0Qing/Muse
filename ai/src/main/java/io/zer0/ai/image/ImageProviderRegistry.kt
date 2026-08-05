package io.zer0.ai.image

import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.Logger

/**
 * v1.0.18: 图片生成 Provider 注册中心。
 *
 * 参考 QingTian(参考开源项目)的 AdapterRegistry 模式:
 *  - 各 [ImageProvider] 实现类在启动时注册到本中心;
 *  - 调用方通过 [selectFor] 按 [ProviderConfig] 自动选择合适的 provider;
 *  - 选择策略按 specId / baseUrl host / type 三层匹配,兜底 OpenAIImageProvider。
 *
 * 选择优先级:
 *  1. specId / id 精确匹配(如 specId="agnes" → AgnesImageProvider);
 *  2. baseUrl host 包含供应商关键字(如含 "agnes" → AgnesImageProvider);
 *  3. type 匹配(OPENAI / OPENAI_RESPONSES → OpenAIImageProvider);
 *  4. 兜底 OpenAIImageProvider(对未知中转站友好,走 OpenAI 兼容协议)。
 *
 * Anthropic / Gemini 等暂未实现独立 ImageProvider,会兜底到 OpenAIImageProvider
 * 并由其返回的 HTTP 错误自然提示用户。Gemini 绘图走 streamChat 多模态路径,
 * 由 ImageGenCoordinator 直接处理,不经过本注册中心。
 */
class ImageProviderRegistry {

    private val providers = mutableMapOf<String, ImageProvider>()

    /** 注册一个 [ImageProvider](以其 [ImageProvider.providerId] 为 key,后者覆盖前者)。 */
    fun register(provider: ImageProvider) {
        providers[provider.providerId] = provider
        Logger.i(TAG, "registered image provider: ${provider.providerId}")
    }

    /** 按 providerId 取已注册的 [ImageProvider]。 */
    fun get(providerId: String): ImageProvider? = providers[providerId]

    /** 所有已注册的 provider。 */
    fun all(): Collection<ImageProvider> = providers.values

    /**
     * 根据 [ProviderConfig] 自动选择合适的 [ImageProvider]。
     *
     * 选择顺序见类注释。返回 null 仅当注册中心为空(启动未注册任何 provider)。
     */
    fun selectFor(config: ProviderConfig): ImageProvider? {
        // 1. specId / id 精确匹配
        val key = config.specId?.takeIf { it.isNotBlank() }
            ?: config.id.removePrefix("preset_").takeIf { it.isNotBlank() }
        if (key != null) {
            providers[key]?.let { return it }
        }

        // 2. baseUrl host 关键字匹配
        val host = runCatching {
            java.net.URI(config.baseUrl).host?.lowercase()
        }.getOrNull().orEmpty()
        if (host.isNotEmpty()) {
            // 含 "agnes" → Agnes(对齐 ENDPOINT_AGNES=apihub.agnes-ai.com)
            if (host.contains("agnes")) {
                providers[AgnesImageProvider.PROVIDER_ID]?.let { return it }
            }
        }

        // 3. type 匹配:OPENAI / OPENAI_RESPONSES → OpenAIImageProvider
        if (config.type == ProviderType.OPENAI || config.type == ProviderType.OPENAI_RESPONSES) {
            providers[OpenAIImageProvider.PROVIDER_ID]?.let { return it }
        }

        // 4. 兜底 OpenAIImageProvider(对未知中转站友好)
        return providers[OpenAIImageProvider.PROVIDER_ID]
    }

    companion object {
        private const val TAG = "ImageProviderRegistry"
    }
}
