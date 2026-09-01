package io.zer0.ai.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * OpenAI Chat Completions API 的请求/响应 DTO。
 *
 * Phase 7 扩展:tool_calls / tool_call_id / tools 字段(function calling)。
 * Phase 8.6 扩展:content 改为 JsonElement,支持多模态(string 或 parts 数组)。
 * 字段命名严格对齐 OpenAI 官方文档。
 */

// ════════════════════════════════════════════════════════════════════════════
// v1.0.7: OpenAI Responses API DTO(/v1/responses 端点)
//
// 对齐 既有实现 的 openai-responses / openai-codex-responses 协议。
// 与 Chat Completions API 的关键差异:
//  - 请求体:messages → input;system role → instructions 顶层字段;
//    max_tokens → max_output_tokens;新增 reasoning: {effort, summary}
//  - 流式响应:event: response.output_text.delta + data: {delta: "..."},
//    结束标记 response.completed(并发 data: [DONE])
//  - 非流式响应:choices[0].message.content → output[] 数组,
//    reasoning / function_call 都是 output 顶层 sibling(不在 message 内)
// ════════════════════════════════════════════════════════════════════════════

/**
 * v1.0.7: Responses API 请求体。
 *
 * @param model 模型 id
 * @param input 输入数组(替代 Chat Completions 的 messages)
 * @param instructions 系统指令(替代 system role,顶层字段)
 * @param stream 是否流式(Codex 协议强制 true)
 * @param max_output_tokens 输出上限(替代 max_tokens)
 * @param temperature 采样温度
 * @param tools 工具定义(同 Chat Completions 结构,但可含 image_generation 等扩展类型)
 * @param reasoning 推理配置(effort + summary)
 * @param store 是否持久化(Codex 协议强制 false)
 */
@Serializable
internal data class ResponsesRequest(
    val model: String,
    val input: List<ResponsesInputItem>,
    val instructions: String? = null,
    val stream: Boolean = true,
    val max_output_tokens: Int? = null,
    val temperature: Float? = null,
    val tools: List<ResponsesTool>? = null,
    val tool_choice: String? = null,
    val reasoning: ResponsesReasoningConfig? = null,
    val store: Boolean? = null,
)

/**
 * v1.0.7: Responses API 推理配置。
 *
 * @param effort 推理强度:"minimal" / "low" / "medium" / "high"
 * @param summary 摘要类型:"auto" / "concise" / "detailed" / "none"(可选)
 */
@Serializable
internal data class ResponsesReasoningConfig(
    val effort: String,
    val summary: String? = null,
)

/**
 * v1.0.7: Responses API input 数组项。
 *
 * @param role 角色:"user" / "assistant" / "system" / "developer"
 * @param content 内容(string 或 ContentBlock 数组,用 JsonElement 保留灵活性)
 * @param type item 类型;null=普通 message;"reasoning"=推理回放 item;"function_call"=工具调用回放
 * @param id reasoning item 的 id(用于回放);function_call 的 id
 * @param call_id function_call 的 call_id(对应 tool_call_id)
 * @param name function_call 的函数名
 * @param arguments function_call 的参数 JSON 字符串
 * @param summary reasoning item 的摘要数组(回放时原样透传)
 * @param encrypted_content reasoning item 的加密负载(回放关键)
 */
@Serializable
internal data class ResponsesInputItem(
    val role: String? = null,
    val content: JsonElement? = null,
    val type: String? = null,
    val id: String? = null,
    val call_id: String? = null,
    val name: String? = null,
    val arguments: String? = null,
    val summary: JsonElement? = null,
    val encrypted_content: String? = null,
)

/**
 * v1.0.7: Responses API 非流式响应。
 *
 * @param id response id(如 "resp_xxx")
 * @param status 状态(如 "completed",等价于 finish_reason)
 * @param model 模型 id
 * @param output 输出数组(message / reasoning / function_call 等顶层 sibling)
 * @param output_text 顶层快捷文本(若存在直接用,等价于拼接 message.content.output_text)
 * @param usage token 用量
 */
@Serializable
internal data class ResponsesResult(
    val id: String? = null,
    val status: String? = null,
    val model: String? = null,
    val output: List<ResponsesOutputItem> = emptyList(),
    @SerialName("output_text")
    val outputText: String? = null,
    val usage: ResponsesUsage? = null,
)

