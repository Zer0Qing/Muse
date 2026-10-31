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
 * @param supportsResumeFromText 是否支持流式中断后从已产出文本续传(见字段注释)。
 * @param maxContextWindow Provider 级上下文窗口上限(token),null 表示无统一上限。
 * @param defaultMaxTokens 默认 max_tokens,null 表示用 Provider 默认。
 * @param thinkingFormat v1.0.7: 思考格式(共 9 种)。
 *   null=走标准 reasoning_effort;非 null=走对应厂商扩展字段(thinking / enable_thinking 等)。
 *   仅驱动 OpenAIProvider 的请求体构造;AnthropicProvider / GeminiProvider 各自实现原生思考。
 * @param reasoningReplayContract v1.0.7: reasoning 回放契约(carrier + policy)。
 *   null=不回放历史 reasoning;非 null=按 carrier/policy 在请求体中写入对应 wire 字段。
 *   决定 UIMessage.reasoning / thinkingSignature 如何序列化回请求体(多轮对话必备)。
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
    /**
     * C-11: 是否支持流式中断后从已产出文本续传(resumeFromText)。
     *
     * resumeFromText 会把未完成的已显示内容作为"末尾 assistant 消息"注入请求,
     * 让模型从中断处继续。部分 Provider / 后端/模型不能正确对待一个被截断的
     * assistant 轮次(可能重答、拒绝或把未完成内容当作完整话语),此时应置 false,
     * 由 ChatService 关闭自动续传(改为让 UI 层 LCP 去重接管,而不是注入部分消息)。
     *
     * 保守默认 true(不明确不支持则不关闭)。
     */
    val supportsResumeFromText: Boolean = true,
    val maxContextWindow: Int? = null,
    val defaultMaxTokens: Int? = null,
    /**
     * v1.0.7: 思考格式(共 9 种)。
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
     * v1.0.7: reasoning 回放契约(5 种 carrier + 3 种 policy)。
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
    /**
     * v2.4.5: 采样温度上限(排他),null 表示仅受通用 [MIN_TEMPERATURE, MAX_TEMPERATURE] 约束。
     *
     * 部分供应商对 temperature 走开区间(如商汤 sensecore `[0.0, 2.0)`,2.0 非法),
     * 而应用内的温度滑块上限恰为 2.0、导入的第三方角色卡温度可任意。
     * 该值由 [ProviderCompatRules] 按 host 声明,由 ChatService 在发请求前统一收紧。
     */
    val maxTemperatureExclusive: Float? = null,
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
 * v2.7: 三层之上再叠一层目录覆盖 —— 模型能力目录里对该模型**显式声明**的思考格式优先于
 * 硬编码表。优先级:[目录] > [硬编码表] > [默认值],见 [resolve] 末尾。
 *
 * 独立编写。
 */
object ProviderCompatRules {

    private const val TAG = "ProviderCompatRules"

    /**
     * v2.7: 当前生效的模型能力目录(model-catalog)。
     *
     * 由 [io.zer0.ai.registry.ModelRegistry.installCatalog] 在注入运行时目录时同步写入 ——
     * 目录只有一处加载入口,能力派生与兼容性派生读同一份数据。
     */
    @Volatile
    private var catalog: ModelCatalog = ModelCatalog()

    /**
     * 注入/替换能力目录。
     *
     * 与 [io.zer0.ai.registry.ModelRegistry.installCatalog] 成对调用,不要在别处单独调用,
     * 否则两条派生链会看到不同的目录。
     */
    fun installCatalog(newCatalog: ModelCatalog) {
        catalog = newCatalog
        warnOnAmbiguousDeclarations(newCatalog)
    }

