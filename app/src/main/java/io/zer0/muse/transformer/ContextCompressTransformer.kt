package io.zer0.muse.transformer

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.CancellationException

/**
 * 上下文压缩 Transformer(Phase 8.1 H1)。
 *
 * 当消息历史超过阈值时,把前面的旧消息压缩成一条 SYSTEM 摘要,
 * 保留最近 N 条原样,避免上下文过长导致 token 爆炸。
 *
 * 行为:
 *  - messages.size <= threshold(默认 20) → 不压缩
 *  - 超过阈值:
 *    1. 跳过头部连续的 SYSTEM 消息(system prompt / RAG / webSearch / lorebook 等动态注入的 prefix)
 *    2. 取 prefix 之后到 recent 之前的部分作为待压缩(跳过已压缩的摘要消息)
 *    3. 调用 LLM 生成摘要
 *    4. 替换为一条 SYSTEM 消息("[COMPRESSED] 历史对话摘要\n\n...")
 *    5. 保留最后 keepRecent 条原样
 *
 * 由 [context.extra] 控制参数:
 *  - "compress_enabled" (Boolean, 默认 false): 是否启用压缩
 *  - "compress_threshold" (Int, 默认 20): 触发压缩的消息数阈值
 *  - "compress_keep_recent" (Int, 默认 15): 压缩后保留的最近消息数
 *
 * 注意: 此 Transformer 会调用 LLM(网络请求),耗时较长。
 *       失败时降级为"截断"(丢弃旧消息,插入标记 SYSTEM 消息告知模型历史被截断)。
 */
