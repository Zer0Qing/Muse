package io.zer0.ai

import io.zer0.common.ErrorCode
import io.zer0.common.toMessage
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ModelVerification
import io.zer0.ai.core.Provider
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.ProviderCompatRules
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.ai.core.withFirstEventWatchdog
import io.zer0.ai.core.firstEventTimeoutMs
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
        // 指纹:id + type + baseUrl + apiKey 后4位 + specific + P1-3 限流参数,变化即失效
        // P1-3: 限流参数纳入指纹,确保用户修改 RPM/并发后缓存失效重建 decorator
        val fp = "${config.type}|${config.resolvedBaseUrl()}|${config.apiKey.takeLast(4)}|${config.specific}" +
            "|rpm=${config.requestLimitPerMinute}|conc=${config.maxConcurrentRequests}"
        val key = CacheKey(config.id, fp)
        return cache.getOrPut(key) {
            val base = when (config.type) {
                ProviderType.OPENAI -> OpenAIProvider(config)
                ProviderType.ANTHROPIC -> AnthropicProvider(config)
                ProviderType.GEMINI -> GeminiProvider(config)
                // v1.0.6: OPENAI_RESPONSES 暂复用 OpenAIProvider(其内部通过 specific.useResponseApi 切换到 /v1/responses)
                ProviderType.OPENAI_RESPONSES -> OpenAIProvider(config)
            }
            // P1-3: 任一限流参数 > 0 时叠加 RateLimitDecorator(RPM 滑动窗口 + 并发 Semaphore)
            // decorator 是前置控制,与 KeyRoulette(后置 429 key 切换)互补;两者可叠加共存
            if (config.requestLimitPerMinute > 0 || config.maxConcurrentRequests > 0) {
                io.zer0.ai.decorator.RateLimitDecorator(
                    delegate = base,
                    requestLimitPerMinute = config.requestLimitPerMinute,
                    maxConcurrentRequests = config.maxConcurrentRequests,
                )
            } else {
                base
            }
        }
    }

    /** 清除所有缓存(配置删除/重置时调用)。 */
    fun clearCache() {
        cache.clear()
    }
}

/**
 * Decide whether the request should carry tool definitions.
 *
 * Provider model-list endpoints frequently omit capability metadata for custom
 * or relay models. An empty ability set therefore means "unknown", not
 * "unsupported"; only an explicit known capability set without TOOL should
 * suppress tools.
 */
