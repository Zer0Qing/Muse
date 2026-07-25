package io.zer0.ai.core

/**
 * v1.0.7: 思考格式枚举(对齐 openhanako thinkingFormat 9 种)。
 *
 * 不同 OpenAI 兼容服务用不同字段表达"思考"参数,本枚举集中描述这些差异。
 * Provider 在构造请求体时按此枚举决定发什么字段;ProviderCompatRules 按
 * baseUrl host + modelId 派生具体值。
 *
 * ## 9 种格式(对齐 openhanako)
 *
 * - [ANTHROPIC]:Anthropic 原生 thinking 块(本枚举仅用于 OpenAI 兼容协议适配,
 *   AnthropicProvider 自身走原生 Messages API,不读此字段)
 * - [QWEN]:阿里通义/Qwen-VL 系列,`enable_thinking: bool` + `thinking_budget: int`
 * - [QWEN_CHAT_TEMPLATE]:Qwen3-Instruct 等 chat_template 模型,
 *   `chat_template_kwargs: {enable_thinking: bool}`
 * - [ZHIPU]:智谱 GLM-4 / GLM-Z1 系列,`thinking: {type: "enabled"|"disabled", clear_thinking: bool}`
 * - [DEEPSEEK]:DeepSeek-R1 / deepseek-reasoner,不发思考参数(服务端默认开),
 *   仅通过 `reasoning_content` 字段回传思考链
 * - [OPENROUTER]:OpenRouter 聚合,`reasoning: {effort: "low"|"medium"|"high"}`
 *   (在 Chat Completions 协议上的扩展,与 Responses API 的 reasoning 不同)
 * - [KIMI]:月之暗面 Kimi K1/K2,`thinking: {type: "enabled"|"disabled", keep: bool}`
 * - [VOLCENGINE]:火山引擎 Doubao 1.5 Thinking,`thinking: {type: "enabled"|"disabled"}`
 * - [LONGCAT]:美团 LongCat,`thinking: {type: "enabled"|"disabled"}`
 *
 * 注意:
 *  - null(未设置)走标准 OpenAI `reasoning_effort` 字段(由 ProviderCompat.supportsReasoningEffort 决定)
 *  - [DEEPSEEK] 不发任何思考参数,但其 reasoning_content 必须能被流式解析(由 OpenAIProvider 的 delta.reasoningContent 处理)
 *  - 该枚举仅驱动 OpenAIProvider 的请求体构造;AnthropicProvider / GeminiProvider 各自实现原生思考
 */
enum class ThinkingFormat {
    ANTHROPIC,
    QWEN,
    QWEN_CHAT_TEMPLATE,
    ZHIPU,
    DEEPSEEK,
    OPENROUTER,
    KIMI,
    VOLCENGINE,
    LONGCAT,
}

/**
 * v1.0.7: reasoning 回放载体(对齐 openhanako 5 种 carrier)。
 *
 * 当历史消息含上一轮推理内容时,不同协议用不同 wire 字段回放。
 * 本枚举决定 UIMessage.reasoning / UIMessage.thinkingSignature 如何序列化回请求体。
 *
 * - [REASONING_CONTENT]:Kimi / DeepSeek / MiMo / Zhipu Chat Completions,
 *   在 `messages[i].reasoning_content` 字段(string),policy 通常为 [ReasoningReplayPolicy.REQUIRE_TOOL_CALL]
 * - [THINKING_BLOCKS]:Anthropic Messages,在 `messages[i].content[j]` 中加 thinking block
 *   (由 AnthropicProvider 自身处理,不走 ProviderPayloadNormalizer)
 * - [REASONING_ITEMS]:OpenAI Responses API,在 `input[]` 顶层 sibling 加 reasoning item
 *   (由 OpenAIProvider useResponseApi 分支处理)
 * - [REASONING_DETAILS]:OpenRouter,在 `messages[i].reasoning_details: [{type, id, data}]` 数组
 * - [THOUGHT_SIGNATURE]:Gemini native,在 `messages[i].content[j]` 中加 thought part
 *   (由 GeminiProvider 自身处理)
 */
enum class ReasoningCarrier {
    REASONING_CONTENT,
    THINKING_BLOCKS,
    REASONING_ITEMS,
    REASONING_DETAILS,
    THOUGHT_SIGNATURE,
}

/**
 * v1.0.7: reasoning 回放策略(对齐 openhanako 3 种 policy)。
 *
 * - [NONE]:不回放(默认),历史 reasoning 字段不写入请求体
 * - [PRESERVE]:始终保留,只要历史消息带 reasoning 字段就原样回放
 *   (Anthropic thinking_blocks / OpenAI Responses reasoning_items / OpenRouter reasoning_details /
 *    Gemini thought_signature 都用此策略)
 * - [REQUIRE_TOOL_CALL]:仅当历史 assistant 消息带 tool_calls 时才回放 reasoning_content,
 *   否则 fail closed(防止 Kimi/DeepSeek 因缺 reasoning_content 报多轮对话错误)
 *   (Kimi / DeepSeek / MiMo / Zhipu Chat Completions 用此策略)
 */
enum class ReasoningReplayPolicy {
    NONE,
    PRESERVE,
    REQUIRE_TOOL_CALL,
}

/**
 * v1.0.7: reasoning 回放契约(carrier + policy)。
 *
 * 由 [ProviderCompat.reasoningReplayContract] 携带,OpenAIProvider 在 toOpenAI 转换
 * 历史消息时消费此契约,决定如何写入 reasoning 字段。
 *
 * @param carrier wire 字段位置(见 [ReasoningCarrier])
 * @param policy 回放策略(见 [ReasoningReplayPolicy])
 */
data class ReasoningReplayContract(
    val carrier: ReasoningCarrier,
    val policy: ReasoningReplayPolicy,
)