class ContextCompressTransformer(
    private val chatService: ChatService,
    /**
     * 可选的对话压缩器(分块并行 + 独立便宜模型)。
     * - 非 null:transform 时委托 [compressor] 完成分块并行压缩(推荐,既有实现)
     * - null:回退到原同步单次 LLM 压缩(向后兼容 / 测试场景)
     */
    private val compressor: ConversationCompressor? = null,
) : Transformer {

    override val name: String = "ContextCompress"

    override suspend fun transform(
        messages: List<UIMessage>,
        context: TransformContext,
    ): List<UIMessage> {
        val enabled = (context.extra("compress_enabled") as? Boolean) ?: false
        if (!enabled) return messages

        val threshold = (context.extra("compress_threshold") as? Int) ?: DEFAULT_THRESHOLD
        val keepRecent = (context.extra("compress_keep_recent") as? Int) ?: DEFAULT_KEEP_RECENT

        // L-COMP6: threshold < keepRecent 时配置语义失效,告警
        if (threshold < keepRecent) {
            Logger.w(name, "compress_threshold($threshold) < compress_keep_recent($keepRecent), 压缩可能无法正常触发")
        }

        if (messages.size <= threshold) return messages

        // M-COMP3: 压缩水位线 — 已存在压缩摘要时,要求 size > threshold + threshold/2 才再次压缩,
        // 避免压缩触发频率失控
        val hasCompressed = messages.any {
            it.role == MessageRole.SYSTEM && it.content.startsWith(COMPRESSED_MARKER)
        }
        if (hasCompressed && messages.size <= threshold + threshold / 2) return messages

        // v1.116 (C1-5): 跳过头部连续的 SYSTEM 消息(system prompt / RAG / webSearch / lorebook 等动态注入的 prefix),
        // 避免压缩这些不可恢复的上下文。一旦遇到第一个非 SYSTEM 消息(用户对话起点),才开始可压缩区间。
        val firstNonSystemIndex = messages.indexOfFirst { it.role != MessageRole.SYSTEM }
        val prefixEnd = if (firstNonSystemIndex >= 0) firstNonSystemIndex else messages.size
        val prefix = messages.subList(0, prefixEnd)

        // v5: 优先级保留 — 工具调用消息和包含工具结果的消息应保留,不压缩
        val priorityIds = messages.filter { msg ->
            msg.toolCalls != null || msg.toolCallInfo != null ||
                msg.role == MessageRole.TOOL || msg.role == MessageRole.SYSTEM
        }.map { it.id.toString() }.toSet()

        // 可压缩区间 = prefix 之后到 recent 之前
        val compressibleEnd = messages.size - keepRecent
        if (prefixEnd >= compressibleEnd) return messages  // prefix 本身就占了大部分,无可压缩区间

        // M-COMP3: 跳过已压缩的 SYSTEM 摘要消息,避免摘要叠加摘要
        val toCompress = messages.subList(prefixEnd, compressibleEnd).filter {
            !(it.role == MessageRole.SYSTEM && it.content.startsWith(COMPRESSED_MARKER))
        }
        val recent = messages.takeLast(keepRecent)

        // v5: 把优先级高的消息(工具调用等)保留到 recent 中,避免被压缩
        val priorityMessages = toCompress.filter { it.id.toString() in priorityIds }
        val adjustedRecent = (recent + priorityMessages).distinctBy { it.id.toString() }
        val adjustedToCompress = toCompress.filter { it.id.toString() !in priorityIds }

        // Phase 8.5 修复: keepRecent >= messages.size 时 toCompress 为空,跳过避免发无意义 LLM 请求
        if (adjustedToCompress.isEmpty()) return messages

        val summary = try {
            if (compressor != null) {
                // 优先走分块并行 + 独立便宜模型(既有实现 ChatService.compressConversation)
                compressWithCompressor(adjustedToCompress)
            } else {
                // 回退:原同步单次 LLM 压缩
                compressMessages(adjustedToCompress)
            }
        } catch (e: CancellationException) {
            // H-COMP1: 不吞 CancellationException,直接重抛(协程取消必须传播)
            throw e
        } catch (t: Throwable) {
            Logger.e(name, "compress failed, fallback to truncation", t)
            // M-COMP4: 降级时插入 SYSTEM 标记消息,告知模型历史被截断(而非静默丢弃全部历史)
            val fallbackMsg = UIMessage(
                role = MessageRole.SYSTEM,
                content = "(历史暂不可用,仅保留最近消息)",
            )
            return prefix + listOf(fallbackMsg) + adjustedRecent
        }

        // M-COMP3: 压缩摘要加 [COMPRESSED] 前缀标记,下次压缩时识别并跳过
        val summaryMsg = UIMessage(
            role = MessageRole.SYSTEM,
            content = "$COMPRESSED_MARKER 历史对话摘要\n\n$summary",
        )
        return prefix + listOf(summaryMsg) + adjustedRecent
    }

    /** 调用 LLM 压缩旧消息为摘要。 */
    private suspend fun compressMessages(oldMessages: List<UIMessage>): String {
        val prompt = buildString {
            appendLine("请把下面的对话历史压缩成简洁的摘要,保留关键信息(事实/决策/用户偏好)。")
            appendLine("- 用要点形式,每点一行")
            appendLine("- 不要编造未提及的内容")
            appendLine("- 总长度不超过 800 字")
            appendLine()
            appendLine("对话历史:")
            oldMessages.forEach { msg ->
                val role = when (msg.role) {
                    MessageRole.USER -> "用户"
                    MessageRole.ASSISTANT -> "助手"
                    MessageRole.SYSTEM -> "系统"
                    MessageRole.TOOL -> "工具"
                }
                // L-COMP5: 截断处加 "…" 标记(而非静默截断)
                // v1.116 (C1-5): 单条消息截断阈值从 500 提升到 1500 字符,
                // 避免长回复(如代码块/详细分析)被过度截断导致摘要丢失关键信息。
                val raw = msg.content
                val text = if (raw.length > MAX_COMPRESS_MSG_CHARS) raw.take(MAX_COMPRESS_MSG_CHARS) + "…" else raw
                appendLine("[$role] $text")
            }
        }

        val request = listOf(UIMessage(role = MessageRole.USER, content = prompt))
        // H-COMP2 / L-COMP7: 用 resultOf 替代 runCatching.getOrElse
        //  - resultOf 会重抛 CancellationException(不吞协程取消)
        //  - 其他错误转为 Result.Error,onError 记录原始异常后回退 streamChat
        //  - CancellationException 不会到达 getOrNull(),因此不会错误回退
        val completion: ChatCompletion = resultOf {
            chatService.completeText(messages = request)
        }.onError { msg, t ->
            Logger.w(name, "completeText 失败,回退 streamChat: $msg", t)
        }.getOrNull() ?: run {
            // 兜底: 流式收集(仅对非 CancellationException 错误到达此处)
            val sb = StringBuilder()
            chatService.streamChat(messages = request).collect { ev ->
                if (ev is ChatStreamEvent.ContentDelta) sb.append(ev.delta)
            }
            ChatCompletion(text = sb.toString())
        }
        return completion.text.trim().ifBlank { "历史对话已压缩(摘要为空)" }
    }

    /**
     * 委托 [compressor] 完成分块并行压缩,把多块摘要合并为单条文本。
     *
     * - 单块失败时 [compressor] 返回占位文本(不抛异常),不影响其他块
     * - 多块摘要用 "[对话摘要 i/N]" 分段标记合并为一条 SYSTEM 消息
     *   (与 [ContextCompressTransformer] 原"单条 SYSTEM 摘要"语义保持一致,
     *    避免下游 transformer / 持久化逻辑感知分块)
     */
    private suspend fun compressWithCompressor(oldMessages: List<UIMessage>): String {
        val summaries = compressor!!.compress(oldMessages)
        if (summaries.isEmpty()) return "历史对话已压缩(摘要为空)"
        if (summaries.size == 1) return summaries.first()
        return buildString {
            summaries.forEachIndexed { idx, s ->
                append("[对话摘要 ${idx + 1}/${summaries.size}]\n")
                append(s)
                if (idx != summaries.lastIndex) append("\n\n")
            }
        }
    }

    private companion object {
        const val DEFAULT_THRESHOLD = 20
        const val DEFAULT_KEEP_RECENT = 15
        // M-COMP3: 压缩摘要前缀标记,下次压缩时识别并跳过
        const val COMPRESSED_MARKER = "[COMPRESSED]"
        // v1.116 (C1-5): 单条消息送入 LLM 压缩时的最大字符数(原 500,提升到 1500)
        const val MAX_COMPRESS_MSG_CHARS = 1500
    }
}
