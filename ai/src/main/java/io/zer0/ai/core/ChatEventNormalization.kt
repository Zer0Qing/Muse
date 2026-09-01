package io.zer0.ai.core

/**
 * M2.4/M2.5: 流式与非流式的统一事件边界。
 *
 * - [ChatCompletion.toEventSequence]:把非流式一次性结果转换为与 streamChat()
 *   相同的 [ChatStreamEvent] 事件序列(内容 → 思考 → 工具 → 用量 → 引用 → 完成)。
 *   此前该逻辑只存在于 app 层聊天 UI(io.zer0.muse.ui.chat.ChatResponsePolicy),
 *   M2 起下沉到 ai 模块作为唯一归一化实现,app 层改为委托调用。
 * - [ChatEventReducer]:把任意来源的事件序列聚合为 [NormalizedChatResult],
 *   供"流式/非流式最终 projection 等价"验收与 SSE fixture replay 测试使用。
 *
 * 事件序列约定(与 ChatStreamEvent 的 KDoc 一致):
 *  ReasoningDelta → ContentDelta → ImageDelta* → ToolCallDelta*(index 升序) →
 *  UsageDelta* → CitationDelta → Done
 */

/**
 * 把非流式完整结果转换为与流式等价的事件序列。
 *
 * 空正文、仅 reasoning 的响应(部分推理模型的非流式行为)也产生有效序列:
 * reasoning 事件照常发出,调用方据此判断响应有效,而不是误报空文本错误。
 */
fun ChatCompletion.toEventSequence(): List<ChatStreamEvent> = buildList {
    reasoningContent?.takeIf { it.isNotEmpty() }?.let {
        add(
            ChatStreamEvent.ReasoningDelta(
                delta = it,
                signature = thinkingSignature,
                encryptedContent = thinkingEncryptedContent,
            ),
        )
    }
    text.takeIf { it.isNotEmpty() }?.let { add(ChatStreamEvent.ContentDelta(it)) }
    toolCalls.orEmpty().forEachIndexed { index, toolCall ->
        add(
            ChatStreamEvent.ToolCallDelta(
                index = index,
                id = toolCall.id,
                name = toolCall.name,
                argumentsDelta = toolCall.arguments,
                // 非流式结果携带完整参数快照,消费方应替换而非追加
                isSnapshot = true,
            ),
        )
    }
    usageTokens?.let { add(ChatStreamEvent.UsageDelta(it)) }
    citationUrls.takeIf { it.isNotEmpty() }?.let { add(ChatStreamEvent.CitationDelta(it)) }
    add(ChatStreamEvent.Done(finishReason))
}

/**
 * M2.4: 归一化聊天结果 — 流式聚合与非流式转换的统一产物。
 *
 * 同一逻辑响应无论走 streamChat() 还是 completeText() → toEventSequence(),
 * 经 [ChatEventReducer] 聚合后得到的 [NormalizedChatResult] 必须相等(M2 验收)。
 *
 * @param text 聚合后的正文(空串表示无正文)
 * @param reasoning 聚合后的思考内容(null 表示无)
 * @param toolCalls 聚合后的工具调用(按 index 升序)
 * @param finishReason 完成原因(Done 事件携带)
 * @param usage 最后一次用量事件(Provider 未返回时为 null)
 * @param citationUrls 引用 URL(保持事件顺序去重)
 * @param thinkingSignature 思考签名(Anthropic / OpenAI Responses)
 * @param thinkingEncryptedContent OpenAI Responses encrypted_content
 * @param imageBase64List Gemini inlineData 图片增量(按到达顺序)
 * @param error 错误事件消息(Error/StreamInterrupted);null 表示流正常完成
 * @param interrupted 是否为 StreamInterrupted(已收部分内容后中断)
 * @param postFinishFramesDropped Done 之后到达并被丢弃的 delta 帧数(协议异常观测)
 */
data class NormalizedChatResult(
    val text: String = "",
    val reasoning: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val finishReason: String? = null,
    val usage: UsageTokens? = null,
    val citationUrls: List<String> = emptyList(),
    val thinkingSignature: String? = null,
    val thinkingEncryptedContent: String? = null,
    val imageBase64List: List<String> = emptyList(),
    val error: String? = null,
    val interrupted: Boolean = false,
    val postFinishFramesDropped: Int = 0,
)

/**
 * M2.6: [ChatStreamEvent] 聚合器(fixture replay 的核心纯函数)。
 *
 * 聚合规则:
 *  - ContentDelta/ReasoningDelta:按到达顺序追加。
 *  - ToolCallDelta:按 index 聚合;isSnapshot=true 时替换该 index 的参数(重复快照帧
 *    天然去重),否则增量追加;id/name 首个非空值生效;输出按 index 升序。
 *    注:正文 ContentDelta 不做任意去重 —— 合法响应可能含重复片段,
 *    重复帧去重只对"完整快照替换"语义的工具调用帧安全。
 *  - UsageDelta:最后一次生效(Anthropic 输入/输出分两次发出)。
 *  - CitationDelta:顺序合并去重。
 *  - Done:记录 finishReason;之后的 delta 帧丢弃并计数(finish 后 delta 是协议异常)。
 *  - Error / StreamInterrupted:记录错误与中断标志,已聚合内容保留(部分回复不丢)。
 */