    /**
     * v2.7: 目录装载时检查「同名模型跨供应商声明不一致」,只打日志、不改行为。
     *
     * 背景:[catalogThinkingFormat] 拿不到 providerId 时按模型名全局查,先出现的供应商获胜。
     * 同一个模型名被多个供应商声明时,两种形态会静默走错:
     *  - 取值冲突:两家都声明且映射出不同格式 —— 无论发给哪家,总有一家套到别人的 wire 约定。
     *    这是纯数据问题,该改目录生成侧。
     *  - 单边声明:只有一家声明、其余没写 —— 未声明的供应商会「借到」那一份,而不是回落到
     *    硬编码表(当前 glm-5.2 就是这种形态)。结果可能恰好对,但机制是错的。
     *
     * 只报警不修:真正该修的是把查询改成按 (providerId, modelId) 查、miss 时回落硬编码表。
     */
    private fun warnOnAmbiguousDeclarations(installed: ModelCatalog) {
        if (installed.providers.isEmpty()) return
        val declarations = HashMap<String, MutableList<Pair<String, String?>>>()
        for ((providerId, models) in installed.providers) {
            for ((modelId, entry) in models) {
                declarations.getOrPut(modelId) { mutableListOf() } += providerId to entry.compat?.thinkingFormat
            }
        }
        for ((modelId, entries) in declarations) {
            if (entries.size < 2) continue
            val formats = entries.map { thinkingFormatOfCatalog(it.second) }
            val distinct = formats.filterNotNull().toSet()
            val detail = entries.joinToString(" | ") { "${it.first}=${it.second ?: "<未声明>"}" }
            when {
                distinct.size >= 2 ->
                    Logger.w(TAG, "目录声明冲突:$modelId $detail —— 同名模型取值不同,查询会静默取先出现者")
                distinct.size == 1 && formats.any { it == null } ->
                    Logger.w(TAG, "目录单边声明:$modelId $detail —— 未声明的供应商借用了别人的声明,而非回落硬编码表")
                else -> Unit
            }
        }
    }

    /**
     * v1.0.53: 已知 host 集合(与 [overrideByHost] 的 when 分支一一对应)。
     *
     * 用于判断 baseUrl 的 host 是否为已知的官方端点或聚合站。
     * 未知 host(用户自建中转站,如 tokenrhythm.studio)不在此集合内,
     * [overrideByModelId] 将跳过厂商扩展 thinkingFormat 注入,避免中转站不支持
     * thinking / enable_thinking 等字段而返回 HTTP 400。
     */
    private val KNOWN_HOSTS: Set<String> = setOf(
        // 国内直连
        "api.deepseek.com", "api.moonshot.cn", "api.kimi.com",
        "open.bigmodel.cn", "api.z.ai",
        "dashscope.aliyuncs.com", "coding.dashscope.aliyuncs.com",
        "api.minimax.chat",
        "api.baichuan-ai.com", "api.lingyiwanwu.com", "api.stepfun.com",
        "ark.cn-beijing.volces.com",
        "api.hunyuan.cloud.tencent.com", "qianfan.baidubce.com",
        "api-inference.modelscope.cn", "cloud.infini-ai.com",
        "api.xiaomimimo.com", "token-plan-cn.xiaomimimo.com",
        "apihub.agnes-ai.com", "api.longcat.chat",
        // v2.4.5: 商汤 SenseNova / SenseCore,temperature 为开区间 [0.0, 2.0)
        "token.sensenova.cn", "api.sensenova.cn",
        // 海外直连
        "api.x.ai", "cli-chat-proxy.grok.com", "api.perplexity.ai",
        "api.groq.com", "api.together.xyz", "api.mistral.ai",
        "api.deepinfra.com", "api.fireworks.ai",
        "api.githubcopilot.com", "models.inference.ai.azure.com",
        "localhost",
        // 中转/聚合站
        "api.siliconflow.cn", "openrouter.ai", "opencode.ai",
    )

