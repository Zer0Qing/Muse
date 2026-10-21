package io.zer0.muse.data.diary

import io.zer0.ai.ChatService
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.moment.MiniContextBuilder
import io.zer0.muse.data.moment.MomentContentMode
import io.zer0.muse.data.moment.MomentEntity
import io.zer0.muse.data.moment.MomentRepository
import io.zer0.muse.schedule.GenerationGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AI 日记生成器 — 基于当天动态 / 小手机私信 / 记忆，由 LLM 写一篇第一人称日记。
 *
 * v2.6.7（P0-1）：
 * - 走 [MiniContextBuilder] 完整生成链路（世界书 / 记忆 / 用户画像 / 提示词注入都生效）
 * - 补入小手机私信内容：此前角色跟你私信聊了一整天，日记里一个字都不会提
 * - 按 [MomentContentMode] 分流视角：真实系只写共同经历，角色系可写角色自己的感受
 */
class DiaryGenerator(
    private val chatService: ChatService?,
    private val momentRepository: MomentRepository?,
    private val sessionRepository: io.zer0.muse.data.session.SessionRepository?,
    private val contextBuilder: MiniContextBuilder,
    private val settingsRepository: SettingsRepository,
    private val factStore: io.zer0.memory.fact.FactStore? = null,
    private val assistantRepository: io.zer0.muse.data.assistant.AssistantRepository? = null,
) {

    /** 生成某天的日记。返回 null 表示 LLM 失败且兜底也失败。 */
    suspend fun generateFor(date: String): String? {
        val material = withContext(Dispatchers.IO) { collectMaterial(date) }
        val llm = withContext(Dispatchers.IO) {
            resultOf { generateWithLlm(date, material) }
                .onError { msg, t -> Logger.w(TAG, "LLM 写日记失败: ${t?.message ?: msg}") }
                .getOrNull()
        }
        if (llm != null) return llm
        return fallbackSummary(date, material)
    }

    /** 当天素材：朋友圈动态 + 小手机私信 + 记忆片段。 */
    private data class DiaryMaterial(
        val moments: List<MomentEntity>,
        val chats: List<String>,
        val memories: List<String>,
    ) {
        val isEmpty: Boolean get() = moments.isEmpty() && chats.isEmpty() && memories.isEmpty()
    }

    private suspend fun collectMaterial(date: String): DiaryMaterial {
        val dayStart = dayStartMillis(date)
        val moments = resultOf { momentRepository?.getAll(50) }.getOrNull()
            .orEmpty()
            .filter { it.createdAt >= dayStart }

        // v2.6.7: 小手机私信 — 角色与用户的真实互动，日记里应该有
        val chats = resultOf { sessionRepository?.getMiniPhoneSessions() }.getOrNull()
            .orEmpty()
            .take(6)
            .mapNotNull { session ->
                val preview = session.lastMessagePreview.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                "${session.title}: $preview"
            }

        // 记忆片段：只取当天新增的（Fact.createdAt 是 ISO 字符串，直接与日起点比较）
        val dayStartIso = java.time.Instant.ofEpochMilli(dayStart).toString()
        val memories = resultOf { factStore?.getAll("main") }.getOrNull()
            .orEmpty()
            .filter { it.createdAt >= dayStartIso }
            .map { it.fact }
            .take(10)

        return DiaryMaterial(moments = moments, chats = chats, memories = memories)
    }

    private suspend fun generateWithLlm(date: String, material: DiaryMaterial): String? {
        val service = chatService ?: return null
        val mode = resultOf { settingsRepository.momentContentMode() }.getOrNull() ?: MomentContentMode.REAL
        val assistant = resultOf { assistantForDiary() }.getOrNull()

        val userContent = buildString {
            appendLine("<diary_task>")
            appendLine("日期: $date")
            appendLine("请写一篇第一人称短日记，80-180 字，抓 2-4 个重点。素材少就写短。")
            appendLine("</diary_task>")
            appendLine()
            appendLine("<content_mode>")
            when (mode) {
                MomentContentMode.REAL -> {
                    appendLine("- 只写素材中确实发生的事；不编造用户的感受、行动或对话。")
                }
                MomentContentMode.CHARACTER -> {
                    appendLine("- 可以写你自己的心情与想法，但涉及用户的部分仍不得编造。")
                    // v2.6.9: 角色视角 —— 日记写成“他眼中的我们”，而不是流水账。
                    // 只在角色系下开启，与真实系的克制原则不冲突。
                    appendLine("- 从你的视角回看你们相处的一个瞬间，写你从中感受到的东西。")
                    appendLine("- 这是你的日记，不是给用户看的报告；不要总结、不要列点。")
                }
            }
            appendLine("</content_mode>")
            appendLine()
            appendLine("<diary_material>")
            if (material.moments.isNotEmpty()) {
                appendLine("朋友圈动态:")
                material.moments.forEach { m -> appendLine("- (${m.senderName}) ${m.content}") }
            }
            if (material.chats.isNotEmpty()) {
                appendLine("小手机私信:")
                material.chats.forEach { c -> appendLine("- $c") }
            }
            if (material.memories.isNotEmpty()) {
                appendLine("记忆片段:")
                material.memories.forEach { m -> appendLine("- $m") }
            }
            if (material.isEmpty) {
                appendLine("今天没有可用素材,不要编造具体事件。")
            }
            appendLine("</diary_material>")
            appendLine()
            appendLine("- 只输出正文,不要标题、日期前缀、解释、MOOD、反思或 Markdown。")
        }

        val messages = contextBuilder.buildMessages(assistant = assistant, userContent = userContent)

        return resultOf {
            withTimeoutOrNull(20_000L) {
                GenerationGate.withPermit {
                    service.completeText(
                        messages = messages,
                        temperature = 0.65f,
                        maxTokens = 320,
                    ).text.let { io.zer0.muse.transformer.stripThinkTags(it) }
                }
            }
        }.onError { msg, t ->
            Logger.w(TAG, "LLM 日记调用失败: ${t?.message ?: msg}")
        }.getOrNull()?.takeIf { it.isNotBlank() && it.length > 10 }
    }

    /** 日记默认使用第一个启用的助手，失败时返回 null（Assembler 会退化为默认人格）。 */
    private suspend fun assistantForDiary(): io.zer0.muse.data.assistant.AssistantEntity? {
        val repo = assistantRepository ?: return null
        return resultOf { repo.getAll() }.getOrNull()?.firstOrNull { it.enabled }
    }

    /** 兜底:素材聚合文本(LLM 失败时保底)。 */
    private fun fallbackSummary(date: String, material: DiaryMaterial): String {
        val sb = StringBuilder()
        sb.appendLine("$date 的日记")
        if (material.moments.isNotEmpty()) {
            sb.appendLine("今天发了 ${material.moments.size} 条动态:")
            material.moments.take(5).forEach { sb.appendLine("· ${it.content}") }
        } else {
            sb.appendLine("今天没有发动态。")
        }
        if (material.chats.isNotEmpty()) {
            sb.appendLine("聊过的话:")
            material.chats.take(5).forEach { sb.appendLine("· $it") }
        }
        return sb.toString()
    }

    private fun dayStartMillis(date: String): Long = runCatching {
        java.time.LocalDate.parse(date)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
    }.getOrDefault(System.currentTimeMillis())

    companion object {
        private const val TAG = "DiaryGenerator"
    }
}