@Serializable
internal data class ResponsesOutputItem(
    val type: String,  // "message" | "reasoning" | "function_call" | "image_generation_call"
    val id: String? = null,
    val role: String? = null,
    val status: String? = null,
    val content: List<ResponsesContentBlock>? = null,
    @SerialName("call_id")
    val callId: String? = null,
    val name: String? = null,
    val arguments: String? = null,
    val summary: JsonElement? = null,
    @SerialName("encrypted_content")
    val encryptedContent: String? = null,
    val result: String? = null,  // image_generation_call 的 base64 结果
)

@Serializable
internal data class ResponsesContentBlock(
    val type: String,  // "output_text" | "text" | "refusal" | "input_text" | "input_image"
    val text: String? = null,
    val annotations: List<ResponsesAnnotation> = emptyList(),
)

@Serializable
internal data class ResponsesAnnotation(
    val type: String? = null,
    val url: String? = null,
    val title: String? = null,
)

@Serializable
internal data class ResponsesUsage(
    val input_tokens: Int? = null,
    val output_tokens: Int? = null,
    val reasoning_tokens: Int? = null,
    val total_tokens: Int? = null,
)

/**
 * v1.0.7: Responses API 流式 SSE 事件。
 *
 * 关键事件类型(对齐 既有实现 readCodexResponsesStream):
 *  - response.output_text.delta:正文增量(delta 字段)
 *  - response.output_text.done:正文结束(text 字段,兜底完整文本)
 *  - response.reasoning_summary_text.delta:推理摘要增量(delta 字段)
 *  - response.reasoning_summary_text.done:推理摘要结束(text 字段)
 *  - response.function_call_arguments.delta:工具调用参数增量(item_id + delta)
 *  - response.output_item.added:新增 output item(item 字段,如 function_call 起始)
 *  - response.completed:流结束(response 字段,含最终 output 数组)
 *
 * 字段全部可选,不同事件类型用不同字段;type 是判别字段。
 */
@Serializable
internal data class ResponsesStreamEvent(
    val type: String,
    val delta: String? = null,
    val text: String? = null,
    val item: JsonElement? = null,
    val item_id: String? = null,
    val output_index: Int? = null,
    val summary_index: Int? = null,
    val response: ResponsesResult? = null,
)

@Serializable
internal data class OpenAIRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val max_tokens: Int? = null,
    val stream: Boolean = true,
            /** Phase 7: 工具定义列表,启用 function calling。 */
    val tools: List<OpenAITool>? = null,
    /** v1.0.40: 工具选择策略。tools=null 时强制 "none" 防止模型幻觉 tool call。 */
    val tool_choice: String? = null,
    /**
     * Phase 8.5 修复: 推理等级(o1/gpt-5 系列的 reasoning_effort 字段)。
     * 仅当 reasoningLevel != OFF/AUTO 且 effort 非空时发送,值 "minimal"/"low"/"medium"/"high"。
     */
    val reasoning_effort: String? = null,
    // v1.80 (L-OAI10): 删除 response_format/OpenAIResponseFormat 死代码。
    //   原字段从未被赋值(始终 null),且 OpenAI JSON mode 需配合 schema 说明,
    //   当前业务无 JSON mode 需求,如需启用应单独设计入口而非保留死字段。
)

/**
 * OpenAI 消息体。
 *
 * - role=assistant 且 LLM 决策调用工具时,[toolCalls] 非空,[content] 可为空串
 * - role=tool 时(回填工具结果),[toolCallId] 必填,[content] 是工具执行结果文本
 *
 * Phase 8.6: [content] 改为 [JsonElement] 以支持多模态:
 *  - 纯文本: `JsonPrimitive("text")`
 *  - 多模态: `JsonArray([{type:"text", text:"..."}, {type:"image_url", image_url:{url:"data:..."}}])`
 */