    /**
     * v2.7: 目录 `compat.thinkingFormat` 的词汇 → [ThinkingFormat]。
     *
     * 只映射「OpenAI 兼容协议上靠厂商扩展字段表达思考」这一组。以下两类按未声明处理,
     * 由硬编码表接管:
     *  - `anthropic` / `google-thinking-level`:原生协议自带能力,分别由 AnthropicProvider /
     *    GeminiProvider 自己实现。[ProviderCompat.thinkingFormat] 只覆盖 OpenAI 兼容路径,
     *    把 `anthropic` 映射进来会让走 OpenAI 兼容链路的 claude 模型套上 Anthropic 的 wire 约定。
     *  - 未知词汇:目录与代码的词汇表版本不一致,不猜。
     *
     * 另外三组目录字段本轮**未接线**,原因留在这里,免得下次重新猜:
     *  - `toolUse.dialect`:目录里 270 条非空值全部是 `openai`(与[ToolUseSpec]的缺省值相同),
     *    而工具调用的 wire 格式实际由 [ProviderType] 决定。接进来既不改任何行为,还会把目录里
     *    的 Anthropic / Gemini 模型标成 openai 方言 —— 这是目录数据问题,先不接。
     *  - `thinkingLevels` / `defaultThinkingLevel`:模型级档位集合,但 [ProviderCompat] 没有承载
     *    档位的字段,真正落地要改推理等级归一(即 Provider 请求组装),超出本轮范围。
     *  - `compat.reasoningProfile`:`anthropic-adaptive*` / `kimi-openai` 这类「自适应思考」画像,
     *    代码里还没有 adaptive thinking 分支,没有可映射的目标字段。
     */
    fun thinkingFormatOfCatalog(raw: String?): ThinkingFormat? = when (raw?.trim()?.lowercase()) {
        "deepseek" -> ThinkingFormat.DEEPSEEK
        "kimi" -> ThinkingFormat.KIMI
        "qwen" -> ThinkingFormat.QWEN
        "qwen-chat-template", "qwen_chat_template" -> ThinkingFormat.QWEN_CHAT_TEMPLATE
        "zhipu" -> ThinkingFormat.ZHIPU
        "openrouter" -> ThinkingFormat.OPENROUTER
        "volcengine" -> ThinkingFormat.VOLCENGINE
        "longcat" -> ThinkingFormat.LONGCAT
        else -> null
    }

    /**
     * v2.7: 目录为某模型声明的思考格式;未声明或词汇不可映射时返回 null(交给硬编码表)。
     *
     * 查询口径与 [io.zer0.ai.registry.ModelRegistry.catalogEntryOf] 一致:先按 providerId +
     * modelId 精确命中,再退回按模型名全局查。请求期派生只拿得到 modelId(见 [resolve]),
     * 走全局查;用户自建供应商/中转站的 providerId 与目录里的逻辑供应商名对不上,同样走全局查。
     *
     * 已知边界:不带 providerId 的全局查**不区分供应商** —— [ModelCatalog.entryByModelId]
     * 按供应商在目录里的顺序取第一个命中者,同名模型被多个供应商声明时先出现的获胜,且无任何
     * 日志。两种后果:取值不同时会静默发错字段;只有一方声明时,未声明的供应商会借到另一方的
     * 声明而不是回落到硬编码表(如 glm-5.2 在 zhipu / zhipu-coding 里没声明,会借到
     * opencode-go 的那份)。当前目录里没有取值冲突,但未来若出现同名跨供应商取值冲突,
     * 这里必须改成带 providerId 的查询,不能继续用全局索引。
     *
     * 已知分歧(目录 vs 硬编码表,共 1 处,保持目录优先、未记例外):
     * `opencode-go/glm-5.2` 目录声明 `zhipu`,而 `opencode.ai` 的 host 规则给 `DEEPSEEK`。
     * 表那侧的依据是 2d15c2c3 修的一次真实 400,但只覆盖 DeepSeek 系模型的 reasoning_content
     * 回放,从未对 GLM 模型做过陈述;目录那侧是上游声明的逐模型元数据,本地未实测。两边对该模型
     * 都无实测依据,所以按「目录优先」保留。若日后在 opencode.ai 上出现发 thinking 对象即 400 的
     * 反馈,应回退到硬编码表(或改目录数据),而不是在这里加代码例外。
     */
    fun catalogThinkingFormat(modelId: String?, providerId: String? = null): ThinkingFormat? {
        if (modelId.isNullOrBlank()) return null
        val current = catalog
        if (current.providers.isEmpty()) return null
        val entry = providerId?.takeIf { it.isNotBlank() }?.let { current.entryOf(it, modelId) }
            ?: current.entryByModelId(modelId)
        return thinkingFormatOfCatalog(entry?.compat?.thinkingFormat)
    }

