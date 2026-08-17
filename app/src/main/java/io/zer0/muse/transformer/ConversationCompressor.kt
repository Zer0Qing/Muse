package io.zer0.muse.transformer

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.Model
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 对话压缩器 — 分块并行 LLM 摘要,使用独立便宜模型。
 *
 * 既有实现 ChatService.compressConversation 的设计思路:
 *  - 把待压缩的历史消息按 [CHUNK_SIZE] 切分为多块
 *  - 每块独立调用 LLM 生成摘要,多块并行(coroutineScope + async)
 *  - 使用独立的"压缩模型"([compressModelIdFlow],用户可配置为便宜模型),
 *    避免占用主对话模型额度,同时避免管道内同步阻塞导致首字延迟
 *
 * 与 [ContextCompressTransformer] 的关系:
 *  - [ContextCompressTransformer] 负责阈值判断 / prefix 跳过 / 优先级保留等管道逻辑
 *  - 本类只负责"分块并行 + 独立模型"的摘要生成
 *  - [ContextCompressTransformer] 在 transform 中委托本类完成实际压缩
 *
 * 降级策略:
 *  - 单块压缩失败 → 该块回退为"摘要生成失败"占位文本,不阻断其他块
 *  - 全部失败 → 调用方([ContextCompressTransformer])走原有降级逻辑(截断 / 标记)
 *  - completeText 失败时回退 streamChat(与 [ContextCompressTransformer] 原实现一致)
 */
