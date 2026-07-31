package io.zer0.ai.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 流式推理过程中产生的增量事件。
 * UI 层消费这些事件来更新气泡内容、推理过程、错误提示。
 */
sealed class ChatStreamEvent {
    /**
     * 推理过程增量(o1 / thinking 模型)。
     *
     * v1.80 (M-ANT4): [signature] 携带 Anthropic thinking 块的签名(可选),
     * 用于多轮 thinking 对话回放(signature_delta 事件发出)。
     * 非 thinking 模型 / OpenAI 等其他 Provider 该字段为 null。
     * 消费方(如 ChatViewModel)应将其存入 [UIMessage.thinkingSignature],
     * 并在下一轮请求时回传给 Anthropic(否则多轮 thinking 会失败)。
     */
    data class ReasoningDelta(
        val delta: String,
        val signature: String? = null,
    ) : ChatStreamEvent()

    /** 正文增量。 */
    data class ContentDelta(val delta: String) : ChatStreamEvent()

    /**
     * Phase 8.6: 图片增量(Gemini 绘图返回的 inlineData)。
     *
     * @param imageBase64 图片 base64 数据(无 data: 前缀)
     * @param mimeType MIME 类型,如 "image/png"
     */
    data class ImageDelta(val imageBase64: String, val mimeType: String = "image/png") : ChatStreamEvent()

    /**
     * 工具调用增量(Phase 7 function calling)。
     *
     * OpenAI 流式协议中,tool_calls 是数组,每个元素有 index/id/function{name/arguments}。
     * 同一个 tool_call 可能跨多个 SSE chunk 发送(arguments 分片),需按 index 累积。
     *
     * @param index 工具调用在数组中的下标(用于累积)
     * @param id 工具调用 id(首个 chunk 携带,后续片段为 null)
     * @param name 函数名(首个 chunk 携带,后续片段为 null)
     * @param argumentsDelta 参数 JSON 增量片段
     */
    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val argumentsDelta: String? = null,
    ) : ChatStreamEvent()

    /** 一轮正常结束,带可选的停止原因。 */
    data class Done(val finishReason: String? = null) : ChatStreamEvent()

    /** 出错,流被中断。 */
    data class Error(val message: String, val throwable: Throwable? = null) : ChatStreamEvent()

    /**
     * v1.0.15: 流式连接被中断(网络切换/后台 Doze/服务端中途断开),但已收到部分内容。
     *
     * 与 [Error] 的区别:
     *  - [Error]: 还没收到任何内容(anyDeltaSent=false)就失败,UI 应显示错误并允许重试
     *  - [StreamInterrupted]: 已收到部分内容(anyDeltaSent=true)后连接断开,
     *    UI 应保留已收内容并提示"网络中断,正在尝试恢复",网络恢复后可自动重连
     *
     * 消费方(ChatViewModel)应:
     *  1. 不清空已收的 ContentDelta/ReasoningDelta,保留已生成的部分回复
     *  2. 显示"网络中断"提示(非致命错误,不弹错误对话框)
     *  3. 监听 NetworkMonitor,网络恢复后自动重新发起请求续生成
     */
    data class StreamInterrupted(
        val message: String,
        val throwable: Throwable? = null,
    ) : ChatStreamEvent()
}

/**
 * v1.0.7: 聊天请求模式(对齐 openhanako mode: "chat" | "utility")。
 *
 * - [CHAT]:用户对话路径,reasoningLevel 由用户偏好/session 决定,可开可关
 * - [UTILITY]:后台短文本生成路径(memory 摘要 / fact 抽取 / 视觉辅助 / skill 执行 /
 *   上下文压缩 / 主动消息 / 定时任务等),强制 reasoningLevel = OFF
 *   (短输出不需要思考链 + 省 token + 降延迟),且不注入 ProviderPromptPatches
 *
 * 设计原则(对齐 openhanako buildProviderCompatOptions):
 *  - UTILITY 模式下,Provider 内部把 effectiveReasoningLevel 强制为 OFF,
 *    调用方传入的 reasoningLevel 被覆盖(对齐 openhanako utility 模式注入 reasoningLevel: "off")
 *  - 各 Provider 在 buildRequestBody 时按 mode 决定是否跳过 thinkingFormat 注入
 */
enum class ChatRequestMode {
    CHAT,
    UTILITY,
}

/**
 * 一次聊天请求的入参。
 *
 * @param messages 完整对话历史(含 system / user / assistant),由调用方裁剪
 * @param model 目标模型
 * @param temperature 采样温度,null 表示走服务端默认
 * @param maxTokens 输出上限,null 表示走服务端默认
 * @param abortSignal 由调用方持有的取消标志,置 true 后尽快中断流
 * @param tools 可选的工具定义列表(Phase 7 function calling),null 表示不启用工具调用
 * @param reasoningLevel 推理等级(Phase 8.1 M11),null 表示用 [ReasoningLevel.DEFAULT]
 *   各 Provider 根据 [Model.supportsReasoning] 和此字段决定是否发 thinking 字段
 *   v1.0.7: 当 [mode]=[ChatRequestMode.UTILITY] 时,此字段被强制覆盖为 OFF
 * @param mode v1.0.7: 请求模式,默认 [ChatRequestMode.CHAT];
 *   UTILITY 模式强制关思考(memory 摘要 / fact 抽取 / 视觉辅助等后台任务)
 */
data class ChatRequest(
    val messages: List<UIMessage>,
    val model: Model,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val abortSignal: AbortSignal = AbortSignal(),
    val tools: List<ToolDefinition>? = null,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.DEFAULT,
    val mode: ChatRequestMode = ChatRequestMode.CHAT,
)

