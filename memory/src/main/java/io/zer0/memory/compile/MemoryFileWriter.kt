package io.zer0.memory.compile

import io.zer0.common.Logger
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * v6: 记忆编译产物文件化输出。
 *
 * 把 [MemoryCompiler] 生成的各段内容同时写到应用私有目录,便于:
 *  - 开发者调试(直接打开文件查看 AI 记住了什么)
 *  - 备份/恢复(文件比 Room DB 更容易迁移)
 *  - 与 openhanako 的 agent-dir/memory.md 保持一致的观测体验
 *
 * 输出结构:
 *  - {baseDir}/memory/memory.md      拼装后的四段记忆
 *  - {baseDir}/memory/daily/{date}.md 按天摘要(openhanako 风格滚动传送带)
 *
 * v6.1: 新增按天滚动传送带辅助方法(list/read/assemble/roll),支持零 LLM 装配 week。
 */
class MemoryFileWriter(
    /** 根目录,一般为 Context.filesDir。 */
    private val baseDir: File,
) {

    private val memoryDir by lazy { File(baseDir, "memory").apply { mkdirs() } }
    private val dailyDir by lazy { File(memoryDir, "daily").apply { mkdirs() } }

    companion object {
        private const val TAG = "MemoryFileWriter"

        /** week 段展示今天之前的 N 个已结束逻辑日;更早的条目 fold 进 longterm。 */
        const val DAILY_WINDOW_RETENTION_DAYS = 6

        /** week.md 硬性总长上限(字符数),与被取代的 LLM week 段体量大致相当。 */
        const val WEEK_ASSEMBLY_MAX_CHARS = 1200

        private val DAILY_FILE_RE = Regex("^(\\d{4}-\\d{2}-\\d{2})\\.md$")
        private val DAILY_HEADING_RE = Regex("^#{1,2}\\s*(\\d{4}-\\d{2}-\\d{2})\\s*$")
    }

    /**
     * 写入拼装后的 memory.md。
     *
     * @param content 完整 markdown 内容(四段记忆)
     * @param locale 语言,仅用于日志
     */
    fun writeMemoryMd(content: String, locale: String = "zh-CN") {
        runCatching {
            val file = File(memoryDir, "memory.md")
            file.writeText(content)
            Logger.d(TAG, "memory.md updated (${content.length} chars, locale=$locale)")
        }.onFailure {
            Logger.w(TAG, "写入 memory.md 失败: ${it.message}", it)
        }
    }

    /** 读取当前 memory.md 内容,不存在或失败返回 null。 */
    fun readMemoryMd(): String? = runCatching {
        val file = File(memoryDir, "memory.md")
        if (file.exists()) file.readText() else null
    }.getOrElse {
        Logger.w(TAG, "读取 memory.md 失败: ${it.message}")
        null
    }

    /**
     * 写入按天摘要 daily/{date}.md。
     *
     * @param date ISO 日期(yyyy-MM-dd)
     * @param content 当天摘要正文(不含日期抬头;调用方会自行加上)
     */
    fun writeDailyMd(date: String, content: String) {
        runCatching {
            val body = content.trim()
            val file = File(dailyDir, "$date.md")
            if (body.isEmpty()) {
                file.writeText("")
            } else {
                file.writeText("## $date\n\n$body\n")
            }
            Logger.d(TAG, "daily/$date.md updated (${body.length} chars)")
        }.onFailure {
            Logger.w(TAG, "写入 daily/$date.md 失败: ${it.message}", it)
        }
    }

    /** 读取指定日期 daily 文件正文(剥离 "## {date}" 抬头),不存在或失败返回空字符串。 */
    fun readDailyEntryBody(date: String): String = runCatching {
        val file = File(dailyDir, "$date.md")
        if (!file.exists()) return@runCatching ""
        normalizeDailyBody(file.readText())
    }.getOrElse {
        Logger.w(TAG, "读取 daily/$date.md 失败: ${it.message}")
        ""
    }

    /** 手动写入单日日记正文(用户编辑/权威改写),格式与 compileDaily 产物一致。 */
    fun writeDailyEntryBody(date: String, body: String) {
        writeDailyMd(date, body)
    }

    /** 列出所有 daily 文件条目,按日期正序。 */
    fun listDailyEntries(): List<DailyEntry> = runCatching {
        dailyDir.listFiles { _, name -> DAILY_FILE_RE.matches(name) }
            ?.map { DailyEntry(it.nameWithoutExtension, it) }
            ?.sortedBy { it.date }
            ?: emptyList()
    }.getOrElse {
        Logger.w(TAG, "列出 daily 文件失败: ${it.message}")
        emptyList()
    }

    /**
     * 列出最近 [maxDays] 条 daily 条目。
     *
     * @param maxDays 最大保留天数,默认 [DAILY_WINDOW_RETENTION_DAYS]
     */
    fun listRecentDailyEntries(maxDays: Int = DAILY_WINDOW_RETENTION_DAYS): List<DailyEntry> {
        return listDailyEntries().takeLast(maxDays)
    }

    /**
     * 从 daily/ 目录纯文件装配 week.md 内容:取最近 N 天日记条目按日期正序拼接。
     * 零 LLM 调用。
     *
     * @param maxDays 窗口天数,默认 [DAILY_WINDOW_RETENTION_DAYS]
     * @param maxChars 总长上限,默认 [WEEK_ASSEMBLY_MAX_CHARS]
     * @return 装配后的 markdown 字符串(空段返回空字符串)
     */
    fun assembleWeekFromDaily(
        maxDays: Int = DAILY_WINDOW_RETENTION_DAYS,
        maxChars: Int = WEEK_ASSEMBLY_MAX_CHARS,
    ): String = runCatching {
        val entries = listRecentDailyEntries(maxDays)
        val blocks = entries.mapNotNull { entry ->
            val text = entry.file.readText().trim()
            text.takeIf { it.isNotEmpty() }
        }
        if (blocks.isEmpty()) return@runCatching ""

        var content = blocks.joinToString("\n\n")
        if (content.length > maxChars) {
            val kept = blocks.toMutableList()
            while (kept.size > 1 && kept.joinToString("\n\n").length > maxChars) {
                kept.removeAt(0)
            }
            content = kept.joinToString("\n\n")
            if (content.length > maxChars) {
                content = content.take(maxChars)
            }
            Logger.w(TAG, "assembleWeekFromDaily: 总长超过上限($maxChars 字),已从最老条目开始截断")
        }
        "$content\n"
    }.getOrElse {
        Logger.w(TAG, "assembleWeekFromDaily 失败: ${it.message}", it)
        ""
    }

    /**
     * 把滚出 N 日窗口的 daily 条目内容合并返回,成功后删除源文件。
     * 调用方应负责将返回的合并内容 fold 进 longterm。
     *
     * @param referenceDate 参考日期(yyyy-MM-dd),默认今天
     * @param retentionDays 保留天数,默认 [DAILY_WINDOW_RETENTION_DAYS]
     * @return 合并后的 markdown 内容(空表示没有需要 fold 的内容)和失败的日期列表
     */
    fun rollDailyWindow(
        referenceDate: String = LocalDate.now().toString(),
        retentionDays: Int = DAILY_WINDOW_RETENTION_DAYS,
    ): RollResult = runCatching {
        val cutoff = runCatching {
            LocalDate.parse(referenceDate).minusDays(retentionDays.toLong()).toString()
        }.getOrElse {
            Logger.w(TAG, "rollDailyWindow: referenceDate 解析失败($referenceDate),跳过")
            return@runCatching RollResult(emptyList(), emptyList(), "")
        }

        val entries = listDailyEntries().filter { it.date < cutoff }
        if (entries.isEmpty()) {
            return@runCatching RollResult(emptyList(), emptyList(), "")
        }

        val blocks = entries.mapNotNull { entry ->
            val text = entry.file.readText().trim()
            text.takeIf { it.isNotEmpty() }?.let { "## ${entry.date}\n\n$it" }
        }

        if (blocks.isEmpty()) {
            // 全是空文件:直接清理
            entries.forEach { runCatching { it.file.delete() } }
            return@runCatching RollResult(entries.map { it.date }, emptyList(), "")
        }

        val combined = blocks.joinToString("\n\n")
        RollResult(entries.map { it.date }, emptyList(), combined)
    }.getOrElse {
        Logger.w(TAG, "rollDailyWindow 失败: ${it.message}", it)
        RollResult(emptyList(), emptyList(), "")
    }

    /** 在 fold 成功后删除指定日期的 daily 文件;失败时保留源文件供下轮重试。 */
    fun deleteDailyFiles(dates: List<String>) {
        dates.forEach { date ->
            runCatching {
                File(dailyDir, "$date.md").delete()
            }.onFailure {
                Logger.w(TAG, "删除 daily/$date.md 失败: ${it.message}")
            }
        }
    }

    /** 删除指定日期 daily 文件。 */
    fun deleteDailyMd(date: String) {
        runCatching {
            File(dailyDir, "$date.md").delete()
            File(dailyDir, "$date.md.fingerprint").delete()
        }.onFailure {
            Logger.w(TAG, "删除 daily/$date.md 失败: ${it.message}")
        }
    }

    /** 读取指定日期 daily 文件的 fingerprint,不存在返回 null。 */
    fun readDailyFingerprint(date: String): String? = runCatching {
        val file = File(dailyDir, "$date.md.fingerprint")
        if (file.exists()) file.readText().trim() else null
    }.getOrElse {
        Logger.w(TAG, "读取 daily/$date.md.fingerprint 失败: ${it.message}")
        null
    }

    /** 写入指定日期 daily 文件的 fingerprint。 */
    fun writeDailyFingerprint(date: String, fingerprint: String) {
        runCatching {
            File(dailyDir, "$date.md.fingerprint").writeText(fingerprint)
        }.onFailure {
            Logger.w(TAG, "写入 daily/$date.md.fingerprint 失败: ${it.message}", it)
        }
    }

    /** 删除指定日期 daily fingerprint 文件。 */
    fun deleteDailyFingerprint(date: String) {
        runCatching {
            File(dailyDir, "$date.md.fingerprint").delete()
        }.onFailure {
            Logger.w(TAG, "删除 daily/$date.md.fingerprint 失败: ${it.message}")
        }
    }

    /** 删除所有 daily 文件及 fingerprint(记忆重置用)。 */
    fun clearAllDailyFiles() {
        runCatching {
            dailyDir.listFiles { _, name ->
                DAILY_FILE_RE.matches(name) || name.endsWith(".md.fingerprint")
            }?.forEach { it.delete() }
        }.onFailure {
            Logger.w(TAG, "清空 daily 文件失败: ${it.message}", it)
        }
    }

    /** 在文件头部追加元信息(生成时间),便于调试。 */
    fun buildHeader(note: String): String {
        return "<!-- Generated: ${Instant.now()} | $note -->\n\n"
    }

    /** 剥离 daily 文件中的 "## {date}" 抬头,返回正文。 */
    private fun normalizeDailyBody(raw: String): String {
        val lines = raw.lines()
        val bodyLines = mutableListOf<String>()
        var skippedHeading = false
        for (line in lines) {
            if (!skippedHeading && DAILY_HEADING_RE.matches(line.trim())) {
                skippedHeading = true
                continue
            }
            bodyLines.add(line)
        }
        return bodyLines.joinToString("\n").trim()
    }

    /** 单日日记文件条目。 */
    data class DailyEntry(
        val date: String,
        val file: File,
    )

    /** 滚动窗口结果。 */
    data class RollResult(
        val folded: List<String>,
        val failed: List<String>,
        val combinedContent: String,
    )
}