internal fun shouldSendTools(
    model: Model,
    config: ProviderConfig,
    tools: List<ToolDefinition>?,
): Boolean = when {
    tools.isNullOrEmpty() -> false
    // MCP 工具是助手显式绑定的外部能力。只有在模型能力为空、上游没有给出可靠
    // 能力声明时,才交给 Provider 能力矩阵尝试发送;模型明确声明“仅推理”时仍不能
    // 强行塞 tools,避免把 MCP 问题变成 API 400。
    tools.any { it.name.startsWith("mcp_") } && model.abilities.isEmpty() -> ProviderCompatRules.resolve(
        providerType = config.type,
        baseUrl = config.resolvedBaseUrl(),
        modelId = model.id,
    ).supportsToolCalling
    model.abilities.isNotEmpty() -> ModelAbility.TOOL in model.abilities
    model.verification != ModelVerification.UNVERIFIED -> false
    else -> ProviderCompatRules.resolve(
        providerType = config.type,
        baseUrl = config.resolvedBaseUrl(),
        modelId = model.id,
    ).supportsToolCalling
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
        toolChoice: String? = null,
        nativeWebSearch: Boolean = false,
        reasoningLevel: ReasoningLevel = ReasoningLevel.DEFAULT,
        providerConfig: ProviderConfig? = null,
        mode: ChatRequestMode = ChatRequestMode.CHAT,
        resumeFromText: String? = null,
        topP: Float? = null,
    ): Flow<ChatStreamEvent> {
        // C-11 ②: 续传能力与 Provider 能力绑定 — 仅当 Provider 声明 supportsResumeFromText 时,
        // 才把已产出文本作为"末尾 assistant 消息"注入,让模型从中断处续写。
        // 不支持续传的 Provider 会误解/重答一段被截断的 assistant 轮次,此时关闭自动续传,
        // 交由 UI 层 LCP 去重(duplicateRemaining)接管去重,而不是把部分消息发给模型。
        val effectiveMessages = if (!resumeFromText.isNullOrBlank() &&
            supportsResumeFromText(providerConfig, model)
        ) {
            messages + UIMessage(role = MessageRole.ASSISTANT, content = resumeFromText)
        } else {
            messages
        }
        val (provider, request) = buildProviderRequest(
            effectiveMessages,
            model,
            temperature,
            maxTokens,
            tools,
            toolChoice,
            nativeWebSearch,
            reasoningLevel,
            providerConfig,
            mode,
            topP,
        )
        // B3-01: SSE 建立后 15s 无首事件,自动降级为非流式重试一次
        // 审计修复 (7.8): 用户已停止时不再 fallback(省一次计费请求)
        return provider.streamChat(request).withFirstEventWatchdog(
            timeoutMs = model?.firstEventTimeoutMs() ?: 15_000L,
            fallback = { provider.completeText(request) },
            abortCheck = { request.abortSignal.aborted },
        )
    }

    /**
     * C-11: 查询 [ProviderCompatRules] 判定该 Provider 是否支持 resumeFromText 续传。
     *
     * 按 providerType + baseUrl(host) + modelId 三层解析(与请求体参数构造一致的能力矩阵)。
     * Provider 未声明时保守返回 true,行为与未接入续传门控前一致。
     */
    private suspend fun supportsResumeFromText(providerConfig: ProviderConfig?, model: Model?): Boolean {
        // providerConfig 由调用方显式传入通常直接命中;缺省回退 configStore(与 buildProviderRequest 同源)。
        // 取不到配置时保守放行续传:不放行会静默跳过续传,比可能重答的危害更隐蔽,故让 Provider 能力矩阵兜底。
        val config = providerConfig ?: runCatching { configStore.get() }.getOrNull() ?: return true
        return ProviderCompatRules.resolve(config.type, config.resolvedBaseUrl(), model?.id).supportsResumeFromText
    }

    /**
     * 非流式聊天。一次性返回完整结果,适用于 memory 编译、fact 抽取等后台任务。
     * @see [buildProviderRequest] 负责公共前置逻辑。
     *
     * v1.0.7: 默认 [mode]=[ChatRequestMode.UTILITY](对齐 既有实现 callText 硬编码 utility),
     *   后台任务无需显式传 mode 即可自动关思考。用户对话路径应调 [streamChat]。
     */
    suspend fun completeText(
        messages: List<UIMessage>,
        model: Model? = null,
        temperature: Float? = null,
        maxTokens: Int? = null,
        tools: List<ToolDefinition>? = null,
        toolChoice: String? = null,
        nativeWebSearch: Boolean = false,
        reasoningLevel: ReasoningLevel = ReasoningLevel.DEFAULT,
        providerConfig: ProviderConfig? = null,
        mode: ChatRequestMode = ChatRequestMode.UTILITY,
        topP: Float? = null,
    ): ChatCompletion {
        val (provider, request) = buildProviderRequest(
            messages,
            model,
            temperature,
            maxTokens,
            tools,
            toolChoice,
            nativeWebSearch,
            reasoningLevel,
            providerConfig,
            mode,
            topP,
        )
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
        toolChoice: String?,
        nativeWebSearch: Boolean,
        reasoningLevel: ReasoningLevel,
        providerConfig: ProviderConfig? = null,
        mode: ChatRequestMode = ChatRequestMode.CHAT,
        topP: Float? = null,
    ): Pair<Provider, ChatRequest> {
        val config = providerConfig ?: configStore.get()
            ?: error(ErrorCode.NO_PROVIDER_CONFIGURED.toMessage())
        val resolvedModel = model ?: config.models.firstOrNull()
            ?: error(ErrorCode.NO_MODEL_SELECTED.toMessage())
        // Phase 2C:通过 ModelRegistry 自动适配模型能力
        val enhancedModel = ModelRegistry.enhanceModel(resolvedModel)
        // 未声明能力的自定义/中转模型不能当成“不支持工具”;交给 Provider 能力矩阵判断。
        val effectiveTools = tools.takeIf { shouldSendTools(enhancedModel, config, it) }
        val provider = ProviderRegistry.create(config)
        // v1.0.7: UTILITY 模式强制关思考(对齐 既有实现 buildProviderCompatOptions)
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
            toolChoice = toolChoice
                ?.takeIf { it == "auto" || it == "required" || it == "none" }
                ?.takeIf { effectiveTools != null },
            // 原生搜索仅由明确支持其协议的 Provider 接管；其他类型 fail closed，
            // 防止把 google_search / web_search_preview 字段误发给兼容层导致 400。
            nativeWebSearch = nativeWebSearch && config.type in setOf(
                ProviderType.GEMINI,
                ProviderType.OPENAI_RESPONSES,
            ),
            reasoningLevel = effectiveReasoningLevel,
            mode = mode,
            topP = topP?.coerceIn(0f, 1f),
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
