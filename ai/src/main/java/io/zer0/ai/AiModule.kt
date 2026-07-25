package io.zer0.ai

import io.zer0.common.ErrorCode
import io.zer0.common.toMessage
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.Model
import io.zer0.ai.core.Provider
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.ai.anthropic.AnthropicProvider
import io.zer0.ai.gemini.GeminiProvider
import io.zer0.ai.openai.OpenAIProvider
import io.zer0.ai.registry.ModelRegistry
import kotlinx.coroutines.flow.Flow

/**
 * Provider 注册表。根据 [ProviderConfig.type] 动态构造对应 [Provider] 实例。
 *
 * v1.80 (M-CORE9): 加入简单缓存 — 按 config.id + apiKey + baseUrl 的指纹缓存 Provider 实例,
 * 避免每次请求都重建 OkHttpClient / VertexAiAuthToken 等重型对象。
 * config 内容变化时自动失效重建。
 */
object ProviderRegistry {

    private data class CacheKey(val configId: String, val fingerprint: String)
    private val cache = java.util.concurrent.ConcurrentHashMap<CacheKey, Provider>()

    fun create(config: ProviderConfig): Provider {
        // 指纹:id + type + baseUrl + apiKey 前后4位,变化即失效
        val fp = "${config.type}|${config.resolvedBaseUrl()}|${config.apiKey.takeLast(4)}|${config.specific}"
        val key = CacheKey(config.id, fp)
        return cache.getOrPut(key) {
            when (config.type) {
                ProviderType.OPENAI -> OpenAIProvider(config)
                ProviderType.ANTHROPIC -> AnthropicProvider(config)
                ProviderType.GEMINI -> GeminiProvider(config)
                // v1.0.6: OPENAI_RESPONSES 暂复用 OpenAIProvider(其内部通过 specific.useResponseApi 切换到 /v1/responses)
                ProviderType.OPENAI_RESPONSES -> OpenAIProvider(config)
            }
        }
    }

    /** 清除所有缓存(配置删除/重置时调用)。 */
    fun clearCache() {
        cache.clear()
    }
}

/**
 * 应用层调用 AI 的统一入口。
 *
 * 设计为无状态:每次调用都基于最新 config 重建 Provider。
 * 所有公共逻辑集中在 [buildProviderRequest] 以消除 streamChat 和 completeText 之间的重复。
 */
class ChatService(
    private val configStore: ProviderConfigStore,
) {
    /**
     * 流式聊天。委托给 Provider.streamChat。
     * @see [buildProviderRequest] 负责公共前置逻辑。
     */
    suspend fun streamChat(
        messages: List<UIMessage>,
        model: Model? = null,
        temperature: Float? = null,
        maxTokens: Int? = null,
        tools: List<ToolDefinition>? = null,
        reasoningLevel: ReasoningLevel = ReasoningLevel.DEFAULT,
        providerConfig: ProviderConfig? = null,
        mode: ChatRequestMode = ChatRequestMode.CHAT,
    ): Flow<ChatStreamEvent> {
        val (provider, request) = buildProviderRequest(messages, model, temperature, maxTokens, tools, reasoningLevel, providerConfig, mode)
        return provider.streamChat(request)
    }

    /**
     * 非流式聊天。一次性返回完整结果,适用于 memory 编译、fact 抽取等后台任务。
     * @see [buildProviderRequest] 负责公共前置逻辑。
     *
     * v1.0.7: 默认 [mode]=[ChatRequestMode.UTILITY](对齐 openhanako callText 硬编码 utility),
     *   后台任务无需显式传 mode 即可自动关思考。用户对话路径应调 [streamChat]。
     */
    suspend fun completeText(
        messages: List<UIMessage>,
        model: Model? = null,
        temperature: Float? = null,
        maxTokens: Int? = null,
        tools: List<ToolDefinition>? = null,
        reasoningLevel: ReasoningLevel = ReasoningLevel.DEFAULT,
        providerConfig: ProviderConfig? = null,
        mode: ChatRequestMode = ChatRequestMode.UTILITY,
    ): ChatCompletion {
        val (provider, request) = buildProviderRequest(messages, model, temperature, maxTokens, tools, reasoningLevel, providerConfig, mode)
        return provider.completeText(request)
    }

    /**
     * [streamChat] 和 [completeText] 的公共前置逻辑。
     * 从 configStore 获取配置,解析模型,创建 Provider,构造 ChatRequest。
     * 提取后两个方法都不再需要维护重复的 15 行样板代码。
     */
    private suspend fun buildProviderRequest(
        messages: List<UIMessage>,
        model: Model?,
        temperature: Float?,
        maxTokens: Int?,
        tools: List<ToolDefinition>?,
        reasoningLevel: ReasoningLevel,
        providerConfig: ProviderConfig? = null,
        mode: ChatRequestMode = ChatRequestMode.CHAT,
    ): Pair<Provider, ChatRequest> {
        val config = providerConfig ?: configStore.get()
            ?: error(ErrorCode.NO_PROVIDER_CONFIGURED.toMessage())
        val resolvedModel = model ?: config.models.firstOrNull()
            ?: error(ErrorCode.NO_MODEL_SELECTED.toMessage())
        // Phase 2C:通过 ModelRegistry 自动适配模型能力
        val enhancedModel = ModelRegistry.enhanceModel(resolvedModel)
        // 若模型不支持工具调用则剥离工具
        val effectiveTools = if (enhancedModel.supportsToolCalling()) tools else null
        val provider = ProviderRegistry.create(config)
        // v1.0.7: UTILITY 模式强制关思考(对齐 openhanako buildProviderCompatOptions)
        //  在 ChatService 层统一覆盖 reasoningLevel=OFF,所有 Provider(OpenAI/Anthropic/Gemini)
        //  自动生效,无需各 Provider 内部重复判断 mode。
        val effectiveReasoningLevel = if (mode == ChatRequestMode.UTILITY) {
            ReasoningLevel.OFF
        } else {
            reasoningLevel
        }
        val request = ChatRequest(
            messages = messages,
            model = enhancedModel,
            temperature = temperature,
            maxTokens = maxTokens,
            tools = effectiveTools,
            reasoningLevel = effectiveReasoningLevel,
            mode = mode,
        )
        return Pair(provider, request)
    }
}

/** Provider 配置存取抽象,由 app 模块基于 DataStore 实现。 */
interface ProviderConfigStore {
    /** 当前生效的 Provider 配置,null 表示未配置。 */
    suspend fun get(): ProviderConfig?

    /** v1.54: 全部已配置的 Provider 列表(用于 embedding provider 选择)。默认空列表。 */
    suspend fun getAllProviders(): List<ProviderConfig> = emptyList()
}