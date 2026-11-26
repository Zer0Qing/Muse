package io.zer0.muse.data.agentbot

import android.content.Context
import android.provider.CalendarContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.zer0.common.Logger
import io.zer0.memory.compile.MemoryCompileTarget
import io.zer0.memory.compile.MemoryCompiler
import io.zer0.memory.dream.MemoryMaintenanceLock
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactEntity
import io.zer0.memory.fact.FactStore
import io.zer0.memory.runtime.DefaultMemoryWriteGate
import io.zer0.memory.runtime.MemoryScopeKey
import io.zer0.memory.runtime.MemoryWriteGate
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.tools.CalendarSemantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.DateFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * 一次夜间整理的完整结果。
 *
 * 报告只落本地：晨间摘要由用户下次打开工作台时读取，
 * [notificationsSent] 恒为 0，表示夜间整理链路从不触碰系统通知。
 */
@Serializable
data class AgentDreamReport(
    val generatedAt: Long = 0L,
    val archivedTemporaryCount: Int = 0,
    val mergedPreferenceCount: Int = 0,
    val nextDaySignals: List<AgentDreamNextDaySignal> = emptyList(),
    val morningSummary: String = "",
    val durationMs: Long = 0L,
    val warnings: List<String> = emptyList(),
    val notificationsSent: Int = 0,
) {
    val hasWork: Boolean
        get() = archivedTemporaryCount > 0 ||
            mergedPreferenceCount > 0 ||
            nextDaySignals.isNotEmpty() ||
            morningSummary.isNotBlank()
}

/** 记忆侧整理能力；生产实现走 [FactDbProvider]，测试用假实现验证纯逻辑。 */
interface AgentDreamMemoryStore {
    /** 把已过显式 TTL 的临时事实写入归档文件并从记忆库删除，返回归档条数。 */
    suspend fun archiveExpiredTemporaryFacts(now: Instant): Int

    /** 合并同类长期偏好，返回实际合并掉的原始事实条数。 */
    suspend fun dedupeLongTermPreferences(): Int
}

/** 明日日程事件。 */
data class AgentDreamCalendarEvent(
    val title: String,
    val startAt: Long,
    val allDay: Boolean = false,
)

/** 明日到期的跟进项。 */
data class AgentDreamWatcherBrief(
    val watcherId: String,
    val title: String,
    val nextCheckAt: Long,
)

/** 仍未完成的长期目标。 */
data class AgentDreamGoalBrief(
    val goalId: String,
    val title: String,
    val progress: Int,
)

/** 次日预研所需的全部外部事实。 */
data class AgentDreamAgenda(
    val calendarEvents: List<AgentDreamCalendarEvent> = emptyList(),
    val dueWatchers: List<AgentDreamWatcherBrief> = emptyList(),
    val openGoals: List<AgentDreamGoalBrief> = emptyList(),
) {
    val isEmpty: Boolean
        get() = calendarEvents.isEmpty() && dueWatchers.isEmpty() && openGoals.isEmpty()
}

/** 明日日程/跟进/目标的读取口。 */
interface AgentDreamAgendaSource {
    suspend fun loadTomorrow(now: Instant, zoneId: ZoneId): AgentDreamAgenda
}

/** 把结构化议程格式化成一条晨间折叠摘要。 */
fun interface AgentDreamSummaryFormatter {
    fun format(agenda: AgentDreamAgenda): String
}

/** 长期偏好的分类判定（纯函数，便于单测与后续扩展）。 */
object AgentDreamPreferenceRules {
    private val PREFERENCE_CATEGORIES = setOf(
        "preference",
        "preferences",
        "habit",
        "habits",
        "interest",
        "interests",
        "like",
        "likes",
    )
    private val PREFERENCE_TAGS = setOf(
        "preference",
        "preferences",
        "habit",
        "长期偏好",
        "偏好",
        "习惯",
    )

    fun isLongTermPreference(category: String, tags: List<String>): Boolean {
        val normalizedCategory = category.trim().lowercase()
        if (normalizedCategory in PREFERENCE_CATEGORIES) return true
        return tags.any { tag -> tag.trim().lowercase() in PREFERENCE_TAGS }
    }

    private val WHITESPACE_RUN = Regex("\\s+")
    private val TRAILING_PUNCTUATION = charArrayOf('。', '.', '！', '!', '？', '?', '；', ';', '，', ',')