/**
 * 工具定义(Provider 无关的中间表示)。
 *
 * @param name 函数名
 * @param description 描述(LLM 据此决定是否调用)
 * @param parametersJsonSchema 参数的 JSON Schema 字符串(OpenAI 兼容格式)
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
)

/** 协作式取消信号。UI 点"停止"时把 [aborted] 置 true。 */
class AbortSignal {
    @Volatile
    var aborted: Boolean = false
        private set

    fun abort() { aborted = true }
}

/**
 * 非流式调用的结果。memory 模块等后台任务用 [completeText] 拿完整文本,
 * 不需要逐步消费增量事件。
 *
 * Phase 7: [toolCalls] 用于非流式场景的工具调用结果。
 *
 * v1.80 (M-OAI8): 新增 [reasoningContent] 字段,承载推理模型的推理内容。
 * 部分推理模型(o1/DeepSeek-R1 等)在非流式响应中可能只返回 reasoning_content
 * 而 content 为空,此时调用方需据此判断响应是否有效,避免误报"空文本"错误。
 */
data class ChatCompletion(
    val text: String,
    val finishReason: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val reasoningContent: String? = null,
    /** v1.0.53 Phase 3: token 用量(prompt + completion + reasoning 合计)。null 表示上游未返回。 */
    val usageTokens: UsageTokens? = null,
)

/**
 * v1.0.53 Phase 3: token 用量明细(对齐 OpenAI usage 字段)。
 *
 * Provider 未返回 usage 时为 null,AgentTokenBudget 跳过累加(避免误判耗尽)。
 */
data class UsageTokens(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** 推理 token(部分模型单独计入,如 OpenAI o1 的 completion_tokens_details.reasoning_tokens)。 */
    val reasoningTokens: Int = 0,
) {
    /** 总消耗 = prompt + completion(已含 reasoning,不重复加)。 */
    val total: Int get() = promptTokens + completionTokens
}

/**
 * Provider 抽象。每个具体厂商(OpenAI / Google / Anthropic)实现一份,
 * 负责把统一 [ChatRequest] 翻译成自家 HTTP API 调用并以 [Flow] 形式返回流式事件。
 */
interface Provider {
    val id: String
    val displayName: String

    /** 流式聊天。返回的 Flow 在收集时执行网络请求,完成或出错时关闭。 */
    fun streamChat(request: ChatRequest): Flow<ChatStreamEvent>

    /**
     * 非流式聊天。一次性返回完整结果,适用于 memory 编译、fact 抽取等后台任务。
     * 默认抛 [UnsupportedOperationException],由具体 Provider 按需实现。
     */
    suspend fun completeText(request: ChatRequest): ChatCompletion =
        throw UnsupportedOperationException("$displayName does not implement completeText")

    /**
     * 拉取上游模型列表。
     *
     * 各具体 Provider 调用上游 /models 接口返回可用模型,供 UI 编辑页"拉取上游模型"使用。
     * [config] 由调用方传入(可能含未保存的编辑值),实现按其解析 baseUrl/apiKey。
     * 默认返回空列表,表示该 Provider 未实现拉取(如自定义中转可走 OpenAI 兼容)。
     */
    suspend fun listModels(config: ProviderConfig): List<Model> = emptyList()

    /**
     * v1.0.8 (7.6): 模型健康检查 — 用真实 LLM 调用验证 apiKey / baseUrl / model 是否可用。
     *
     * 实现:向 [model] 发送一条 "Reply exactly OK." 的 user 消息,
     *  15s 超时内若拿到非空响应即视为健康(返回 success=true)。
     *  适用于:
     *   - 设置页"测试"按钮:用户改完 apiKey/baseUrl 后一键验证连通性
     *   - 启动时自动检查默认 Provider 是否可用
     *
     *  与 [listModels] 的区别:
     *   - listModels 仅验证 /models 端点(轻量,但不保证 chat 接口可用)
     *   - healthCheck 验证完整 chat 链路(发真实消息,能检测出 model id 错误 / 余额不足等)
     *
     *  默认实现走 [completeText],具体 Provider 如需特殊处理可覆盖。
     *
     * @param model 待测试的模型(必填,决定请求的 model 字段)
     * @return [HealthCheckResult] 含 success 标志 + 失败时的提示消息
     */
    suspend fun healthCheck(model: Model): HealthCheckResult {
        return try {
            val result = withTimeoutOrNull(HEALTH_CHECK_TIMEOUT_MS) {
                val request = ChatRequest(
                    messages = listOf(
                        UIMessage(role = MessageRole.USER, content = "Reply exactly OK."),
                    ),
                    model = model,
                    // 限制输出长度,避免健康检查消耗过多 token
                    maxTokens = 16,
                    // 后台任务模式:关思考,降延迟
                    mode = ChatRequestMode.UTILITY,
                )
                completeText(request)
            }
            if (result == null) {
                HealthCheckResult(success = false, message = "timeout (15s)")
            } else if (result.text.isBlank() && result.toolCalls.isNullOrEmpty()) {
                HealthCheckResult(success = false, message = "empty response")
            } else {
                HealthCheckResult(success = true, message = result.text.take(80))
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            HealthCheckResult(success = false, message = t.message?.take(120))
        }
    }

    companion object {
        /** 健康检查超时时间(毫秒),15s 兼顾慢网络与用户体验。 */
        const val HEALTH_CHECK_TIMEOUT_MS: Long = 15_000L
    }
}

/**
 * v1.0.8 (7.6): 健康检查结果。
 *
 * @param success 是否通过(真实 LLM 调用拿到非空响应)
 * @param message 成功时为模型回复摘要(供 UI 展示);失败时为错误提示
 */
data class HealthCheckResult(
    val success: Boolean,
    val message: String?,
)
