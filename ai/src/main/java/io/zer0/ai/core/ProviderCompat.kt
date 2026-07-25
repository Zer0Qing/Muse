package io.zer0.ai.core

import io.zer0.common.Logger

/**
 * Provider 能力矩阵(compatibility)。
 *
 * 不同 Provider / 中转 / 模型对 OpenAI 协议的支持程度不同(如 Anthropic 不支持
 * developer role、Gemini OpenAI 兼容层不支持 store、Zhipu 不支持 reasoning_effort)。
 * 本类把这些差异集中化,Provider 在请求构造前查询对应字段决定是否注入某些参数,
 * 避免在每个 Provider 内分散硬编码特判。
 *
 * 设计原则:
 *  - 保守默认值:不明确的字段一律 true(让 Provider 自己处理或忽略未知参数),
 *    仅在确证不支持时显式置 false。
 *  - 集中派生:所有差异判断集中在 [ProviderCompatRules.resolve],
 *    Provider 只消费结果,不重复推理。
 *
 * 字段语义:
 * @param supportsDeveloperRole 是否支持把 system role 切换为 developer role
 *   (OpenAI o1/o3 等推理模型要求用 developer role 传递系统指令)。
 * @param supportsStore 是否支持 OpenAI Chat Completions 的 store 参数
 *   (用于在 Assistants API 之外持久化对话)。
 * @param supportsReasoningEffort 是否支持 reasoning_effort 参数
 *   (OpenAI o 系列 / GPT-5 推理等级;DeepSeek/Anthropic/Gemini 各自用不同字段)。
 * @param supportsToolCalling 是否支持 function calling / tools 参数。
 * @param supportsVision 是否支持图片输入(多模态)。
 * @param supportsParallelToolCalls 是否支持 parallel_tool_calls 参数。
 * @param supportsJsonMode 是否支持 response_format={"type":"json_object"}。
 * @param supportsStreamOptions 是否支持 stream_options.include_usage。
 * @param supportsLogprobs 是否支持 logprobs 参数(默认 false,多数 Provider 不常用)。
 * @param maxContextWindow Provider 级上下文窗口上限(token),null 表示无统一上限。
 * @param defaultMaxTokens 默认 max_tokens,null 表示用 Provider 默认。
 * @param thinkingFormat v1.0.7: 思考格式(对齐 openhanako thinkingFormat 9 种)。
 *   null=走标准 reasoning_effort;非 null=走对应厂商扩展字段(thinking / enable_thinking 等)。
 *   仅驱动 OpenAIProvider 的请求体构造;AnthropicProvider / GeminiProvider 各自实现原生思考。
 * @param reasoningReplayContract v1.0.7: reasoning 回放契约(carrier + policy)。
 *   null=不回放历史 reasoning;非 null=按 carrier/policy 在请求体中写入对应 wire 字段。
 *   决定 UIMessage.reasoning / thinkingSignature 如何序列化回请求体(多轮对话必备)。
 *
 * 独立编写,参考 openhanako 项目的 core/provider-compat/ 目录实现思路。
 */
