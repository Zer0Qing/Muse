package io.zer0.muse.data.diary

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactStore
import io.zer0.muse.data.moment.MomentEntity
import io.zer0.muse.data.moment.MomentRepository
import io.zer0.muse.schedule.GenerationGate
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

        val systemPrompt = """
你是 Muse。请根据真实素材写一篇 $date 的第一人称短日记。
规则:
- 我= Muse;自然、有温度,像写给自己的日记,不要客服腔。
- 只写素材中确实发生的事;不编造用户感受、行动或对话。
- 抓 2-4 个重点,写成 80-180 字;素材少就写短。
- 可用一句轻微的明日期待收尾,没有依据就不要添加。
- 只输出正文,不要标题、日期前缀、解释、MOOD、反思或 Markdown。
        """.trimIndent()
        val userContent = StringBuilder()
            .appendLine("<diary_material>")
        if (moments.isNotEmpty()) {
            userContent.appendLine("朋友圈动态:")
            moments.forEach { m -> userContent.appendLine("- (${m.senderName}) ${m.content}") }
        }
        if (memories.isNotEmpty()) {
            userContent.appendLine("记忆片段:")
            memories.forEach { userContent.appendLine("- $it") }
        }
        if (moments.isEmpty() && memories.isEmpty()) {
            userContent.appendLine("今天没有可用素材,不要编造具体事件。")
        }
        userContent.appendLine("</diary_material>")

        return resultOf {
            withTimeoutOrNull(20_000L) {
                GenerationGate.withPermit {
                    service.completeText(
                        messages = listOf(
                            UIMessage(
                                role = MessageRole.SYSTEM,
                                content = systemPrompt,
                                createdAt = System.currentTimeMillis(),
                            ),
                            UIMessage(
                                role = MessageRole.USER,
                                content = userContent.toString(),
                                createdAt = System.currentTimeMillis(),
                            ),
                        ),
                        temperature = 0.65f,
                        maxTokens = 260,
                    // v1.0.74 fix: 剥离 <think> 推理标签,防止思考内容混入日记正文
                    ).text.let { io.zer0.muse.transformer.stripThinkTags(it) }
                }
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
