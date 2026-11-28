package io.zer0.memory.deep

import io.zer0.ai.core.Model
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactStore
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.prompt.FactExtractionPrompt
import io.zer0.memory.prompt.PromptExampleGuard
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val ASSISTANT_ATTRIBUTED_LINE_REGEX =
    Regex("""^\s*[-*+]\s*\[(?:助手|assistant)[^\]]*\].*$""", RegexOption.IGNORE_CASE)

internal fun filterAssistantAttributedMemoryEntries(summary: String): String = summary.lineSequence()
    .filterNot { ASSISTANT_ATTRIBUTED_LINE_REGEX.matches(it) }
    .joinToString("\n")

private data class SessionExtractionSession(
    val summary: SessionSummaryManager.SummaryData,
    val summaryManager: SessionSummaryManager,
    val model: Model?,
    val locale: String,
    val config: MemoryConfig,
    val fence: io.zer0.memory.runtime.MemoryWriteFence?,
)

private data class SessionExtractionStorage(
    val factStore: FactStore,
    val memoryScope: String,
    val gateScope: io.zer0.memory.runtime.MemoryScopeKey,
    val tombstones: List<String>,
)

private data class SessionExtractionPrompts(
    val effectiveSnapshot: String,
    val effectiveSummary: String,
    val systemPrompt: String,
    val userContent: String,
)

private data class SessionExtractionContext(
    val session: SessionExtractionSession,
    val storage: SessionExtractionStorage,
    val prompts: SessionExtractionPrompts,
) {
    val summary get() = session.summary
    val summaryManager get() = session.summaryManager
    val model get() = session.model
    val locale get() = session.locale
    val config get() = session.config
    val fence get() = session.fence
    val factStore get() = storage.factStore
    val memoryScope get() = storage.memoryScope
    val gateScope get() = storage.gateScope
    val tombstones get() = storage.tombstones
    val effectiveSnapshot get() = prompts.effectiveSnapshot
    val effectiveSummary get() = prompts.effectiveSummary
    val systemPrompt get() = prompts.systemPrompt
    val userContent get() = prompts.userContent
}

private sealed interface ExtractionAttempt {
    data class Success(val factsAdded: Int) : ExtractionAttempt
    data class Failed(val error: Exception) : ExtractionAttempt
    data object Done : ExtractionAttempt
    data object Blocked : ExtractionAttempt
}

