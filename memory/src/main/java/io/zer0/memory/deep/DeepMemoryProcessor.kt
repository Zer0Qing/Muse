package io.zer0.memory.deep

import io.zer0.ai.core.Model
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactStore
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.prompt.FactExtractionPrompt
import io.zer0.memory.summary.SessionSummaryManager
import io.zer0.memory.ticker.MemoryConfig
import io.zer0.memory.time.TimeContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 深度记忆处理器 (openhanako deep-memory.ts 移植)。
 *
 * 每日遍历脏 session(summary !== snapshot),用 LLM 从 summary diff
 * 抽取元事实,写入 [FactStore]。
 *
 * 关键设计:
 *  - 并发: 最多 [MAX_CONCURRENT] 个 session 同时处理(Semaphore)
 *  - 重试: 单 session 失败 [MAX_RETRIES] 次后 markProcessed 跳过,不再无限重试
 *  - JSON 修复: LLM 输出可能带 ```json 围栏或 ictd 块,需清洗后解析
 *  - 时间规范化: fact.time 必须在 source_time_range.localDates 内
 *
 * v0.22: 通过 [FactDbProvider] 按 summary.assistantId 获取对应 Assistant 的
 * 独立 FactStore,实现 per-assistant facts.db 隔离。
 */
class DeepMemoryProcessor(
    private val factDbProvider: io.zer0.memory.fact.FactDbProvider,
    private val llmClient: MemoryLlmClient,
) {

    companion object {
        const val MAX_CONCURRENT = 3
        const val MAX_RETRIES = 3
        const val FACT_EXTRACTION_MAX_TOKENS = 4096
        // 连续 daily 失败达此上限后 markProcessed,不再重试(避免浪费 LLM 调用)
        const val MAX_DAILY_FAILURES = 3
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val concurrency = Semaphore(MAX_CONCURRENT)
    // session → 连续 daily 失败次数,成功时清除,达 MAX_DAILY_FAILURES 后 markProcessed 跳过
    private val failureCounts = ConcurrentHashMap<String, Int>()

    /** 处理结果。 */
    data class ProcessResult(
        val processed: Int,
        val factsAdded: Int,
        val failures: Int,
    )

    /**
     * 遍历脏 session,diff 抽元事实写 FactStore。
     *
     * v0.32: 新增 [config] 参数,在每个 session 处理完后调用 [FactStore.applyDecay]
     * 执行一次配置驱动的遗忘(对照 openhanako memory.decay_per_day / forget_speed)。
     * 默认 [MemoryConfig] 等价于旧行为(默认 cutoff 约 40 天,几乎不删)。
     *
     * @param summaryManager 摘要管理器
     * @param model 目标模型
     * @param locale 语言
     * @param config 记忆配置(由 [io.zer0.memory.ticker.MemoryTicker] 透传)
     * @return 处理统计
     */
    suspend fun processDirtySessions(
        summaryManager: SessionSummaryManager,
        model: Model?,
        locale: String = "zh-CN",
        config: MemoryConfig = MemoryConfig(),
    ): ProcessResult = withContext(Dispatchers.IO) {
        val dirty = summaryManager.getDirtySessions()
        if (dirty.isEmpty()) return@withContext ProcessResult(0, 0, 0)

        val processed = AtomicInteger(0)
        val factsAdded = AtomicInteger(0)
        val failures = AtomicInteger(0)

        coroutineScope {
            dirty.map { summary ->
                async {
                    concurrency.withPermit {
                        // v1.78 (M2): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                        resultOf {
                            processSingleSession(summary, summaryManager, model, locale, config)
                        }.onSuccess { added ->
                            processed.incrementAndGet()
                            factsAdded.addAndGet(added)
                        }.onError { msg, t ->
                            // CancellationException 已被 resultOf 重抛,此处 t 不会是 CancellationException
                            Logger.w("DeepMemoryProcessor", "processSingleSession failed for ${summary.sessionId.take(8)}…: $msg", t)
                            failures.incrementAndGet()
                        }
                    }
                }
            }.awaitAll()
        }

        ProcessResult(processed.get(), factsAdded.get(), failures.get())
    }

    /** 处理单个 session。返回新增 fact 数量。 */
    private suspend fun processSingleSession(
        summary: SessionSummaryManager.SummaryData,
        summaryManager: SessionSummaryManager,
        model: Model?,
        locale: String = "zh-CN",
        config: MemoryConfig = MemoryConfig(),
    ): Int {
        // 连续 daily 失败达上限,标记为已处理不再重试(避免反复浪费 LLM 调用)
        if ((failureCounts[summary.sessionId] ?: 0) >= MAX_DAILY_FAILURES) {
            Logger.w("DeepMemoryProcessor", "session=${summary.sessionId.take(8)}… 连续 daily 失败 ${MAX_DAILY_FAILURES} 次,标记为已处理跳过")
            summaryManager.markProcessed(summary.sessionId)
            failureCounts.remove(summary.sessionId)
            return 0
        }

        val summaryText = summary.summary
        val prevSnapshot = summary.snapshot

        // v0.22: 按 summary.assistantId 获取对应 Assistant 的独立 FactStore
        val factStore = factDbProvider.getFactStore(summary.assistantId)

        // 构建 LLM 输入
        val isZh = locale.startsWith("zh")
        val hasPrevious = prevSnapshot.isNotBlank()
        val systemPrompt = FactExtractionPrompt.buildSystemPrompt(locale, hasPrevious)

        val timeContextLabel = if (isZh) "## 时间上下文" else "## Time Context"
        val timeContextText = buildTimeContextText(summary, isZh)

        val userContent = if (hasPrevious) {
            val prevLabel = if (isZh) "## 上次快照" else "## Previous Snapshot"
            val curLabel = if (isZh) "## 当前摘要" else "## Current Summary"
            "$timeContextLabel\n\n$timeContextText\n\n$prevLabel\n\n$prevSnapshot\n\n$curLabel\n\n$summaryText"
        } else {
            val curLabel = if (isZh) "## 摘要内容" else "## Summary Content"
            "$timeContextLabel\n\n$timeContextText\n\n$curLabel\n\n$summaryText"
        }

        // 调 LLM(带重试)
        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                val rawResult = llmClient.callText(
                    systemPrompt = systemPrompt,
                    userContent = userContent,
                    model = model,
                    temperature = 0.3f,
                    maxTokens = FACT_EXTRACTION_MAX_TOKENS,
                )
                val facts = parseFactExtractionResult(rawResult, summary, locale)
                if (facts.isEmpty()) {
                    // v1.78: 区分"解析失败返回空"与"LLM 真的判定无事实"
                    // 这里无法完全区分,但记录原始返回长度便于排查
                    Logger.d("DeepMemoryProcessor", "fact extraction returned empty (rawLen=${rawResult.length}, session=${summary.sessionId.take(8)}…)")
                    failureCounts.remove(summary.sessionId)
                    summaryManager.markProcessed(summary.sessionId)
                    return 0
                }
                val added = factStore.addBatch(facts)
                // v0.32: 接入 MemoryConfig —— 每个脏 session 处理完后顺手跑一次衰减,
                // 让 decayPerDay / forgetSpeed / compileThreshold / baseImportance 真正生效。
                // 单 session 的 fact 增量很小,applyDecay 的开销可忽略;失败也不阻塞主流程。
                // v1.78 (H4): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                resultOf { factStore.applyDecay(config) }.onError { msg, t ->
                    Logger.w("DeepMemoryProcessor", "applyDecay failed for ${summary.sessionId.take(8)}…: $msg", t)
                }
                failureCounts.remove(summary.sessionId)
                summaryManager.markProcessed(summary.sessionId)
                return added
            } catch (e: CancellationException) {
                // v1.78 (H5): 必须重抛,否则协程取消信号丢失,repeat 循环继续重试无法退出
                throw e
            } catch (e: Exception) {
                lastError = e
                Logger.w("DeepMemoryProcessor", "fact extraction attempt ${attempt + 1}/$MAX_RETRIES failed for ${summary.sessionId.take(8)}…: ${e.message}")
                // v1.78 (M6): 指数退避,避免 LLM 限流时立即重试加剧限流
                if (attempt < MAX_RETRIES - 1) {
                    delay(1000L * (1L shl attempt)) // 1s, 2s, 4s
                }
            }
        }

        // v1.78 (M5): 重试耗尽时不调 markProcessed —— 否则该 session 会被永久跳过,
        // 真正的失败被掩盖。改为仅记录失败日志,让下次 daily pipeline 重新拾取重试。
        // 递增连续失败计数,达 MAX_DAILY_FAILURES 后下次 daily 跳过该 session。
        failureCounts[summary.sessionId] = (failureCounts[summary.sessionId] ?: 0) + 1
        Logger.w(
            "DeepMemoryProcessor",
            "fact extraction 重试耗尽(${MAX_RETRIES}次),session=${summary.sessionId.take(8)}…," +
                "连续 daily 失败 ${failureCounts[summary.sessionId]}/$MAX_DAILY_FAILURES," +
                "下次 daily pipeline 将重试: ${lastError?.message}",
            lastError,
        )
        throw lastError ?: RuntimeException("fact extraction failed after $MAX_RETRIES retries")
    }

    /** 构建 time context 文本(供 LLM 参考)。 */
    private fun buildTimeContextText(
        summary: SessionSummaryManager.SummaryData,
        isZh: Boolean,
    ): String {
        val range = summary.sourceTimeRange ?: return if (isZh) "（无时间信息）" else "(no time info)"
        val localDatesLabel = if (isZh) "本地日期" else "Local dates"
        val timezoneLabel = if (isZh) "时区" else "Timezone"
        return "$timezoneLabel: ${range.timezone}\n$localDatesLabel: ${range.localDates.joinToString(", ")}"
    }

    /**
     * 解析 fact extraction 结果。
     * LLM 输出可能带 ```json 围栏或 <think> 块,需清洗。
     */
    private fun parseFactExtractionResult(
        raw: String,
        summary: SessionSummaryManager.SummaryData,
        locale: String,
    ): List<FactStore.Fact> {
        if (raw.isBlank()) return emptyList()

        // 1. 去前置 <think>...</think> 块
        var s = raw
        s = s.replace(Regex("<think(?:ing)?>[\\s\\S]*?</think(?:ing)?>", RegexOption.IGNORE_CASE), "")
        // 2. 去 ```json ... ``` 围栏
        val fenceMatch = Regex("""```(?:json)?\s*\n([\s\S]*?)\n```""").find(s)
        if (fenceMatch != null) s = fenceMatch.groupValues[1]
        else s = s.trim()

        // 3. 提取 JSON 数组(若不以 [ 开头,扫描括号深度)
        if (!s.startsWith("[")) {
            s = findJsonArrayCandidate(s) ?: return emptyList()
        }

        // 4. 解析
        val dtos = runCatching {
            json.decodeFromString(ListSerializer(FactDto.serializer()), s)
        }.getOrElse {
            // v1.78: 记录解析失败,便于排查 LLM 输出格式问题
            Logger.w("DeepMemoryProcessor", "fact extraction JSON 解析失败 (rawLen=${raw.length}): ${it.message}")
            return emptyList()
        }

        // 5. 时间规范化 + 转 Fact
        val localDates = summary.sourceTimeRange?.localDates ?: emptyList()
        val summaryTimes = io.zer0.memory.time.TimeContext.extractSummaryTimeSignals(summary.summary).dateTimes
        val ctx = TimeContext.FactTimeContext(localDates, summaryTimes)

        return dtos.mapNotNull { dto ->
            val normalizedTime = io.zer0.memory.time.TimeContext.normalizeFactTime(dto.time, ctx)
            FactStore.Fact(
                fact = dto.fact,
                tags = dto.tags,
                time = normalizedTime,
                sessionId = summary.sessionId,
                importance = dto.importance.coerceIn(0, 2),
                category = dto.category.takeIf { it.isNotBlank() } ?: "general",
                confidence = dto.confidence?.coerceIn(0f, 1f) ?: if (dto.source == "user_explicit") 1.0f else 0.7f,
                source = dto.source.takeIf { it.isNotBlank() } ?: "inferred",
                expiresAt = dto.expiresAt,
                lastConfirmedAt = dto.lastConfirmedAt,
            )
        }
    }

    /**
     * 扫描字符串找出第一个完整 JSON 数组(括号深度状态机)。
     */
    private fun findJsonArrayCandidate(s: String): String? {
        val start = s.indexOf('[')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until s.length) {
            val c = s[i]
            if (escape) { escape = false; continue }
            if (inString) {
                when (c) {
                    '\\' -> escape = true
                    '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** fact extraction 的 JSON DTO。v5: 含 importance / category / confidence / source / expires_at / last_confirmed_at 字段。 */
    @Serializable
    private data class FactDto(
        val fact: String,
        val tags: List<String> = emptyList(),
        val time: String? = null,
        val importance: Int = 0,
        val category: String = "general",
        val confidence: Float? = null,
        val source: String = "inferred",
        val expiresAt: String? = null,
        val lastConfirmedAt: String? = null,
    )
}