    /**
     * 偏好的文本等价判定基准：只忽略排版差异（空白、大小写、句末标点）。
     *
     * 不能再用相似度阈值："喜欢喝咖啡" 与 "喜欢喝茶，不加糖" 的 bigram 相似度不高，
     * 但 "喜欢用深色主题" 与 "喜欢用深色主题，字号调大" 会被判为相似 —— 合并后者就丢了独有条件。
     */
    fun normalizedText(text: String): String {
        val collapsed = text.trim().lowercase().replace(WHITESPACE_RUN, " ")
        if (collapsed.isEmpty()) return collapsed
        // 汉字之间的空格只是排版；英文词间空格是语义，保留。
        val withoutHanSpaces = buildString(collapsed.length) {
            collapsed.forEachIndexed { index, char ->
                val betweenHan = char == ' ' && index > 0 && index < collapsed.lastIndex &&
                    isHan(collapsed[index - 1]) && isHan(collapsed[index + 1])
                if (!betweenHan) append(char)
            }
        }
        return withoutHanSpaces.trimEnd(*TRAILING_PUNCTUATION).trim()
    }

    private fun isHan(char: Char): Boolean =
        char in '\u3400'..'\u4DBF' || char in '\u4E00'..'\u9FFF' || char in '\uF900'..'\uFAFF'

    /** 同一簇里按文本等价分组；只有完全等价的子组才允许合并。 */
    fun equivalentClusters(facts: List<FactStore.Fact>): List<List<FactStore.Fact>> =
        facts.groupBy { normalizedText(it.fact) }
            .values
            .filter { cluster -> cluster.size >= 2 }
}

/** 昼夜边界：以中午为界，凌晨执行仍归入前一晚，保证同一晚最多一次。 */
object AgentDreamNightGate {
    private const val NIGHT_ANCHOR_HOUR = 12

    fun nightKey(epochMillis: Long, zoneId: ZoneId): String {
        val zoned = Instant.ofEpochMilli(epochMillis).atZone(zoneId)
        val date = if (zoned.hour < NIGHT_ANCHOR_HOUR) {
            zoned.toLocalDate().minusDays(1)
        } else {
            zoned.toLocalDate()
        }
        return date.toString()
    }
}

/** 把次日事实转成建议信号；数量为 0 的类别不产出。 */
object AgentDreamSuggestionPlanner {
    const val PRIORITY_CALENDAR = 96
    const val PRIORITY_WATCHER = 95
    const val PRIORITY_GOAL = 94

    fun plan(agenda: AgentDreamAgenda): List<AgentDreamNextDaySignal> = buildList {
        if (agenda.calendarEvents.isNotEmpty()) {
            add(
                AgentDreamNextDaySignal(
                    kind = AgentDreamSignalKind.CALENDAR,
                    itemCount = agenda.calendarEvents.size,
                    priority = PRIORITY_CALENDAR,
                ),
            )
        }
        if (agenda.dueWatchers.isNotEmpty()) {
            add(
                AgentDreamNextDaySignal(
                    kind = AgentDreamSignalKind.WATCHER,
                    itemCount = agenda.dueWatchers.size,
                    priority = PRIORITY_WATCHER,
                ),
            )
        }
        if (agenda.openGoals.isNotEmpty()) {
            add(
                AgentDreamNextDaySignal(
                    kind = AgentDreamSignalKind.GOAL,
                    itemCount = agenda.openGoals.size,
                    priority = PRIORITY_GOAL,
                ),
            )
        }
    }
}

/**
 * 夜间整理纯逻辑：归档临时记忆、合并长期偏好、扫描次日议程。
 *
 * 三个步骤互相隔离：任一步失败只记 warning，其余步骤继续，
 * 避免一次日历权限异常把整晚的记忆整理全部拖垮。
 */
