package io.zer0.memory.summary

import io.zer0.ai.core.Model
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.format.RollingSummaryFormat
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.pii.PiiGuard
import io.zer0.memory.prompt.RollingSummaryPrompt
import io.zer0.memory.time.TimeContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Session 摘要管理器 (openhanako session-summary.ts SessionSummaryManager class 移植)。
 *
 * 每个 session 一个摘要(存 Room),通过 [rollingSummary] 滚动更新(覆盖式,非追加)。
 * 输出固定为 [RollingSummaryFormat] 契约规定的两节格式(facts + timeline)。
 *
 * 服务两条路径:
 *  - 普通记忆: [io.zer0.memory.compile.MemoryCompiler] 读摘要 → 递归压缩 → memory.md
 *  - 深度记忆: [io.zer0.memory.deep.DeepMemoryProcessor] 读 snapshot diff → 拆元事实
 */
class SessionSummaryManager(
    private val dao: SessionSummaryDao,
    private val llmClient: MemoryLlmClient,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 业务层数据结构(与 Entity 分离,屏蔽 snapshot/snapshotAt 等内部字段)。 */
    data class SummaryData(
        val sessionId: String,
        val createdAt: String,
        val updatedAt: String,
        val summary: String,
        val messageCount: Int,
        val sourceTimeRange: TimeContext.SourceTimeRange? = null,
        val snapshot: String = "",
        val snapshotAt: String? = null,
        val assistantId: String = "",
    )

    /** 读取指定 session 的摘要,不存在返回 null。 */
    suspend fun getSummary(sessionId: String): SummaryData? = withContext(Dispatchers.IO) {
        dao.get(sessionId)?.toSummaryData()
    }

    /** 写入摘要(覆盖式)。 */
    suspend fun saveSummary(sessionId: String, data: SummaryData) = withContext(Dispatchers.IO) {
        dao.upsert(data.toEntity(sessionId))
    }

    /** 获取所有"脏" session(summary !== snapshot),供深度记忆用。 */
    suspend fun getDirtySessions(since: String? = null): List<SummaryData> = withContext(Dispatchers.IO) {
        dao.getDirty()
            .filter { since == null || it.updatedAt > since }
            .map { it.toSummaryData() }
    }

    /** 标记 session 已被深度记忆处理(snapshot = summary)。 */
    suspend fun markProcessed(sessionId: String) = withContext(Dispatchers.IO) {
        dao.markProcessed(sessionId, Instant.now().toString())
    }

    /** 获取所有摘要(按 updated_at 降序)。 */
    suspend fun getAllSummaries(): List<SummaryData> = withContext(Dispatchers.IO) {
        dao.getAll().map { it.toSummaryData() }
    }

    /** 获取指定日期范围内的摘要。 */
    suspend fun getSummariesInRange(
        start: Instant,
        end: Instant,
        since: String? = null,
    ): List<SummaryData> = withContext(Dispatchers.IO) {
        dao.getInRange(start.toString(), end.toString(), since).map { it.toSummaryData() }
    }

    /** 清空所有摘要。 */
    suspend fun clearAll() = withContext(Dispatchers.IO) { dao.deleteAll() }

    /**
     * P2: 删除指定 session 的摘要(用于记忆页 UI 删除 Summary 层单条)。
     * 返回是否删除成功(session 不存在时返回 false)。
     */
    suspend fun delete(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        // v1.78 (M1): 包装 suspend DAO 调用必须用 resultOf,避免吞 CancellationException
        resultOf { dao.deleteById(sessionId); true }.onError { msg, t ->
            // v1.78 (M5): 记录删除失败原因
            Logger.w("SessionSummaryManager", "delete(${sessionId.take(8)}…) 失败: $msg", t)
        }.getOrNull() ?: false
    }

    /**
     * 滚动更新 session 摘要: 每 10 轮或 session 结束时触发。
     * 若有旧摘要则将旧摘要 + 新对话合并产出新摘要(覆盖,非追加);
     * 若无旧摘要则直接从对话生成。
     *
     * @param sessionId 会话 id
     * @param messages 完整对话历史(含 system / user / assistant)
     * @param model 目标模型
     * @param locale 语言
     * @return 更新后的摘要文本(失败时返回旧摘要,不抛错)
     */
    suspend fun rollingSummary(
        sessionId: String,
        messages: List<UIMessage>,
        model: Model?,
        locale: String = "zh-CN",
        timeZone: String = TimeContext.DEFAULT_TIMEZONE,
        assistantId: String = "",
    ): String = withContext(Dispatchers.IO) {
        val draft = createRollingSummaryDraft(sessionId, messages, model, locale, timeZone, assistantId) ?: return@withContext ""
        if (draft.changed && draft.data != null) {
            saveSummary(sessionId, draft.data)
        }
        draft.summary
    }

    /** 生成草稿(不落盘)。null 表示空对话直接跳过。 */
    suspend fun createRollingSummaryDraft(
        sessionId: String,
        messages: List<UIMessage>,
        model: Model?,
        locale: String = "zh-CN",
        timeZone: String = TimeContext.DEFAULT_TIMEZONE,
        assistantId: String = "",
    ): Draft? = withContext(Dispatchers.IO) {
        val existing = getSummary(sessionId)
        val prevSummary = existing?.summary ?: ""

        // 增量: 只取上次摘要之后的新消息,避免长 session 上下文爆炸
        val lastMessageCount = existing?.messageCount ?: 0
        val newMessages = if (lastMessageCount > 0 && lastMessageCount < messages.size) {
            messages.drop(lastMessageCount)
        } else {
            messages
        }

        val zone = TimeContext.resolveTimeZone(timeZone)
        val timestamps = messages.map { Instant.ofEpochMilli(it.createdAt).toString() }
        val sourceTimeRange = TimeContext.buildSourceTimeRange(timestamps, zone)
        val convText = buildConversationText(newMessages, locale, zone)
        if (convText.isBlank()) {
            return@withContext Draft(prevSummary, changed = false, null)
        }

        // 按全量用户轮数计算摘要配额
        val turnCount = messages.count { it.role == io.zer0.ai.core.MessageRole.USER }
        val budget = rollingSummaryBudget(turnCount)

        val systemPrompt = RollingSummaryPrompt.buildSystemPrompt(locale = locale)
        val factTitle = RollingSummaryFormat.getFactSectionTitle(locale)
        val timelineTitle = RollingSummaryFormat.getTimelineSectionTitle(locale)
        val isZh = locale.startsWith("zh")

        val factsBudget = maxOf(15, (budget.totalBudget * 0.3).toInt())
        val eventsBudget = budget.totalBudget - factsBudget
        val budgetText = if (isZh) {
            "$factTitle 最多 $factsBudget 字。$timelineTitle 最多 $eventsBudget 字。"
        } else {
            "$factTitle max ${maxOf(10, (factsBudget * 0.6).toInt())} words. $timelineTitle max ${maxOf(20, (eventsBudget * 0.6).toInt())} words."
        }
        val prevLabel = if (isZh) "## 已有摘要" else "## Existing Summary"
        val newLabel = if (isZh) "## 新增对话" else "## New Conversation"
        val budgetLabel = if (isZh) "## 本次摘要预算" else "## This Run's Summary Budget"
        val userContent = listOfNotNull(
            prevSummary.takeIf { it.isNotBlank() }?.let { "$prevLabel\n\n$it" },
            "$newLabel\n\n$convText",
            "$budgetLabel\n\n$budgetText",
        ).joinToString("\n\n")

        val rawResult = resultOf {
            llmClient.callText(
                systemPrompt = systemPrompt,
                userContent = userContent,
                model = model,
                temperature = 0.3f,
                maxTokens = budget.visibleMaxTokens,
            )
        }.onError { msg, t ->
            // v1.78 (M5): 记录 rollingSummary LLM 失败原因
            Logger.w("SessionSummaryManager", "rollingSummary LLM 调用失败 (${sessionId.take(8)}…): $msg", t)
        }.getOrNull() ?: return@withContext Draft(prevSummary, changed = false, null)

        var newSummary = rawResult.trim()
        if (newSummary.isBlank()) return@withContext Draft(prevSummary, changed = false, null)

        // 写入前结构校验 + 有限次数格式修复
        var repairsUsed = 0
        var validation = RollingSummaryFormat.validate(newSummary)
        while (!validation.ok && repairsUsed < RollingSummaryFormat.MAX_REPAIRS) {
            repairsUsed++
            val repairSystemPrompt = RollingSummaryFormat.buildRepairPrompt(locale)
            val repairInput = RollingSummaryFormat.buildRepairInput(locale, validation.issues, newSummary)
            val repairResult = resultOf {
                llmClient.callText(
                    systemPrompt = repairSystemPrompt,
                    userContent = repairInput,
                    model = model,
                    temperature = 0.3f,
                    maxTokens = budget.visibleMaxTokens,
                )
            }.onError { msg, t ->
                // v1.78 (M5): 记录格式修复 LLM 失败原因
                Logger.w("SessionSummaryManager", "格式修复 LLM 调用失败 (${sessionId.take(8)}…, attempt=$repairsUsed): $msg", t)
            }.getOrNull() ?: break
            if (repairResult.isBlank()) {
                validation = RollingSummaryFormat.ValidationResult(false, validation.issues + "format repair returned empty")
                break
            }
            newSummary = repairResult.trim()
            validation = RollingSummaryFormat.validate(newSummary)
        }
        if (!validation.ok) {
            // 修不好就保留旧摘要,不覆盖
            return@withContext Draft(prevSummary, changed = false, null)
        }

        // PII 脱敏
        val (scrubbed, detected) = PiiGuard.scrub(newSummary)
        if (detected.isNotEmpty()) newSummary = scrubbed

        // 脱敏后再校验一次
        val finalValidation = RollingSummaryFormat.validate(newSummary)
        if (!finalValidation.ok) {
            return@withContext Draft(prevSummary, changed = false, null)
        }

        val now = Instant.now().toString()
        val data = SummaryData(
            sessionId = sessionId,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            summary = newSummary,
            messageCount = messages.size,
            sourceTimeRange = sourceTimeRange ?: existing?.sourceTimeRange,
            snapshot = existing?.snapshot ?: "",
            snapshotAt = existing?.snapshotAt,
            assistantId = assistantId,
        )
        Draft(newSummary, changed = true, data)
    }

    /** 草稿结果。 */
    data class Draft(
        val summary: String,
        val changed: Boolean,
        val data: SummaryData?,
    )

    /**
     * 摘要预算计算。
     * 按轮数线性缩放: 每轮 40 字配额,10 轮封顶 400 字。
     */
    private fun rollingSummaryBudget(turnCount: Int): Budget {
        val totalBudget = minOf(400, maxOf(40, turnCount * 40))
        val visibleMaxTokens = maxOf(150, minOf(750, totalBudget * 3 / 2))
        return Budget(totalBudget, visibleMaxTokens)
    }

    private data class Budget(val totalBudget: Int, val visibleMaxTokens: Int)

    /** 从消息列表构建带时间戳的对话文本(供 LLM 总结)。 */
    private fun buildConversationText(
        messages: List<UIMessage>,
        locale: String,
        zone: java.time.ZoneId,
    ): String {
        val isZh = locale.startsWith("zh")
        val userLabel = if (isZh) "用户" else "User"
        val assistantLabel = if (isZh) "助手" else "Assistant"
        val parts = mutableListOf<String>()
        for (msg in messages) {
            val content = msg.content.trim()
            if (content.isEmpty()) continue
            val timePrefix = if (msg.createdAt > 0) {
                val instant = Instant.ofEpochMilli(msg.createdAt)
                val formatted = TimeContext.formatZonedDateTime(instant, zone)
                "[$formatted] "
            } else ""
            val speaker = if (msg.role == io.zer0.ai.core.MessageRole.USER) userLabel else assistantLabel
            parts.add("$timePrefix【$speaker】$content")
        }
        return parts.joinToString("\n\n")
    }

    // ════════════════════════════
    //  Entity ↔ SummaryData 转换
    // ════════════════════════════

    private fun SessionSummaryEntity.toSummaryData(): SummaryData {
        val range = sourceTimeRange?.let {
            runCatching {
                json.decodeFromString(TimeContext.SourceTimeRange.serializer(), it)
            }.getOrElse { e ->
                // v1.78 (M5): 记录 sourceTimeRange JSON 解析失败
                Logger.w("SessionSummaryManager", "sourceTimeRange JSON 解析失败: ${e.message}")
                null
            }
        }
        return SummaryData(
            sessionId = sessionId,
            createdAt = createdAt,
            updatedAt = updatedAt,
            summary = summary,
            messageCount = messageCount,
            sourceTimeRange = range,
            snapshot = snapshot,
            snapshotAt = snapshotAt,
            assistantId = assistantId,
        )
    }

    private fun SummaryData.toEntity(sid: String): SessionSummaryEntity {
        val rangeJson = sourceTimeRange?.let {
            json.encodeToString(TimeContext.SourceTimeRange.serializer(), it)
        }
        return SessionSummaryEntity(
            sessionId = sid,
            createdAt = createdAt,
            updatedAt = updatedAt,
            summary = summary,
            messageCount = messageCount,
            sourceTimeRange = rangeJson,
            snapshot = snapshot,
            snapshotAt = snapshotAt,
            assistantId = assistantId,
        )
    }
}
