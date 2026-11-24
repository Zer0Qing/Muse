package io.zer0.muse.transformer

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.routing.UtilityModelRouter
import io.zer0.muse.data.routing.UtilityTier
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 对话压缩器 — 分块并行 LLM 摘要,使用独立便宜模型。
 *
 * 设计思路:
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
 * 降级策略(v2.3.2 起):
 *  - 单块压缩失败/摘要为空 → [compressChunk] 返回 null(不再返回"摘要生成失败"占位文本)
 *  - **任一块失败 → [compress] 整体返回 null**,由调用方 [ContextCompressTransformer] 保留原文、
 *    本轮不做压缩(宁可上下文长一点,也不能让模型把占位当成它见过的历史)
 *  - completeText 失败时仍回退 streamChat(与 [ContextCompressTransformer] 原实现一致)
 */
class ConversationCompressor(
    private val chatService: ChatService,
    private val settingsRepository: SettingsRepository,
) {
    /** v2.x: 辅助模型路由 — 压缩归「大工具」档(留空级联小工具,再留空回退主对话模型)。 */
    private val utilityRouter = UtilityModelRouter(settingsRepository)

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

        /** v2.3.2: 未知上下文窗口时的保守默认值(token)。 */
        private const val DEFAULT_CONTEXT_WINDOW_TOKENS = 32_000

        /** v2.3.2: 单块 prompt 字符预算的下限与上限。 */
        private const val MIN_CHUNK_CHARS = 6_000
        private const val MAX_CHUNK_CHARS = 48_000

        /** v2.3.2: 输入预算占模型上下文窗口的比例(其余留给摘要输出与指令)。 */
        private const val CHUNK_BUDGET_RATIO = 0.4

        /**
         * 判断待压缩对话是否应使用中文摘要契约：优先统计对话文本的汉字/拉丁占比，
         * 纯数字或空样本时回落应用界面 locale，避免英文对话被强行压成中文摘要。
         */
        internal fun detectChineseConversation(messages: List<UIMessage>, appLocale: String): Boolean {
            var cjkCount = 0
            var latinCount = 0
            var inspected = 0
            for (msg in messages.take(20)) {
                for (ch in msg.content) {
                    if (ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF') {
                        cjkCount++
                    } else if (ch in 'A'..'Z' || ch in 'a'..'z') {
                        latinCount++
                    }
                    inspected++
                    if (inspected >= 2_000) break
                }
                if (inspected >= 2_000) break
            }
            return when {
                cjkCount > 0 && cjkCount * 2 >= latinCount -> true
                latinCount > 0 -> false
                else -> isChineseLocale(appLocale)
            }
        }

        /**
         * 取出上一版摘要里的「已否定」段。
         *
         * 这段内容不参与滚动衰减：每次增量压缩都必须把它单独带回去，
         * 否则被否掉的方案会从摘要里消失，模型下一轮又会重新提出来。
         */
        internal fun extractNegatedSection(summary: String): String {
            val lines = summary.lines()
            val headingRegex = Regex("^(#{1,6})\\s*(已否定|Negated)\\s*$")
            val start = lines.indexOfFirst { headingRegex.matches(it.trim()) }
            if (start < 0) return ""
            val level = headingRegex.find(lines[start].trim())?.groupValues?.get(1)?.length ?: return ""
            val body = mutableListOf<String>()
            for (i in start + 1 until lines.size) {
                val line = lines[i]
                val header = Regex("^(#{1,6})\\s+.+$").find(line.trim())
                if (header != null && (header.groupValues[1].length <= level)) break
                body.add(line)
            }
            return body.joinToString("\n").trim()
        }

        /**
         * v2.3.2: 单块压缩 prompt 的字符预算。
         *
         * 原实现只按条数切块(最多 256 条 × 单条 1500 字符 ≈ **38.4 万字符**),
         * 长会话一旦触发压缩就必然超出压缩模型窗口(表现为 400 报错或摘要被截断)。
         * 现按模型上下文窗口的 [CHUNK_BUDGET_RATIO] 估算(中文约 1 字符 ≈ 1 token),
         * 窗口未知时用 [DEFAULT_CONTEXT_WINDOW_TOKENS] 保守兜底。
         */
        internal fun chunkBudgetChars(contextWindowTokens: Int?): Int {
            val window = contextWindowTokens?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW_TOKENS
            return (window * CHUNK_BUDGET_RATIO).toInt().coerceIn(MIN_CHUNK_CHARS, MAX_CHUNK_CHARS)
        }

        /**
         * 把消息列表切分为多块:**条数([CHUNK_SIZE])与字符预算([maxCharsPerChunk])双重约束**。
         *
         * v2.3.2: 原实现只按条数切块,单块最多 256×1500 ≈ 38.4 万字符,长会话必然超压缩模型窗口;
         * 现按累计字符切分(单条按 [MAX_MSG_CHARS] 截断后再计费,与真正送进 prompt 的长度一致)。
         * 单条消息本身超过预算时单独成块(无法再切,长度已由 [MAX_MSG_CHARS] 兜底)。
         *
         * @param maxCharsPerChunk 单块字符预算(由 [chunkBudgetChars] 按模型窗口算出)
         */
        internal fun chunkMessages(messages: List<UIMessage>, maxCharsPerChunk: Int): List<List<UIMessage>> {
            if (messages.isEmpty()) return emptyList()
            val result = mutableListOf<List<UIMessage>>()
            var current = mutableListOf<UIMessage>()
            var currentChars = 0
            messages.forEach { msg ->
                val chars = minOf(msg.content.length, MAX_MSG_CHARS)
                val shouldSplit = current.size >= CHUNK_SIZE || currentChars + chars > maxCharsPerChunk
                if (current.isNotEmpty() && shouldSplit) {
                    result.add(current)
                    current = mutableListOf()
                    currentChars = 0
                }
                current.add(msg)
                currentChars += chars
            }
            if (current.isNotEmpty()) result.add(current)
            return result
        }
    }

    /** v1.0.51: 并发限制信号量,与 DeepMemoryProcessor/MemoryTicker 对齐(上限 3)。 */
    private val chunkSemaphore = Semaphore(MAX_CONCURRENT_CHUNKS)

    /**
     * 压缩对话历史 — 分块并行 + 独立便宜模型。
     *
     * @param toCompress 待压缩的消息列表(调用方已完成 prefix 跳过 / 优先级保留等过滤)
     * @param instruction 本次压缩附加指令(优先于设置级 customCompressPrompt,用于手动压缩对话框)
     * @return 摘要文本列表(每块对应一条摘要);**任一块失败时返回 null**,
     *         调用方据此走降级分支(保留原文),而不是把失败占位当成合法摘要
     */
    suspend fun compress(
        toCompress: List<UIMessage>,
        instruction: String? = null,
        /**
         * 上一版摘要。非空表示这次是在既有摘要上做增量更新，而不是从头总结。
         *
         * 反复压缩时，若不把它喂回去，早期事实只能靠模型「自觉复述」——
         * 而它看到的历史里那段摘要是 system 消息，并不会刻意去合并。
         * 于是每压一次，最早的内容就淡一层。
         */
        previousSummary: String? = null,
    ): List<String>? {
        if (toCompress.isEmpty()) return emptyList()

        // v2.3.2: 解析一次模型(原先每块各解析一次),并据此计算单块字符预算 ——
        // 避免长会话切出 38 万字符的巨块直接超压缩模型窗口。
        val (providerConfig, model) = resolveCompressModel()
        val budgetChars = chunkBudgetChars(model?.contextWindow)

        // 分块(条数上限 + 字符预算双重约束)
        val chunks = chunkMessages(toCompress, budgetChars)
        Logger.i(
            TAG,
            "compress: ${toCompress.size} 条消息分为 ${chunks.size} 块并行压缩" +
                "(单块预算 $budgetChars 字符, 窗口 ${model?.contextWindow ?: "未知"})",
        )

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

        val lang = settingsRepository.getLanguageSync()
        val resolvedLocale = when (lang) {
            "system", "" -> java.util.Locale.getDefault().language
            else -> lang
        }
        val useChinese = detectChineseConversation(toCompress, resolvedLocale)

        // 并行压缩每块(v1.0.51: 用 Semaphore 限制并发,避免大量块同时调 LLM 轰炸 API)
        // 只把 previousSummary 交给第 0 块做增量合并，避免多块并行时 N 倍重复携带并改写同一份既有摘要。
        val results =
            coroutineScope {
                chunks.mapIndexed { index, chunk ->
                    async {
                        chunkSemaphore.withPermit {
                            val chunkPrevious = if (index == 0) previousSummary else null
                            compressChunk(chunk, customPrompt, model, providerConfig, chunkPrevious, useChinese)
                        }
                    }
                }.let { deferredList ->
                    deferredList.map { it.await() }
                }
            }
        // v2.3.2: 任一块失败(返回 null)即整体判失败 —— 宁可本轮不做压缩,
        // 也不能让模型把"摘要生成失败"占位当成它已经见过的历史摘要(那是静默失忆)。
        val failed = results.count { it == null }
        return if (failed > 0) {
            Logger.w(TAG, "compress: $failed/${results.size} 块摘要失败, 整体降级(返回 null)")
            null
        } else {
            results.filterNotNull()
        }
    }

    /**
     * 压缩单块消息为摘要文本。
     *
     * v2.3.2: 失败返回 **null**(不再返回"摘要生成失败"占位文本)。占位文本会被上层
     * 当成合法摘要插进请求,让模型自以为见过一段它从未见过的历史;返回 null 由 [compress]
     * 汇总为整体失败,调用方据此保留原文。除协程取消外仍不抛异常,保证并行流程不因单块失败而中断。
     *
     * v1.0.52: 支持 [customPrompt] 参数 — 用户可在设置中覆盖默认压缩指令。
     * 非空时用用户自定义指令替代默认的结构化指令,对话历史仍以相同格式追加。
     *
     * @param chunk 待压缩的消息块
     * @param customPrompt 用户自定义压缩指令(null/空串表示用默认结构化指令)
     * @param model 压缩模型(v2.3.2: 由 [compress] 统一解析一次后传入,不再每块各解析一次)
     * @param providerConfig 与 [model] 配套的 Provider 绑定
     * @param previousSummary 上一版摘要(仅第 0 块传入,避免多块重复合并)
     * @param useChinese 是否使用中文压缩契约(false 时使用英文契约并保持与对话一致的语言)
     * @return 摘要文本;失败或摘要为空时返回 null
     */
    private suspend fun compressChunk(
        chunk: List<UIMessage>,
        customPrompt: String? = null,
        model: Model? = null,
        providerConfig: ProviderConfig? = null,
        previousSummary: String? = null,
        useChinese: Boolean = true,
    ): String? {
        val hasPrevious = !previousSummary.isNullOrBlank()
        val systemPrompt = buildString {
            if (!customPrompt.isNullOrBlank()) {
                appendLine(customPrompt.trim())
            } else if (useChinese) {
                // 默认结构化压缩指令：三段固定结构。
                // 「已否定」单独成段，是为了避免压缩后旧方案被重新提出——
                // 只写「已完成/待办」时，被否掉的方案会从摘要里消失，模型就会再提一次。
                appendLine("你是对话压缩助手。请把用户提供的对话历史压缩成一份可交接的简洁摘要，保留继续工作必需的信息。")
                appendLine("必须只输出以下三个三级标题，顺序固定；每节用要点形式，每点一行：")
                appendLine("### 已完成")
                appendLine("- 已经完成并确认有效的事项、做出的决策及理由")
                appendLine("### 已否定")
                appendLine("- 已经明确否定、不再采纳的方案、结论或建议；没有则写 `- 无`。此节内容后续不得被重新提出")
                appendLine("### 待办")
                appendLine("- 尚未完成的工作、下一步、未解决问题和需要跟进的事项")
                appendLine("- 不要编造未提及的内容；总长度不超过 800 字")
            } else {
                appendLine("You are a conversation compression assistant. Compress the conversation history into a concise handoff summary in the conversation's language, retaining only what is needed to continue work.")
                appendLine("Output ONLY these three level-3 headings in exact order, using one bullet per line:")
                appendLine("### Completed")
                appendLine("- Completed and confirmed work, decisions made, and key reasons")
                appendLine("### Negated")
                appendLine("- Explicitly rejected approaches, conclusions, or options; write `- None` if none. Items here must not be re-proposed")
                appendLine("### Pending")
                appendLine("- Unfinished work, next steps, open questions, and follow-ups")
                appendLine("- Never fabricate unmentioned facts; keep the total under 500 words")
            }
            if (hasPrevious) {
                appendLine()
                if (useChinese) {
                    appendLine("增量合并要求:")
                    appendLine("- 在已有摘要基础上更新，而不是另起一份")
                    appendLine("- 已有摘要里的事实与决策一律保留，除非新对话明确推翻它")
                    appendLine("- 新进展覆盖旧状态；两边重复项合并为一条")
                } else {
                    appendLine("Incremental merge rules:")
                    appendLine("- Update the existing summary rather than starting from scratch")
                    appendLine("- Preserve prior facts and decisions unless explicitly superseded by new turns")
                    appendLine("- Replace outdated statuses with latest progress and deduplicate overlapping points")
                }
            }
        }.trim()

        val userContent = buildString {
            if (hasPrevious) {
                val trimmedPrevious = previousSummary?.trim().orEmpty()
                if (useChinese) {
                    appendLine("已有摘要:")
                } else {
                    appendLine("Existing summary:")
                }
                appendLine(trimmedPrevious)
                val negated = extractNegatedSection(trimmedPrevious)
                if (negated.isNotBlank()) {
                    appendLine()
                    if (useChinese) {
                        appendLine("已否定结论（不得重新提出，必须原样保留到新的「### 已否定」一节）:")
                    } else {
                        appendLine("Negated conclusions (must not be re-proposed; preserve verbatim under `### Negated`):")
                    }
                    appendLine(negated)
                }
                appendLine()
            }
            appendLine(if (useChinese) "对话历史:" else "Conversation history:")
            chunk.forEach { msg ->
                val role = if (useChinese) {
                    when (msg.role) {
                        MessageRole.USER -> "用户"
                        MessageRole.ASSISTANT -> "助手"
                        MessageRole.SYSTEM -> "系统"
                        MessageRole.TOOL -> "工具"
                    }
                } else {
                    when (msg.role) {
                        MessageRole.USER -> "User"
                        MessageRole.ASSISTANT -> "Assistant"
                        MessageRole.SYSTEM -> "System"
                        MessageRole.TOOL -> "Tool"
                    }
                }
                val raw = msg.content
                // v2.x: 与 ContextCompressTransformer 口径一致 —— 保头尾，让尾部结论进得了摘要
                val text = io.zer0.common.TextTruncation.headTailText(raw, MAX_MSG_CHARS)
                appendLine("[$role] $text")
            }
        }

        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = systemPrompt),
            UIMessage(role = MessageRole.USER, content = userContent),
        )

        return try {
            // H-COMP2 / L-COMP7: 用 resultOf 替代 runCatching,不吞 CancellationException
            // 显式传 UTILITY + ReasoningLevel.OFF，避免后台摘要浪费推理 token
            val completion = resultOf {
                chatService.completeText(
                    messages = messages,
                    model = model,
                    providerConfig = providerConfig,
                    temperature = COMPRESS_TEMPERATURE,
                    maxTokens = COMPRESS_MAX_TOKENS,
                    reasoningLevel = ReasoningLevel.OFF,
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
                    reasoningLevel = ReasoningLevel.OFF,
                    mode = ChatRequestMode.UTILITY,
                ).collect { ev ->
                    if (ev is ChatStreamEvent.ContentDelta) sb.append(ev.delta)
                }
                io.zer0.ai.core.ChatCompletion(text = sb.toString())
            }
            // v1.0.74 fix: 剥离 <think> 推理标签,防止思考内容混入压缩摘要
            // v2.3.2: 空摘要同样按失败处理(返回 null),不再用"摘要为空"占位冒充摘要
            completion.text.let { io.zer0.muse.transformer.stripThinkTags(it) }
                .takeIf { it.isNotBlank() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 不吞协程取消,直接传播
            throw e
        } catch (t: Throwable) {
            Logger.e(TAG, "compressChunk failed (返回 null,由调用方降级)", t)
            null
        }
    }

    /**
     * 解析压缩用的模型与 ProviderConfig。
     *
     * v2.x: 改走辅助模型路由「大工具」档 — 用户在设置中绑定后,压缩任务使用带 provider
     * 的精确绑定(修复 v1.0.62 跨 Provider 按 id 匹配命中无关渠道的缺陷);留空时级联
     * 小工具模型,再留空返回 null 让 ChatService 使用激活 Provider 的当前模型。
     */
    private suspend fun resolveCompressModel(): Pair<ProviderConfig?, Model?> {
        val routed = utilityRouter.resolve(UtilityTier.LARGE) ?: return null to null
        return routed.first to routed.second
    }
}