class AgentDreamEngine(
    private val memoryStore: AgentDreamMemoryStore,
    private val agendaSource: AgentDreamAgendaSource,
    private val summaryFormatter: AgentDreamSummaryFormatter,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zoneId: () -> ZoneId = { ZoneId.systemDefault() },
) {

    suspend fun run(): AgentDreamReport {
        val startedAt = clock()
        val now = Instant.ofEpochMilli(startedAt)
        val zone = zoneId()
        val warnings = mutableListOf<String>()
        var archived = 0
        var merged = 0

        try {
            archived = memoryStore.archiveExpiredTemporaryFacts(now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "归档过期临时记忆失败: " + e.message, e)
            warnings += WARNING_ARCHIVE
        }

        try {
            merged = memoryStore.dedupeLongTermPreferences()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "合并长期偏好失败: " + e.message, e)
            warnings += WARNING_PREFERENCE
        }

        val agenda = try {
            agendaSource.loadTomorrow(now, zone)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "扫描次日议程失败: " + e.message, e)
            warnings += WARNING_AGENDA
            AgentDreamAgenda()
        }

        val signals = AgentDreamSuggestionPlanner.plan(agenda)
        val summary = if (agenda.isEmpty) {
            ""
        } else {
            try {
                summaryFormatter.format(agenda)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "生成晨间摘要失败: " + e.message, e)
                warnings += WARNING_SUMMARY
                ""
            }
        }
        val finishedAt = clock()
        return AgentDreamReport(
            generatedAt = finishedAt,
            archivedTemporaryCount = archived,
            mergedPreferenceCount = merged,
            nextDaySignals = signals,
            morningSummary = summary,
            durationMs = (finishedAt - startedAt).coerceAtLeast(0L),
            warnings = warnings,
            notificationsSent = 0,
        )
    }

    private companion object {
        const val TAG = "AgentDreamEngine"
        const val WARNING_ARCHIVE = "archive"
        const val WARNING_PREFERENCE = "preference"
        const val WARNING_AGENDA = "agenda"
        const val WARNING_SUMMARY = "summary"
    }
}

/**
 * 生产记忆整理：读取事实分库、归档过期 TTL 事实、按文本等价的保守合并偏好。
 *
 * 所有写入前都查 [MemoryWriteGate]（备份恢复期间不得改写记忆），
 * 写完/合并完由 [MemoryCompiler] 确定性刷新受影响 scope+space 的 FACTS 段，
 * 不走 MemoryTicker 的「当天已编译」缓存 —— 合并发生在缓存生成之后，等 tick 会让注入内容滞后一天。
 */