@Serializable
internal data class OpenAIMessage(
    val role: String,
    val content: JsonElement,
    /** assistant 消息携带的工具调用请求。 */
    @SerialName("tool_calls")
    val toolCalls: List<OpenAIToolCall>? = null,
    /** tool 消息回填时对应的 tool_call_id。 */
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    /**
     * v1.0.7: 历史推理内容回放(对齐 既有实现 reasoning-content-replay)。
     *
     * 仅用于把上一轮 assistant 消息的推理过程原样回传给 API,使多轮对话中
     * 模型能"看见"自己之前的思考链。carrier=REASONING_CONTENT 的厂商
     * (Kimi / DeepSeek / MiMo / Zhipu Chat Completions)在 assistant + tool_calls
     * 场景**强制要求**回传 reasoning_content,否则返回 400 fail-closed 错误
     * (这些厂商用 reasoning_content 验证思考链完整性,缺失则认为请求被篡改)。
     *
     * - null:不写入 JSON(explicitNulls=false 时序列化忽略)
     * - 非 null:写入 `"reasoning_content": "..."` 字段
     *
     * 注:此字段与流式 delta.reasoning_content / 非流式 message.reasoning_content
     *   是同一 wire 字段,但语义不同 — 流式/非流式是 API 响应字段(模型输出),
     *   此处是请求字段(回放历史)。两者共用 wire 名是 OpenAI 兼容协议约定。
     */
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
)

/** OpenAI 工具定义(type=function)。 */
@Serializable
internal data class OpenAITool(
    val type: String = "function",
    val function: OpenAIToolFunction,
)

/** Responses API 工具：既支持函数工具，也支持 web_search_preview 等内置工具。 */
@Serializable
internal data class ResponsesTool(
    val type: String,
    val name: String? = null,
    val description: String? = null,
    val parameters: JsonElement? = null,
)

@Serializable
internal data class OpenAIToolFunction(
    val name: String,
    val description: String,
    /** 参数 JSON Schema(原始 JSON,由 Provider 用 Json.decodeFromString(JsonElement.serializer()) 解析)。 */
    val parameters: JsonElement,
)

/** assistant 消息中的工具调用请求。 */
@Serializable
internal data class OpenAIToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAIToolCallFunction,
)

@Serializable
internal data class OpenAIToolCallFunction(
    val name: String,
    val arguments: String,
)

@Serializable
internal data class OpenAIStreamChunk(
    val choices: List<OpenAIChoice> = emptyList(),
    /**
     * A5: 流式 usage — 请求注入 stream_options.include_usage 时,末 chunk 携带 usage
     * (choices 为空数组)。部分网关不注入也会返回,字段可空兼容两种行为。
     */
    val usage: OpenAICompletionUsage? = null,
)

@Serializable
internal data class OpenAIChoice(
    val index: Int = 0,
    val delta: OpenAIDelta? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

/**
 * 流式增量。
 *
 * Phase 7: [toolCalls] 是流式工具调用增量数组,每个元素有 index/id/function{name/arguments}。
 * arguments 是分片字符串,需按 index 累积拼接。
 */
@Serializable
internal data class OpenAIDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAIDeltaToolCall>? = null,
)

/** 流式 tool_call 增量片段。 */
@Serializable
internal data class OpenAIDeltaToolCall(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: OpenAIDeltaToolCallFunction? = null,
)

@Serializable
internal data class OpenAIDeltaToolCallFunction(
    val name: String? = null,
    val arguments: String? = null,
)

/** 非流式响应(stream=false 时返回)。 */
@Serializable
internal data class OpenAICompletionResponse(
    val choices: List<OpenAICompletionChoice> = emptyList(),
    /** v1.0.53 Phase 3: token 用量(Chat Completions API 返回)。 */
    val usage: OpenAICompletionUsage? = null,
)

/**
 * v1.0.53 Phase 3: Chat Completions API 的 token 用量(对齐 OpenAI usage 字段)。
 *
 * 部分模型在 completion_tokens_details 里单独返回 reasoning_tokens(如 o1)。
 */
@Serializable
internal data class OpenAICompletionUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("completion_tokens_details") val completionTokensDetails: OpenAICompletionTokensDetails? = null,
    /** A5: prompt 缓存命中 token(OpenAI 返回 prompt_tokens_details.cached_tokens)。 */
    @SerialName("prompt_tokens_details") val promptTokensDetails: OpenAICompletionPromptTokensDetails? = null,
)

@Serializable
internal data class OpenAICompletionTokensDetails(
    @SerialName("reasoning_tokens") val reasoningTokens: Int = 0,
)

/** A5: prompt 明细 — cached_tokens(上下文缓存命中数,仅展示,不参与消耗合计)。 */
@Serializable
internal data class OpenAICompletionPromptTokensDetails(
    @SerialName("cached_tokens") val cachedTokens: Int = 0,
)

