package io.zer0.memory.compile

// NOTE-i18n: section 标题(重要事实/今天等)参与 LLM 输出解析契约,需契约与文案分离架构改动后才能提取。

import io.zer0.ai.core.Model
import io.zer0.common.Logger
import io.zer0.common.resultOf
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
 * 记忆编译器 (openhanako compile.ts 移植)。
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
class MemoryCompiler(
    private val sectionDao: CompiledSectionDao,
    private val llmClient: MemoryLlmClient,
    /** v6: 文件化输出,为 null 时不写文件(便于测试)。 */
    private val fileWriter: MemoryFileWriter? = null,
) {

    /** 四块 section key。 */
    enum class Section(val key: String) {
        FACTS("facts"),
        TODAY("today"),
        WEEK("week"),
        LONGTERM("longterm");

        companion object {
            val ALL = listOf(FACTS, TODAY, WEEK, LONGTERM)
        }
    }

    /** 编译结果。 */
    enum class Result { COMPILED, SKIPPED }

    /** 读取某块当前内容。 */
    suspend fun readSection(section: Section): String = withContext(Dispatchers.IO) {
        sectionDao.get(section.key)?.content ?: ""
    }

    /** 读取四块拼装后的 memory.md(注入 system prompt 用)。 */
    suspend fun readCompiledMemoryMarkdown(locale: String = "zh-CN"): String = withContext(Dispatchers.IO) {
        val facts = CompiledMemoryState.normalizeSectionBody(readSection(Section.FACTS))
        val today = CompiledMemoryState.normalizeSectionBody(readSection(Section.TODAY))
        val week = CompiledMemoryState.normalizeSectionBody(readSection(Section.WEEK))
        val longterm = CompiledMemoryState.normalizeSectionBody(readSection(Section.LONGTERM))
        val md = buildCompiledMarkdown(facts, today, week, longterm, locale)
        // v6: 同时输出到文件系统,便于调试和备份
        fileWriter?.writeMemoryMd(md, locale)
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
    ): Result = withContext(Dispatchers.IO) {
        val zone = io.zer0.memory.time.TimeContext.resolveTimeZone(timeZone)
        val logicalDay = io.zer0.memory.time.TimeContext.getLogicalDay(Instant.now(), zone)
        val sessions = summaryManager.getSummariesInRange(
            start = logicalDay.rangeStart,
            end = Instant.now(),
        )

        if (sessions.isEmpty()) {
            // 空 sessions: 清空内容,不写指纹
            val current = readSection(Section.TODAY)
            if (current.isNotEmpty()) {
                sectionDao.updateContent(Section.TODAY.key, "", null, Instant.now().toString())
            }
            return@withContext Result.COMPILED
        }

        // 指纹: sessions 的 (id, updated_at) 拼接 md5
        val fpKeys = sessions.joinToString("\n") { "${it.sessionId}:${it.updatedAt}" }
        val fp = md5(fpKeys)
        val existing = sectionDao.get(Section.TODAY.key)
        if (existing?.fingerprint == fp && existing.content.isNotEmpty()) {
            return@withContext Result.SKIPPED
        }

        val input = sessions.joinToString("\n\n---\n\n") { it.summary }
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
        }.getOrNull() ?: return@withContext Result.SKIPPED

        val normalized = CompiledMemoryState.normalizeLlmResult(result, "compileToday")
        sectionDao.updateContent(Section.TODAY.key, normalized, fp, Instant.now().toString())
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
    suspend fun compileDaily(
        summaryManager: SessionSummaryManager,
        logicalDate: String,
        yesterdayTodayDraft: String? = null,
        model: Model?,
        locale: String = "zh-CN",
        timeZone: String = io.zer0.memory.time.TimeContext.DEFAULT_TIMEZONE,
    ): Result = withContext(Dispatchers.IO) {
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
                val sessions = summaryManager.getSummariesInRange(start = dayStart, end = dayEnd)
                if (sessions.isEmpty()) {
                    fileWriter.deleteDailyMd(logicalDate)
                    return@withContext Result.COMPILED
                }
                sessions.joinToString("\n\n---\n\n") { it.summary }
            }
        }

        if (input.isBlank()) {
            fileWriter.deleteDailyMd(logicalDate)
            return@withContext Result.COMPILED
        }

        val fp = md5(input)
        val existingFp = fileWriter.readDailyFingerprint(logicalDate)
        if (existingFp == fp && fileWriter.readDailyEntryBody(logicalDate).isNotBlank()) {
            return@withContext Result.SKIPPED
        }

        val result = resultOf {
            llmClient.callText(
                systemPrompt = CompilePrompts.buildDailyPrompt(locale),
                userContent = input,
                model = model,
                temperature = 0.3f,
                maxTokens = 100,
            )
        }.onError { msg, t ->
            Logger.w("MemoryCompiler", "compileDaily($logicalDate) LLM 调用失败: $msg", t)
        }.getOrNull() ?: return@withContext Result.SKIPPED

        val normalized = CompiledMemoryState.normalizeLlmResult(result, "compileDaily")
        fileWriter.writeDailyMd(logicalDate, normalized)
        fileWriter.writeDailyFingerprint(logicalDate, fp)
        Result.COMPILED
    }

    /**
     * 从 daily/ 目录纯文件装配 week.md(零 LLM)。
     * 取最近 N 天日记条目按日期正序拼接,超长时从最老条目截断。
     */
    suspend fun assembleWeekFromDaily(
        maxDays: Int = MemoryFileWriter.DAILY_WINDOW_RETENTION_DAYS,
        maxChars: Int = MemoryFileWriter.WEEK_ASSEMBLY_MAX_CHARS,
    ): Result = withContext(Dispatchers.IO) {
        if (fileWriter == null) {
            // 无文件 writer 时清空 week 段,避免残留旧内容
            val current = readSection(Section.WEEK)
            if (current.isNotEmpty()) {
                sectionDao.updateContent(Section.WEEK.key, "", null, Instant.now().toString())
            }
            return@withContext Result.COMPILED
        }

        val assembled = fileWriter.assembleWeekFromDaily(maxDays, maxChars).trim()
        if (assembled.isEmpty()) {
            val current = readSection(Section.WEEK)
            if (current.isNotEmpty()) {
                sectionDao.updateContent(Section.WEEK.key, "", null, Instant.now().toString())
            }
            return@withContext Result.COMPILED
        }

        val fp = md5(assembled)
        val existing = sectionDao.get(Section.WEEK.key)
        if (existing?.fingerprint == fp && existing.content.isNotEmpty()) {
            return@withContext Result.SKIPPED
        }

        sectionDao.updateContent(Section.WEEK.key, assembled, fp, Instant.now().toString())
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
    ): Result = withContext(Dispatchers.IO) {
        val assembledResult = assembleWeekFromDaily()
        if (assembledResult == Result.COMPILED || assembledResult == Result.SKIPPED) {
            val currentWeek = readSection(Section.WEEK)
            if (currentWeek.isNotBlank()) return@withContext assembledResult
        }

        // 回退路径:无 daily 文件时按 7 天 session 摘要 LLM 编译(旧行为)
        val now = Instant.now()
        val zone = io.zer0.memory.time.TimeContext.resolveTimeZone(timeZone)
        val logicalDay = io.zer0.memory.time.TimeContext.getLogicalDay(now, zone)
        val sevenDaysAgo = logicalDay.rangeStart.minus(7, ChronoUnit.DAYS)
        val sessions = summaryManager.getSummariesInRange(start = sevenDaysAgo, end = now)

        if (sessions.isEmpty()) {
            val current = readSection(Section.WEEK)
            if (current.isNotEmpty()) {
                sectionDao.updateContent(Section.WEEK.key, "", null, Instant.now().toString())
            }
            return@withContext Result.COMPILED
        }

        val fpKeys = sessions.joinToString("\n") { "${it.sessionId}:${it.updatedAt}" }
        val fp = md5(fpKeys)
        val existing = sectionDao.get(Section.WEEK.key)
        if (existing?.fingerprint == fp && existing.content.isNotEmpty()) {
            return@withContext Result.SKIPPED
        }

        val input = sessions.joinToString("\n\n---\n\n") { it.summary }
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
        }.getOrNull() ?: return@withContext Result.SKIPPED

        val normalized = CompiledMemoryState.normalizeLlmResult(result, "compileWeek")
        sectionDao.updateContent(Section.WEEK.key, normalized, fp, Instant.now().toString())
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
    ): Result = withContext(Dispatchers.IO) {
        if (fileWriter == null) return@withContext Result.SKIPPED

        val roll = fileWriter.rollDailyWindow(referenceDate)
        if (roll.combinedContent.isBlank()) {
            return@withContext Result.COMPILED
        }

        val result = foldIntoLongterm(roll.combinedContent, model, locale)
        if (result == Result.COMPILED || result == Result.SKIPPED) {
            fileWriter.deleteDailyFiles(roll.folded)
        }
        result
    }

    /**
     * 编译 longterm: week.md fold 进 longterm.md。
     * fingerprint = md5(weekContent),week 没变就跳过。
     */
    suspend fun compileLongterm(
        model: Model?,
        locale: String = "zh-CN",
    ): Result = foldIntoLongterm(readSection(Section.WEEK), model, locale)

    private suspend fun foldIntoLongterm(
        newContent: String,
        model: Model?,
        locale: String = "zh-CN",
    ): Result = withContext(Dispatchers.IO) {
        val trimmed = newContent.trim()
        if (trimmed.isBlank()) return@withContext Result.SKIPPED

        val fp = md5(trimmed)
        val existing = sectionDao.get(Section.LONGTERM.key)
        if (existing?.fingerprint == fp && existing.content.isNotEmpty()) {
            return@withContext Result.SKIPPED
        }

        val prevLongterm = readSection(Section.LONGTERM).trim()
        val isZh = locale.startsWith("zh")
        val input = if (prevLongterm.isNotBlank()) {
            val prevLabel = if (isZh) "## 上一份长期情况" else "## Previous long-term context"
            val newLabel = if (isZh) "## 新沉淀内容" else "## Newly settled content"
            "$prevLabel\n\n$prevLongterm\n\n$newLabel\n\n$trimmed"
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
            Logger.w("MemoryCompiler", "foldIntoLongterm LLM 调用失败: $msg", t)
        }.getOrNull() ?: return@withContext Result.SKIPPED

        val normalized = CompiledMemoryState.normalizeLlmResult(result, "compileLongterm")
        sectionDao.updateContent(Section.LONGTERM.key, normalized, fp, Instant.now().toString())
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
    suspend fun compileFacts(
        summaryManager: SessionSummaryManager,
        model: Model?,
        locale: String = "zh-CN",
        config: MemoryConfig = MemoryConfig(),
    ): Result = withContext(Dispatchers.IO) {
        val now = Instant.now()
        // L4: 这里用绝对时间 now-30d 而非逻辑日对齐(与 compileWeek 不同)。
        // 原因: compileFacts 是 30 天的滑动窗口,窗口长(30 天),跨日边界归属偏差
        // 在大窗口下影响可忽略;而 compileWeek 窗口仅 7 天,跨日边界偏差相对更大,
        // 故 compileWeek 用 logicalDay.rangeStart 对齐 04:00 切日。此处无需对齐。
        val thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS)
        val sessions = summaryManager.getSummariesInRange(start = thirtyDaysAgo, end = now)

        // 从每个摘要提取 facts 段
        // v0.32: 同时按 (updatedAt 年龄 + config) 计算分数,过滤掉低于 compileThreshold 的 session
        val factParts = mutableListOf<String>()
        var skippedByThreshold = 0
        for (s in sessions) {
            if (s.summary.isBlank()) continue
            if (!RollingSummaryFormat.hasFactSectionHeading(s.summary)) continue
            val text = RollingSummaryFormat.extractFactSection(s.summary)
            if (text.isNotBlank() && !RollingSummaryFormat.isEmptyFactSection(text)) {
                val ageDays = sessionAgeDays(s.updatedAt, now)
                val score = MemoryConfig.factScore(ageDays, config)
                if (!MemoryConfig.shouldCompile(score, config)) {
                    skippedByThreshold++
                    continue
                }
                factParts.add(text)
            }
        }
        if (skippedByThreshold > 0) {
            // v1.78 (M2): 记录被阈值过滤的 session 数,便于调试 compileThreshold 配置
            Logger.d("MemoryCompiler", "compileFacts: $skippedByThreshold sessions skipped by threshold")
        }

        val prevFacts = readSection(Section.FACTS).trim()
        if (factParts.isEmpty()) {
            // 没有新事实: 保留旧 facts
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
        }.getOrNull() ?: return@withContext Result.SKIPPED

        val normalized = CompiledMemoryState.normalizeLlmResult(result, "compileFacts")
        sectionDao.updateContent(Section.FACTS.key, normalized, null, Instant.now().toString())
        Result.COMPILED
    }

    /** 清空所有编译产物(记忆重置用)。 */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        sectionDao.clearAll(Instant.now().toString())
    }

    /**
     * P2: 清空指定 section 的内容(用于记忆页 UI 删除 Compile 层单段)。
     * 不删除行,只把 content/fingerprint 清空,保持下次编译可直接 upsert。
     */
    suspend fun clearSection(section: Section) = withContext(Dispatchers.IO) {
        sectionDao.clearByKey(section.key, Instant.now().toString())
    }

    /**
     * P2: 直接写入指定 section 的内容(用于记忆页 UI 编辑 Compile 层单段)。
     * 清空 fingerprint,使下次定时编译能正常重新生成。
     */
    suspend fun writeSection(section: Section, content: String) = withContext(Dispatchers.IO) {
        sectionDao.upsert(
            CompiledSectionEntity(
                sectionKey = section.key,
                content = content,
                fingerprint = null,
                updatedAt = Instant.now().toString(),
            )
        )
    }

    /** 拼装 memory.md(4 个 ## 标题段,空段写占位符)。 */
    private fun buildCompiledMarkdown(
        facts: String,
        today: String,
        week: String,
        longterm: String,
        locale: String = "zh-CN",
    ): String {
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

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * 把 session 的 updatedAt(ISO 字符串)换算成距 [now] 的天数。
     * 解析失败或时间反转时返回 0(等价于"刚发生",score 最高,不会被阈值过滤)。
     */
    private fun sessionAgeDays(updatedAtIso: String, now: Instant): Float {
        val updated = runCatching { Instant.parse(updatedAtIso) }.getOrNull() ?: return 0f
        val ms = now.toEpochMilli() - updated.toEpochMilli()
        if (ms < 0) return 0f
        return ms / (1000f * 60 * 60 * 24)
    }
}