/**
 * 深度记忆处理器。
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
    private val writeGate: io.zer0.memory.runtime.MemoryWriteGate? = null,
) {

    companion object {
        const val MAX_CONCURRENT = 3
        const val MAX_RETRIES = 3
        const val FACT_EXTRACTION_MAX_TOKENS = 4096

        // 连续 daily 失败达此上限后 markProcessed,不再重试(避免浪费 LLM 调用)
        const val MAX_DAILY_FAILURES = 3
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val concurrency = Semaphore(MAX_CONCURRENT)

    /**
     * v2.6.2（记忆审计）: 按 session 分片锁，避免同一 session 被
     * [processDirtySessions]（遍历全部脏）与 [processSession]（会话结束）双跑 ——
     * 两者会从同一 snapshot 重复抽取、重复写入并各自 markProcessed。
     */
    private val sessionLocks = Array(64) { kotlinx.coroutines.sync.Mutex() }

    private fun lockFor(sessionId: String) = sessionLocks[(sessionId.hashCode() and 0x7fffffff) % sessionLocks.size]

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
     * 执行一次配置驱动的遗忘(decayPerDay / forgetSpeed 配置)。
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
        if (writeGate?.isBlocked(io.zer0.memory.runtime.MemoryScopeKey("default", "main", "default")) == true) {
            return@withContext ProcessResult(0, 0, 0)
        }
        // 入口取栅栏:一次抽取调用可能让恢复窗口在会话处理期间完整开合,
        // 返回时 restoring 已复位,只有栅栏能证明这份结果基于恢复前的库。
        val fence = writeGate?.fence()
        val dirty = summaryManager.getDirtySessions()
        if (dirty.isEmpty()) return@withContext ProcessResult(0, 0, 0)

        val processed = AtomicInteger(0)
        val factsAdded = AtomicInteger(0)
        val failures = AtomicInteger(0)

        coroutineScope {
            dirty.map { summary ->
                async {
                    concurrency.withPermit {
                        // v2.6.2（记忆审计）: 同一 session 的处理串行化（processDirtySessions 与
                        // processSession 可能并发触发）。锁内重新读最新 summary，拿到的不是快照。
                        lockFor(summary.sessionId).withLock {
                            val latest = summaryManager.getSummary(summary.sessionId) ?: return@withLock
                            if (latest.summary == latest.snapshot) return@withLock // 已被其他路径处理
                            // v1.78 (M2): 包装 suspend 调用必须用 resultOf,避免吞 CancellationException
                            resultOf {
                                processSingleSession(latest, summaryManager, model, locale, config, fence)
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
                }
            }.awaitAll()
        }

        ProcessResult(processed.get(), factsAdded.get(), failures.get())
    }

    /**
     * v1.0.51: 处理指定 session 的事实提取(用于会话结束后立即处理)。
     *
     * 与 [processDirtySessions] 的差异:
     *  - 只处理指定 sessionId,不扫描全部 dirty sessions
     *  - 供 [io.zer0.memory.ticker.MemoryTicker.notifySessionEnd] 在 rollingSummary 成功后调用
     *  - 复用 [concurrency] Semaphore(3) 限制全局并发
     *
     * @return 新增 fact 数量(session 不存在/非 dirty/提取失败均返回 0)
     */
    suspend fun processSession(
        sessionId: String,
        summaryManager: SessionSummaryManager,
        model: Model?,
        locale: String = "zh-CN",
        config: MemoryConfig = MemoryConfig(),
    ): Int = withContext(Dispatchers.IO) {
        if (writeGate?.isBlocked(io.zer0.memory.runtime.MemoryScopeKey("default", "main", "default")) == true) {
            return@withContext 0
        }
        // 同 [processDirtySessions]:入口取栅栏,写前再比一次。
        val fence = writeGate?.fence()
        val summary = summaryManager.getSummary(sessionId) ?: return@withContext 0
        // 只处理 dirty session(summary !== snapshot)
        if (summary.summary == summary.snapshot) return@withContext 0

        concurrency.withPermit {
            // v2.6.2（记忆审计）: 同一 session 与 processDirtySessions 串行化。
            lockFor(sessionId).withLock {
                resultOf {
                    processSingleSession(summary, summaryManager, model, locale, config, fence)
                }.onSuccess { added ->
                    if (added > 0) {
                        Logger.i("DeepMemoryProcessor", "processSession(${sessionId.take(8)}…): $added 条新事实")
                    }
                }.onError { msg, t ->
                    Logger.w("DeepMemoryProcessor", "processSession(${sessionId.take(8)}…) 失败: $msg", t)
                }.getOrNull() ?: 0
            }
        }
    }

    /**
     * 处理单个 session。返回新增 fact 数量。
     *
     * [fence] 为入口取的恢复栅栏:模型返回后、stage 前与 publish 前各判一次闸,
     * 被挡时已 stage 的 generation 直接孤立,不写 markProcessed —— 该 session 保持 dirty,
     * 恢复结束后从同一 snapshot 重抽。
     */
    private suspend fun processSingleSession(
        summary: SessionSummaryManager.SummaryData,
        summaryManager: SessionSummaryManager,
        model: Model?,
        locale: String = "zh-CN",
        config: MemoryConfig = MemoryConfig(),
        fence: io.zer0.memory.runtime.MemoryWriteFence? = null,
    ): Int {
        if (maybeSkipExhaustedSession(summary, summaryManager)) return 0
        val factStore = factDbProvider.getFactStore(summary.assistantId)
        val memoryScope = summary.assistantId
            .takeIf { it.isNotBlank() && it != "default" }
            ?: "main"
        val gateScope = io.zer0.memory.runtime.MemoryScopeKey(
            assistantId = summary.assistantId.ifBlank { "default" },
            scope = memoryScope,
            spaceId = summary.spaceId.ifBlank { "default" },
        )
        val tombstones = resultOf { factStore.getTombstones(memoryScope, summary.spaceId) }.getOrNull().orEmpty()
        val effectiveSummary = filterAssistantAttributedMemoryEntries(
            FactStore.filterTombstonedLines(summary.summary, tombstones),
        )
        val effectiveSnapshot = filterAssistantAttributedMemoryEntries(
            FactStore.filterTombstonedLines(summary.snapshot, tombstones),
        )
        if (effectiveSummary == effectiveSnapshot) {
            summaryManager.markProcessed(summary.sessionId)
            return 0
        }
        val isZh = locale.startsWith("zh")
        val hasPrevious = summary.snapshot.isNotBlank()
        val context = SessionExtractionContext(
            session = SessionExtractionSession(
                summary = summary,
                summaryManager = summaryManager,
                model = model,
                locale = locale,
                config = config,
                fence = fence,
            ),
            storage = SessionExtractionStorage(
                factStore = factStore,
                memoryScope = memoryScope,
                gateScope = gateScope,
                tombstones = tombstones,
            ),
            prompts = SessionExtractionPrompts(
                effectiveSnapshot = effectiveSnapshot,
                effectiveSummary = effectiveSummary,
                systemPrompt = FactExtractionPrompt.buildSystemPrompt(locale, hasPrevious),
                userContent = buildExtractionUserContent(
                    isZh = isZh,
                    hasPrevious = hasPrevious,
                    timeContextText = renderTimeContext(summary, isZh),
                    effectiveSnapshot = effectiveSnapshot,
                    effectiveSummary = effectiveSummary,
                ),
            ),
        )
        return runFactExtraction(context)
    }

    private suspend fun runFactExtraction(context: SessionExtractionContext): Int {
        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            when (val result = runFactExtractionAttempt(context)) {
                is ExtractionAttempt.Success -> return result.factsAdded
                ExtractionAttempt.Done, ExtractionAttempt.Blocked -> return 0
                is ExtractionAttempt.Failed -> {
                    lastError = result.error
                    Logger.w(
                        "DeepMemoryProcessor",
                        "fact extraction attempt ${attempt + 1}/$MAX_RETRIES failed for " +
                            "${context.summary.sessionId.take(8)}…: ${result.error.message}",
                    )
                }
            }
            if (attempt < MAX_RETRIES - 1) delay(1000L * (1L shl attempt))
        }
        val sessionId = context.summary.sessionId
        failureCounts[sessionId] = (failureCounts[sessionId] ?: 0) + 1
        Logger.w(
            "DeepMemoryProcessor",
            "fact extraction 重试耗尽(${MAX_RETRIES}次),session=${sessionId.take(8)}…," +
                "连续 daily 失败 ${failureCounts[sessionId]}/$MAX_DAILY_FAILURES," +
                "下次 daily pipeline 将重试: ${lastError?.message}",
            lastError,
        )
        throw checkNotNull(lastError) { "fact extraction failed after $MAX_RETRIES retries" }
    }

    private suspend fun runFactExtractionAttempt(context: SessionExtractionContext): ExtractionAttempt {
        val callResult = try {
            Result.success(
                llmClient.callText(
                    systemPrompt = context.systemPrompt,
                    userContent = context.userContent,
                    model = context.model,
                    temperature = 0.3f,
                    maxTokens = FACT_EXTRACTION_MAX_TOKENS,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: java.io.IOException) {
            Result.failure<String>(error)
        } catch (error: IllegalStateException) {
            Result.failure<String>(error)
        } catch (error: IllegalArgumentException) {
            Result.failure<String>(error)
        }
        val parseResult = callResult.mapCatching { raw ->
            parseFactExtractionResult(raw, context.summary, context.locale)
        }
        val facts = parseResult.getOrNull()
        val guardedFacts = facts?.filterNot {
            PromptExampleGuard.isUngroundedExample(
                it.fact,
                "${context.effectiveSnapshot}\n${context.effectiveSummary}",
            )
        }
        val filteredFacts = guardedFacts?.filter { fact ->
            context.tombstones.none { tombstone -> FactStore.matchesTombstone(fact.fact, tombstone) }
        }
        return when {
            writeGate?.blocksWrite(context.gateScope, context.fence) == true -> {
                Logger.d(
                    "DeepMemoryProcessor",
                    "恢复窗口内跳过事实落库(session=${context.summary.sessionId.take(8)}…),保留 dirty 待重试",
                )
                ExtractionAttempt.Blocked
            }
            callResult.isFailure -> ExtractionAttempt.Failed(callResult.exceptionOrNull().toException())
            parseResult.isFailure -> ExtractionAttempt.Failed(parseResult.exceptionOrNull().toException())
            facts == null -> ExtractionAttempt.Failed(
                IllegalStateException("fact extraction 解析失败"),
            )
            guardedFacts.isNullOrEmpty() -> markEmptyExtraction(context)
            filteredFacts.isNullOrEmpty() -> markTombstoneFilteredExtraction(context)
            else -> publishExtractedFacts(context, filteredFacts)
        }
    }

    private suspend fun markEmptyExtraction(context: SessionExtractionContext): ExtractionAttempt {
        Logger.d("DeepMemoryProcessor", "fact extraction returned empty (session=${context.summary.sessionId.take(8)}…)")
        failureCounts.remove(context.summary.sessionId)
        context.summaryManager.markProcessed(context.summary.sessionId)
        return ExtractionAttempt.Done
    }

    private suspend fun markTombstoneFilteredExtraction(context: SessionExtractionContext): ExtractionAttempt {
        Logger.d(
            "DeepMemoryProcessor",
            "fact extraction 全部命中墓碑(session=${context.summary.sessionId.take(8)}…),跳过写入",
        )
        failureCounts.remove(context.summary.sessionId)
        context.summaryManager.markProcessed(context.summary.sessionId)
        return ExtractionAttempt.Done
    }

    private suspend fun publishExtractedFacts(context: SessionExtractionContext, facts: List<FactStore.Fact>): ExtractionAttempt {
        val atomicity = FactAtomicityValidator.enforce(facts)
        if (atomicity.splitCount > 0) {
            Logger.i(
                "DeepMemoryProcessor",
                "原子性校验: 拆分 ${atomicity.splitCount} 条多断言事实(+${atomicity.addedCount} 条, " +
                    "session=${context.summary.sessionId.take(8)}…)",
            )
        }
        val summary = context.summary
        val generationId = buildGenerationId(summary.sessionId, summary.summaryRevision, summary.sourceFingerprint)
        context.factStore.stageSessionFacts(
            generationId = generationId,
            sessionId = summary.sessionId,
            sourceRevision = summary.summaryRevision,
            facts = atomicity.facts,
            scope = context.memoryScope,
            spaceId = summary.spaceId,
        )
        if (!context.summaryManager.isRevisionCurrent(summary.sessionId, summary.summaryRevision)) {
            context.factStore.orphanProjection(generationId)
            incrementFailure(summary.sessionId)
            return ExtractionAttempt.Failed(IllegalStateException("session summary changed during fact extraction"))
        }
        if (writeGate?.blocksWrite(context.gateScope, context.fence) == true) {
            context.factStore.orphanProjection(generationId)
            Logger.d(
                "DeepMemoryProcessor",
                "恢复窗口内跳过事实发布(session=${summary.sessionId.take(8)}…),已孤立 generation",
            )
            return ExtractionAttempt.Blocked
        }
        val published = context.factStore.publishSessionFacts(generationId, summary.sessionId, summary.summaryRevision)
        if (published !is io.zer0.memory.fact.ProjectionPublishResult.Published) {
            context.factStore.orphanProjection(generationId)
            incrementFailure(summary.sessionId)
            return ExtractionAttempt.Failed(IllegalStateException("fact projection publish rejected: $published"))
        }
        return if (!context.summaryManager.markProcessedIfCurrent(summary.sessionId, summary.summaryRevision)) {
            context.factStore.orphanProjection(generationId)
            incrementFailure(summary.sessionId)
            ExtractionAttempt.Failed(IllegalStateException("summary revision advanced before markProcessedIfCurrent"))
        } else {
            resultOf {
                context.factStore.applyDecay(context.config, scope = context.memoryScope, spaceId = summary.spaceId)
            }.onError { msg, t ->
                Logger.w("DeepMemoryProcessor", "applyDecay failed for ${summary.sessionId.take(8)}…: $msg", t)
            }
            failureCounts.remove(summary.sessionId)
            ExtractionAttempt.Success(atomicity.facts.size)
        }
    }

    private fun incrementFailure(sessionId: String) {
        failureCounts[sessionId] = (failureCounts[sessionId] ?: 0) + 1
    }

    private fun Throwable?.toException(): Exception = when (this) {
        is Exception -> this
        null -> IllegalStateException("unknown fact extraction failure")
        else -> IllegalStateException(message ?: javaClass.simpleName)
    }

    /** 构建事实抽取 LLM 输入(按语言/是否有上次快照)。 */
    private fun buildExtractionUserContent(
        isZh: Boolean,
        hasPrevious: Boolean,
        timeContextText: String,
        effectiveSnapshot: String,
        effectiveSummary: String,
    ): String {
        val timeContextLabel = if (isZh) "## 时间上下文" else "## Time Context"
        return if (hasPrevious) {
            val prevLabel = if (isZh) "## 上次快照" else "## Previous Snapshot"
            val curLabel = if (isZh) "## 当前摘要" else "## Current Summary"
            listOf(timeContextLabel, timeContextText, prevLabel, effectiveSnapshot, curLabel, effectiveSummary)
                .joinToString("\n\n")
        } else {
            val curLabel = if (isZh) "## 摘要内容" else "## Summary Content"
            "$timeContextLabel\n\n$timeContextText\n\n$curLabel\n\n$effectiveSummary"
        }
    }

    /** 连续失败达上限的 session 标记已处理跳过,避免反复浪费 LLM 调用。 */
    private suspend fun maybeSkipExhaustedSession(
        summary: SessionSummaryManager.SummaryData,
        summaryManager: SessionSummaryManager,
    ): Boolean {
        if ((failureCounts[summary.sessionId] ?: 0) < MAX_DAILY_FAILURES) return false
        val skipMsg = "session=${summary.sessionId.take(8)}… 连续 daily 失败 ${MAX_DAILY_FAILURES} 次,标记为已处理跳过"
        Logger.w("DeepMemoryProcessor", skipMsg)
        summaryManager.markProcessed(summary.sessionId)
        failureCounts.remove(summary.sessionId)
        return true
    }

    /** 构建 time context 文本(供 LLM 参考)。 */
    private fun renderTimeContext(summary: SessionSummaryManager.SummaryData, isZh: Boolean): String {
        val range = summary.sourceTimeRange ?: return if (isZh) "（无时间信息）" else "(no time info)"
        val localDatesLabel = if (isZh) "本地日期" else "Local dates"
        val timezoneLabel = if (isZh) "时区" else "Timezone"
        return "$timezoneLabel: ${range.timezone}\n$localDatesLabel: ${range.localDates.joinToString(", ")}"
    }

    /** 生成深记忆投影 generation id。revision/fingerprint 变化会让旧 generation 失去可发布性。 */
    internal fun buildGenerationId(sessionId: String, revision: Long, fingerprint: String): String {
        val safeSession = sessionId.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        return "deep_" + safeSession + "_" + revision + "_" + fingerprint.hashCode()
    }

    /**
     * 解析 fact extraction 结果。
     * LLM 输出可能带 ```json 围栏或 <think> 块,需清洗。
     *
     * v1.0.51: 返回类型改 nullable —— 解析失败返回 null(调用方不应 markProcessed),
     * LLM 合法判定无事实返回 emptyList(调用方可 markProcessed)。
     * 修复"JSON 解析失败 → emptyList → markProcessed → session 永久跳过 → 事实丢失"的致命 bug。
     */
    // Phase 2.3: 改为 internal 以便单元测试 JSON 容错逻辑(无需通过 LLM 调用进入此方法)
    internal fun parseFactExtractionResult(
        raw: String,
        summary: SessionSummaryManager.SummaryData,
        locale: String,
    ): List<FactStore.Fact>? {
        if (raw.isBlank()) return emptyList()

        // 1. 去前置 <think>...</think> 块
        var s = raw
        s = s.replace(Regex("<think(?:ing)?>[\\s\\S]*?</think(?:ing)?>", RegexOption.IGNORE_CASE), "")
        // 2. 去 ```json ... ``` 围栏
        val fenceMatch = Regex("""```(?:json)?\s*\n([\s\S]*?)\n```""").find(s)
        if (fenceMatch != null) {
            s = fenceMatch.groupValues[1]
        } else {
            s = s.trim()
        }

        // 3. 提取 JSON 数组(若不以 [ 开头,扫描括号深度)
        if (!s.startsWith("[")) {
            // v1.0.51: 找不到 JSON 数组候选 → 解析失败,返回 null 触发重试
            val candidate = extractJsonArray(s)
            if (candidate == null) {
                Logger.w("DeepMemoryProcessor", "fact extraction 未找到 JSON 数组 (rawLen=${raw.length}, session=${summary.sessionId.take(8)}…)")
                return null
            }
            s = candidate
        }

        // 4. 解析
        val dtos = runCatching {
            json.decodeFromString(ListSerializer(FactDto.serializer()), s)
        }.getOrElse {
            // v1.0.51: JSON 解析失败 → 返回 null,调用方不 markProcessed,下次 daily 重试
            Logger.w(
                "DeepMemoryProcessor",
                "fact extraction JSON 解析失败 (rawLen=${raw.length}, session=${summary.sessionId.take(8)}…): ${it.message}",
            )
            return null
        }

        // 5. 时间规范化 + 转 Fact
        val localDates = summary.sourceTimeRange?.localDates ?: emptyList()
        val summaryTimes = io.zer0.memory.time.TimeContext.scanSummaryTimeSignals(summary.summary).dateTimes
        val ctx = TimeContext.FactTimeContext(localDates, summaryTimes)

        return dtos.mapNotNull { dto ->
            val normalizedTime = io.zer0.memory.time.TimeContext.normalizeFactTimestamp(dto.time, ctx)
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
                // H-MEM-2: 传递 LLM 提取的 entityKey,支持精确去重
                entityKey = dto.entityKey,
            )
        }
    }

    /**
     * 扫描字符串找出第一个完整 JSON 数组(括号深度状态机)。
     */
    private fun extractJsonArray(s: String): String? {
        val start = s.indexOf('[')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until s.length) {
            val c = s[i]
            if (escape) {
                escape = false
                continue
            }
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

    /** fact extraction 的 JSON DTO。v5: 含 importance / category / confidence / source / expires_at / last_confirmed_at 字段。
     * H-MEM-2: 新增 entityKey — LLM 提取时要求输出实体键,支持精确去重通道(v12+)。 */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class FactDto(
        val fact: String,
        val tags: List<String> = emptyList(),
        val time: String? = null,
        val importance: Int = 0,
        val category: String = "general",
        val confidence: Float? = null,
        val source: String = "inferred",
        @SerialName("expires_at")
        @JsonNames("expiresAt")
        val expiresAt: String? = null,
        @SerialName("last_confirmed_at")
        @JsonNames("lastConfirmedAt")
        val lastConfirmedAt: String? = null,
        @SerialName("entity_key")
        @JsonNames("entityKey")
        val entityKey: String? = null,
    )
}
