package io.zer0.memory.compile

// NOTE-i18n: section 标题(重要事实/今天等)参与 LLM 输出解析契约,需契约与文案分离架构改动后才能提取。

import io.zer0.ai.core.Model
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.format.RollingSummaryFormat
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.prompt.CompilePrompts
import io.zer0.memory.state.CompiledMemoryState
import io.zer0.memory.summary.CompiledSectionDao
import io.zer0.memory.summary.CompiledSectionEntity
import io.zer0.memory.summary.SessionSummaryManager
import io.zer0.memory.ticker.MemoryConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 记忆编译器。
 *
 * 四块独立编译 + assemble:
 *  - compileToday: 当天 sessions 摘要 → today.md(Room)
 *  - compileDaily: 已结束那天的 today 草稿/摘要 → daily/{date}.md(文件)
 *  - assembleWeekFromDaily: daily/ 目录最近 N 条纯文件装配 → week.md(零 LLM)
 *  - compileWeek: 兼容入口,优先 assembleWeekFromDaily,无 daily 文件时回退到旧 LLM 路径
 *  - compileLongterm: week/daily 内容 fold 进 longterm.md(每日一次)
 *  - compileFacts: 30 天摘要的 facts 段 → facts.md
 *  - rollDailyWindow: 滚出窗口的 daily 条目 fold 进 longterm 后删除源文件
 *  - assemble: 四块拼接成 memory.md(同步,不调 LLM)
 *
 * 每块都有指纹缓存: 输入没变就跳过 LLM 调用。
 * 空 sessions 不写指纹(避免 rolling 失败期被指纹锁死)。
 */
/** D3-P2: 候选吸收的单行长度上限(超长行视为段落,不入表)。 */
private const val MAX_ABSORB_LINE_CHARS = 200