data class ProviderCompat(
    val supportsDeveloperRole: Boolean = true,
    val supportsStore: Boolean = true,
    val supportsReasoningEffort: Boolean = true,
    val supportsToolCalling: Boolean = true,
    val supportsVision: Boolean = true,
    val supportsParallelToolCalls: Boolean = true,
    val supportsJsonMode: Boolean = true,
    val supportsStreamOptions: Boolean = true,
    val supportsLogprobs: Boolean = false,
    val maxContextWindow: Int? = null,
    val defaultMaxTokens: Int? = null,
    /**
     * v1.0.7: 思考格式(对齐 openhanako thinkingFormat 9 种)。
     *
     * - null:走标准 OpenAI `reasoning_effort` 字段(由 [supportsReasoningEffort] 决定是否发送)
     * - [ThinkingFormat.DEEPSEEK]:不发任何思考参数,仅消费流式 reasoning_content
     * - [ThinkingFormat.KIMI]:发 `thinking: {type: "enabled"|"disabled", keep: bool}`
     * - [ThinkingFormat.QWEN]:发 `enable_thinking: bool`(+ 可选 `thinking_budget: int`)
     * - [ThinkingFormat.QWEN_CHAT_TEMPLATE]:发 `chat_template_kwargs: {enable_thinking: bool}`
     * - [ThinkingFormat.ZHIPU]:发 `thinking: {type: "enabled"|"disabled", clear_thinking: bool}`
     * - [ThinkingFormat.OPENROUTER]:发 `reasoning: {effort: "low"|"medium"|"high"}`
     * - [ThinkingFormat.VOLCENGINE]:发 `thinking: {type: "enabled"|"disabled"}`
     * - [ThinkingFormat.LONGCAT]:发 `thinking: {type: "enabled"|"disabled"}`
     * - [ThinkingFormat.ANTHROPIC]:OpenAIProvider 不消费(AnthropicProvider 自身处理原生 thinking 块)
     */
    val thinkingFormat: ThinkingFormat? = null,
    /**
     * v1.0.7: reasoning 回放契约(对齐 openhanako 5 种 carrier + 3 种 policy)。
     *
     * - null:不回放历史 reasoning(默认,向后兼容)
     * - [ReasoningReplayContract]([ReasoningCarrier.REASONING_CONTENT], [ReasoningReplayPolicy.REQUIRE_TOOL_CALL]):
     *   Kimi / DeepSeek / MiMo / Zhipu Chat Completions(仅 tool_calls 时回放)
     * - [ReasoningReplayContract]([ReasoningCarrier.REASONING_ITEMS], [ReasoningReplayPolicy.PRESERVE]):
     *   OpenAI Responses API(始终保留 reasoning item)
     * - [ReasoningReplayContract]([ReasoningCarrier.REASONING_DETAILS], [ReasoningReplayPolicy.PRESERVE]):
     *   OpenRouter(始终保留 reasoning_details)
     */
    val reasoningReplayContract: ReasoningReplayContract? = null,
)

/**
 * Provider 能力派生规则。
 *
 * 三层匹配策略(由粗到细,后者覆盖前者):
 *  1. 按 [ProviderType] 分发(基础规则,反映协议级差异)
 *  2. 按 baseUrl host 进一步细化(反映厂商 OpenAI 兼容层差异)
 *  3. 按 modelId 进一步细化(反映具体模型对工具调用/推理的支持情况)
 *
 * 任一层未命中时保持上一层的值,最终返回融合后的 [ProviderCompat]。
 *
 * 独立编写。
 */
object ProviderCompatRules {

    private const val TAG = "ProviderCompatRules"

    /**
     * 派生 [ProviderCompat]。
     *
     * @param providerType Provider 类型(决定协议级基础规则)
     * @param baseUrl Provider 的 baseUrl(已解析或原始均可,空串时跳过 host 匹配)
     * @param modelId 具体模型 id(可选,用于模型级细化)
     */
    fun resolve(
        providerType: ProviderType,
        baseUrl: String,
        modelId: String? = null,
    ): ProviderCompat {
        // 第 1 层:按 ProviderType 取基础规则
        var compat = baseForType(providerType)

        // 第 2 层:按 baseUrl host 细化
        val host = extractHost(baseUrl)
        if (host.isNotEmpty()) {
            compat = compat.overrideByHost(host)
        }

        // 第 3 层:按 modelId 细化
        if (!modelId.isNullOrBlank()) {
            compat = compat.overrideByModelId(modelId)
        }

        return compat
    }