class DefaultAgentDreamMemoryStore(
    private val context: Context,
    private val factDbProvider: FactDbProvider,
    private val memoryCompiler: MemoryCompiler? = null,
    private val writeGate: MemoryWriteGate? = null,
) : AgentDreamMemoryStore {

    private val archiveStore = AgentDreamArchiveStore(File(context.filesDir, AgentDreamArchiveStore.DIRECTORY))

    override suspend fun archiveExpiredTemporaryFacts(now: Instant): Int = withContext(Dispatchers.IO) {
        var archived = 0
        val touched = linkedSetOf<TargetKey>()
        for (assistantId in assistantIds()) {
            val db = try {
                factDbProvider.getFactDb(assistantId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "打开事实库失败 assistant=" + assistantId + ": " + e.message, e)
                continue
            }
            val store = try {
                factDbProvider.getFactStore(assistantId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "创建事实存储失败 assistant=" + assistantId + ": " + e.message, e)
                continue
            }
            val expired = try {
                db.factDao().getAll(null)
                    .filter { entity -> entity.pinnedAt == null && isExpired(entity, now) }
                    .take(MAX_ARCHIVE_PER_RUN)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "枚举过期临时记忆失败 assistant=" + assistantId + ": " + e.message, e)
                continue
            }
            if (expired.isEmpty()) continue
            val writable = expired.filterNot { entity -> isPaused(assistantId, entity.scope, entity.spaceId) }
            if (writable.isEmpty()) continue
            val rows = writable.map { entity -> archiveRow(assistantId, entity, now) }
            // 归档失败就一条都不删：归档是删除的前置凭证，写不进去就继续删等于永久丢数据。
            if (!archiveStore.append(rows)) {
                Logger.w(TAG, "归档文件写入失败，跳过本库删除 assistant=" + assistantId)
                continue
            }
            for (entity in writable) {
                try {
                    if (store.delete(entity.id)) {
                        archived++
                        touched += TargetKey(assistantId, entity.scope, entity.spaceId)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "删除已归档临时记忆失败 id=" + entity.id + ": " + e.message, e)
                }
            }
        }
        touched.forEach { refreshCompiledFacts(it) }
        archived
    }

    override suspend fun dedupeLongTermPreferences(): Int = withContext(Dispatchers.IO) {
        var merged = 0
        var limitReached = false
        val touched = linkedSetOf<TargetKey>()
        for (assistantId in assistantIds()) {
            if (limitReached) break
            val store = try {
                factDbProvider.getFactStore(assistantId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "创建事实存储失败 assistant=" + assistantId + ": " + e.message, e)
                continue
            }
            val pairs = try {
                store.listScopeSpacePairs()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "读取记忆作用域失败 assistant=" + assistantId + ": " + e.message, e)
                continue
            }
            for ((scope, spaceId) in pairs) {
                if (limitReached) break
                if (isPaused(assistantId, scope, spaceId)) continue
                val groups = try {
                    store.findSimilarGroups(scope, spaceId, maxGroups = MAX_PREFERENCE_GROUPS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "查找偏好相似簇失败 " + scope + "/" + spaceId + ": " + e.message, e)
                    continue
                }
                for (group in groups) {
                    if (group.size < 2) continue
                    if (group.any { it.pinnedAt != null }) continue
                    if (!group.all { fact -> AgentDreamPreferenceRules.isLongTermPreference(fact.category, fact.tags) }) {
                        continue
                    }
                    // 相似簇只是候选；真正允许合并的是其中文本完全等价的子组。
                    // 用「最长文本」合并相似但不等价的条目会丢掉「字号调大」这类独有条件。
                    for (cluster in AgentDreamPreferenceRules.equivalentClusters(group)) {
                        val mergedText = cluster.first().fact.trim()
                        if (mergedText.isBlank()) continue
                        val newId = try {
                            store.mergeFacts(cluster.map { it.id }, mergedText, scope, spaceId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Logger.w(TAG, "合并长期偏好失败 " + scope + "/" + spaceId + ": " + e.message, e)
                            null
                        }
                        if (newId != null) {
                            merged += (cluster.size - 1).coerceAtLeast(1)
                            touched += TargetKey(assistantId, scope, spaceId)
                            if (merged >= MAX_MERGES_PER_RUN) {
                                limitReached = true
                                break
                            }
                        }
                    }
                    if (limitReached) break
                }
            }
        }
        touched.forEach { refreshCompiledFacts(it) }
        merged
    }

    private fun assistantIds(): List<String> = buildList {
        add(DEFAULT_ASSISTANT_ID)
        addAll(factDbProvider.listAssistantDbIds())
    }.distinct()

    private fun isPaused(assistantId: String, scope: String, spaceId: String): Boolean =
        writeGate?.isBlocked(MemoryScopeKey(assistantId, scope, spaceId)) == true

    /**
     * 刷新受影响 scope+space 的 FACTS 编译段。
     *
     * 传空列表而不是刚合并的行：编译器有 FactStore 时会重查最新表，
     * 这样「最后一条被删空」也会走 `source.isEmpty()` 的清空分支，而不是留下旧行。
     */
    private suspend fun refreshCompiledFacts(target: TargetKey) {
        val compiler = memoryCompiler ?: return
        if (isPaused(target.assistantId, target.scope, target.spaceId)) return
        try {
            compiler.reconcileFactsSectionWithStore(
                facts = emptyList(),
                target = MemoryCompileTarget(
                    assistantId = target.assistantId,
                    scope = target.scope,
                    spaceId = target.spaceId,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "刷新 FACTS 编译段失败 " + target.scope + "/" + target.spaceId + ": " + e.message, e)
        }
    }

    private fun isExpired(entity: FactEntity, now: Instant): Boolean {
        val raw = entity.expiresAt
        if (raw.isNullOrBlank()) return false
        val expiresAt = parseInstant(raw)
        if (expiresAt == null) return false
        return !expiresAt.isAfter(now)
    }

    private fun parseInstant(raw: String): Instant? = try {
        Instant.parse(raw)
    } catch (e: Exception) {
        Logger.w(TAG, "解析记忆过期时间失败: " + raw + ": " + e.message)
        null
    }

    private fun archiveRow(assistantId: String, row: FactEntity, now: Instant) = AgentDreamArchiveRow(
        archivedAt = now.toString(),
        assistantId = assistantId,
        factId = row.id,
        content = row.fact,
        category = row.category,
        tagsJson = row.tags,
        expiresAt = row.expiresAt.orEmpty(),
        scope = row.scope,
        spaceId = row.spaceId,
    )

    private data class TargetKey(val assistantId: String, val scope: String, val spaceId: String)

    private companion object {
        const val TAG = "AgentDreamMemory"
        const val DEFAULT_ASSISTANT_ID = "default"
        const val MAX_ARCHIVE_PER_RUN = 200
        const val MAX_PREFERENCE_GROUPS = 12
        const val MAX_MERGES_PER_RUN = 30
    }
}