@Serializable
internal data class OpenAICompletionChoice(
    val index: Int = 0,
    val message: OpenAICompletionMessage? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
internal data class OpenAICompletionMessage(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAIToolCall>? = null,
)

/** 非流式错误响应(仅在 SSE 流里夹带 HTTP 错误体时用到)。 */
@Serializable
internal data class OpenAIErrorBody(
    val error: OpenAIErrorDetail? = null,
)

@Serializable
internal data class OpenAIErrorDetail(
    val message: String? = null,
    val type: String? = null,
    val code: JsonElement? = null,
)

/**
 * 宽容的字符串列表反序列化器。
 *
 * 部分上游（火山 coding plan 等）把 capabilities/modalities 返回成
 * 逗号分隔字符串（"code,chat"）而非 JSON 数组（["code","chat"]），
 * 也有返回 null 的情况。这里统一兼容：数组、逗号分隔字符串、null。
 */
private object FlexibleStringListSerializer : kotlinx.serialization.KSerializer<List<String>?> {
    private val listSerializer = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("FlexibleStringList")

    override fun deserialize(decoder: Decoder): List<String>? {
        val json = decoder as? JsonDecoder
            ?: return try { listSerializer.deserialize(decoder) } catch (_: Exception) { null }
        return when (val element = json.decodeJsonElement()) {
            is JsonNull -> null
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> element.content
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty { null }
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: List<String>?) {
        encoder.encodeSerializableValue(listSerializer, value.orEmpty())
    }
}

/** GET /models 响应。data[].id 为模型 id。 */
@Serializable
internal data class OpenAIModelsResponse(
    val data: List<OpenAIModelInfo>? = null,
)

/**
 * 上游 /models 单条模型信息。
 *
 * v1.132 (H-OAI3): 扩展字段以支持 OpenRouter / Together / DeepInfra 等
 * 返回丰富元数据的 OpenAI 兼容服务。所有扩展字段均带默认值,标准 OpenAI
 * /models 接口未返回时回退到默认值(向后兼容)。
 *
 * - [id]: 模型 id(必有)
 * - [context_length]: OpenRouter/Together 等返回的上下文窗口(token 数)
 * - [top_provider]: OpenRouter 的 provider 信息(含 max_completion_tokens)
 * - [pricing]: OpenRouter 的定价信息(prompt/completion per token)
 * - [capabilities]: P2-3 新增,服务端声明的模型能力列表(如 ["vision","tools"])。
 *   Ollama 自 0.4.0 起在 /api/show 返回 capabilities,部分聚合服务也会在
 *   /models 响应中携带;有值时优先于本地推断(见 [io.zer0.ai.ollama.OllamaVisionInferrer])。
 *   标准服务未返回时为 null,不影响向后兼容。
 */
@Serializable
internal data class OpenAIModelInfo(
    val id: String = "",
    val context_length: Int? = null,
    val max_completion_tokens: Int? = null,
    val top_provider: OpenAIModelTopProvider? = null,
    val pricing: OpenAIModelPricing? = null,
    @Serializable(with = FlexibleStringListSerializer::class)
    val capabilities: List<String>? = null,
    /**
     * v1.0.8: 部分上游(OpenRouter / 部分 OpenAI 兼容中转站)返回的模态声明。
     * 常见值: "text" / "image" / "audio" / "video";与 capabilities 互补。
     */
    @Serializable(with = FlexibleStringListSerializer::class)
    val modalities: List<String>? = null,
)

@Serializable
internal data class OpenAIModelTopProvider(
    val max_completion_tokens: Int? = null,
)

@Serializable
internal data class OpenAIModelPricing(
    /** prompt 价格(per token,字符串形式避免精度丢失)。 */
    val prompt: String? = null,
    /** completion 价格(per token)。 */
    val completion: String? = null,
)

/** v1.0.53 Phase 3: Chat Completions usage -> [io.zer0.ai.core.UsageTokens]。 */
internal fun OpenAICompletionUsage.toUsageTokens(): io.zer0.ai.core.UsageTokens = io.zer0.ai.core.UsageTokens(
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    reasoningTokens = completionTokensDetails?.reasoningTokens ?: 0,
    cachedTokens = promptTokensDetails?.cachedTokens ?: 0,
)

/** v1.0.53 Phase 3: Responses API usage -> [io.zer0.ai.core.UsageTokens]。 */
internal fun ResponsesUsage.toUsageTokens(): io.zer0.ai.core.UsageTokens = io.zer0.ai.core.UsageTokens(
    promptTokens = input_tokens ?: 0,
    completionTokens = output_tokens ?: 0,
    reasoningTokens = reasoning_tokens ?: 0,
)