class ChatEventReducer {

    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private val toolCalls = sortedMapOf<Int, ToolCallAccumulator>()
    private val images = mutableListOf<String>()
    private val citations = linkedSetOf<String>()
    private var finishReason: String? = null

    /**
     * 审查修复(P2): 终态判定用显式标志而非 `finishReason != null` —
     * [ChatStreamEvent.Done] 允许 finishReason 为 null(部分 Provider 无停止原因),
     * 只靠 finishReason 会让 Done(null) 之后的增量帧不被丢弃,与协议约定不符。
     */
    private var doneSeen = false
    private var usage: UsageTokens? = null
    private var signature: String? = null
    private var encryptedContent: String? = null
    private var error: String? = null
    private var interrupted = false
    private var postFinishFramesDropped = 0

    /** 逐事件聚合;可在任意线程串行调用(非线程安全,调用方保证顺序)。 */
    fun reduce(event: ChatStreamEvent) {
        if (doneSeen && isIncrementalFrame(event)) {
            // Done 之后只丢弃增量帧(协议异常观测);终结性事件(Error/Usage/Citation)照常处理
            postFinishFramesDropped++
            return
        }
        when (event) {
            is ChatStreamEvent.ReasoningDelta -> consumeReasoning(event)
            is ChatStreamEvent.ContentDelta -> content.append(event.delta)
            is ChatStreamEvent.ImageDelta -> images.add(event.imageBase64)
            is ChatStreamEvent.ToolCallDelta -> consumeToolCall(event)
            else -> reduceControl(event)
        }
    }

    /** 思考增量:正文累积 + 签名/加密内容取非空覆盖。 */
    private fun consumeReasoning(event: ChatStreamEvent.ReasoningDelta) {
        reasoning.append(event.delta)
        event.signature?.let { signature = it }
        event.encryptedContent?.let { encryptedContent = it }
    }

    /** 终结性/控制事件(用量、引用、完成、错误、断流、降级提示)。 */
    private fun reduceControl(event: ChatStreamEvent) = when (event) {
        is ChatStreamEvent.UsageDelta -> usage = event.usage
        is ChatStreamEvent.CitationDelta -> citations.addAll(event.urls)
        is ChatStreamEvent.Done -> {
            finishReason = event.finishReason
            // 审查修复(P2): Done(null) 也是终态,必须置位,后续增量帧才会被丢弃
            doneSeen = true
        }
        is ChatStreamEvent.Error -> recordFailure(event.message, interrupted = false)
        is ChatStreamEvent.StreamInterrupted -> recordFailure(event.message, interrupted = true)
        else -> Unit
    }

    /** 工具调用帧按 index 聚合(快照替换 / 增量追加)。 */
    private fun consumeToolCall(event: ChatStreamEvent.ToolCallDelta) {
        val acc = toolCalls.getOrPut(event.index) { ToolCallAccumulator() }
        acc.consume(event)
    }

    /** 错误/断流落账:保留已聚合内容(部分回复不丢)。 */
    private fun recordFailure(message: String, interrupted: Boolean) {
        error = message
        this.interrupted = interrupted
    }

    /** 增量帧判定:Done 之后出现的这类帧属于协议异常,丢弃并计数。 */
    private fun isIncrementalFrame(event: ChatStreamEvent): Boolean = when (event) {
        is ChatStreamEvent.ContentDelta,
        is ChatStreamEvent.ReasoningDelta,
        is ChatStreamEvent.ToolCallDelta,
        is ChatStreamEvent.ImageDelta,
        -> true
        else -> false
    }

    fun reduceAll(events: Iterable<ChatStreamEvent>) {
        events.forEach(::reduce)
    }

    /** 输出聚合结果;可多次调用(返回快照,不改变聚合状态)。 */
    fun toResult(): NormalizedChatResult = NormalizedChatResult(
        text = content.toString(),
        reasoning = reasoning.toString().ifEmpty { null },
        toolCalls = toolCalls.map { (index, acc) -> acc.toToolCall(index) },
        finishReason = finishReason,
        usage = usage,
        citationUrls = citations.toList(),
        thinkingSignature = signature,
        thinkingEncryptedContent = encryptedContent,
        imageBase64List = images.toList(),
        error = error,
        interrupted = interrupted,
        postFinishFramesDropped = postFinishFramesDropped,
    )

    /**
     * 单个 index 的工具调用累积器。
     * id/name 取首个非空值(后续分片通常为 null);参数按 isSnapshot 语义替换或追加。
     */
    private class ToolCallAccumulator {
        var id: String? = null
        var name: String? = null
        private val arguments = StringBuilder()

        fun consume(event: ChatStreamEvent.ToolCallDelta) {
            if (id == null && event.id != null) id = event.id
            if (name == null && event.name != null) name = event.name
            val delta = event.argumentsDelta ?: return
            if (event.isSnapshot) {
                arguments.setLength(0)
                arguments.append(delta)
            } else {
                arguments.append(delta)
            }
        }

        fun toToolCall(index: Int): ToolCall = ToolCall(
            id = id ?: "call_index_$index",
            name = name ?: "",
            arguments = arguments.toString(),
        )
    }
}

/**
 * 便捷入口:事件序列一次性聚合为 [NormalizedChatResult]。
 */
fun List<ChatStreamEvent>.normalize(): NormalizedChatResult =
    ChatEventReducer().also { it.reduceAll(this) }.toResult()
