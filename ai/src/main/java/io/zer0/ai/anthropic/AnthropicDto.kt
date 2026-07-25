package io.zer0.ai.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Anthropic Messages API 数据传输对象。
 *
 * 与 OpenAI Chat Completions 的关键差异:
 *  - system 是顶层字段,不在 messages 数组里
 *  - 流式事件用 type 区分(message_start / content_block_start / content_block_delta / message_delta / message_stop)
 *  - 认证用 x-api-key + anthropic-version header,不是 Bearer
 *  - thinking 模型(claude-3-7-sonnet / claude-opus-4)的思考链走 thinking_delta
 *
 * Phase 8.1 (M9): 加 [cache_control] 字段支持 Prompt Caching,
 * 启用后 Claude 会缓存 system prompt 和长对话前缀,5m/1h TTL,
 * 重复请求只计 10% 输入 token 成本。
 *
 * Prompt Caching 字段:
 *  - [AnthropicSystemBlock.cache_control]: system prompt 缓存标记
 *  - [AnthropicMessage.cache_control]: 消息缓存标记(用于长对话前缀)
 *  - [AnthropicRequest.system] 改为 List<AnthropicSystemBlock> 支持缓存标记
 *
 * v1.80 (H-ANT1~4 / M-ANT5 / L-ANT1~3): 扩展流式事件 DTO,
 * 支持 thinking_delta / input_json_delta / signature_delta / error / content_block_start / message_start。
 */

@Serializable
internal data class AnthropicRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    val system: List<AnthropicSystemBlock>? = null,
    val max_tokens: Int,
    val temperature: Double? = null,
    val stream: Boolean = false,
    /**
     * Phase 8.1 (M11): thinking 配置(extended thinking)。
     * null 表示不启用;非 null 时按 [io.zer0.ai.core.ReasoningLevel] 映射。
     */
    val thinking: AnthropicThinking? = null,
    /**
     * v1.80 (H-ANT2): 工具定义列表,启用 Anthropic 原生 function calling。
     * null 表示不启用工具调用;非空时透传为 tools 数组。
     */
    val tools: List<AnthropicTool>? = null,
)

/**
 * v1.80 (H-ANT2): Anthropic 工具定义。
 *
 * 与 OpenAI 的差异:Anthropic 用 `input_schema`(而非 `parameters`),
 * 且 schema 顶层需声明 `"type": "object"`。
 *
 * @param name 函数名
 * @param description 描述(LLM 据此决定是否调用)
 * @param input_schema 参数 JSON Schema(直接透传 JsonElement,避免重复解析)
 */
@Serializable
internal data class AnthropicTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonElement,
)

/**
 * system prompt 块(支持 cache_control)。
 * 单字符串场景包装成单元素列表 [{type: "text", text: "..."}]。
 */
@Serializable
internal data class AnthropicSystemBlock(
    val type: String = "text",
    val text: String,
    val cache_control: AnthropicCacheControl? = null,
)

/**
 * 缓存控制标记。
 * @param type 固定 "ephemeral"(临时缓存)
 */
@Serializable
internal data class AnthropicCacheControl(
    val type: String = "ephemeral",
    @SerialName("ttl") val ttl: String? = null,  // "5m" / "1h",不传走默认 5m
)

/**
 * thinking 配置(Anthropic extended thinking)。
 * @param type 固定 "enabled"
 * @param budget_tokens 思考预算 token 数
 */
@Serializable
internal data class AnthropicThinking(
    val type: String = "enabled",
    val budget_tokens: Int,
)

@Serializable
internal data class AnthropicMessage(
    val role: String,  // "user" / "assistant"
    val content: JsonElement,  // Phase 8.6: 纯文本 JsonPrimitive 或多模态 JsonArray
    val cache_control: AnthropicCacheControl? = null,
)

@Serializable
internal data class AnthropicCompletionResponse(
    val content: List<AnthropicContentBlock>,
    val stop_reason: String? = null,
)