class MemoryCompiler(
    private val sectionDao: CompiledSectionDao,
    private val llmClient: MemoryLlmClient,
    /** v6: 文件化输出,为 null 时不写文件(便于测试)。 */
    private val fileWriter: MemoryFileWriter? = null,
    /**
     * 审计修复 (S-04): 删除墓碑源(FactStore)。
     *
     * 用户删除记忆后,已删事实不得从会话摘要重新编译时"复活"。compileFacts/
     * compileToday 按 [FactStore.getTombstones] 过滤输入与输出。为 null 时过滤禁用。
     */
    private val factStore: FactStore? = null,
    /** v3: scoped 编译产物；null 时保留旧测试/兼容路径。 */
    private val scopedSectionDao: io.zer0.memory.summary.ScopedCompiledSectionDao? = null,
    private val compileContext: MemoryCompileContext = MemoryCompileContext(),
    /** Resolve assistant-local facts and tombstones for scoped compile targets. */
    private val factDbProvider: FactDbProvider? = null,
) {

    /** 四块 section key。 */
    enum class Section(val key: String) {
        FACTS("facts"),
        TODAY("today"),
        WEEK("week"),
        LONGTERM("longterm"),
        ;

        companion object {
            val ALL = listOf(FACTS, TODAY, WEEK, LONGTERM)
        }
    }

    /**
     * 编译结果。
     * v1.0.51: 新增 FAILED — LLM 调用失败或返回空响应时使用。
     *   - COMPILED: 成功编译并写入
     *   - SKIPPED: 指纹命中,无需编译(安全)
     *   - FAILED: LLM 失败或空响应,不写入,不标记 checkpoint,下次重试
     */
    enum class Result { COMPILED, SKIPPED, FAILED }

    private suspend fun currentScope(): String = compileContext.getScope().ifBlank { "main" }

    private suspend fun currentSpaceId(): String = compileContext.getSpaceId().ifBlank { "default" }

    private suspend fun readStoredSection(
        key: String,
        target: MemoryCompileTarget? = null,
    ): io.zer0.memory.summary.ScopedCompiledSectionEntity? {
        val scope = target?.normalizedScope ?: currentScope()
        val spaceId = target?.normalizedSpaceId ?: currentSpaceId()
        val scoped = scopedSectionDao
        return if (scoped != null) {
            scoped.get(key, scope, spaceId)
                ?: if (target == null && scope == "main" && spaceId == "default") {
                    sectionDao.get(key)?.let { legacy ->
                        io.zer0.memory.summary.ScopedCompiledSectionEntity(
                            sectionKey = legacy.sectionKey, scope = scope, spaceId = spaceId,
                            content = legacy.content, fingerprint = legacy.fingerprint, updatedAt = legacy.updatedAt,
                        )
                    }
                } else {
                    null
                }
        } else {
            sectionDao.get(key)?.let { legacy ->
                io.zer0.memory.summary.ScopedCompiledSectionEntity(
                    sectionKey = legacy.sectionKey,
                    content = legacy.content,
                    fingerprint = legacy.fingerprint,
                    updatedAt = legacy.updatedAt,
                )
            }
        }
    }

    private suspend fun updateStoredContent(
        key: String,
        content: String,
        fingerprint: String?,
        now: String,
        target: MemoryCompileTarget? = null,
    ) {
        val scoped = scopedSectionDao
        if (scoped != null) {
            scoped.updateContent(
                key,
                target?.normalizedScope ?: currentScope(),
                target?.normalizedSpaceId ?: currentSpaceId(),
                content,
                fingerprint,
                now,
            )
        } else {
            sectionDao.updateContent(key, content, fingerprint, now)
        }
    }

    private suspend fun clearStoredAll(now: String) {
        scopedSectionDao?.clearAll(now) ?: sectionDao.clearAll(now)
    }

    private suspend fun clearStoredByKey(key: String, now: String, target: MemoryCompileTarget? = null) {
        scopedSectionDao?.clearByKey(
            key,
            target?.normalizedScope ?: currentScope(),
            target?.normalizedSpaceId ?: currentSpaceId(),
            now,
        ) ?: sectionDao.clearByKey(key, now)
    }

    private suspend fun upsertStored(
        key: String,
        content: String,
        fingerprint: String?,
        now: String,
        target: MemoryCompileTarget? = null,
    ) {
        val scoped = scopedSectionDao
        if (scoped != null) {
            scoped.updateContent(
                key,
                target?.normalizedScope ?: currentScope(),
                target?.normalizedSpaceId ?: currentSpaceId(),
                content,
                fingerprint,
                now,
            )
        } else {
            sectionDao.upsert(
                CompiledSectionEntity(
                    sectionKey = key,
                    content = content,
                    fingerprint = fingerprint,
                    updatedAt = now,
                ),
            )
        }
    }

    /** 读取某块当前内容。 */
    suspend fun readSection(section: Section): String = withContext(Dispatchers.IO) {
        readStoredSection(section.key)?.content ?: ""
    }

    suspend fun readSection(section: Section, target: MemoryCompileTarget): String = withContext(Dispatchers.IO) {
        readStoredSection(section.key, target)?.content ?: ""
    }

    /** 按显式 scope + space 读取，供 system prompt 注入避免依赖全局默认槽位。 */
    suspend fun readSection(section: Section, scope: String, spaceId: String): String = withContext(Dispatchers.IO) {
        val normalizedScope = scope.ifBlank { "main" }
        val normalizedSpace = spaceId.ifBlank { "default" }
        scopedSectionDao?.get(section.key, normalizedScope, normalizedSpace)?.content
            ?: if (normalizedScope == "main" && normalizedSpace == "default") {
                sectionDao.get(section.key)?.content
            } else {
                null
            }
            ?: ""
    }

    /**
     * v1.0.53: 是否存在任何已编译 section。
     * 用于 daily pipeline 进度校验:旧版 updateContent 是 UPDATE 语义,
     * 表初始为空时写入静默丢失,但 daily_state 仍记录完成 → 需重置进度重跑。
     */
    suspend fun hasAnyCompiledSection(): Boolean = withContext(Dispatchers.IO) {
        scopedSectionDao?.getAll()?.isNotEmpty() == true || sectionDao.getAll().isNotEmpty()
    }

    // ─── S-04: 删除墓碑过滤(防已删事实从摘要复活) ───

    /**
     * S-04: 过滤命中墓碑的文本行(逐行规范化子串匹配)。
     * 墓碑为空时原样返回(零开销路径)。
     * 审查修复 (2.0 B-07): 匹配逻辑下沉到 [FactStore.filterTombstonedLines]
     * (空白压缩 + 标点剥离双通道),本方法保留签名与可见性兼容测试。
     */
    internal fun filterTombstonedLines(text: String, tombstones: List<String>): String = FactStore.filterTombstonedLines(text, tombstones)

    /** Resolve the FactStore belonging to the explicit compile scope, preserving per-assistant isolation. */
    private fun factStoreFor(target: MemoryCompileTarget): FactStore? {
        val assistantId = target.assistantId?.takeIf { it.isNotBlank() }
            ?: target.normalizedScope.takeIf { it != "main" }
            ?: "default"
        return if (assistantId == "default") {
            factStore ?: factDbProvider?.getFactStore("default")
        } else {
            factDbProvider?.getFactStore(assistantId) ?: factStore
        }
    }

    private suspend fun effectiveTarget(
        target: MemoryCompileTarget? = null,
        assistantId: String? = null,
        scope: String? = null,
        spaceId: String? = null,
    ): MemoryCompileTarget {
        val resolvedScope = (scope ?: target?.scope ?: currentScope()).ifBlank { "main" }
        val resolvedSpace = (spaceId ?: target?.spaceId ?: currentSpaceId()).ifBlank { "default" }
        val resolvedAssistant = target?.assistantId?.takeIf { it.isNotBlank() }
            ?: assistantId?.takeIf { it.isNotBlank() }
            ?: resolvedScope.takeIf { it != "main" }
            ?: "default"
        return MemoryCompileTarget(resolvedAssistant, resolvedScope, resolvedSpace)
    }

    /** Tombstones are read from the assistant database and exact (scope, space) pair being compiled. */
    private suspend fun loadTombstones(target: MemoryCompileTarget? = null): List<String> {
        val resolved = effectiveTarget(target)
        return runCatching { factStoreFor(resolved)?.getTombstones(resolved.normalizedScope, resolved.normalizedSpaceId) }
            .getOrNull() ?: emptyList()
    }

    /**
     * S-04: 立即从已编译的各注入段剔除命中墓碑的内容。
     *
     * 用户在记忆页删除事实后调用(FactStore.delete 已记墓碑),无需等待下次
     * 定时编译 — 删除即刻对注入生效。返回是否有内容被剔除。
     *
     * 审查修复 (2.0 A-05): 覆盖全部注入段 — 此前只清 FACTS 段,而 TODAY/WEEK/
     * LONGTERM 同样注入 system prompt(week 由 daily 文件零 LLM 装配、longterm
     * 由 fold 产生,均不过滤墓碑),用户删除的隐私会持续残留。
     */
    suspend fun purgeTombstonedFacts(target: MemoryCompileTarget? = null): Boolean = withContext(Dispatchers.IO) {
        val resolvedTarget = effectiveTarget(target)
        val tombstones = loadTombstones(resolvedTarget)
        if (tombstones.isEmpty()) return@withContext false
        var removedAny = false
        for (section in Section.ALL) {
            val current = readSection(section, resolvedTarget)
            val filtered = filterTombstonedLines(current, tombstones)
            if (filtered != current) {
                // 指纹置 null,保证下次编译重新合并(而非 SKIPPED)
                updateStoredContent(section.key, filtered, null, Instant.now().toString(), resolvedTarget)
                val removedCount = countRemovedLines(current, filtered)
                Logger.i("MemoryCompiler", "purgeTombstonedFacts: ${section.key} 剔除 $removedCount 行")
                removedAny = true
            }
        }
        removedAny
    }

    private fun countRemovedLines(before: String, after: String): Int {
        val beforeLines = before.lines().filter { it.isNotBlank() }.size
        val afterLines = after.lines().filter { it.isNotBlank() }.size
        return (beforeLines - afterLines).coerceAtLeast(0)
    }

    /** 读取四块拼装后的 memory.md(注入 system prompt 用)。
     *  审查修复 (2.0 A-05): 注入读路径统一按墓碑过滤 — 兜底任何未及时 purge 的段,
     *  已删内容不会泄漏进 system prompt(双通道匹配见 FactStore.filterTombstonedLines)。 */
    suspend fun readCompiledMemoryMarkdown(
        locale: String = "zh-CN",
        scope: String? = null,
        spaceId: String? = null,
        target: MemoryCompileTarget? = null,
    ): String = withContext(Dispatchers.IO) {
        val resolvedTarget = effectiveTarget(target, scope = scope, spaceId = spaceId)
        val tombstones = loadTombstones(resolvedTarget)
        val read: suspend (Section) -> String = { section -> readSection(section, resolvedTarget) }
        val facts = CompiledMemoryState.normalizeSectionBody(
            filterTombstonedLines(read(Section.FACTS), tombstones),
        )
        val today = CompiledMemoryState.normalizeSectionBody(
            filterTombstonedLines(read(Section.TODAY), tombstones),
        )
        val week = CompiledMemoryState.normalizeSectionBody(
            filterTombstonedLines(read(Section.WEEK), tombstones),
        )
        val longterm = CompiledMemoryState.normalizeSectionBody(
            filterTombstonedLines(read(Section.LONGTERM), tombstones),
        )
        val md = assembleCompiledMarkdown(facts, today, week, longterm, locale)
        // v6: 同时输出到文件系统,便于调试和备份
        fileWriter?.writeMemoryMd(md, locale, resolvedTarget)
        md
    }

    /**
     * 编译 today: 当天 sessions → today.md。
     */
    suspend fun compileToday(
        summaryManager: SessionSummaryManager,
        model: Model?,
        locale: String = "zh-CN",
        timeZone: String = io.zer0.memory.time.TimeContext.DEFAULT_TIMEZONE,
        /**
         * A-19: 主助手 id — 非 null 时只编译主助手(及无归属旧数据)的摘要,
         * 子助手会话摘要不得串台进入主助手注入的"今天"段。
         */
        mainAssistantId: String? = null,
        target: MemoryCompileTarget? = null,
    ): Result = withContext(Dispatchers.IO) {
        val assistantId = target?.assistantId ?: mainAssistantId
        val zone = io.zer0.memory.time.TimeContext.resolveTimeZone(timeZone)
        val logicalDay = io.zer0.memory.time.TimeContext.logicalDayFor(Instant.now(), zone)
        val sessions = summaryManager.getSummariesInRange(
            start = logicalDay.rangeStart,
            end = Instant.now(),
            mainAssistantId = assistantId,
            spaceId = target?.normalizedSpaceId,
        )

        if (sessions.isEmpty()) {
            // 空 sessions: 清空内容,不写指纹
            val current = readSection(Section.TODAY, target ?: MemoryCompileTarget(assistantId = assistantId))
            if (current.isNotEmpty()) {
                updateStoredContent(Section.TODAY.key, "", null, Instant.now().toString(), target)
            }
            return@withContext Result.COMPILED
        }

        // 指纹: sessions 的 (id, updated_at) 拼接 md5
        // S-04: 墓碑并入指纹 — 用户删除事实后指纹变化,强制重编剔除已删内容
        val tombstones = loadTombstones(effectiveTarget(target, assistantId))
        val fpKeys = sessions.joinToString("\n") { "${it.sessionId}:${it.updatedAt}" } +
            "\nT:" + tombstones.joinToString("|")
        val fp = fingerprint(fpKeys)
        val existing = readStoredSection(Section.TODAY.key, target)
        if (existing?.fingerprint == fp && existing.content.isNotEmpty()) {
            return@withContext Result.SKIPPED
        }

        // S-04: 输入按墓碑过滤,已删事实不出现在 today 候选里
        val sessionInput = sessions.joinToString("\n\n---\n\n") { filterTombstonedLines(it.summary, tombstones) }
        if (sessionInput.isBlank()) {
            val current = readSection(Section.TODAY, target ?: MemoryCompileTarget(assistantId = assistantId))
            if (current.isNotEmpty()) {
                updateStoredContent(Section.TODAY.key, "", null, Instant.now().toString(), target)
            }
            return@withContext Result.COMPILED
        }
        // v1.0.51: 注入当前 facts,让 LLM 知道哪些事实已记录,避免 today 里重复
        val currentFacts = readSection(Section.FACTS, target ?: MemoryCompileTarget(assistantId = assistantId)).trim()
        val input = if (currentFacts.isNotBlank()) {
            val isZh = locale.startsWith("zh")
            val factsLabel = if (isZh) {
                "## 已记录的重要事实(供参考,不要在 today 里重复)"
            } else {
                "## Already Recorded Facts (for reference, do not repeat in today)"
            }
            "$factsLabel\n\n$currentFacts\n\n---\n\n$sessionInput"
        } else {
            sessionInput
        }
        val result = resultOf {
            llmClient.callText(
                systemPrompt = CompilePrompts.buildTodayPrompt(locale),
                userContent = input,
                model = model,
                temperature = 0.3f,
                maxTokens = 450,
            )
        }.onError { msg, t ->
            // v1.78 (M1): 记录 LLM 失败原因,避免静默吞异常
            Logger.w("MemoryCompiler", "compileToday LLM 调用失败: $msg", t)
        }.getOrNull() ?: return@withContext Result.FAILED

        // v1.0.51: 空响应防御 — LLM 返回空/纯标签时不覆盖已有内容,返回 FAILED 供下次重试
        val normalized = CompiledMemoryState.normalizeLlmResult(result, "compileToday")
        if (normalized.isBlank()) {
            Logger.w("MemoryCompiler", "compileToday: LLM 返回空响应,保留旧内容,返回 FAILED")
            return@withContext Result.FAILED
        }
        updateStoredContent(Section.TODAY.key, normalized, fp, Instant.now().toString())
        Result.COMPILED
    }

    /**
     * 编译已结束那天 → memory/daily/{date}.md。
     *
     * v6.1: 输入优先使用前一天最终版 today 草稿([yesterdayTodayDraft])。
     * 草稿缺失时回退到当天 session 摘要编译,保证升级首日/状态丢失时数据不丢。
     * 当天没有任何内容时不产文件(零占位)。
     *
     * @param logicalDate 要编译的逻辑日(yyyy-MM-dd),一般为昨天
     * @param yesterdayTodayDraft 前一天 Room today 段的最终草稿,可为 null
     */
    @Suppress("LongParameterList")
    suspend fun compileDaily(
        summaryManager: SessionSummaryManager,
        logicalDate: String,
        yesterdayTodayDraft: String? = null,
        model: Model?,
        locale: String = "zh-CN",
        timeZone: String = io.zer0.memory.time.TimeContext.DEFAULT_TIMEZONE,
        /**
         * 审查修复 (2.0 B-09): 主助手 id — 兜底路径(无 today 草稿时按 session 摘要编译)
         * 只取主助手摘要,子助手摘要不得经 daily → week → longterm 链串台进主助手注入。
         */
        mainAssistantId: String? = null,
        target: MemoryCompileTarget? = null,
    ): Result = withContext(Dispatchers.IO) {
        val assistantId = target?.assistantId ?: mainAssistantId
        if (fileWriter == null) return@withContext Result.SKIPPED

        val date = runCatching { LocalDate.parse(logicalDate) }.getOrNull()
            ?: return@withContext Result.SKIPPED

        val zone = io.zer0.memory.time.TimeContext.resolveTimeZone(timeZone)
        val dayStart = date.atStartOfDay(zone)
            .plusHours(io.zer0.memory.time.TimeContext.LOGICAL_DAY_CUTOVER_HOUR.toLong())
            .toInstant()
        val dayEnd = dayStart.plus(1, ChronoUnit.DAYS)

        // 优先使用 yesterday 的 today 草稿;缺失则回落到当天 session 摘要
        val input = when {
            !yesterdayTodayDraft.isNullOrBlank() -> yesterdayTodayDraft.trim()
            else -> {
                val sessions = summaryManager.getSummariesInRange(
                    start = dayStart,
                    end = dayEnd,
                    mainAssistantId = assistantId,
                    spaceId = target?.normalizedSpaceId,
                )
                if (sessions.isEmpty()) {
                    fileWriter.deleteDailyMd(logicalDate, target)
                    return@withContext Result.COMPILED
                }
                // B-09: 兜底路径同样过滤墓碑 — 已删内容不得进入 daily → week → longterm 注入链
                val tombstones = loadTombstones(effectiveTarget(target, assistantId))
                sessions.joinToString("\n\n---\n\n") { filterTombstonedLines(it.summary, tombstones) }
            }
        }

        if (input.isBlank()) {
            fileWriter.deleteDailyMd(logicalDate, target)
            return@withContext Result.COMPILED
        }

        val fp = fingerprint(input)
        val existingFp = fileWriter.readDailyFingerprint(logicalDate, target)
        if (existingFp == fp && fileWriter.readDailyEntryBody(logicalDate, target).isNotBlank()) {
            return@withContext Result.SKIPPED
        }

        val result = resultOf {
            llmClient.callText(
                systemPrompt = CompilePrompts.buildDailyPrompt(locale),
                userContent = input,
                model = model,
                temperature = 0.3f,
                // v1.0.51: 100 → 250 — 100 token 对中文约 50-70 字,作为一天的定稿过短,
                // 会导致 daily → week → longterm 链路丢失过多细节。
                // 250 token 约 120-175 字,与 week 的 1200 字符窗口(6天 × 200字)匹配
                maxTokens = 250,
            )
        }.onError { msg, t ->
            Logger.w("MemoryCompiler", "compileDaily($logicalDate) LLM 调用失败: $msg", t)
        }.getOrNull() ?: return@withContext Result.FAILED

        // v1.0.51: 空响应防御 — 不写空 daily 文件,返回 FAILED 供下次重试
        val normalized = CompiledMemoryState.normalizeLlmResult(result, "compileDaily")
        if (normalized.isBlank()) {
            Logger.w("MemoryCompiler", "compileDaily($logicalDate): LLM 返回空响应,返回 FAILED")
            return@withContext Result.FAILED
        }
        fileWriter.writeDailyMd(logicalDate, normalized, target)
        fileWriter.writeDailyFingerprint(logicalDate, fp, target)
        Result.COMPILED
    }

    /**
     * 从 daily/ 目录纯文件装配 week.md(零 LLM)。
     * 取最近 N 天日记条目按日期正序拼接,超长时从最老条目截断。
     */
    suspend fun assembleWeekFromDaily(
        maxDays: Int = MemoryFileWriter.DAILY_WINDOW_RETENTION_DAYS,
        maxChars: Int = MemoryFileWriter.WEEK_ASSEMBLY_MAX_CHARS,
        target: MemoryCompileTarget? = null,
    ): Result = withContext(Dispatchers.IO) {
        if (fileWriter == null) {
            // 无文件 writer 时清空 week 段,避免残留旧内容
            val current = readSection(Section.WEEK)
            if (current.isNotEmpty()) {
                updateStoredContent(Section.WEEK.key, "", null, Instant.now().toString())
            }
            return@withContext Result.COMPILED
        }

        val assembled = fileWriter.assembleWeekFromDaily(maxDays, maxChars, target).trim()
        if (assembled.isEmpty()) {
            val current = readSection(Section.WEEK)
            if (current.isNotEmpty()) {
                updateStoredContent(Section.WEEK.key, "", null, Instant.now().toString())
            }
            return@withContext Result.COMPILED
        }

        val fp = fingerprint(assembled)
        val existing = readStoredSection(Section.WEEK.key)
        if (existing?.fingerprint == fp && existing.content.isNotEmpty()) {
            return@withContext Result.SKIPPED
        }

        updateStoredContent(Section.WEEK.key, assembled, fp, Instant.now().toString())
        Result.COMPILED
    }

    /**
     * 编译 week: 优先从 daily/ 目录零 LLM 装配;
     * 无 daily 文件时回退到旧路径(按 7 天 session 摘要 LLM 编译),保证升级首日有内容。
     */
    suspend fun compileWeek(
        summaryManager: SessionSummaryManager,
        model: Model?,
        locale: String = "zh-CN",
        timeZone: String = io.zer0.memory.time.TimeContext.DEFAULT_TIMEZONE,
        target: MemoryCompileTarget? = null,
    ): Result = withContext(Dispatchers.IO) {
        val assistantId = target?.assistantId
        val assembledResult = assembleWeekFromDaily(target = target)
        if (assembledResult == Result.COMPILED || assembledResult == Result.SKIPPED) {
            val currentWeek = readSection(Section.WEEK)
            if (currentWeek.isNotBlank()) return@withContext assembledResult
        }

        // 回退路径:无 daily 文件时按 7 天 session 摘要 LLM 编译(旧行为)
        val now = Instant.now()
        val zone = io.zer0.memory.time.TimeContext.resolveTimeZone(timeZone)
        val logicalDay = io.zer0.memory.time.TimeContext.logicalDayFor(now, zone)
        val sevenDaysAgo = logicalDay.rangeStart.minus(7, ChronoUnit.DAYS)
        val sessions = summaryManager.getSummariesInRange(
            start = sevenDaysAgo,
            end = now,
            mainAssistantId = assistantId,
            spaceId = target?.normalizedSpaceId,
        )

        if (sessions.isEmpty()) {
            val current = readSection(Section.WEEK)
            if (current.isNotEmpty()) {
                updateStoredContent(Section.WEEK.key, "", null, Instant.now().toString())
            }
            return@withContext Result.COMPILED
        }

        val fpKeys = sessions.joinToString("\n") { "${it.sessionId}:${it.updatedAt}" }
        val fp = fingerprint(fpKeys)
        val existing = readStoredSection(Section.WEEK.key, target)
        if (existing?.fingerprint == fp && existing.content.isNotEmpty()) {
            return@withContext Result.SKIPPED
        }

        val tombstones = loadTombstones(effectiveTarget(target, assistantId))
        val input = sessions.joinToString("\n\n---\n\n") { filterTombstonedLines(it.summary, tombstones) }
        val result = resultOf {
            llmClient.callText(
                systemPrompt = CompilePrompts.buildWeekPrompt(locale),
                userContent = input,
                model = model,
                temperature = 0.3f,
                maxTokens = 600,
            )
        }.onError { msg, t ->
            Logger.w("MemoryCompiler", "compileWeek LLM 调用失败: $msg", t)
        }.getOrNull() ?: return@withContext Result.FAILED

        // v1.0.51: 空响应防御 — 不覆盖已有 week,不写指纹(避免锁死),返回 FAILED 供下次重试
        val normalized = CompiledMemoryState.normalizeLlmResult(result, "compileWeek")
        if (normalized.isBlank()) {
            Logger.w("MemoryCompiler", "compileWeek: LLM 返回空响应,保留旧内容,返回 FAILED")
            return@withContext Result.FAILED
        }
        updateStoredContent(Section.WEEK.key, normalized, fp, Instant.now().toString())
        Result.COMPILED
    }

    /**
     * 把滚出 N 日窗口的 daily 条目 fold 进 longterm,成功后删除源文件;
     * 失败的条目保留在 daily/ 目录,交给下一轮重试,不静默丢弃。
     */
    suspend fun rollDailyWindow(
        model: Model?,
        locale: String = "zh-CN",
        referenceDate: String = LocalDate.now().toString(),
        target: MemoryCompileTarget? = null,
    ): Result = withContext(Dispatchers.IO) {
        if (fileWriter == null) return@withContext Result.SKIPPED

        val roll = fileWriter.rollDailyWindow(referenceDate, target = target)
        if (roll.combinedContent.isBlank()) {
            return@withContext Result.COMPILED
        }

        val result = foldIntoLongTerm(roll.combinedContent, model, locale)
        // v1.0.51: 仅 COMPILED 时删除源文件 — FAILED 时保留供下次重试,SKIPPED(指纹命中)时
        //   longterm 已包含相同内容,可安全删除
        if (result == Result.COMPILED || result == Result.SKIPPED) {
            fileWriter.deleteDailyFiles(roll.folded, target)
        }
        result
    }

    /**
     * 编译 longterm: week.md fold 进 longterm.md。
     * fingerprint = fingerprint(weekContent),week 没变就跳过。
     */
    suspend fun compileLongterm(model: Model?, locale: String = "zh-CN"): Result =
        foldIntoLongTerm(readSection(Section.WEEK), model, locale)

    private suspend fun foldIntoLongTerm(newContent: String, model: Model?, locale: String = "zh-CN"): Result =
        withContext(Dispatchers.IO) {
            val trimmed = newContent.trim()
            if (trimmed.isBlank()) return@withContext Result.SKIPPED

            val fp = fingerprint(trimmed)
            val existing = readStoredSection(Section.LONGTERM.key)
            if (existing?.fingerprint == fp && existing.content.isNotEmpty()) {
                return@withContext Result.SKIPPED
            }

            val prevLongterm = readSection(Section.LONGTERM).trim()
            // 审查修复 (B-21): 截断上限 2000 → 4000,减轻长期知识不可逆丢弃。
            // 每次 fold 时旧内容最多保留 4000 字符,给新内容留足 LLM 输出空间
            // (maxTokens=600 约 2400 字符);被裁剪部分附到"裁剪记录"日志,便于追溯丢失知识。
            // 取舍说明见任务报告: 未改动 buildLongtermPrompt 的 LLM 契约,仅提高上限 + 记录裁剪。
            val prevLongtermCapped = if (prevLongterm.length > 4000) {
                val trimmedTail = prevLongterm.takeLast(prevLongterm.length - 4000)
                Logger.d(
                    "MemoryCompiler",
                    "foldIntoLongTerm: 截断旧 longterm(${prevLongterm.length} → 4000 chars),丢失 ${trimmedTail.length} chars: ${trimmedTail.take(
                        500,
                    )}…",
                )
                prevLongterm.take(4000)
            } else {
                prevLongterm
            }
            val isZh = locale.startsWith("zh")
            val input = if (prevLongtermCapped.isNotBlank()) {
                val prevLabel = if (isZh) "## 上一份长期情况" else "## Previous long-term context"
                val newLabel = if (isZh) "## 新沉淀内容" else "## Newly settled content"
                "$prevLabel\n\n$prevLongtermCapped\n\n$newLabel\n\n$trimmed"
            } else {
                val newLabel = if (isZh) "## 新沉淀内容" else "## Newly settled content"
                "$newLabel\n\n$trimmed"
            }

            val result = resultOf {
                llmClient.callText(
                    systemPrompt = CompilePrompts.buildLongtermPrompt(locale),
                    userContent = input,
                    model = model,
                    temperature = 0.3f,
                    maxTokens = 600,
                )
            }.onError { msg, t ->
                Logger.w("MemoryCompiler", "foldIntoLongTerm LLM 调用失败: $msg", t)
            }.getOrNull() ?: return@withContext Result.FAILED

            // v1.0.51: 空响应防御 — 不覆盖已有 longterm,不写指纹(避免锁死),返回 FAILED
            val normalized = CompiledMemoryState.normalizeLlmResult(result, "compileLongterm")
            if (normalized.isBlank()) {
                Logger.w("MemoryCompiler", "foldIntoLongTerm: LLM 返回空响应,保留旧内容,返回 FAILED")
                return@withContext Result.FAILED
            }
            updateStoredContent(Section.LONGTERM.key, normalized, fp, Instant.now().toString())
            Result.COMPILED
        }

    /**
     * 编译 facts: 30 天摘要的 facts 段 → facts.md。
     * 不用指纹(每次都跑,但输入包含 prevFacts,LLM 自行合并)。
     *
     * v0.32: 接入 [MemoryConfig.compileThreshold] —— 按 session 年龄计算衰减分数,
     * 分数低于阈值的 session 的 fact 段不进入 LLM 输入(低分记忆被过滤)。
     * 默认 [MemoryConfig] 下默认阈值 4.5 < 默认 baseImportance 10,30 天内不会过滤,
     * 等价于旧行为;用户调高阈值或调低 baseImportance 才会生效。
     *
     * @param config 记忆配置(由 [io.zer0.memory.ticker.MemoryTicker] 透传)
     */
    // v2.6.2: 增加 spaceId 过滤后函数体达到 80 行(阈值边界)，且分支数上升；与既有
    // CyclomaticComplexMethod 豁免同因(编译管道职责集中)，按项目惯例按点豁免。
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    suspend fun compileFacts(
        summaryManager: SessionSummaryManager,
        model: Model?,
        locale: String = "zh-CN",
        config: MemoryConfig = MemoryConfig(),
        /**
         * A-19: 主助手 id — 非 null 时只编译主助手(及无归属旧数据)的摘要,
         * 子助手会话摘要不得串台进入主助手注入的"重要事实"段。
         */
        mainAssistantId: String? = null,
        target: MemoryCompileTarget? = null,
    ): Result = withContext(Dispatchers.IO) {
        val assistantId = target?.assistantId ?: mainAssistantId
        val resolvedTarget = effectiveTarget(target, assistantId)
        val store = factStoreFor(resolvedTarget)
        val now = Instant.now()
        // L4: 这里用绝对时间 now-30d 而非逻辑日对齐(与 compileWeek 不同)。
        // 原因: compileFacts 是 30 天的滑动窗口,窗口长(30 天),跨日边界归属偏差
        // 在大窗口下影响可忽略;而 compileWeek 窗口仅 7 天,跨日边界偏差相对更大,
        // 故 compileWeek 用 logicalDay.rangeStart 对齐 04:00 切日。此处无需对齐。
        val thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS)
        val sessions = summaryManager.getSummariesInRange(
            start = thirtyDaysAgo,
            end = now,
            mainAssistantId = assistantId,
            spaceId = resolvedTarget.normalizedSpaceId,
        )

        // 从每个摘要提取 facts 段
        // v0.32: 同时按 (updatedAt 年龄 + config) 计算分数,过滤掉低于 compileThreshold 的 session
        // S-04: 墓碑过滤 — 已删事实从旧产物/摘要候选/LLM 输出三路剔除,防"复活"
        val tombstones = loadTombstones(resolvedTarget)
        val rawPrevFacts = readSection(Section.FACTS, target ?: MemoryCompileTarget(assistantId = assistantId)).trim()
        val prevFacts = filterTombstonedLines(rawPrevFacts, tombstones)
        if (prevFacts != rawPrevFacts) {
            // 删除即刻生效: 先剔除旧编译产物,无需等待 LLM 重编
            updateStoredContent(Section.FACTS.key, prevFacts, null, Instant.now().toString(), target)
        }

        val factParts = mutableListOf<String>()
        var skippedByThreshold = 0
        for (s in sessions) {
            if (s.summary.isBlank()) continue
            if (!RollingSummaryFormat.hasFactSectionHeading(s.summary)) continue
            val text = RollingSummaryFormat.extractFactSection(s.summary)
            if (text.isNotBlank() && !RollingSummaryFormat.isEmptyFactSection(text)) {
                val ageDays = ageInDays(s.updatedAt, now)
                val score = MemoryConfig.factScore(ageDays, config)
                if (!MemoryConfig.shouldCompile(score, config)) {
                    skippedByThreshold++
                    continue
                }
                // S-04: 摘要事实段内命中墓碑的行剔除;全被剔除则该摘要不进入候选
                val filtered = filterTombstonedLines(text, tombstones)
                if (filtered.isBlank()) continue
                factParts.add(filtered)
            }
        }
        if (skippedByThreshold > 0) {
            // v1.78 (M2): 记录被阈值过滤的 session 数,便于调试 compileThreshold 配置
            Logger.d("MemoryCompiler", "compileFacts: $skippedByThreshold sessions skipped by threshold")
        }

        if (factParts.isEmpty()) {
            // 没有新事实: 保留旧 facts(已按墓碑过滤;全部被删时上方已清空)
            return@withContext Result.COMPILED
        }

        val isZh = locale.startsWith("zh")
        val newFacts = factParts.joinToString("\n")
        val combined = if (prevFacts.isNotBlank()) {
            val existingLabel = if (isZh) "## 现有 Facts" else "## Existing Facts"
            val newLabel = if (isZh) "## 新增候选 Facts" else "## New Candidate Facts"
            "$existingLabel\n\n$prevFacts\n\n$newLabel\n\n$newFacts"
        } else {
            val newLabel = if (isZh) "## 新增候选 Facts" else "## New Candidate Facts"
            "$newLabel\n\n$newFacts"
        }

        val result = resultOf {
            llmClient.callText(
                systemPrompt = CompilePrompts.buildFactsPrompt(locale),
                userContent = combined,
                model = model,
                temperature = 0.3f,
                maxTokens = 300,
            )
        }.onError { msg, t ->
            // v1.78 (M1): 记录 LLM 失败原因
            Logger.w("MemoryCompiler", "compileFacts LLM 调用失败: $msg", t)
        }.getOrNull() ?: return@withContext Result.FAILED

        // v1.0.51: 空响应防御 — 不覆盖已有 facts,返回 FAILED 供下次重试
        val normalized = CompiledMemoryState.normalizeLlmResult(result, "compileFacts")
        if (normalized.isBlank()) {
            Logger.w("MemoryCompiler", "compileFacts: LLM 返回空响应,保留旧 facts,返回 FAILED")
            return@withContext Result.FAILED
        }
        // S-04: LLM 输出再过滤一遍(防 LLM 复述已删事实)
        val filteredResult = filterTombstonedLines(normalized, tombstones)
        updateStoredContent(Section.FACTS.key, filteredResult, null, Instant.now().toString(), resolvedTarget)
        // D3-P2: 候选吸收 + 确定性投影 — LLM 产物先作为候选补录进表(防摘要孤儿丢失),
        // 随后 FACTS 段由 facts 表投影覆盖(编辑/删除即刻一致)。
        store?.let { scopedStore ->
            val scope = resolvedTarget.normalizedScope
            val spaceId = resolvedTarget.normalizedSpaceId
            resultOf { absorbFactLines(filteredResult, scope, spaceId, tombstones, scopedStore) }
            resultOf { scopedStore.getByScopeAndSpace(scope, spaceId) }
                .onSuccess { resultOf { reconcileFactsSectionWithStore(it, resolvedTarget) } }
                .onError { msg, t -> Logger.w("MemoryCompiler", "compileFacts 投影失败(保留 LLM 产物): ${t?.message ?: msg}") }
        }
        Result.COMPILED
    }

    /**
     * D3-P2: FACTS 段与 facts 表收敛 — 对账升级为「确定性投影」。
     *
     * 流程:
     *  1) 吸收: 当前段中不在事实表的行(编译产物孤儿)补录进表(防投影后丢失);
     *  2) 投影: 用最新表数据渲染 FACTS 段(每行一条,与既有 LLM 产物格式对齐)。
     *
     * 至此"编辑/删除/新增"均在下一轮投影即刻一致;v12 的逐行对账语义退役,
     * 墓碑过滤仍作为吸收/投影的输入过滤保留(S-04)。
     *
     * @return 1 = 段内容有更新;0 = 无变化(含空表/无内容)
     */
    suspend fun reconcileFactsSectionWithStore(facts: List<io.zer0.memory.fact.FactStore.Fact>, target: MemoryCompileTarget? = null): Int =
        withContext(Dispatchers.IO) {
            val resolvedTarget = effectiveTarget(target)
            val tombstones = loadTombstones(resolvedTarget)
            val scope = resolvedTarget.normalizedScope
            val spaceId = resolvedTarget.normalizedSpaceId
            val store = factStoreFor(resolvedTarget)

            // 1) 吸收段孤儿(factStore 可用时;墓碑命中行跳过,防"删除复活")
            if (store != null) {
                val currentForAbsorb = readSection(Section.FACTS, resolvedTarget)
                if (currentForAbsorb.isNotBlank()) {
                    absorbFactLines(currentForAbsorb, scope, spaceId, tombstones, store)
                }
            }

            // 2) 投影(factStore 可用时重查最新表;否则退回传入快照,兼容无 store 场景)
            val source = if (store != null) {
                resultOf { store.getByScopeAndSpace(scope, spaceId) }.getOrNull() ?: return@withContext 0
            } else {
                facts
            }
            val current = readSection(Section.FACTS, resolvedTarget)
            if (source.isEmpty()) {
                if (current.isBlank()) return@withContext 0
                clearStoredByKey(Section.FACTS.key, Instant.now().toString(), resolvedTarget)
                Logger.i("MemoryCompiler", "FACTS 段已清空: 事实表为空(scope=$scope, space=$spaceId)")
                return@withContext 1
            }
            val projected = FactsSectionProjector.project(
                source.filter { filterTombstonedLines(it.fact, tombstones).isNotBlank() },
            )
            if (projected.isBlank()) {
                if (current.isBlank()) return@withContext 0
                clearStoredByKey(Section.FACTS.key, Instant.now().toString(), target)
                Logger.i("MemoryCompiler", "FACTS 段已清空: 全部事实被墓碑过滤(scope=$scope, space=$spaceId)")
                return@withContext 1
            }
            if (projected.trim() == current.trim()) return@withContext 0
            // 用 @Insert(REPLACE) 而非 updateContent(UPSERT 语法在部分测试 SQLite 版本报错);
            // REPLACE 对无外键的 compiled_sections 语义一致(冲突时删除重建)。
            upsertStored(
                key = Section.FACTS.key,
                content = projected,
                fingerprint = null,
                now = Instant.now().toString(),
                target = resolvedTarget,
            )
            Logger.i("MemoryCompiler", "FACTS 段确定性投影: ${projected.lines().size} 条 / ${projected.length} 字符")
            1
        }

    /**
     * D3-P2: 把文本行中不在事实表的条目补录进表(防"仅在编译产物、未入表"的事实
     * 在投影后丢失 — 摘要孤儿的兼容补丁)。
     *
     * 过滤: 空行 / # 标题行 / 墓碑命中行(防删除复活) / 超长行(> [MAX_ABSORB_LINE_CHARS]
     * 视为段落弃用);去 "- " 前缀;与表内条目归一化等价的行跳过(防重复)。
     * 吸收条目的 source = "compiled",继承 scope/space。
     *
     * @return 实际吸收条数
     */
    private suspend fun absorbFactLines(text: String, scope: String, spaceId: String, tombstones: List<String>, store: FactStore?): Int {
        store ?: return 0
        val existing = resultOf { store.getByScopeAndSpace(scope, spaceId) }
            .getOrNull()?.map { normalizeLine(it.fact) }?.toSet() ?: return 0
        var absorbed = 0
        val seen = mutableSetOf<String>()
        for (raw in text.lines()) {
            val trimmed = raw.trim().removePrefix("-").trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (trimmed.length > MAX_ABSORB_LINE_CHARS) continue
            if (filterTombstonedLines(trimmed, tombstones).isBlank()) continue
            val norm = normalizeLine(trimmed)
            if (norm.isBlank() || norm in existing || !seen.add(norm)) continue
            resultOf {
                store.add(
                    io.zer0.memory.fact.FactStore.Fact(fact = trimmed, source = "compiled"),
                    scope = scope,
                    spaceId = spaceId,
                )
            }.onSuccess { absorbed++ }
                .onError { msg, t -> Logger.w("MemoryCompiler", "候选吸收失败: ${t?.message ?: msg}") }
        }
        if (absorbed > 0) {
            Logger.i("MemoryCompiler", "FACTS 候选吸收: $absorbed 条编译产物事实补录入表")
        }
        return absorbed
    }

    /** v12: 归一化相等比较(去主语 + 大小写 + 全半角 + 空白,用于产物行与 facts 表条目匹配)。 */
    private fun normalizedEq(a: String, b: String): Boolean {
        val na = normalizeLine(a)
        val nb = normalizeLine(b)
        return na.isNotBlank() && na == nb
    }

    private fun normalizeLine(text: String): String {
        return text.trim().lowercase()
            .replace(Regex("^(用户|我|他|她|这个用户|the user|i am|he is|she is)\\s*"), "")
            .trim()
            .toHalfWidth()
            .replace(whiteSpaceRe, " ")
            .trim()
    }

    /**
     * v12: 全角转半角(字母/数字/常用符号),用于产物行与 facts 表条目跨写法匹配。
     * 全角空格(U+3000)转为普通空格,由 whiteSpaceRe 统一压缩。
     */
    private fun String.toHalfWidth(): String {
        val sb = StringBuilder(length)
        for (ch in this) {
            val code = ch.code
            when {
                code in 0xFF01..0xFF5E -> sb.append((code - 0xFEE0).toChar())
                code == 0x3000 -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** v12: 压缩连续空白(产物行匹配用)。 */
    private val whiteSpaceRe = Regex("\\s+")

    /** 清空所有编译产物(记忆重置用)。 */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        clearStoredAll(Instant.now().toString())
    }

    /**
     * P2: 清空指定 section 的内容(用于记忆页 UI 删除 Compile 层单段)。
     * 不删除行,只把 content/fingerprint 清空,保持下次编译可直接 upsert。
     */
    suspend fun clearSection(section: Section) = withContext(Dispatchers.IO) {
        clearStoredByKey(section.key, Instant.now().toString())
    }

    /**
     * P2: 直接写入指定 section 的内容(用于记忆页 UI 编辑 Compile 层单段)。
     * 清空 fingerprint,使下次定时编译能正常重新生成。
     */
    suspend fun writeSection(section: Section, content: String) = withContext(Dispatchers.IO) {
        upsertStored(
            key = section.key,
            content = content,
            fingerprint = null,
            now = Instant.now().toString(),
        )
    }

    /** P2-33: 按显式 scope + space 直接写入指定 section(记忆页按当前选中 scope/space 编辑用)。
     *  原 writeSection 依赖编译上下文的 currentScope/currentSpaceId(默认 main/default),
     *  子助手/其他 space 的编译段被 UI 编辑时写错槽位,与注入读路径(带 scope/space)错位。 */
    suspend fun writeSection(section: Section, content: String, scope: String, spaceId: String) = withContext(Dispatchers.IO) {
        upsertStored(
            key = section.key,
            content = content,
            fingerprint = null,
            now = Instant.now().toString(),
            target = MemoryCompileTarget(
                scope = scope,
                spaceId = spaceId,
            ),
        )
    }

    /** 拼装 memory.md(4 个 ## 标题段,空段写占位符)。 */
    private fun assembleCompiledMarkdown(facts: String, today: String, week: String, longterm: String, locale: String = "zh-CN"): String {
        val isZh = locale.startsWith("zh")
        val empty = if (isZh) "（暂无）" else "(none)"
        val factsTitle = if (isZh) "重要事实" else "Key facts"
        val todayTitle = if (isZh) "今天" else "Today"
        val weekTitle = if (isZh) "本周早些时候" else "Earlier this week"
        val longtermTitle = if (isZh) "长期情况" else "Long-term context"
        val section = { title: String, content: String ->
            "## $title\n\n${content.ifBlank { empty }}"
        }
        return listOf(
            section(factsTitle, facts),
            section(todayTitle, today),
            section(weekTitle, week),
            section(longtermTitle, longterm),
        ).joinToString("\n\n") + "\n"
    }

    private fun fingerprint(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * 把 session 的 updatedAt(ISO 字符串)换算成距 [now] 的天数。
     * 解析失败或时间反转时返回 0(等价于"刚发生",score 最高,不会被阈值过滤)。
     */
    private fun ageInDays(updatedAtIso: String, now: Instant): Float {
        val updated = runCatching { Instant.parse(updatedAtIso) }.getOrNull() ?: return 0f
        val ms = now.toEpochMilli() - updated.toEpochMilli()
        if (ms < 0) return 0f
        return ms / (1000f * 60 * 60 * 24)
    }
}