/**
 * 夜间整理归档：按助手分行只追加的 JSONL，固定字节上限 + 有界轮转。
 *
 * 旧实现只限制「每次最多 200 条」，文件本身可以无限增长，长期夜间整理会把
 * app 私有目录写成几十 MB。轮转保留当前文件 + 两个历史文件，总占用有上界。
 */
class AgentDreamArchiveStore(private val directory: File) {

    /** 写入一批归档行。返回 false 时调用方不得删除对应事实。 */
    internal fun append(rows: List<AgentDreamArchiveRow>): Boolean = synchronized(LOCK) {
        if (rows.isEmpty()) return true
        try {
            if (!directory.isDirectory && !directory.mkdirs()) {
                Logger.w(TAG, "创建归档目录失败: " + directory.absolutePath)
                return false
            }
            val payload = buildString {
                for (row in rows) {
                    append(agentDreamJson.encodeToString(AgentDreamArchiveRow.serializer(), row))
                    append('\n')
                }
            }.toByteArray(Charsets.UTF_8)
            if (payload.size > MAX_FILE_BYTES) {
                Logger.w(TAG, "单批归档超过字节上限，拒绝写入以免丢数据: " + payload.size)
                return false
            }
            val current = File(directory, FILE_NAME)
            if (current.length() + payload.size > MAX_FILE_BYTES && !rotate(current)) {
                Logger.w(TAG, "归档轮转失败，跳过本批写入")
                return false
            }
            FileOutputStream(current, true).use { output -> output.write(payload) }
            true
        } catch (e: Exception) {
            Logger.w(TAG, "写入归档文件失败: " + e.message, e)
            false
        }
    }

    /** 删除某个助手的归档行（重写每个归档文件），返回去掉的行数。 */
    fun purgeAssistant(assistantId: String): Int = synchronized(LOCK) {
        val id = assistantId.trim()
        if (id.isEmpty()) return 0
        var removed = 0
        for (file in archiveFiles()) {
            val kept = mutableListOf<String>()
            var fileRemoved = 0
            try {
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val row = runCatching {
                        agentDreamJson.decodeFromString(AgentDreamArchiveRow.serializer(), line)
                    }.getOrNull()
                    // 解析不了的行保留：宁可留下读不懂的旧行，也不要因为格式问题删掉别的助手的数据。
                    if (row?.assistantId == id) fileRemoved++ else kept += line
                }
            } catch (e: Exception) {
                Logger.w(TAG, "读取归档文件失败，跳过: " + file.name + ": " + e.message, e)
                continue
            }
            if (fileRemoved == 0) continue
            try {
                val tmp = File(directory, file.name + ".tmp")
                tmp.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                removed += fileRemoved
            } catch (e: Exception) {
                Logger.w(TAG, "重写归档文件失败: " + file.name + ": " + e.message, e)
            }
        }
        removed
    }

    /** 重置全部记忆时删掉所有归档文件，返回删除的文件数。 */
    fun clear(): Int = synchronized(LOCK) {
        var removed = 0
        for (file in archiveFiles()) {
            if (runCatching { file.delete() }.getOrDefault(false)) {
                removed++
            } else {
                Logger.w(TAG, "删除归档文件失败: " + file.name)
            }
        }
        removed
    }

    private fun archiveFiles(): List<File> = directory.listFiles { file ->
        file.isFile && file.name.startsWith(FILE_NAME) && !file.name.endsWith(".tmp")
    }.orEmpty().sortedBy { it.name }

    /** 当前文件 → `.1` → `.2`，多出的最旧文件删除；任何一步失败都不写入。 */
    private fun rotate(current: File): Boolean {
        try {
            val overflow = File(directory, "$FILE_NAME.$MAX_ROTATED_FILES")
            if (overflow.exists() && !overflow.delete()) return false
            for (index in MAX_ROTATED_FILES - 1 downTo 1) {
                val source = File(directory, "$FILE_NAME.$index")
                if (!source.exists()) continue
                if (!source.renameTo(File(directory, "$FILE_NAME.${index + 1}"))) return false
            }
            return !current.exists() || current.renameTo(File(directory, "$FILE_NAME.1"))
        } catch (e: Exception) {
            Logger.w(TAG, "归档轮转失败: " + e.message, e)
            return false
        }
    }

    companion object {
        const val DIRECTORY = "agent_dream/archive"
        private const val FILE_NAME = "temporary-facts.jsonl"
        private const val MAX_FILE_BYTES = 4L * 1024 * 1024
        private const val MAX_ROTATED_FILES = 2
        private const val TAG = "AgentDreamArchive"
        private val LOCK = Any()
    }
}