    /**
     * 第 1 层:按 [ProviderType] 取基础规则。
     *
     * - OPENAI: 全部 true(默认,覆盖 OpenAI 官方 + Custom + 所有 OpenAI 兼容中转)
     * - ANTHROPIC: 不支持 developer role / store / json_mode / stream_options
     *   (Anthropic 走 Messages API,system 是顶层字段,无 store/json_mode/stream_options 概念)
     * - GEMINI: 不支持 store(OpenAI 兼容层限制) / reasoning_effort
     *   (Gemini 用 thinkingConfig.thinkingBudget 而非 reasoning_effort)
     *
     * 注:[ProviderType] 没有 CUSTOM 枚举值,Custom 走 OPENAI 分支(默认全 true),
     *   其差异由第 2 层 host 匹配处理。
     */
    private fun baseForType(providerType: ProviderType): ProviderCompat = when (providerType) {
        ProviderType.OPENAI -> ProviderCompat()
        ProviderType.ANTHROPIC -> ProviderCompat(
            supportsDeveloperRole = false,
            supportsStore = false,
            supportsJsonMode = false,
            supportsStreamOptions = false,
        )
        ProviderType.GEMINI -> ProviderCompat(
            supportsStore = false,
            supportsReasoningEffort = false,
        )
        // v1.0.7: OPENAI_RESPONSES 走 Responses API,基础规则同 OPENAI,
        //   但 reasoning 回放走 REASONING_ITEMS carrier + PRESERVE policy(对齐 openhanako)。
        //   thinkingFormat 留 null(Responses API 自身用 reasoning.effort 字段,由 OpenAIProvider useResponseApi 分支处理)
        ProviderType.OPENAI_RESPONSES -> ProviderCompat(
            reasoningReplayContract = ReasoningReplayContract(
                ReasoningCarrier.REASONING_ITEMS,
                ReasoningReplayPolicy.PRESERVE,
            ),
        )
    }