    /**
     * 派生 [ProviderCompat]。
     *
     * @param providerType Provider 类型(决定协议级基础规则)
     * @param baseUrl Provider 的 baseUrl(已解析或原始均可,空串时跳过 host 匹配)
     * @param modelId 具体模型 id(可选,用于模型级细化)
     */
    fun resolve(providerType: ProviderType, baseUrl: String, modelId: String? = null): ProviderCompat {
        // 第 1 层:按 ProviderType 取基础规则
        var compat = baseForType(providerType)

        // 第 2 层:按 baseUrl host 细化
        val host = extractHost(baseUrl)
        // v1.0.53: 判断 host 是否为已知端点(官方/聚合站)。
        // 未知中转站(如 tokenrhythm.studio)的 host 不在 KNOWN_HOSTS 内,
        // 第 3 层 modelId 细化将跳过厂商扩展 thinkingFormat 注入,保守不发 thinking 字段。
        val hostKnown = host.isNotEmpty() && host in KNOWN_HOSTS
        if (host.isNotEmpty()) {
            compat = compat.overrideByHost(host)
        }

        // 第 3 层:按 modelId 细化
        if (!modelId.isNullOrBlank()) {
            compat = compat.overrideByModelId(modelId, hostKnown)

            // v2.5.3 (P2-1): 模型级回放声明 —— 作为兜底。
            // 前两层未声明回放契约时，按模型 id 声明回放策略（覆盖未知 host / 聚合站场景，
            // 例如 Kimi/DeepSeek 在聚合站下调时 host 不匹配也应回放 reasoning_content）。
            // 已有显式声明（官方直连 host 层或模型层）时不覆盖。
            if (compat.reasoningReplayContract == null) {
                ModelReasoningReplayResolver.declare(modelId)?.let { decl ->
                    compat = compat.copy(
                        reasoningReplayContract =
                            ReasoningReplayContract(
                                decl.carrier,
                                decl.policy,
                                decl.requiresNonEmptyReasoning,
                            ),
                    )
                }
            }
        }

        // 第 4 层(最高优先):目录显式声明。
        // 优先级:目录 > 硬编码表 > 默认值(null)。
        // 目录是逐模型声明,比按 host / 名称前缀猜的表更具体,所以覆盖前三层的结果。
        // 但它描述的是「该模型在官方端点上的字段约定」;对不在 [KNOWN_HOSTS] 里的自建中转站,
        // 仍保守沿用 v1.0.53 的处理 —— 未知中转站可能不接受 thinking / enable_thinking 等
        // 厂商扩展字段,无条件注入会 400,这种情况保留硬编码表的结果。
        val catalogFormat = catalogThinkingFormat(modelId)
        if (catalogFormat != null && (host.isEmpty() || hostKnown)) {
            compat = compat.copy(thinkingFormat = catalogFormat)
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
        //   但 reasoning 回放走 REASONING_ITEMS carrier + PRESERVE policy。
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
            "api.baichuan-ai.com" -> this // v1.0.1 (P3): Baichuan,默认全 true
            "api.lingyiwanwu.com" -> this // v1.0.1 (P3): 零一万物,默认全 true
            "api.stepfun.com" -> this // v1.0.1 (P3): 阶跃星辰,默认全 true
            "ark.cn-beijing.volces.com" -> this // v1.0.1 (P3): 火山引擎 Doubao,默认全 true(thinking 由 modelId 细化)
            // v1.0.6: 新增国产供应商(均为 OpenAI 兼容,默认全 true)
            "api.hunyuan.cloud.tencent.com" -> this // 腾讯混元
            "qianfan.baidubce.com" -> this // 百度千帆
            "api-inference.modelscope.cn" -> this // 魔搭
            "cloud.infini-ai.com" -> this // 无问芯穹
            // v1.0.7: 小米 MiMo 用类似 Kimi 的 thinking.type 协议
            "api.xiaomimimo.com", "token-plan-cn.xiaomimimo.com" -> copy(
                thinkingFormat = ThinkingFormat.KIMI,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
                ),
            )
            "apihub.agnes-ai.com" -> this // 思必驰 Agnes
            // v2.4.5: 商汤 SenseNova/SenseCore 的 temperature 为开区间 [0.0, 2.0),
            //   2.0 会被上游以 400 "field Temperature invalid" 拒绝。
            //   应用内温度滑块上限恰为 2.0,第三方角色卡导入值可任意,需在出口收紧。
            "token.sensenova.cn", "api.sensenova.cn" -> copy(
                maxTemperatureExclusive = 2.0f,
            )
            // v1.0.7: 美团 LongCat 用 thinking.type 协议
            "api.longcat.chat" -> copy(
                thinkingFormat = ThinkingFormat.LONGCAT,
            )

            // ── 海外直连 ──
            // v1.0.6: cli-chat-proxy.grok.com 是 xAI OAuth Responses 端点,与 api.x.ai 同属 xAI
            "api.x.ai", "cli-chat-proxy.grok.com" -> this // xAI Grok,默认全 true
            "api.perplexity.ai" -> this // v1.0.6: Perplexity,默认全 true
            "api.groq.com" -> copy(
                supportsStore = false, // Groq 无 Assistants API
            )
            "api.together.xyz" -> this // v1.0.1 (P3): Together AI,默认全 true
            "api.mistral.ai" -> copy(
                supportsStore = false, // Mistral 无 Assistants API
            )
            "api.deepinfra.com" -> this // v1.0.1 (P3): DeepInfra,默认全 true
            "api.fireworks.ai" -> this // v1.0.1 (P3): Fireworks AI,默认全 true
            "api.githubcopilot.com" -> this // v1.0.1 (P3): GitHub Copilot Models
            "models.inference.ai.azure.com" -> this // v1.0.1 (P3): GitHub Models
            "localhost" -> this // v1.0.6: Ollama 本地,默认全 true

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
            // v1.x: Console Go(opencode.ai)中转 DeepSeek 系推理模型,
            //   透传 DeepSeek 协议:thinking 模式下历史 assistant 消息必须回传
            //   reasoning_content,否则返回 400 "The reasoning_content in the
            //   thinking mode must be passed back to the API"。
            "opencode.ai" -> copy(
                thinkingFormat = ThinkingFormat.DEEPSEEK,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
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
     * v1.0.53: 厂商扩展 thinkingFormat(Qwen/Doubao/GLM/Kimi/LongCat)仅在 [hostKnown]=true
     *  时注入。未知中转站(如 tokenrhythm.studio)不支持 thinking / enable_thinking 等扩展字段,
     *  无条件注入会导致 HTTP 400 "未知请求字段"。
     *  DeepSeek-R1 例外:ThinkingFormat.DEEPSEEK 不发任何思考参数,仅消费流式 reasoning_content,
     *  对中转站透明,因此无需 hostKnown 守卫。
     *
     * 其他 o1/o3/o4 系列保留 supportsToolCalling=true(后续版本已支持,
     * 不做版本猜测避免误关)。如后续发现具体版本不支持,可在此处补充。
     */
    private fun ProviderCompat.overrideByModelId(modelId: String, hostKnown: Boolean): ProviderCompat {
        val id = stripAggregatorPrefix(modelId)
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
                // v1.0.53: DEEPSEEK 不发任何思考参数,对中转站透明,无需 hostKnown 守卫
                thinkingFormat = ThinkingFormat.DEEPSEEK,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
                ),
            )
            // v1.0.7+: 其余 deepseek-* 模型(deepseek-chat/deepseek-v4-flash 等官方+中转站)
            // 也走 DEEPSEEK 协议。DeepSeek 官方 API 的 thinking 模式要求历史 assistant 消息
            // 回传 reasoning_content,否则多轮/工具循环返回 400
            // ("The reasoning_content in the thinking mode must be passed back to the API")。
            // DEEPSEEK thinkingFormat 不发送任何思考参数,对未知中转站透明,无需 hostKnown 守卫;
            // 回放仅在历史消息真的带 reasoning 时生效,普通对话不受影响。
            id.startsWith("deepseek-") -> copy(
                thinkingFormat = ThinkingFormat.DEEPSEEK,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
                ),
            )
            // v1.0.7: Qwen3-Coder 系列走 chat_template_kwargs.enable_thinking
            //   (Qwen3-Coder 模型用 chat_template 协议,enable_thinking 必须嵌在 chat_template_kwargs 内)
            // v1.0.53: 仅已知 host 注入,未知中转站不支持 chat_template_kwargs 字段
            id.startsWith("qwen3-coder") && hostKnown -> copy(
                thinkingFormat = ThinkingFormat.QWEN_CHAT_TEMPLATE,
            )
            // v1.0.7: Qwen3 / QwQ / Qwen-VL 系列走 enable_thinking + thinking_budget
            // v1.0.53: 仅已知 host 注入,未知中转站不支持 enable_thinking 字段
            (id.startsWith("qwen3") || id.startsWith("qwq-") || id.startsWith("qwen-vl")) && hostKnown -> copy(
                thinkingFormat = ThinkingFormat.QWEN,
            )
            // v1.0.7: 火山引擎 Doubao Thinking 系列走 thinking.type
            // v1.0.53: 仅已知 host 注入,未知中转站不支持 thinking 字段
            id.startsWith("doubao") && id.contains("thinking") && hostKnown -> copy(
                thinkingFormat = ThinkingFormat.VOLCENGINE,
            )
            // v1.0.7: 智谱 GLM-Z1 系列走 thinking.type + clear_thinking(覆盖 host 未匹配场景,如聚合站)
            // v1.0.53: 仅已知 host 注入,未知中转站不支持 thinking 字段
            (id.startsWith("glm-z1") || id.startsWith("glm-4-thinking")) && hostKnown -> copy(
                thinkingFormat = ThinkingFormat.ZHIPU,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
                ),
            )
            // v1.0.7: Kimi K1/K2 系列走 thinking.type + keep(覆盖 host 未匹配场景)
            // v1.0.53: 仅已知 host 注入,未知中转站(如 tokenrhythm.studio)不支持 thinking 字段,
            //   无条件注入会导致 HTTP 400 "未知请求字段：thinking"
            (id.startsWith("kimi-k1") || id.startsWith("kimi-k2")) && hostKnown -> copy(
                thinkingFormat = ThinkingFormat.KIMI,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
                ),
            )
            // v1.0.7: 美团 LongCat 系列(覆盖 host 未匹配场景)
            // v1.0.53: 仅已知 host 注入,未知中转站不支持 thinking 字段
            id.startsWith("longcat") && hostKnown -> copy(
                thinkingFormat = ThinkingFormat.LONGCAT,
            )
            else -> this
        }
    }

    /** 去除聚合站/厂商前缀后再识别模型族，原始 ID 仍用于实际请求。 */
    private fun stripAggregatorPrefix(modelId: String): String {
        val raw = modelId.trim().lowercase()
        // v2.6.1: 先剥中转站前导分组标签(如 `[ais]gemini-3.8-flash`)。
        val deLabeled = io.zer0.ai.core.stripChannelGroupLabels(raw)
        val prefixes = listOf(
            "openrouter/", "opencode-go/", "siliconflow/", "dashscope/",
            "deepseek-ai/", "zhipu/", "qwen/", "openai/", "anthropic/",
        )
        return prefixes.firstOrNull { deLabeled.startsWith(it) }?.let(deLabeled::removePrefix)
            ?: deLabeled.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: deLabeled
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