/** 读系统日历、活跃 Watcher 与未完成 Goal，形成明日议程。 */
class DeviceAgentDreamAgendaSource(
    private val context: Context,
    private val watcherDao: AgentWatcherDao,
    private val goalDao: AgentGoalDao,
) : AgentDreamAgendaSource {

    override suspend fun loadTomorrow(now: Instant, zoneId: ZoneId): AgentDreamAgenda {
        val tomorrow = now.atZone(zoneId).toLocalDate().plusDays(1)
        val start = tomorrow.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end = tomorrow.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val calendarEvents = readCalendarEvents(start, end)
        val watchers = watcherDao.listActive()
            .filter { watcher -> watcher.nextCheckAt == 0L || watcher.nextCheckAt in start until end }
            .map { watcher -> AgentDreamWatcherBrief(watcher.watcherId, watcher.title, watcher.nextCheckAt) }
        val goals = goalDao.getAll()
            .filter { goal ->
                goal.status != AgentGoalStatus.ACHIEVED &&
                    goal.status != AgentGoalStatus.ABANDONED &&
                    goal.progress < 100
            }
            .map { goal -> AgentDreamGoalBrief(goal.goalId, goal.title, goal.progress) }
        return AgentDreamAgenda(
            calendarEvents = calendarEvents,
            dueWatchers = watchers,
            openGoals = goals,
        )
    }

    private fun readCalendarEvents(start: Long, end: Long): List<AgentDreamCalendarEvent> {
        val resolver = context.contentResolver
        return try {
            val events = mutableListOf<AgentDreamCalendarEvent>()
            resolver.query(
                CalendarSemantics.instancesUri(start, end),
                CalendarSemantics.INSTANCE_PROJECTION,
                null,
                null,
                CalendarContract.Instances.BEGIN + " ASC",
            )?.use { cursor ->
                val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                while (cursor.moveToNext()) {
                    val title = cursor.getString(titleIndex)
                    if (!title.isNullOrBlank()) {
                        events += AgentDreamCalendarEvent(
                            title = title,
                            startAt = cursor.getLong(beginIndex),
                            allDay = cursor.getInt(allDayIndex) != 0,
                        )
                    }
                }
            }
            events
        } catch (e: SecurityException) {
            Logger.w(TAG, "没有日历读取权限，跳过明日日程: " + e.message)
            emptyList()
        } catch (e: Exception) {
            Logger.w(TAG, "读取明日日程失败: " + e.message, e)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "AgentDreamAgenda"
    }
}

/** 用现有字符串资源拼一条折叠摘要，不新增用户可见文案。 */
class AndroidAgentDreamSummaryFormatter(
    private val context: Context,
) : AgentDreamSummaryFormatter {

    override fun format(agenda: AgentDreamAgenda): String {
        val lines = mutableListOf<String>()
        agenda.calendarEvents.firstOrNull()?.let { event ->
            val time = if (event.allDay) {
                ""
            } else {
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.startAt))
            }
            lines += if (time.isBlank()) {
                event.title
            } else {
                context.getString(R.string.greeting_tomorrow_event, time, event.title)
            }
        }
        if (agenda.dueWatchers.isNotEmpty()) {
            lines += context.getString(R.string.agent_follow_up_watchers_title) + ": " +
                agenda.dueWatchers.take(MAX_TITLES).joinToString(", ") { watcher -> watcher.title }
        }
        if (agenda.openGoals.isNotEmpty()) {
            lines += context.getString(R.string.agent_follow_up_goals_title) + ": " +
                agenda.openGoals.take(MAX_TITLES).joinToString(", ") { goal -> goal.title }
        }
        return lines.joinToString("\n").take(MAX_SUMMARY_CHARS)
    }

    private companion object {
        const val MAX_TITLES = 3
        const val MAX_SUMMARY_CHARS = 400
    }
}