class ConversationCompressor(
    private val chatService: ChatService,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        private const val TAG = "ConversationCompressor"
        /** 每块最多消息条数(超过则切分为多块并行)。 */
        private const val CHUNK_SIZE = 256
        /** 压缩温度(低温度保证摘要稳定、不编造)。 */
        private const val COMPRESS_TEMPERATURE = 0.3f
        /** 单块摘要最大 token 数。 */
        private const val COMPRESS_MAX_TOKENS = 1000
        /** 单条消息送入 LLM 时的最大字符数(超过则截断,与 ContextCompressTransformer 对齐)。 */
        private const val MAX_MSG_CHARS = 1500
        /** v1.0.51: 并行压缩块数上限 — 避免长对话切出大量块时并发轰炸 API。 */
        private const val MAX_CONCURRENT_CHUNKS = 3
    }

    /** v1.0.51: 并发限制信号量,与 DeepMemoryProcessor/MemoryTicker 对齐(上限 3)。 */
    private val chunkSemaphore = Semaphore(MAX_CONCURRENT_CHUNKS)

    /**
     * 压缩对话历史 — 分块并行 + 独立便宜模型。
     *
     * @param toCompress 待压缩的消息列表(调用方已完成 prefix 跳过 / 优先级保留等过滤)
     * @param instruction 本次压缩附加指令(优先于设置级 customCompressPrompt,用于手动压缩对话框)
     * @return 摘要文本列表(每块对应一条摘要),调用方据此构造摘要消息
     */
    suspend fun compress(toCompress: List<UIMessage>, instruction: String? = null): List<String> {
        if (toCompress.isEmpty()) return emptyList()

        // 分块(每块最多 CHUNK_SIZE 条)
        val chunks = chunkMessages(toCompress, CHUNK_SIZE)
        Logger.i(TAG, "compress: ${toCompress.size} 条消息分为 ${chunks.size} 块并行压缩")

        // v1.0.52: 读取用户自定义压缩 prompt(null/空串表示用默认)
        // H10: 本次附加指令优先,否则回退设置级自定义 prompt
        val customPrompt = instruction
            ?.takeIf { it.isNotBlank() }
            ?: resultOf { settingsRepository.customCompressPromptFlow.first() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        if (customPrompt != null) {
            Logger.i(TAG, "compress: 使用自定义压缩指令")
        }

        // 并行压缩每块(v1.0.51: 用 Semaphore 限制并发,避免大量块同时调 LLM 轰炸 API)
        return coroutineScope {
            chunks.map { chunk ->
                async { chunkSemaphore.withPermit { compressChunk(chunk, customPrompt) } }
            }.let { deferredList ->
                deferredList.map { it.await() }
            }
        }
    }

    /**
     * 把消息列表按 [chunkSize] 切分为多块。
     * size <= chunkSize 时返回单块(与原任务实现一致,不做二分递归以保持简单)。
     */
    private fun chunkMessages(messages: List<UIMessage>, chunkSize: Int): List<List<UIMessage>> {
        if (messages.isEmpty()) return emptyList()
        if (messages.size <= chunkSize) return listOf(messages)
        val result = mutableListOf<List<UIMessage>>()
        var i = 0
        while (i < messages.size) {
            result.add(messages.subList(i, minOf(i + chunkSize, messages.size)))
            i += chunkSize
        }
        return result
    }

    /**
     * 压缩单块消息为摘要文本。
     * 失败时返回占位文本(不抛异常),保证并行流程不因单块失败而中断。
     *
     * v1.0.52: 支持 [customPrompt] 参数 — 用户可在设置中覆盖默认压缩指令。
     * 非空时用用户自定义指令替代默认的结构化指令,对话历史仍以相同格式追加。
     *
     * @param chunk 待压缩的消息块
     * @param customPrompt 用户自定义压缩指令(null/空串表示用默认结构化指令)
     */
    private suspend fun compressChunk(chunk: List<UIMessage>, customPrompt: String? = null): String {
        val (providerConfig, model) = resolveCompressModel()

        val prompt = buildString {
            if (!customPrompt.isNullOrBlank()) {
                // v1.0.52: 用户自定义压缩指令
                appendLine(customPrompt)
            } else {
                // 默认结构化压缩指令
                appendLine("请把下面的对话历史压缩成简洁的摘要,保留关键信息(事实/决策/用户偏好)。")
                appendLine("按以下结构组织摘要(每节用要点形式,每点一行;某节无内容可省略):")
                appendLine("- Key topics: 讨论了哪些主题,以及为什么重要(粗颗粒,不要流水账)")
                appendLine("- Decisions: 做出了哪些决策,以及背后的理由")
                appendLine("- Current work: 正在进行的工作及其当前状态")
                appendLine("- Next steps: 待办的下一步、未解决的问题、需要后续跟进的事项")
                appendLine("- User preferences: 用户表现出的偏好或约束(如有)")
                appendLine("- 不要编造未提及的内容")
                appendLine("- 总长度不超过 800 字")
            }
            appendLine()
            appendLine("对话历史:")
            chunk.forEach { msg ->
                val role = when (msg.role) {
                    MessageRole.USER -> "用户"
                    MessageRole.ASSISTANT -> "助手"
                    MessageRole.SYSTEM -> "系统"
                    MessageRole.TOOL -> "工具"
                }
                val raw = msg.content
                val text = if (raw.length > MAX_MSG_CHARS) raw.take(MAX_MSG_CHARS) + "…" else raw
                appendLine("[$role] $text")
            }
        }

        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "你是对话压缩助手,输出简洁中文摘要。"),
            UIMessage(role = MessageRole.USER, content = prompt),
        )

        return try {
            // H-COMP2 / L-COMP7: 用 resultOf 替代 runCatching,不吞 CancellationException
            // completeText 默认走 UTILITY 模式(自动关思考),适合后台摘要任务
            val completion = resultOf {
                chatService.completeText(
                    messages = messages,
                    model = model,
                    providerConfig = providerConfig,
                    temperature = COMPRESS_TEMPERATURE,
                    maxTokens = COMPRESS_MAX_TOKENS,
                    mode = ChatRequestMode.UTILITY,
                )
            }.onError { msg, t ->
                Logger.w(TAG, "completeText 失败,回退 streamChat: $msg", t)
            }.getOrNull() ?: run {
                // 兜底:流式收集(仅对非 CancellationException 错误到达此处)
                val sb = StringBuilder()
                chatService.streamChat(
                    messages = messages,
                    model = model,
                    providerConfig = providerConfig,
                    temperature = COMPRESS_TEMPERATURE,
                    maxTokens = COMPRESS_MAX_TOKENS,
                    mode = ChatRequestMode.UTILITY,
                ).collect { ev ->
                    if (ev is ChatStreamEvent.ContentDelta) sb.append(ev.delta)
                }
                io.zer0.ai.core.ChatCompletion(text = sb.toString())
            }
            // v1.0.74 fix: 剥离 <think> 推理标签,防止思考内容混入压缩摘要
            completion.text.let { io.zer0.muse.transformer.stripThinkTags(it) }
                .ifBlank { "历史对话已压缩(摘要为空)" }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 不吞协程取消,直接传播
            throw e
        } catch (t: Throwable) {
            Logger.e(TAG, "compressChunk failed, fallback to placeholder", t)
            "摘要生成失败: ${t.message?.take(80)}"
        }
    }

    /**
     * 解析压缩用的模型与 ProviderConfig。
     *
     * 优先级:
     *  1. [SettingsRepository.compressModelIdFlow] 配置的压缩模型 id(跨 Provider 查找)
     *  2. 当前激活 Provider 的 selectedModel(回退,与 [ContextCompressTransformer] 原行为一致)
     *
     * 找不到压缩模型时回退到激活 Provider 的首个模型(与 [ChatService.completeText] 默认行为一致)。
     */
    private suspend fun resolveCompressModel(): Pair<ProviderConfig?, Model?> {
        // v1.0.62: 压缩模型跟随对话默认模型，不再使用独立 compressModelId。
        // 此前独立配置存在跨 Provider 按 id 匹配的缺陷：同 id 模型在多个渠道存在时
        // 会命中无关渠道的小模型，导致压缩质量忽高忽低。返回 null 让 ChatService
        // 内部使用激活 Provider 的当前选中模型。
        return null to null
    }
}
