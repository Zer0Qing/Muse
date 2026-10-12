package io.zer0.ai

import io.zer0.ai.anthropic.AnthropicProvider
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ModelOutputPolicy
import io.zer0.ai.core.Provider
import io.zer0.ai.core.ProviderCompat
import io.zer0.ai.core.ProviderCompatRules
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.ai.core.firstEventTimeoutMs
import io.zer0.ai.core.withFirstEventWatchdog
import io.zer0.ai.gemini.GeminiProvider
import io.zer0.ai.openai.OpenAIProvider
import io.zer0.ai.registry.ModelRegistry
import io.zer0.common.ErrorCode
import io.zer0.common.Logger
import io.zer0.common.toMessage
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
 * v2.4.5: 采样温度出口归一常量。
 *
 * 应用内允许的温度范围是 [0, 2],但部分供应商走开区间(如商汤 [0.0, 2.0))。
 * 这里给出通用下界/上界,[ProviderCompat.maxTemperatureExclusive] 可进一步收紧上界。
 */
private const val MIN_TEMPERATURE = 0f
private const val MAX_TEMPERATURE = 2f

/** 排他上界收紧时留出的余量,保证落在开区间内。 */
private const val TEMPERATURE_EPSILON = 0.0001f

/**
 * v2.4.5: 采样温度出口归一。
 *
 * 应用内允许的范围是 [0, 2],但部分供应商走开区间(如商汤 [0.0, 2.0)),
 * 由 [ProviderCompat.maxTemperatureExclusive] 声明。原值透传会被上游以
 * 400 "field Temperature invalid" 拒绝,这里统一收紧。
 */
internal fun normalizeTemperature(temperature: Float?, compat: ProviderCompat): Float? {
    val raw = temperature ?: return null
    val upper = compat.maxTemperatureExclusive?.let { it - TEMPERATURE_EPSILON } ?: MAX_TEMPERATURE
    return raw.coerceIn(MIN_TEMPERATURE, upper)
}

/**
 * Decide whether the request should carry tool definitions.
 *
 * Provider model-list endpoints frequently omit capability metadata for custom
 * or relay models. An empty ability set therefore means "unknown", not
 * "unsupported"; only an explicit known capability set without TOOL should
 * suppress tools.
 *
 * v2.6.1: 区分“显式声明不支持”与“未声明”—— 修复中转站/云目录只写了 reasoning
 *   而漏写 toolUse 时(如 deepseek-flash,abilities=[REASONING])工具链被整条
 *   切断、模型只能把工具调用当普通文本吐出的问题。
 *   优先级(从高到低):
 *     1) 用户显式编辑过的模型(userEdited)→ 严格以用户的 abilities 为准,
 *        用户说支持就发、说只推理就不发 —— 用户配置优先级最高。
 *     2) abilities 含 TOOL → 发送。
 *     3) 其余(未声明 / 只声明了 REASONING 等)→ 交由 Provider 能力矩阵终裁,
 *        只有矩阵**显式**判不支持(如 deepseek-reasoner / o1-mini)才抑制。
 *
 * v2.1.0: 抑制路径统一留日志(静默丢弃曾让整个 Agent 工具链路无声失效)。
 */