    /**
     * 第 2 层:按 host 细化。
     *
     * 仅处理已知的国内/海外厂商 OpenAI 兼容端点差异:
     *
     * 国内直连:
     *  - api.deepseek.com: 支持 json_mode,不支持 reasoning_effort
     *    v1.0.7: thinkingFormat=DEEPSEEK(不发思考参数,仅消费流式 reasoning_content)
     *    v1.0.7: reasoningReplay=REASONING_CONTENT/REQUIRE_TOOL_CALL(多轮 tool_calls 必带 reasoning_content)
     *  - api.moonshot.cn / api.kimi.com: 支持 json_mode(Kimi 兼容 OpenAI json_object)
     *    v1.0.7: thinkingFormat=KIMI(thinking.type+keep),reasoningReplay=REASONING_CONTENT/REQUIRE_TOOL_CALL
     *  - open.bigmodel.cn / api.z.ai: 不支持 store,不支持 reasoning_effort
     *    v1.0.7: thinkingFormat=ZHIPU(thinking.type+clear_thinking),reasoningReplay=REASONING_CONTENT/REQUIRE_TOOL_CALL
     *  - dashscope.aliyuncs.com / coding.dashscope.aliyuncs.com: 不支持 store
     *    v1.0.7: thinkingFormat 留 null(由 modelId 层细化,Qwen3 用 QWEN/QWEN_CHAT_TEMPLATE)
     *  - api.xiaomimimo.com / token-plan-cn.xiaomimimo.com: v1.0.7 thinkingFormat=KIMI(小米 MiMo 用类似 Kimi 的 thinking.type)
     *  - api.longcat.chat: v1.0.7 thinkingFormat=LONGCAT(thinking.type)
     *  - ark.cn-beijing.volces.com: 默认全 true;thinkingFormat 留 null(由 modelId 层细化 doubao-thinking)
     *  - api.minimaxi.com: 走 Anthropic 协议(由 type=ANTHROPIC 基础规则处理)
     *  - api.baichuan-ai.com / api.lingyiwanwu.com / api.stepfun.com / api.hunyuan.cloud.tencent.com /
     *    qianfan.baidubce.com / api-inference.modelscope.cn / cloud.infini-ai.com / apihub.agnes-ai.com:
     *    默认全 true(thinkingFormat 留 null,标准 reasoning_effort 协议)
     *
     * 海外直连:
     *  - api.x.ai / cli-chat-proxy.grok.com: 默认全 true(xAI Grok,OpenAI 兼容)
     *  - api.perplexity.ai: 默认全 true(Perplexity Sonar 系列)
     *  - api.groq.com: 不支持 store(Groq 超低延迟推理,无 Assistants API)
     *  - api.together.xyz / api.deepinfra.com / api.fireworks.ai: 默认全 true(聚合,差异由 modelId 处理)
     *  - api.mistral.ai: 不支持 store(Mistral La Plateforme)
     *  - api.githubcopilot.com / models.inference.ai.azure.com: 默认全 true
     *  - localhost: 默认全 true(Ollama 本地,OpenAI 兼容)
     *
     * 中转/聚合站(RELAY):
     *  - api.siliconflow.cn: 默认全 true(thinkingFormat 留 null,SiliconFlow 聚合各家,差异由 modelId 处理)
     *  - openrouter.ai: v1.0.7 thinkingFormat=OPENROUTER(reasoning.effort),reasoningReplay=REASONING_DETAILS/PRESERVE
     *
     * v1.0.1 (P3): RELAY 中转站保守默认策略 — 聚合站透传底层模型能力,
     *  无法在 host 层判断具体模型支持情况,因此保持全 true,
     *  由第 3 层 modelId 细化处理已知模型限制。
     *
     * 未列出的 host 保持现有规则不变(保守默认 true)。
     */
    private fun ProviderCompat.overrideByHost(host: String): ProviderCompat {
        return when (host) {
            // ── 国内直连 ──
            "api.deepseek.com" -> copy(
                supportsJsonMode = true,
                supportsReasoningEffort = false,
                // v1.0.7: DeepSeek 走 reasoning_content 协议,不发任何思考参数
                thinkingFormat = ThinkingFormat.DEEPSEEK,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
                ),
            )
            // v1.0.6: api.kimi.com(Kimi Coding Plan)与 api.moonshot.cn 同属月之暗面,json_mode 支持
            // v1.0.7: thinkingFormat=KIMI(thinking.type+keep)
            "api.moonshot.cn", "api.kimi.com" -> copy(
                supportsJsonMode = true,
                thinkingFormat = ThinkingFormat.KIMI,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
                ),
            )
            // v1.0.6: api.z.ai 是智谱海外端点,与 open.bigmodel.cn 同限制
            // v1.0.7: thinkingFormat=ZHIPU(thinking.type+clear_thinking)
            "open.bigmodel.cn", "api.z.ai" -> copy(
                supportsStore = false,
                supportsReasoningEffort = false,
                thinkingFormat = ThinkingFormat.ZHIPU,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
                ),
            )
            // v1.0.6: coding.dashscope.aliyuncs.com 与 dashscope.aliyuncs.com 同限制
            // v1.0.7: thinkingFormat 留 null(由 modelId 层细化:qwen3-coder → QWEN_CHAT_TEMPLATE,其他 → QWEN)
            "dashscope.aliyuncs.com", "coding.dashscope.aliyuncs.com" -> copy(
                supportsStore = false,
            )
            // v1.0.6: api.minimaxi.com 走 Anthropic 协议,由 type=ANTHROPIC 基础规则处理
            // 保留 api.minimax.chat 旧端点兼容(已废弃但存量用户可能仍有)
            "api.minimax.chat" -> copy(
                supportsJsonMode = false,
            )
            "api.baichuan-ai.com" -> this  // v1.0.1 (P3): Baichuan,默认全 true
            "api.lingyiwanwu.com" -> this  // v1.0.1 (P3): 零一万物,默认全 true
            "api.stepfun.com" -> this  // v1.0.1 (P3): 阶跃星辰,默认全 true
            "ark.cn-beijing.volces.com" -> this  // v1.0.1 (P3): 火山引擎 Doubao,默认全 true(thinking 由 modelId 细化)
            // v1.0.6: 新增国产供应商(对齐 openhanako,均为 OpenAI 兼容,默认全 true)
            "api.hunyuan.cloud.tencent.com" -> this  // 腾讯混元
            "qianfan.baidubce.com" -> this  // 百度千帆
            "api-inference.modelscope.cn" -> this  // 魔搭
            "cloud.infini-ai.com" -> this  // 无问芯穹
            // v1.0.7: 小米 MiMo 用类似 Kimi 的 thinking.type 协议
            "api.xiaomimimo.com", "token-plan-cn.xiaomimimo.com" -> copy(
                thinkingFormat = ThinkingFormat.KIMI,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
                ),
            )
            "apihub.agnes-ai.com" -> this  // 思必驰 Agnes
            // v1.0.7: 美团 LongCat 用 thinking.type 协议
            "api.longcat.chat" -> copy(
                thinkingFormat = ThinkingFormat.LONGCAT,
            )

            // ── 海外直连 ──
            // v1.0.6: cli-chat-proxy.grok.com 是 xAI OAuth Responses 端点,与 api.x.ai 同属 xAI
            "api.x.ai", "cli-chat-proxy.grok.com" -> this  // xAI Grok,默认全 true
            "api.perplexity.ai" -> this  // v1.0.6: Perplexity,默认全 true
            "api.groq.com" -> copy(
                supportsStore = false,  // Groq 无 Assistants API
            )
            "api.together.xyz" -> this  // v1.0.1 (P3): Together AI,默认全 true
            "api.mistral.ai" -> copy(
                supportsStore = false,  // Mistral 无 Assistants API
            )
            "api.deepinfra.com" -> this  // v1.0.1 (P3): DeepInfra,默认全 true
            "api.fireworks.ai" -> this  // v1.0.1 (P3): Fireworks AI,默认全 true
            "api.githubcopilot.com" -> this  // v1.0.1 (P3): GitHub Copilot Models
            "models.inference.ai.azure.com" -> this  // v1.0.1 (P3): GitHub Models
            "localhost" -> this  // v1.0.6: Ollama 本地,默认全 true

            // ── 中转/聚合站(RELAY)── 保守默认全 true,由 modelId 层细化 ──
            "api.siliconflow.cn" -> this
            // v1.0.7: OpenRouter 走 reasoning.effort(Chat Completions 协议扩展),
            //   reasoning 回放走 reasoning_details 数组,始终保留
            "openrouter.ai" -> copy(
                thinkingFormat = ThinkingFormat.OPENROUTER,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_DETAILS,
                    ReasoningReplayPolicy.PRESERVE,
                ),
            )
            else -> this
        }
    }

    /**
     * 第 3 层:按 modelId 细化。
     *
     * 仅处理明确的模型级限制(保守,不臆测):
     *  - o1-preview / o1-mini: 早期 o1 模型不支持 tool calling(后续版本已支持)
     *  - deepseek-reasoner / deepseek-r1: DeepSeek-R1 推理模型,API 文档明确不支持 tool calling
     *    (v1.0.1 P3: 部分中转站用 deepseek-r1 而非 deepseek-reasoner,此处两个 id 均覆盖)
     *
     * v1.0.7 thinkingFormat 模型级细化(覆盖 host 层的 null):
     *  - qwen3-coder-*:QWEN_CHAT_TEMPLATE(chat_template_kwargs.enable_thinking)
     *  - qwen3-* (其他):QWEN(enable_thinking + thinking_budget)
     *  - qwq-* / qwen-vl-*:QWEN(QwQ / Qwen-VL 系列同 Qwen3 思考协议)
     *  - doubao-*-thinking-*:VOLCENGINE(thinking.type)
     *  - glm-z1-*:ZHIPU(若 host 未匹配,如 SiliconFlow 聚合下)
     *  - kimi-k1-* / kimi-k2-*:KIMI(若 host 未匹配,如 SiliconFlow 聚合下)
     *  - deepseek-r1 / deepseek-reasoner:DEEPSEEK(若 host 未匹配,如 SiliconFlow 聚合下)
     *  - longcat-*:LONGCAT(若 host 未匹配,如 SiliconFlow 聚合下)
     *
     * 其他 o1/o3/o4 系列保留 supportsToolCalling=true(后续版本已支持,
     * 不做版本猜测避免误关)。如后续发现具体版本不支持,可在此处补充。
     */
    private fun ProviderCompat.overrideByModelId(modelId: String): ProviderCompat {
        val id = modelId.lowercase()
        return when {
            // 早期 o1 推理模型不支持 tool calling(OpenAI 官方文档已声明)
            id == "o1-preview" || id == "o1-mini" -> copy(
                supportsToolCalling = false,
            )
            // DeepSeek-R1 推理模型不支持 tool calling
            // v1.0.1 (P3): deepseek-reasoner(官方)和 deepseek-r1(中转站别名)均覆盖
            id == "deepseek-reasoner" || id == "deepseek-r1" -> copy(
                supportsToolCalling = false,
                // v1.0.7: 即使在聚合站(SiliconFlow / OpenRouter 等)上,DeepSeek-R1 仍走 DEEPSEEK 协议
                thinkingFormat = ThinkingFormat.DEEPSEEK,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
                ),
            )
            // v1.0.7: Qwen3-Coder 系列走 chat_template_kwargs.enable_thinking
            //   (Qwen3-Coder 模型用 chat_template 协议,enable_thinking 必须嵌在 chat_template_kwargs 内)
            id.startsWith("qwen3-coder") -> copy(
                thinkingFormat = ThinkingFormat.QWEN_CHAT_TEMPLATE,
            )
            // v1.0.7: Qwen3 / QwQ / Qwen-VL 系列走 enable_thinking + thinking_budget
            id.startsWith("qwen3") || id.startsWith("qwq-") || id.startsWith("qwen-vl") -> copy(
                thinkingFormat = ThinkingFormat.QWEN,
            )
            // v1.0.7: 火山引擎 Doubao Thinking 系列走 thinking.type
            id.startsWith("doubao") && id.contains("thinking") -> copy(
                thinkingFormat = ThinkingFormat.VOLCENGINE,
            )
            // v1.0.7: 智谱 GLM-Z1 系列走 thinking.type + clear_thinking(覆盖 host 未匹配场景,如聚合站)
            id.startsWith("glm-z1") || id.startsWith("glm-4-thinking") -> copy(
                thinkingFormat = ThinkingFormat.ZHIPU,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
                ),
            )
            // v1.0.7: Kimi K1/K2 系列走 thinking.type + keep(覆盖 host 未匹配场景)
            id.startsWith("kimi-k1") || id.startsWith("kimi-k2") -> copy(
                thinkingFormat = ThinkingFormat.KIMI,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
                ),
            )
            // v1.0.7: 美团 LongCat 系列(覆盖 host 未匹配场景)
            id.startsWith("longcat") -> copy(
                thinkingFormat = ThinkingFormat.LONGCAT,
            )
            else -> this
        }
    }

    /**
     * 从 baseUrl 提取 host(小写,去端口)。
     *
     * 兼容以下形式:
     *  - https://api.openai.com/v1
     *  - http://localhost:8080/v1
     *  - api.deepseek.com
     *  - 空串 / 异常 → 返回空串(由调用方判断跳过 host 匹配)
     */
    private fun extractHost(baseUrl: String): String {
        val trimmed = baseUrl.trim().ifBlank { return "" }
        return try {
            // 简单实现:去协议前缀,取首个 / 之前的部分,去端口
            val noScheme = trimmed
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .lowercase()
            noScheme.substringBefore(':')
        } catch (t: Throwable) {
            if (t is kotlin.coroutines.cancellation.CancellationException) throw t
            Logger.w(TAG, "extract host 失败(baseUrl=$baseUrl): ${t.message}")
            ""
        }
    }
}