/** 夜间整理报告与开关的本地持久化。 */
class AgentDreamStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun lastNightKey(): String = prefs.getString(KEY_LAST_NIGHT_KEY, "").orEmpty()

    fun markNightCompleted(nightKey: String) {
        prefs.edit().putString(KEY_LAST_NIGHT_KEY, nightKey).apply()
    }

    fun lastReport(): AgentDreamReport? {
        val raw = prefs.getString(KEY_LAST_REPORT, null)
        if (raw == null || raw.isBlank()) return null
        return try {
            agentDreamJson.decodeFromString(AgentDreamReport.serializer(), raw)
        } catch (e: Exception) {
            Logger.w(TAG, "读取夜间整理报告失败: " + e.message, e)
            null
        }
    }

    fun saveReport(report: AgentDreamReport) {
        runCatching { agentDreamJson.encodeToString(AgentDreamReport.serializer(), report) }
            .onSuccess { encoded -> prefs.edit().putString(KEY_LAST_REPORT, encoded).apply() }
            .onFailure { error -> Logger.w(TAG, "保存夜间整理报告失败: " + error.message, error) }
    }

    private companion object {
        const val TAG = "AgentDreamStore"
        const val PREFERENCES_NAME = "agent_dream"
        const val KEY_ENABLED = "enabled"
        const val KEY_LAST_NIGHT_KEY = "last_night_key"
        const val KEY_LAST_REPORT = "last_report_json"
    }
}

/** WorkManager 调度口：每晚一次，充电 + 非计量网络 + 设备空闲。 */
object AgentDreamScheduler {
    const val UNIQUE_NIGHTLY_WORK_NAME = "muse_agent_dream_nightly"
    const val UNIQUE_MANUAL_WORK_NAME = "muse_agent_dream_manual"
    const val KEY_MANUAL = "agent_dream_manual"

    private const val TAG = "AgentDreamScheduler"
    private const val NIGHT_HOUR = 2
    private const val PERIOD_HOURS = 24L

    fun nightlyConstraints(): Constraints = Constraints.Builder()
        .setRequiresCharging(true)
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresDeviceIdle(true)
        .build()

    fun scheduleNightly(
        context: Context,
        now: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ) {
        val request = PeriodicWorkRequestBuilder<AgentDreamWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(nightlyConstraints())
            .setInitialDelay(millisUntilNextNight(now, zoneId), TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NIGHTLY_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }.onFailure { error -> Logger.w(TAG, "注册夜间整理任务失败: " + error.message, error) }
    }

    fun cancelNightly(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NIGHTLY_WORK_NAME) }
            .onFailure { error -> Logger.w(TAG, "取消夜间整理任务失败: " + error.message, error) }
    }

    /** 设置页「立即整理」的 WorkManager 兜底入口；前台试跑走 [AgentDreamRunner]。 */
    fun enqueueManual(context: Context) {
        val request = OneTimeWorkRequestBuilder<AgentDreamWorker>()
            .setInputData(workDataOf(KEY_MANUAL to true))
            .addTag(TAG)
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_MANUAL_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }.onFailure { error -> Logger.w(TAG, "投递立即整理任务失败: " + error.message, error) }
    }

    /** 距下一个本地 02:00 的毫秒数。 */
    fun millisUntilNextNight(now: Long, zoneId: ZoneId): Long {
        val zoned = Instant.ofEpochMilli(now).atZone(zoneId)
        var target = zoned.toLocalDate().atTime(LocalTime.of(NIGHT_HOUR, 0)).atZone(zoneId)
        if (!target.isAfter(zoned)) {
            target = target.plusDays(1)
        }
        return Duration.between(zoned, target).toMillis().coerceAtLeast(0L)
    }
}

/**
 * 从 Koin 组装生产依赖并执行一次整理；返回 null 表示依赖不可用。
 *
 * 手动「立即整理」与夜间 WorkManager 都走这里，并持有 [MemoryMaintenanceLock]：
 * 两者改的是同一批事实/编译段，并发跑会让归档删除与偏好合并互相覆盖。
 * 夜间路径只做本地归档与确定性投影，不调模型，因此不产生额外计费。
 */