/**
 * 内容块。
 * v1.80 (L-ANT3): 增加 [id]/[name]/[input] 字段支持 tool_use 块解析。
 */
@Serializable
internal data class AnthropicContentBlock(
    val type: String,  // "text" / "thinking" / "tool_use" / "tool_result"
    val text: String = "",
    val id: String? = null,          // tool_use 块的工具调用 id
    val name: String? = null,        // tool_use 块的函数名
    val input: JsonElement? = null,  // tool_use 块的输入参数
)

// ── 流式事件 DTO ──

/**
 * 流式 SSE 事件。
 *
 * v1.80 扩展:
 *  - [index] / [content_block]: content_block_start 事件(H-ANT2 / L-ANT2)
 *  - [message]: message_start 事件体,含 usage(L-ANT1)
 *  - [usage]: message_delta 事件的 usage
 *  - [error]: error 事件(H-ANT4)
 */
@Serializable
internal data class AnthropicStreamEvent(
    val type: String,
    val delta: AnthropicDelta? = null,
    val index: Int? = null,                            // content_block_start/stop 的 block 下标
    val content_block: AnthropicContentBlock? = null,  // content_block_start 的块信息
    val message: AnthropicMessageStart? = null,        // message_start 的消息体(含 usage)
    val usage: AnthropicUsage? = null,                 // message_delta 的 usage
    val error: AnthropicErrorDetail? = null,           // error 事件的错误详情
)

/**
 * message_start 事件中的 message 体(L-ANT1)。
 * Anthropic 返回 {"type":"message_start","message":{"usage":{...}}}。
 */
@Serializable
internal data class AnthropicMessageStart(
    val usage: AnthropicUsage? = null,
)

/**
 * token 用量(L-ANT30)。
 * 用于 message_start(message.usage) 和 message_delta(顶层 usage)。
 */
@Serializable
internal data class AnthropicUsage(
    val input_tokens: Int = 0,
    val output_tokens: Int = 0,
    val cache_read_input_tokens: Int = 0,
    /** v1.80 (L-ANT4): Prompt Caching 创建缓存时计入的 token 数(用于成本统计)。 */
    val cache_creation_input_tokens: Int = 0,
)

/**
 * 流式 delta。
 *
 * v1.80 扩展:
 *  - [thinking]: thinking_delta 事件的思考内容(H-ANT1)
 *  - [partial_json]: input_json_delta 的工具参数增量(H-ANT2)
 *  - [signature]: signature_delta 的签名增量(M-ANT5)
 */
@Serializable
internal data class AnthropicDelta(
    val type: String? = null,
    val text: String? = null,
    val thinking: String? = null,          // thinking_delta 的思考内容
    val partial_json: String? = null,      // input_json_delta 的工具参数增量
    val signature: String? = null,         // signature_delta 的签名增量
    val stop_reason: String? = null,
)

/**
 * content_block_start 事件的结构化表示(L-ANT2)。
 * Anthropic 返回 {"type":"content_block_start","index":0,"content_block":{...}}。
 */
@Serializable
internal data class AnthropicContentBlockStart(
    val index: Int = 0,
    val content_block: AnthropicContentBlock? = null,
)

// ── 错误 ──

@Serializable
internal data class AnthropicErrorBody(
    val type: String? = null,
    val error: AnthropicErrorDetail? = null,
)

@Serializable
internal data class AnthropicErrorDetail(
    val type: String? = null,
    val message: String? = null,
)

/**
 * GET /v1/models 响应。data[].id 为模型 id。
 * v1.80 (L-ANT8): 增加 has_more/last_id 支持分页。
 */
@Serializable
internal data class AnthropicModelsResponse(
    val data: List<AnthropicModelInfo> = emptyList(),
    val has_more: Boolean = false,
    val first_id: String? = null,
    val last_id: String? = null,
)

@Serializable
internal data class AnthropicModelInfo(
    val id: String = "",
)
