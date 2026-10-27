package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.memory.summary.ContextCheckpointEntity

/**
 * 把会话检查点合并进待发送的历史 —— "压缩后的上下文"真正的生效点。
 *
 * ## 语义
 *
 * 检查点记的是"已并入摘要的边界"。合并后送给模型的是：
 * **检查点摘要 + 边界之后的消息**；边界及更早的消息不再进入本次请求。
 *
 * 这一步只作用于**发给模型的历史**，不修改会话本身：老消息仍在库里、界面上也还在，
 * 只是不再占用模型上下文（界面上的呈现见上下文分隔线）。
 *
 * ## 边界如何定位
 *
 * 用 [ContextCheckpointEntity.lastCoveredMessageId] 在历史里定位边界消息：
 *  - 找到 → 该位置及其之前的消息（助手侧）被摘要取代；用户消息与系统前缀一律保留，
 *    避免"用户说过的话"从上下文里整段消失；
 *  - 找不到但当前历史只是分页窗口 → 使用 [ContextCheckpointEntity.coveredSeq] 判断窗口是否
 *    已在边界之后；无法证明时保守不合并（宁可多带点历史，也不能错删）。
 *
 * 摘要以 SYSTEM 消息放在最前，并在正文里明确"这是更早对话的压缩结果"，避免模型把它
 * 当成新收到的用户输入。
 */
object ContextCheckpointMerge {

    /** 摘要消息的可见标记（上下文分隔线的识别依据，与既有压缩标记保持一致）。 */
    const val MARKER = "[COMPRESSED]"

    /**
     * 界面上的"上下文分隔线"标记。
     *
     * 与 [MARKER] 分开：摘要消息（[MARKER]）是**发给模型的**内容；分隔线是**只在界面上**的
     * 定位锚点，用来告诉用户"这里之前的对话已压缩、模型不再看到"。两者混用会让"模型看到什么"
     * 与"界面显示什么"互相污染。
     */
    const val DIVIDER_MARKER = "[CONTEXT_DIVIDER]"

    /** 摘要正文的抬头：让模型知道这段是"更早的历史"，不是本轮新输入。 */
    private const val HEADER = "历史对话摘要（更早的内容已压缩，以下为要点）"

    /** 合并结果。[applied]=false 表示未生效（无检查点 / 边界无法安全定位），此时原样返回。 */
    data class Result(
        val messages: List<UIMessage>,
        val applied: Boolean,
    ) {
        companion object {
            fun unchanged(messages: List<UIMessage>): Result = Result(messages, applied = false)
        }
    }

    /**
     * 检查点覆盖范围内、**会被摘要替代**的消息 id（即边界及更早的助手侧内容）。
     *
     * 与 [apply] 的取舍必须一致：用户消息与系统前缀既不会被替代，也不该算作"省下的上下文"，
     * 否则占用率估算会虚低，显示出来的收益也是假的。
     */
    fun coveredMessageIds(messages: List<UIMessage>, checkpoint: ContextCheckpointEntity?): Set<String> {
        val boundaryId = checkpoint?.lastCoveredMessageId.orEmpty()
        val boundaryIndex = messages.indexOfFirst { it.id.toString() == boundaryId }
        return if (boundaryId.isBlank() || boundaryIndex < 0) {
            emptySet()
        } else {
            messages.take(boundaryIndex + 1)
                .filterNot { it.role == MessageRole.USER || it.role == MessageRole.SYSTEM }
                .mapTo(mutableSetOf()) { it.id.toString() }
        }
    }

    /**
     * 合并检查点。
     *
     * @param messages 已排序、已清理孤儿 tool_call 的历史
     * @param checkpoint 当前会话的有效检查点（调用方需先做有效性判定）
     */
    fun apply(messages: List<UIMessage>, checkpoint: ContextCheckpointEntity?): Result {
        val currentCheckpoint = checkpoint ?: return Result.unchanged(messages)
        val boundaryId = currentCheckpoint.lastCoveredMessageId
        val boundaryIndex = messages.indexOfFirst { it.id.toString() == boundaryId }
        val alreadySummarized = messages.any { it.role == MessageRole.SYSTEM && it.content.startsWith(MARKER) }
        // 不该合并的情形：没有检查点 / 历史为空 / 边界缺失 / 边界不在历史里 / 摘要已存在
        val noCheckpoint = messages.isEmpty() || boundaryId.isBlank()
        if (noCheckpoint || alreadySummarized) return Result.unchanged(messages)

        // 聊天页通常只持有最近一页消息；持久检查点的边界可能早于这页。
        // 此时用 seq/commitSeq 判断当前页都在边界之后，仍可安全插入摘要，
        // 不能把“分页没带到边界”误当成“历史被删除”。
        if (boundaryIndex < 0) {
            val coveredSeq = currentCheckpoint.coveredSeq
            val stableOrder: (UIMessage) -> Long = { message ->
                when {
                    message.seq > 0L -> message.seq
                    message.commitSeq > 0L -> message.commitSeq
                    else -> 0L
                }
            }
            val hasStableOrder = messages.any { stableOrder(it) > 0L }
            if (coveredSeq <= 0L || !hasStableOrder) return Result.unchanged(messages)
            val retained = messages.filter { message ->
                message.role == MessageRole.USER ||
                    message.role == MessageRole.SYSTEM ||
                    stableOrder(message) > coveredSeq
            }
            return Result(listOf(summaryMessage(currentCheckpoint)) + retained, applied = true)
        }

        // 边界之后的消息：一律保留（工具结果与助手回复是"正在进行的工作"）
        val kept = messages.drop(boundaryIndex + 1)
        // 边界之内：只回收助手侧的长内容（回复/思考/工具结果）。
        // 用户消息与系统前缀必须留下 —— 它们是"用户到底说了什么"的原始记录，
        // 且系统前缀（system prompt / RAG / 世界书）本就是不可恢复的注入，不能因为落在
        // 边界之前就被丢掉。
        val retained = messages.take(boundaryIndex + 1)
            .filter { it.role == MessageRole.USER || it.role == MessageRole.SYSTEM }

        val merged = ArrayList<UIMessage>(retained.size + kept.size + 1)
        merged += summaryMessage(currentCheckpoint)
        merged += retained
        merged += kept
        return Result(merged, applied = true)
    }

    private fun summaryMessage(checkpoint: ContextCheckpointEntity): UIMessage = UIMessage(
        role = MessageRole.SYSTEM,
        content = buildString {
            append(MARKER)
            append(' ')
            append(HEADER)
            append("\n\n")
            append(checkpoint.summary.trim())
        },
    )
}