object AgentDreamRunner {
    suspend fun runNow(context: Context): AgentDreamReport? = withContext(Dispatchers.IO) {
        MemoryMaintenanceLock.mutex.withLock {
            val koin = GlobalContext.getOrNull()
            if (koin == null) {
                Logger.w(TAG, "Koin 未初始化，跳过夜间整理")
                return@withContext null
            }
            val factDbProvider = runCatching { koin.get<FactDbProvider>() }
                .onFailure { error -> Logger.w(TAG, "解析 FactDbProvider 失败: " + error.message, error) }
                .getOrNull()
            val museDb = runCatching { koin.get<MuseDb>() }
                .onFailure { error -> Logger.w(TAG, "解析 MuseDb 失败: " + error.message, error) }
                .getOrNull()
            if (factDbProvider == null || museDb == null) {
                Logger.w(TAG, "夜间整理依赖缺失，跳过本次执行")
                return@withContext null
            }
            val memoryCompiler = runCatching { koin.get<MemoryCompiler>() }
                .onFailure { error -> Logger.w(TAG, "解析 MemoryCompiler 失败: " + error.message, error) }
                .getOrNull()
            // 已注入的 gate 优先；并行备份门闩会替换成 ProcessWriteGate，直接读共享实例可能拿到旧实现。
            val writeGate = runCatching { koin.get<MemoryWriteGate>() }
                .getOrNull()
                ?: DefaultMemoryWriteGate.shared
            val defaultScope = MemoryScopeKey(DEFAULT_ASSISTANT_ID, "main", DEFAULT_SPACE_ID)
            if (writeGate.isBlocked(defaultScope)) {
                Logger.w(TAG, "记忆写入已暂停（备份恢复中），跳过本次整理")
                return@withContext null
            }
            val appContext = context.applicationContext
            val engine = AgentDreamEngine(
                memoryStore = DefaultAgentDreamMemoryStore(
                    context = appContext,
                    factDbProvider = factDbProvider,
                    memoryCompiler = memoryCompiler,
                    writeGate = writeGate,
                ),
                agendaSource = DeviceAgentDreamAgendaSource(
                    context = appContext,
                    watcherDao = museDb.agentWatcherDao(),
                    goalDao = museDb.agentGoalDao(),
                ),
                summaryFormatter = AndroidAgentDreamSummaryFormatter(appContext),
            )
            val report = engine.run()
            AgentDreamStore(appContext).saveReport(report)
            report
        }
    }

    private const val TAG = "AgentDreamRunner"
    private const val DEFAULT_ASSISTANT_ID = "default"
    private const val DEFAULT_SPACE_ID = "default"
}

/**
 * Dream 夜间离线整理 Worker。
 *
 * 约束由 [AgentDreamScheduler.nightlyConstraints] 固定为充电、非计量网络与设备空闲；
 * 每晚最多一次由 [AgentDreamStore.lastNightKey] 与 [AgentDreamNightGate] 共同保证。
 * 夜间只写本地报告，绝不调用系统通知。
 */
class AgentDreamWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val store = AgentDreamStore(context)
        val manual = inputData.getBoolean(AgentDreamScheduler.KEY_MANUAL, false)
        if (!manual && !store.isEnabled()) {
            Logger.i(TAG, "夜间整理开关已关闭，跳过本次执行")
            return Result.success()
        }
        val koin = GlobalContext.getOrNull()
        if (koin == null) {
            Logger.w(TAG, "Koin 未初始化，跳过本次夜间整理")
            return Result.success()
        }
        val settings = runCatching { koin.get<SettingsRepository>() }
            .onFailure { error -> Logger.w(TAG, "解析 SettingsRepository 失败: " + error.message, error) }
            .getOrNull()
        if (settings != null && !settings.isMemoryEnabled()) {
            Logger.i(TAG, "记忆总开关已关闭，跳过本次夜间整理")
            return Result.success()
        }
        val now = System.currentTimeMillis()
        val nightKey = AgentDreamNightGate.nightKey(now, ZoneId.systemDefault())
        if (!manual && store.lastNightKey() == nightKey) {
            Logger.i(TAG, "今晚已完成过整理，跳过重复执行")
            return Result.success()
        }
        val report = try {
            AgentDreamRunner.runNow(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "夜间整理执行失败: " + e.message, e)
            null
        }
        if (report == null) {
            Logger.w(TAG, "夜间整理没有产出报告，等待下一晚重试")
            return Result.success()
        }
        if (!manual) {
            store.markNightCompleted(nightKey)
        }
        Logger.i(
            TAG,
            "夜间整理完成: 归档=" + report.archivedTemporaryCount +
                ", 合并=" + report.mergedPreferenceCount +
                ", 次日建议=" + report.nextDaySignals.size,
        )
        return Result.success()
    }

    companion object {
        /** 与 [AgentDreamScheduler] 共用同一唯一任务名，便于应用启动时按既有模式注册。 */
        const val UNIQUE_WORK_NAME = "muse_agent_dream_nightly"

        private const val TAG = "AgentDreamWorker"
    }
}

/** 归档行的 JSONL 形态：只追加，不覆盖历史。 */
@Serializable
internal data class AgentDreamArchiveRow(
    val archivedAt: String,
    val assistantId: String,
    val factId: Long,
    val content: String,
    val category: String,
    val tagsJson: String,
    val expiresAt: String,
    val scope: String,
    val spaceId: String,
)

private val agentDreamJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