internal fun shouldSendTools(model: Model, config: ProviderConfig, tools: List<ToolDefinition>?): Boolean {
    if (tools.isNullOrEmpty()) return false
    val sends =
        when {
            // 1) 用户显式编辑过能力 → 用户配置最高,以其声明为准。
            model.userEdited -> ModelAbility.TOOL in model.abilities
            // 2) 明确声明支持工具 → 发送。
            ModelAbility.TOOL in model.abilities -> true
            // 3) 未声明 / 部分声明 → 兼容矩阵终裁;未知 ≠ 不支持。
            else ->
                ProviderCompatRules.resolve(
                    providerType = config.type,
                    baseUrl = config.resolvedBaseUrl(),
                    modelId = model.id,
                ).supportsToolCalling
        }
    if (!sends) {
        Logger.w(
            "ChatService",
            "工具未发送: 模型 ${model.id} 被判定不支持 function calling | " +
                "abilities=${model.abilities}, userEdited=${model.userEdited}, " +
                "verification=${model.verification}, " +
                "discardedTools=${tools.size}, provider=${config.id}",
        )
    }
    return sends
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
        // B3-01: SSE 建立后长时间无首事件(默认 45s,推理/超长上下文档 90s),自动降级为非流式重试一次
        // v2.x: 15s→45s — 慢模型首 token 常在 15s 之后,过早降级导致“整段弹出”+ 双倍等待
        // 审计修复 (7.8): 用户已停止时不再 fallback(省一次计费请求)
        return provider.streamChat(request).withFirstEventWatchdog(
            timeoutMs = model?.firstEventTimeoutMs() ?: 45_000L,
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
        // v2.2.1: 首选回退改为 store 声明的"用户当前选中模型"(默认实现 null 保持旧行为)。
        // 原先直接取 config.models.firstOrNull():当激活 Provider 首个模型不可用/非对话模型时,
        // 所有未传 model 的后台任务(主动消息/日记/日报/翻译等)都会静默跑错模型。
        val resolvedModel = model ?: configStore.getPreferredModel() ?: config.models.firstOrNull()
            ?: error(ErrorCode.NO_MODEL_SELECTED.toMessage())
        // Phase 2C:通过 ModelRegistry 自动适配模型能力
        val enhancedModel = ModelRegistry.enhanceModel(resolvedModel)
        // 未声明能力的自定义/中转模型不能当成“不支持工具”;交给 Provider 能力矩阵判断。
        val effectiveTools = tools.takeIf { shouldSendTools(enhancedModel, config, it) }
        val estimatedInputTokens = ModelOutputPolicy.estimateInputTokens(messages, effectiveTools)
        val regularMaxTokens = ModelOutputPolicy.resolve(maxTokens, enhancedModel)
        val effectiveMaxTokens = ModelOutputPolicy.resolveForContext(
            requestedMaxTokens = maxTokens,
            model = enhancedModel,
            inputTokens = estimatedInputTokens,
        )
        if (effectiveMaxTokens != regularMaxTokens || ModelOutputPolicy.wasClamped(maxTokens, enhancedModel)) {
            Logger.w(
                "ChatService",
                "输出预算已按模型能力收紧: provider=${config.id}, model=${enhancedModel.id}, " +
                    "requested=$maxTokens, inputTokens≈$estimatedInputTokens, " +
                    "modelLimit=${enhancedModel.maxOutputTokens}, contextWindow=${enhancedModel.contextWindow}, " +
                    "effective=$effectiveMaxTokens",
            )
        }
        val provider = ProviderRegistry.create(config)
        // v2.4.5: 取一次能力矩阵,供温度归一使用(与 Provider 内部 resolvedCompat 同一口径)。
        val compat = config.resolvedCompat(enhancedModel.id)
        // v1.0.7: UTILITY 模式强制关思考(对齐 既有实现 buildProviderCompatOptions)
        //  在 ChatService 层统一覆盖 reasoningLevel=OFF,所有 Provider(OpenAI/Anthropic/Gemini)
        //  自动生效,无需各 Provider 内部重复判断 mode。
        val effectiveReasoningLevel = if (mode == ChatRequestMode.UTILITY) {
            ReasoningLevel.OFF
        } else {
            reasoningLevel
        }
        // v2.4.5: 温度出口归一 — 与 topP 同位置收口。
        //  应用内温度滑块上限为 2.0、第三方角色卡导入值可任意,而部分供应商对
        //  temperature 走开区间(如商汤 [0.0, 2.0),2.0 非法)。
        val effectiveTemperature = normalizeTemperature(temperature, compat)
        val request = ChatRequest(
            messages = messages,
            model = enhancedModel,
            temperature = effectiveTemperature,
            maxTokens = effectiveMaxTokens,
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

    /**
     * v2.2.1: 后台任务未显式指定模型时的首选回退(通常返回"用户当前选中的模型")。
     *
     * [ChatService.buildProviderRequest] 的模型三级链条:
     * 调用方显式传入 → [getPreferredModel] → 激活 Provider 首个模型。
     * 默认实现返回 null 保持旧行为(仅影响未覆写的测试替身)。
     */
    suspend fun getPreferredModel(): Model? = null
}
