package io.zer0.muse.data.diary

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactStore
import io.zer0.muse.data.moment.MomentEntity
import io.zer0.muse.data.moment.MomentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v1.0.74: AI 日记生成器 — 基于当天动态/记忆,由 LLM 写一篇第一人称日记。
 * 失败时回退为聚合摘要文本(不阻塞查看)。
 */
class DiaryGenerator(
    private val chatService: ChatService?,
    private val factStore: FactStore?,
    private val momentRepository: MomentRepository?,
) {

    private val TAG = "DiaryGenerator"

    /** 生成某天的日记。返回 null 表示 LLM 失败且兜底也失败。 */
    suspend fun generateFor(date: String): String? {
        val (moments, memories) = withContext(Dispatchers.IO) {
            val ms = momentRepository?.getAll(50)?.filter { it.createdAt >= dayStartMillis(date) } ?: emptyList()
            val facts = resultOf { factStore?.getAll("main") }.getOrNull() ?: emptyList()
            val todayFacts = facts.filter { f ->
                runCatching { java.time.Instant.parse(f.createdAt).toEpochMilli() }.getOrDefault(Long.MAX_VALUE)
                    .let { it >= dayStartMillis(date) && it != Long.MAX_VALUE }
            }.map { it.fact }
            ms to todayFacts
        }

        // LLM 生成
        val llm = withContext(Dispatchers.IO) {
            resultOf { generateWithLlm(date, moments, memories) }
                .onError { msg, t -> Logger.w(TAG, "LLM 写日记失败: ${t?.message ?: msg}") }
                .getOrNull()
        }
        if (llm != null) return llm

        // 兜底:聚合摘要
        return fallbackSummary(date, moments, memories)
    }

    private suspend fun generateWithLlm(
        date: String,
        moments: List<MomentEntity>,
        memories: List<String>,
    ): String? {
        val service = chatService ?: return null

        val sb = StringBuilder()
        sb.appendLine("你是 Muse,一个陪伴用户的 AI 助手。现在要给 $date 写一篇日记。")
        sb.appendLine("要求:")
        sb.appendLine("- 第一人称('我'= Muse),像真实的日记,自然有温度,不要官方腔")
        sb.appendLine("- 150-300 字,记录今天发生了什么、想到了什么、和用户的互动")
        sb.appendLine("- 基于下面真实的素材,不要编造没发生的事")
        sb.appendLine("- 结尾可以有一点对明天的期待或自嘲式幽默")
        sb.appendLine("- 直接输出日记正文,不要标题、不要日期前缀")
        sb.appendLine()
        if (moments.isNotEmpty()) {
            sb.appendLine("今天发的朋友圈动态:")
            moments.forEach { m -> sb.appendLine("- (${m.senderName}) $m.content") }
            sb.appendLine()
        }
        if (memories.isNotEmpty()) {
            sb.appendLine("今天的记忆片段:")
            memories.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }
        if (moments.isEmpty() && memories.isEmpty()) {
            sb.appendLine("(今天素材很少,可以写一些'平静的一天'、观察和感受)")
            sb.appendLine()
        }
        sb.appendLine("请输出日记:")

        return resultOf {
            withTimeoutOrNull(30_000L) {
                service.completeText(
                    messages = listOf(
                        UIMessage(
                            role = MessageRole.USER,
                            content = sb.toString(),
                            createdAt = System.currentTimeMillis(),
                        ),
                    ),
                    temperature = 0.85f,
                    maxTokens = 400,
                // v1.0.74 fix: 剥离 <think> 推理标签,防止思考内容混入日记正文
                ).text.let { io.zer0.muse.transformer.stripThinkTags(it) }
            }
        }.onError { msg, t ->
            Logger.w(TAG, "LLM 日记调用失败: ${t?.message ?: msg}")
        }.getOrNull()?.takeIf { it.isNotBlank() && it.length > 10 }
    }

    /** 兜底:素材聚合文本(LLM 失败时保底)。 */
    private fun fallbackSummary(
        date: String,
        moments: List<MomentEntity>,
        memories: List<String>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("$date 的日记")
        if (moments.isNotEmpty()) {
            sb.appendLine("今天发了 ${moments.size} 条动态:")
            moments.take(5).forEach { sb.appendLine("· $it.content") }
        } else {
            sb.appendLine("今天没有发动态。")
        }
        if (memories.isNotEmpty()) {
            sb.appendLine("记住的片段:")
            memories.take(8).forEach { sb.appendLine("· $it") }
        }
        return sb.toString()
    }

    private fun dayStartMillis(date: String): Long {
        return runCatching {
            java.time.LocalDate.parse(date)
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli()
        }.getOrDefault(System.currentTimeMillis())
    }
}
