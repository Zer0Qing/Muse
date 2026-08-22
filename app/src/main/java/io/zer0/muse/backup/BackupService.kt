package io.zer0.muse.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactEntity
import io.zer0.memory.summary.CompiledSectionEntity
import io.zer0.memory.summary.ScopedCompiledSectionEntity
import io.zer0.memory.summary.DailyStateEntity
import io.zer0.memory.summary.MemoryDb
import io.zer0.memory.summary.SessionSummaryEntity
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.stats.AutoBackupLogDao
import io.zer0.muse.data.stats.AutoBackupLogEntity
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.lorebook.LorebookEntity
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.data.quickmsg.QuickMessageEntity
import io.zer0.muse.data.promptinjection.PromptInjectionEntity
import io.zer0.muse.data.session.FolderEntity
import io.zer0.muse.data.groupchat.GroupChatEntity
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.data.schedule.ScheduledTaskEntity
import io.zer0.muse.data.schedule.ScheduledTaskExecutionEntity
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.data.knowledge.KnowledgeChunkEntity
import io.zer0.muse.data.experience.ExperienceEntity
import io.zer0.muse.data.milestone.MilestoneEntity
import io.zer0.muse.data.moment.MomentEntity
import io.zer0.muse.data.moment.MomentCommentEntity
import io.zer0.muse.data.moment.MomentLikeEntity
import io.zer0.muse.data.agentdm.AgentMessageEntity
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * Phase 5-I: 备份导出/导入服务。
 *
 * Phase 7 扩展:纳入 memory.db + facts.db 全量数据(4 张表)。
 * Phase 8.9 扩展:增加 S3 / WebDAV 云端上传/下载。
 * Phase 12 扩展:全表备份(MuseDb 全部用户数据表 + DataStore 设置快照)。
 *
 * 设计:
 *  - 用 SAF (Storage Access Framework) 让用户选择导出/导入位置
 *  - JSON 格式序列化所有会话 + 消息 + memory 数据 + 扩展表
 *  - 版本号字段 [Backup.version] 便于后续迁移
 *  - 三个独立 Room DB(MuseDb/MemoryDb/FactDb)分别开事务
 *  - 云备份:本地序列化 → 字节 → CloudBackupService.uploadBackupWithLatest
 *  - 云恢复:CloudBackupService.downloadLatestBackup → 字节 → 反序列化导入
 *
 * 限制:
 *  - 大数据量(10000+ 消息)JSON 一次性序列化可能 OOM,留后续分片
 *  - 不含图片二进制(只存 URL,URL 可能失效)
 */
@Suppress("LongParameterList") // 依赖注入构造: 8 个服务依赖平铺注入,拆分聚合类反而增加间接层(项目惯例,见 ChatViewModel/ToolOrchestrator)
class BackupService(
    private val db: MuseDb,
    private val memoryDb: MemoryDb,
    private val factDb: FactDb,
    private val cloudBackupService: CloudBackupService,
    private val settings: SettingsRepository,
    /** F-04: 备份记录持久化(诊断页可见 + 读回校验失败分类记录)。 */
    private val autoBackupLogDao: AutoBackupLogDao,
    /** v1.0.74: 导入后重建 FTS 索引(直插消息绕过 FTS 同步)。 */
    private val sessionRepository: io.zer0.muse.data.session.SessionRepository,
    /**
     * B-23: 单 JSON 备份体量上限(字节)。
     *
     * 该上限仅作用于"确实需要全量解析"的路径(单 JSON 备份必须整份 decode 到内存,
     * 否则恶意/损坏备份会把进程 OOM)。保持向后兼容,默认仍为 64MB;
     * 若业务确实存在超过该体量的合法单 JSON 备份,允许通过构造参数调大。
     * NDJSON 路径走流式逐行解析,不消耗该上限(天然防 OOM)。
     */
    private val singleJsonMaxBytes: Long = MAX_SINGLE_JSON_BACKUP_BYTES,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * 备份数据结构。
     *
     * version=1: 仅 sessions + messages(Phase 5-I)
     * version=2: 含 memory 数据(Phase 7)— 新字段带默认值,兼容旧备份导入
     * version=3: 全表备份(Phase 12)— 新增所有 MuseDb 用户数据表 + DataStore 设置快照
     */
    @Serializable
    data class Backup(
        val version: Int = 3,
        val exportedAt: Long,
        // ── 原有字段(v1/v2) ──
        val sessions: List<SessionEntity>,
        val messages: List<MessageEntity>,
        val sessionSummaries: List<SessionSummaryEntity> = emptyList(),
        val dailyStates: List<DailyStateEntity> = emptyList(),
        val compiledSections: List<CompiledSectionEntity> = emptyList(),
        val scopedCompiledSections: List<ScopedCompiledSectionEntity> = emptyList(),
        val facts: List<FactEntity> = emptyList(),
        // ── v3 新增: MuseDb 扩展表 ──
        val assistants: List<AssistantEntity> = emptyList(),
        val lorebooks: List<LorebookEntity> = emptyList(),
        val skills: List<SkillEntity> = emptyList(),
        val artifacts: List<ArtifactEntity> = emptyList(),
        val quickMessages: List<QuickMessageEntity> = emptyList(),
        val promptInjections: List<PromptInjectionEntity> = emptyList(),
        val folders: List<FolderEntity> = emptyList(),
        val groupChats: List<GroupChatEntity> = emptyList(),
        val groupChatMessages: List<GroupChatMessageEntity> = emptyList(),
        val scheduledTasks: List<ScheduledTaskEntity> = emptyList(),
        val scheduledTaskExecutions: List<ScheduledTaskExecutionEntity> = emptyList(),
        val knowledgeDocs: List<KnowledgeDocEntity> = emptyList(),
        val knowledgeChunks: List<KnowledgeChunkEntity> = emptyList(),
        val experiences: List<ExperienceEntity> = emptyList(),
        val milestones: List<MilestoneEntity> = emptyList(),
        val agentMessages: List<AgentMessageEntity> = emptyList(),
        // ── v1.0.74: 朋友圈三表(此前缺失,换机恢复丢数据)──
        val moments: List<MomentEntity> = emptyList(),
        val momentComments: List<MomentCommentEntity> = emptyList(),
        val momentLikes: List<MomentLikeEntity> = emptyList(),
        // ── v3 新增: DataStore 设置快照 ──
        val settingsSnapshot: Map<String, String> = emptyMap(),
    )

    /**
     * 导出全部会话 + 消息 + memory 数据到指定 URI。
     * @return 导出的会话数 + 消息数(用于 UI 提示)
     */
    suspend fun export(context: Context, uri: Uri): Pair<Int, Int> {
        val backup = buildBackup()
        val text = json.encodeToString(Backup.serializer(), backup)
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { os: OutputStream ->
                OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                    writer.write(text)
                }
            } ?: error(context.getString(R.string.backup_cannot_write, uri))
        }
        return backup.sessions.size to backup.messages.size
    }

    /**
     * 从指定 URI 导入会话 + 消息 + memory 数据。
     * 策略: 清空三个 DB 的全部表 → 插入备份数据(简化版,不做合并去重)。
     * @return 导入的会话数 + 消息数
     */
    suspend fun import(context: Context, uri: Uri): Pair<Int, Int> = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val reader = input.bufferedReader(Charsets.UTF_8)
            val firstLine = generateSequence { reader.readLine() }
                .firstOrNull { !it.isNullOrBlank() }
                ?: error(context.getString(R.string.backup_format_unrecognized))
            val isNdJson = resultOf {
                val obj = json.decodeFromString(JsonObject.serializer(), firstLine)
                obj["type"]?.let { (it as? JsonPrimitive)?.content } == "meta"
            }.getOrNull() ?: false
            if (isNdJson) {
                // B4-07: 首行已消费,继续从 reader 流式读取,不整读
                val rest = generateSequence { reader.readLine() }.takeWhile { it != null }.map { it!! }
                applyNdJsonStreaming(sequenceOf(firstLine) + rest)
            } else {
                // B-25: 单 JSON 备份防 OOM — 逐行读入时累计 UTF-8 体量,超过上限立即抛错,
                // 而非"整份 buildString 后再解析",避免恶意/损坏备份把进程 OOM。
                val text = readSingleJsonWithLimit(reader, firstLine)
                val backup = resultOf { json.decodeFromString(Backup.serializer(), text) }
                    .onError { msg, t -> Logger.w("BackupService", "单 JSON 备份解析失败", t) }
                    .getOrNull()
                    ?: error(context.getString(R.string.backup_format_unrecognized))
                applyBackup(backup)
            }
        } ?: error(context.getString(R.string.backup_cannot_read, uri))
    }

    /**
     * B-25: 读取单 JSON 备份文本并做体量上限校验。
     *
     * 逐行读入时按 UTF-8 字节累计 [第一行 + 后续行],一旦超过 [MAX_SINGLE_JSON_BACKUP_BYTES]
     * 立即抛 [IllegalArgumentException] 给出明确上限,不再静默读入整份超大文本。
     *
     * @param reader 已消费过 [firstLine] 的 reader(首行在格式识别阶段读出)
     * @param firstLine 已读出的首个非空行(计入体量)
     */
    private fun readSingleJsonWithLimit(reader: java.io.BufferedReader, firstLine: String): String {
        var cumulative = firstLine.toByteArray(Charsets.UTF_8).size.toLong()
        check(withinConfigurableLimit(cumulative, 0L)) {
            singleJsonTooLargeConfiguredMessage(cumulative)
        }
        return buildString {
            appendLine(firstLine)
            while (true) {
                val line = reader.readLine() ?: break
                cumulative += line.toByteArray(Charsets.UTF_8).size.toLong()
                check(withinConfigurableLimit(cumulative, 0L)) {
                    singleJsonTooLargeConfiguredMessage(cumulative)
                }
                appendLine(line)
            }
        }
    }

    /**
     * B-23: 以可配置的 [singleJsonMaxBytes] 为上限判断单 JSON 备份体量是否仍在允许范围内。
     * [MAX_SINGLE_JSON_BACKUP_BYTES] 是向后兼容的默认上限,构造参数可调大以兼容更大合法备份。
     */
    private fun withinConfigurableLimit(cumulativeUtf8Bytes: Long, additionalUtf8Bytes: Long): Boolean =
        cumulativeUtf8Bytes <= singleJsonMaxBytes - additionalUtf8Bytes

    /** B-23: 生成单 JSON 备份超限错误信息,反映实例配置的实际上限(默认 64MB)。 */
    private fun singleJsonTooLargeConfiguredMessage(cumulativeUtf8Bytes: Long): String =
        "单 JSON 备份体量 ${(cumulativeUtf8Bytes + 1024 * 1024 - 1) / (1024 * 1024)}MB 超过上限 " +
            "${singleJsonMaxBytes / (1024 * 1024)}MB,已拒绝导入"

    /**
     * Phase 11.2.2: 流式分片导出(NDJSON 格式)。
     *
     * 解决大数据量(10000+ 消息)一次性 JSON 序列化 OOM 问题。
     * 格式:首行 meta({type:"meta",version,counts...}),后续每行一条记录。
     *
     * @return 导出的会话数 + 消息数
     */
    suspend fun exportStreaming(context: Context, uri: Uri): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val summary = context.contentResolver.openOutputStream(uri)?.use { os: OutputStream ->
            BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8)).use { writer ->
                val wrote = writeNdJson(writer)
                writer.flush()
                Logger.i("BackupService", "流式导出完成: ${wrote.sessions} 会话, ${wrote.messages} 消息")
                wrote
            }
        } ?: error(context.getString(R.string.backup_cannot_write, uri))
        summary.sessions to summary.messages
    }

    /**
     * B-25: NDJSON 全量数据拉取 + 逐行写入的公共实现。
     *
     * 由 [exportStreaming](本地 SAF 文件)与 [exportToCloud](云端字节流)复用。
     * 逐条序列化逐行写出,消息按会话分批拉取,不整份 buildBackup + encodeToString,
     * 避免"Backup 全量对象 + 巨串 + 字节数组"三份内存同时驻留导致的 OOM。
     *
     * @return 各主要表计数(供调用方打印规模诊断日志)
     */
    private suspend fun writeNdJson(writer: BufferedWriter): NdJsonExportSummary {
        // B-32: 全部 MuseDb 表读取包进单一事务,获得一致快照。
        // 旧实现逐表非事务 getAll + 逐会话 observeBySession,与运行中写库并发时,
        // 不同表/会话可能读到不同时间点的数据 → 产生 torn 备份。改为在 db.withTransaction
        // 内一次读出全部 MuseDb 数据,事务提交后再流式写出 NDJSON,不占用事务期间的 IO 时间。
        // 事务仅覆盖读取(很快),正式写出在事务外逐行流式进行,不影响流式导出防 OOM 的目标;
        // 事务在 Dispatchers.IO 上执行,不阻塞主线程。
        // memoryDb/factDb/settings 为独立库,无法并入 MuseDb 事务,仍各自读取。
        val muse = db.withTransaction {
            val s = db.sessionDao().observeAll().first()
            val msgs = s.flatMap { db.messageDao().observeBySession(it.id).first() }
            ExportedMuseSnapshot(
                sessions = s,
                messages = msgs,
                assistants = db.assistantDao().getAll(),
                lorebooks = db.lorebookDao().getAll(),
                skills = db.skillDao().getAll(),
                artifacts = db.artifactDao().getAll(),
                quickMessages = db.quickMessageDao().getAll(),
                promptInjections = db.promptInjectionDao().getAll(),
                folders = db.folderDao().getAll(),
                groupChats = db.groupChatDao().getAll(),
                groupChatMessages = db.groupChatMessageDao().getAll(),
                scheduledTasks = db.scheduledTaskDao().getAll(),
                scheduledTaskExecutions = db.scheduledTaskExecutionDao().getAll(),
                knowledgeDocs = db.knowledgeDocDao().getAll(),
                knowledgeChunks = db.knowledgeChunkDao().getAll(),
                experiences = db.experienceDao().getAll(),
                milestones = db.milestoneDao().getAll(),
                agentMessages = db.agentMessageDao().getAll(),
                moments = db.momentDao().getAll(),
                momentComments = db.momentDao().getAllComments(),
                momentLikes = db.momentDao().getAllLikes(),
            )
        }
        val sessions = muse.sessions
        val allMessages = muse.messages
        val sessionSummaries = memoryDb.sessionSummaryDao().getAll()
        val compiledSections = memoryDb.compiledSectionDao().getAll()
        val scopedCompiledSections = memoryDb.scopedCompiledSectionDao().getAll()
        val dailyState = memoryDb.dailyStateDao().get()?.let { listOf(it) } ?: emptyList()
        val facts = factDb.factDao().getAll()
        val assistants = muse.assistants
        val lorebooks = muse.lorebooks
        val skills = muse.skills
        val artifacts = muse.artifacts
        val quickMessages = muse.quickMessages
        val promptInjections = muse.promptInjections
        val folders = muse.folders
        val groupChats = muse.groupChats
        val groupChatMessages = muse.groupChatMessages
        val scheduledTasks = muse.scheduledTasks
        val scheduledTaskExecutions = muse.scheduledTaskExecutions
        val knowledgeDocs = muse.knowledgeDocs
        val knowledgeChunks = muse.knowledgeChunks
        val experiences = muse.experiences
        val milestones = muse.milestones
        val agentMessages = muse.agentMessages
        val moments = muse.moments
        val momentComments = muse.momentComments
        val momentLikes = muse.momentLikes
        val settingsSnapshot = settings.exportSettingsSnapshot()

        // Step 2: 写 meta 行
        val meta = buildJsonObject {
            put("type", "meta")
            put("version", 3)
            put("exportedAt", System.currentTimeMillis())
            put("sessions", sessions.size)
            put("sessionSummaries", sessionSummaries.size)
            put("dailyStates", dailyState.size)
            put("compiledSections", compiledSections.size)
            put("facts", facts.size)
            put("assistants", assistants.size)
            put("lorebooks", lorebooks.size)
            put("skills", skills.size)
            put("artifacts", artifacts.size)
            put("quickMessages", quickMessages.size)
            put("promptInjections", promptInjections.size)
            put("folders", folders.size)
            put("groupChats", groupChats.size)
            put("groupChatMessages", groupChatMessages.size)
            put("scheduledTasks", scheduledTasks.size)
            put("scheduledTaskExecutions", scheduledTaskExecutions.size)
            put("knowledgeDocs", knowledgeDocs.size)
            put("knowledgeChunks", knowledgeChunks.size)
            put("experiences", experiences.size)
            put("milestones", milestones.size)
            put("agentMessages", agentMessages.size)
            put("moments", moments.size)
            put("momentComments", momentComments.size)
            put("momentLikes", momentLikes.size)
        }
        writer.write(meta.toString())
        writer.newLine()

        // Step 3: 写 sessions
        var sessionCount = 0
        sessions.forEach { session ->
            val line = buildJsonObject {
                put("type", "session")
                put("data", json.encodeToJsonElement(SessionEntity.serializer(), session))
            }
            writer.write(line.toString())
            writer.newLine()
            sessionCount++
        }

        // Step 4: 写 messages(B-32: 直接写事务快照中的消息列表,不再逐会话重查)
        var messageCount = 0
        allMessages.forEach { msg ->
            val line = buildJsonObject {
                put("type", "message")
                put("data", json.encodeToJsonElement(MessageEntity.serializer(), msg))
            }
            writer.write(line.toString())
            writer.newLine()
            messageCount++
        }

        // Step 5: 写 memory 数据
        sessionSummaries.forEach { summary ->
            val line = buildJsonObject {
                put("type", "summary")
                put("data", json.encodeToJsonElement(SessionSummaryEntity.serializer(), summary))
            }
            writer.write(line.toString())
            writer.newLine()
        }
        dailyState.forEach { ds ->
            val line = buildJsonObject {
                put("type", "dailyState")
                put("data", json.encodeToJsonElement(DailyStateEntity.serializer(), ds))
            }
            writer.write(line.toString())
            writer.newLine()
        }
        compiledSections.forEach { cs ->
            val line = buildJsonObject {
                put("type", "compiledSection")
                put("data", json.encodeToJsonElement(CompiledSectionEntity.serializer(), cs))
            }
            writer.write(line.toString())
            writer.newLine()
        }
        scopedCompiledSections.forEach { cs ->
            val line = buildJsonObject {
                put("type", "scopedCompiledSection")
                put("data", json.encodeToJsonElement(ScopedCompiledSectionEntity.serializer(), cs))
            }
            writer.write(line.toString())
            writer.newLine()
        }

        // Step 6: 写 facts
        facts.forEach { fact ->
            val line = buildJsonObject {
                put("type", "fact")
                put("data", json.encodeToJsonElement(FactEntity.serializer(), fact))
            }
            writer.write(line.toString())
            writer.newLine()
        }

        // Step 7: 写扩展表(v3)
        writeTypedLines(writer, "assistant", assistants, AssistantEntity.serializer())
        writeTypedLines(writer, "lorebook", lorebooks, LorebookEntity.serializer())
        writeTypedLines(writer, "skill", skills, SkillEntity.serializer())
        writeTypedLines(writer, "artifact", artifacts, ArtifactEntity.serializer())
        writeTypedLines(writer, "quickMessage", quickMessages, QuickMessageEntity.serializer())
        writeTypedLines(writer, "promptInjection", promptInjections, PromptInjectionEntity.serializer())
        writeTypedLines(writer, "folder", folders, FolderEntity.serializer())
        writeTypedLines(writer, "groupChat", groupChats, GroupChatEntity.serializer())
        writeTypedLines(writer, "groupChatMessage", groupChatMessages, GroupChatMessageEntity.serializer())
        writeTypedLines(writer, "scheduledTask", scheduledTasks, ScheduledTaskEntity.serializer())
        writeTypedLines(writer, "scheduledTaskExecution", scheduledTaskExecutions, ScheduledTaskExecutionEntity.serializer())
        writeTypedLines(writer, "knowledgeDoc", knowledgeDocs, KnowledgeDocEntity.serializer())
        writeTypedLines(writer, "knowledgeChunk", knowledgeChunks, KnowledgeChunkEntity.serializer())
        writeTypedLines(writer, "experience", experiences, ExperienceEntity.serializer())
        writeTypedLines(writer, "milestone", milestones, MilestoneEntity.serializer())
        writeTypedLines(writer, "agentMessage", agentMessages, AgentMessageEntity.serializer())
        // v1.0.74: 朋友圈三表
        writeTypedLines(writer, "moment", moments, MomentEntity.serializer())
        writeTypedLines(writer, "momentComment", momentComments, MomentCommentEntity.serializer())
        writeTypedLines(writer, "momentLike", momentLikes, MomentLikeEntity.serializer())

        // Step 8: 写设置快照
        if (settingsSnapshot.isNotEmpty()) {
            val line = buildJsonObject {
                put("type", "settings")
                put("data", json.encodeToJsonElement(
                    MapSerializer(
                        String.serializer(),
                        String.serializer(),
                    ),
                    settingsSnapshot,
                ))
            }
            writer.write(line.toString())
            writer.newLine()
        }

        return NdJsonExportSummary(
            sessions = sessionCount,
            messages = messageCount,
            facts = facts.size,
            groupChatMessages = groupChatMessages.size,
        )
    }

    /**
     * NDJSON 流式写入 helper: 逐条序列化写入 type + data 行。
     */
    private fun <T> writeTypedLines(
        writer: BufferedWriter,
        type: String,
        items: List<T>,
        serializer: kotlinx.serialization.KSerializer<T>,
    ) {
        items.forEach { item ->
            val line = buildJsonObject {
                put("type", type)
                put("data", json.encodeToJsonElement(serializer, item))
            }
            writer.write(line.toString())
            writer.newLine()
        }
    }

    /**
     * B-25: [writeNdJson] 的统计结果,供导出方打印规模诊断日志。
     */
    private data class NdJsonExportSummary(
        val sessions: Int,
        val messages: Int,
        val facts: Int,
        val groupChatMessages: Int,
    )

    /**
     * B-32: [writeNdJson] 在单一 [MuseDb] 事务内读出的一致快照。
     * 事务提交后据此流式写出 NDJSON;持有实体对象列表(轻量)而不持有序列化巨串,
     * 兼顾一致快照与流式导出防 OOM 的目标。
     */
    private data class ExportedMuseSnapshot(
        val sessions: List<SessionEntity>,
        val messages: List<MessageEntity>,
        val assistants: List<AssistantEntity>,
        val lorebooks: List<LorebookEntity>,
        val skills: List<SkillEntity>,
        val artifacts: List<ArtifactEntity>,
        val quickMessages: List<QuickMessageEntity>,
        val promptInjections: List<PromptInjectionEntity>,
        val folders: List<FolderEntity>,
        val groupChats: List<GroupChatEntity>,
        val groupChatMessages: List<GroupChatMessageEntity>,
        val scheduledTasks: List<ScheduledTaskEntity>,
        val scheduledTaskExecutions: List<ScheduledTaskExecutionEntity>,
        val knowledgeDocs: List<KnowledgeDocEntity>,
        val knowledgeChunks: List<KnowledgeChunkEntity>,
        val experiences: List<ExperienceEntity>,
        val milestones: List<MilestoneEntity>,
        val agentMessages: List<AgentMessageEntity>,
        val moments: List<MomentEntity>,
        val momentComments: List<MomentCommentEntity>,
        val momentLikes: List<MomentLikeEntity>,
    )

    /**
     * v1.104: 流式解析 NDJSON 备份并分批插入 DB。
     *
     * 解决大备份(10000+ 消息)导入时全量累积 List 导致 OOM 的问题:
     *  - 逐行解析,按 type 分发到对应批次 buffer
     *  - buffer 达 [IMPORT_BATCH] 时用 withTransaction 分批插入并清空
     *  - 内存峰值 = 一批记录(约几百条),而非全量
     *  - 容错:跳过无法解析的行(继续后续记录,部分恢复)
     *
     * @return 导入的会话数 + 消息数
     */
    private suspend fun applyNdJsonStreaming(lines: Sequence<String>): Pair<Int, Int> {
        // v1.0.74 fix: 空备份保护 — 云端路径有"0 会话 0 消息拒绝恢复",本地路径没有,
        // 误选空/损坏文件会先清空全部表。先读 meta 行校验再决定是否继续。
        val lineIter = lines.iterator()
        val firstLine = if (lineIter.hasNext()) lineIter.next() else null
        if (!firstLine.isNullOrBlank()) {
            resultOf {
                val obj = json.decodeFromString(JsonObject.serializer(), firstLine)
                val type = obj["type"]?.let { (it as? JsonPrimitive)?.content }
                if (type == "meta") {
                    // B-21: 空备份守卫必须统计"所有实际存在的非零类型"。
                    // 旧实现只统计少数主表(sessions/messages/…),纯扩展表备份
                    // (如仅 knowledgeDocs/knowledgeChunks/experiences/milestones 有数据)会被误判为空而拒绝。
                    // 这里枚举 writeNdJson 写出的全部类型计数键,任一类型非零即视为有效备份。
                    val countKeys = listOf(
                        "sessions", "sessionSummaries", "dailyStates", "compiledSections", "scopedCompiledSections",
                        "facts",
                        "assistants", "lorebooks", "skills", "artifacts", "quickMessages",
                        "promptInjections", "folders", "groupChats", "groupChatMessages",
                        "scheduledTasks", "scheduledTaskExecutions", "knowledgeDocs",
                        "knowledgeChunks", "experiences", "milestones", "agentMessages",
                        "moments", "momentComments", "momentLikes",
                    )
                    val total = countKeys
                        .mapNotNull { obj[it]?.let { v -> (v as? JsonPrimitive)?.contentOrNull?.toIntOrNull() } }
                        .sum()
                    if (total == 0) {
                        throw IllegalArgumentException("空备份文件(meta 无任何数据),已拒绝导入")
                    }
                }
            }.onError { msg, t ->
                // meta 解析失败或空备份:抛给调用方,不清空任何表
                throw IllegalArgumentException(t?.message ?: msg)
            }
        } else {
            throw IllegalArgumentException("备份文件为空,已拒绝导入")
        }
        val remaining = sequenceOf(firstLine).plus(lineIter.asSequence())

        // 审计修复 (0.5): 清空 + 全部 MuseDb 插入包进同一个事务。
        // 原实现清空是独立事务、插入是分批独立事务,解析中途异常/取消会留下半空库
        // (对话与记忆全丢)。Room 嵌套 withTransaction 会 join 外层事务,
        // 任一分批失败 → 整个事务(含清空)回滚,数据保持导入前状态。
        // C-10: memoryDb / factDb 为独立数据库,无法与 MuseDb 跨库原子,
        // 故只在 MuseDb 事务成功后再于各自独立事务内统一"清空 + 插入",
        // 消除在 MuseDb 事务内提前提交 memory/fact 造成的跨库半状态及早前重复 flush。
        var sessionCount = 0
        var messageCount = 0
        try {
            // buffer 声明在事务外(尾部 memory/fact 事务块也要访问)
            val sessionBuf = mutableListOf<SessionEntity>()
            val messageBuf = mutableListOf<MessageEntity>()
            val summaryBuf = mutableListOf<SessionSummaryEntity>()
            val dailyBuf = mutableListOf<DailyStateEntity>()
            val compiledBuf = mutableListOf<CompiledSectionEntity>()
            val scopedCompiledBuf = mutableListOf<ScopedCompiledSectionEntity>()
            val factBuf = mutableListOf<FactEntity>()
            // v3: 扩展表 buffer
            val assistantBuf = mutableListOf<AssistantEntity>()
            val lorebookBuf = mutableListOf<LorebookEntity>()
            val skillBuf = mutableListOf<SkillEntity>()
            val artifactBuf = mutableListOf<ArtifactEntity>()
            val quickMsgBuf = mutableListOf<QuickMessageEntity>()
            val promptInjBuf = mutableListOf<PromptInjectionEntity>()
            val folderBuf = mutableListOf<FolderEntity>()
            val groupChatBuf = mutableListOf<GroupChatEntity>()
            val groupChatMsgBuf = mutableListOf<GroupChatMessageEntity>()
            val schedTaskBuf = mutableListOf<ScheduledTaskEntity>()
            val schedExecBuf = mutableListOf<ScheduledTaskExecutionEntity>()
            val knowDocBuf = mutableListOf<KnowledgeDocEntity>()
            val knowChunkBuf = mutableListOf<KnowledgeChunkEntity>()
            val experienceBuf = mutableListOf<ExperienceEntity>()
            val milestoneBuf = mutableListOf<MilestoneEntity>()
            val agentMsgBuf = mutableListOf<AgentMessageEntity>()
            // v1.0.74: 朋友圈三表
            val momentBuf = mutableListOf<MomentEntity>()
            val momentCommentBuf = mutableListOf<MomentCommentEntity>()
            val momentLikeBuf = mutableListOf<MomentLikeEntity>()
            var settingsSnapshot: Map<String, String> = emptyMap()

            db.withTransaction {
                // 1. 先清空所有表
                db.messageDao().deleteAll()
                db.sessionDao().deleteAll()
                db.assistantDao().deleteAll()
                db.lorebookDao().deleteAll()
                db.skillDao().deleteAll()
                db.artifactDao().deleteAll()
                db.quickMessageDao().deleteAll()
                db.promptInjectionDao().deleteAll()
                db.folderDao().deleteAll()
                db.groupChatMessageDao().deleteAll()
                db.groupChatDao().deleteAll()
                db.scheduledTaskExecutionDao().deleteAll()
                db.scheduledTaskDao().deleteAll()
                db.knowledgeChunkDao().deleteAll()
                db.knowledgeDocDao().deleteAll()
                db.experienceDao().deleteAll()
                db.milestoneDao().deleteAll()
                db.agentMessageDao().deleteAll()
                // v1.0.74: 朋友圈三表
                db.momentDao().deleteAllMoments()
                db.momentDao().deleteAllComments()
                db.momentDao().deleteAllLikes()

            // 2. 逐行解析 + 分批插入(仍在 db.withTransaction 事务内,任一失败整体回滚)
        remaining.forEachIndexed { idx, line ->
            if (line.isBlank()) return@forEachIndexed
            resultOf {
                val obj = json.decodeFromString(JsonObject.serializer(), line)
                val type = obj["type"]?.let { (it as? JsonPrimitive)?.content } ?: return@resultOf
                when (type) {
                    "meta" -> { /* version/exportedAt 元信息,流式插入不需要 */ }
                    "session" -> obj["data"]?.let {
                        sessionBuf.add(json.decodeFromJsonElement(SessionEntity.serializer(), it))
                        if (sessionBuf.size >= IMPORT_BATCH) {
                            sessionCount += sessionBuf.size
                            flushBatch(sessionBuf) { batch -> db.withTransaction { batch.forEach { db.sessionDao().insert(it) } } }
                        }
                    }
                    "message" -> obj["data"]?.let {
                        messageBuf.add(json.decodeFromJsonElement(MessageEntity.serializer(), it))
                        if (messageBuf.size >= IMPORT_BATCH) {
                            messageCount += messageBuf.size
                            flushBatch(messageBuf) { batch -> db.withTransaction { batch.forEach { db.messageDao().upsert(it) } } }
                        }
                    }
                    "summary" -> obj["data"]?.let {
                        summaryBuf.add(json.decodeFromJsonElement(SessionSummaryEntity.serializer(), it))
                        // C-10: memory/fact 缓冲只在 MuseDb 事务成功后于各自独立事务内统一提交,
                        // 不再在 MuseDb 事务内循环中提前 flush(旧实现会在 MuseDb 回滚时留下已提交的 memory 半状态)。
                    }
                    "dailyState" -> obj["data"]?.let {
                        dailyBuf.add(json.decodeFromJsonElement(DailyStateEntity.serializer(), it))
                    }
                    "compiledSection" -> obj["data"]?.let {
                        compiledBuf.add(json.decodeFromJsonElement(CompiledSectionEntity.serializer(), it))
                    }
                    "scopedCompiledSection" -> obj["data"]?.let {
                        scopedCompiledBuf.add(json.decodeFromJsonElement(ScopedCompiledSectionEntity.serializer(), it))
                    }
                    "fact" -> obj["data"]?.let {
                        factBuf.add(json.decodeFromJsonElement(FactEntity.serializer(), it))
                    }
                    // v3: 扩展表类型
                    "assistant" -> obj["data"]?.let {
                        assistantBuf.add(json.decodeFromJsonElement(AssistantEntity.serializer(), it))
                        if (assistantBuf.size >= IMPORT_BATCH) flushBatch(assistantBuf) { batch -> db.withTransaction { db.assistantDao().insertAll(batch) } }
                    }
                    "lorebook" -> obj["data"]?.let {
                        lorebookBuf.add(json.decodeFromJsonElement(LorebookEntity.serializer(), it))
                        if (lorebookBuf.size >= IMPORT_BATCH) flushBatch(lorebookBuf) { batch -> db.withTransaction { db.lorebookDao().insertAll(batch) } }
                    }
                    "skill" -> obj["data"]?.let {
                        skillBuf.add(json.decodeFromJsonElement(SkillEntity.serializer(), it))
                        if (skillBuf.size >= IMPORT_BATCH) flushBatch(skillBuf) { batch -> db.withTransaction { batch.forEach { db.skillDao().upsert(it) } } }
                    }
                    "artifact" -> obj["data"]?.let {
                        artifactBuf.add(json.decodeFromJsonElement(ArtifactEntity.serializer(), it))
                        if (artifactBuf.size >= IMPORT_BATCH) flushBatch(artifactBuf) { batch -> db.withTransaction { batch.forEach { db.artifactDao().upsert(it) } } }
                    }
                    "quickMessage" -> obj["data"]?.let {
                        quickMsgBuf.add(json.decodeFromJsonElement(QuickMessageEntity.serializer(), it))
                        if (quickMsgBuf.size >= IMPORT_BATCH) flushBatch(quickMsgBuf) { batch -> db.withTransaction { db.quickMessageDao().insertAll(batch) } }
                    }
                    "promptInjection" -> obj["data"]?.let {
                        promptInjBuf.add(json.decodeFromJsonElement(PromptInjectionEntity.serializer(), it))
                        if (promptInjBuf.size >= IMPORT_BATCH) flushBatch(promptInjBuf) { batch -> db.withTransaction { db.promptInjectionDao().insertAll(batch) } }
                    }
                    "folder" -> obj["data"]?.let {
                        folderBuf.add(json.decodeFromJsonElement(FolderEntity.serializer(), it))
                        if (folderBuf.size >= IMPORT_BATCH) flushBatch(folderBuf) { batch -> db.withTransaction { batch.forEach { db.folderDao().insert(it) } } }
                    }
                    "groupChat" -> obj["data"]?.let {
                        groupChatBuf.add(json.decodeFromJsonElement(GroupChatEntity.serializer(), it))
                        if (groupChatBuf.size >= IMPORT_BATCH) flushBatch(groupChatBuf) { batch -> db.withTransaction { batch.forEach { db.groupChatDao().upsert(it) } } }
                    }
                    "groupChatMessage" -> obj["data"]?.let {
                        groupChatMsgBuf.add(json.decodeFromJsonElement(GroupChatMessageEntity.serializer(), it))
                        if (groupChatMsgBuf.size >= IMPORT_BATCH) flushBatch(groupChatMsgBuf) { batch -> db.withTransaction { db.groupChatMessageDao().insertAll(batch) } }
                    }
                    "scheduledTask" -> obj["data"]?.let {
                        schedTaskBuf.add(json.decodeFromJsonElement(ScheduledTaskEntity.serializer(), it))
                        if (schedTaskBuf.size >= IMPORT_BATCH) flushBatch(schedTaskBuf) { batch -> db.withTransaction { batch.forEach { db.scheduledTaskDao().upsert(it) } } }
                    }
                    "scheduledTaskExecution" -> obj["data"]?.let {
                        schedExecBuf.add(json.decodeFromJsonElement(ScheduledTaskExecutionEntity.serializer(), it))
                        if (schedExecBuf.size >= IMPORT_BATCH) flushBatch(schedExecBuf) { batch -> db.withTransaction { batch.forEach { db.scheduledTaskExecutionDao().insert(it) } } }
                    }
                    "knowledgeDoc" -> obj["data"]?.let {
                        knowDocBuf.add(json.decodeFromJsonElement(KnowledgeDocEntity.serializer(), it))
                        if (knowDocBuf.size >= IMPORT_BATCH) flushBatch(knowDocBuf) { batch -> db.withTransaction { batch.forEach { db.knowledgeDocDao().upsert(it) } } }
                    }
                    "knowledgeChunk" -> obj["data"]?.let {
                        knowChunkBuf.add(json.decodeFromJsonElement(KnowledgeChunkEntity.serializer(), it))
                        if (knowChunkBuf.size >= IMPORT_BATCH) flushBatch(knowChunkBuf) { batch -> db.withTransaction { db.knowledgeChunkDao().insertAll(batch) } }
                    }
                    "experience" -> obj["data"]?.let {
                        experienceBuf.add(json.decodeFromJsonElement(ExperienceEntity.serializer(), it))
                        if (experienceBuf.size >= IMPORT_BATCH) flushBatch(experienceBuf) { batch -> db.withTransaction { batch.forEach { db.experienceDao().upsert(it) } } }
                    }
                    "milestone" -> obj["data"]?.let {
                        milestoneBuf.add(json.decodeFromJsonElement(MilestoneEntity.serializer(), it))
                        if (milestoneBuf.size >= IMPORT_BATCH) flushBatch(milestoneBuf) { batch -> db.withTransaction { batch.forEach { db.milestoneDao().upsert(it) } } }
                    }
                    "agentMessage" -> obj["data"]?.let {
                        agentMsgBuf.add(json.decodeFromJsonElement(AgentMessageEntity.serializer(), it))
                        if (agentMsgBuf.size >= IMPORT_BATCH) flushBatch(agentMsgBuf) { batch -> db.withTransaction { batch.forEach { db.agentMessageDao().upsert(it) } } }
                    }
                    // v1.0.74: 朋友圈三表
                    "moment" -> obj["data"]?.let {
                        momentBuf.add(json.decodeFromJsonElement(MomentEntity.serializer(), it))
                        if (momentBuf.size >= IMPORT_BATCH) flushBatch(momentBuf) { batch -> db.withTransaction { batch.forEach { db.momentDao().insertMoment(it) } } }
                    }
                    "momentComment" -> obj["data"]?.let {
                        momentCommentBuf.add(json.decodeFromJsonElement(MomentCommentEntity.serializer(), it))
                        if (momentCommentBuf.size >= IMPORT_BATCH) flushBatch(momentCommentBuf) { batch -> db.withTransaction { batch.forEach { db.momentDao().insertComment(it) } } }
                    }
                    "momentLike" -> obj["data"]?.let {
                        momentLikeBuf.add(json.decodeFromJsonElement(MomentLikeEntity.serializer(), it))
                        if (momentLikeBuf.size >= IMPORT_BATCH) flushBatch(momentLikeBuf) { batch -> db.withTransaction { batch.forEach { db.momentDao().addLike(it) } } }
                    }
                    "settings" -> obj["data"]?.let {
                        settingsSnapshot = json.decodeFromJsonElement(
                            MapSerializer(
                                String.serializer(),
                                String.serializer(),
                            ),
                            it,
                        )
                    }
                }
            }.onError { msg, t ->
                Logger.w("BackupService", "第 ${idx + 1} 行解析失败,跳过: ${t?.message ?: msg}")
            }
        }

        // 3. flush 剩余 buffer(C-10: memory/fact 缓冲这里不再 flush,
        // 统一在 MuseDb 事务成功后的独立事务内一次性 deleteAll + 插入)。
        if (sessionBuf.isNotEmpty()) {
            sessionCount += sessionBuf.size
            flushBatch(sessionBuf) { batch -> db.withTransaction { batch.forEach { db.sessionDao().insert(it) } } }
        }
        if (messageBuf.isNotEmpty()) {
            messageCount += messageBuf.size
            flushBatch(messageBuf) { batch -> db.withTransaction { batch.forEach { db.messageDao().upsert(it) } } }
        }
        // v3: flush 扩展表
        flushBatch(assistantBuf) { batch -> db.withTransaction { db.assistantDao().insertAll(batch) } }
        flushBatch(lorebookBuf) { batch -> db.withTransaction { db.lorebookDao().insertAll(batch) } }
        flushBatch(skillBuf) { batch -> db.withTransaction { batch.forEach { db.skillDao().upsert(it) } } }
        flushBatch(artifactBuf) { batch -> db.withTransaction { batch.forEach { db.artifactDao().upsert(it) } } }
        flushBatch(quickMsgBuf) { batch -> db.withTransaction { db.quickMessageDao().insertAll(batch) } }
        flushBatch(promptInjBuf) { batch -> db.withTransaction { db.promptInjectionDao().insertAll(batch) } }
        flushBatch(folderBuf) { batch -> db.withTransaction { batch.forEach { db.folderDao().insert(it) } } }
        flushBatch(groupChatBuf) { batch -> db.withTransaction { batch.forEach { db.groupChatDao().upsert(it) } } }
        flushBatch(groupChatMsgBuf) { batch -> db.withTransaction { db.groupChatMessageDao().insertAll(batch) } }
        flushBatch(schedTaskBuf) { batch -> db.withTransaction { batch.forEach { db.scheduledTaskDao().upsert(it) } } }
        flushBatch(schedExecBuf) { batch -> db.withTransaction { batch.forEach { db.scheduledTaskExecutionDao().insert(it) } } }
        flushBatch(knowDocBuf) { batch -> db.withTransaction { batch.forEach { db.knowledgeDocDao().upsert(it) } } }
        flushBatch(knowChunkBuf) { batch -> db.withTransaction { db.knowledgeChunkDao().insertAll(batch) } }
        flushBatch(experienceBuf) { batch -> db.withTransaction { batch.forEach { db.experienceDao().upsert(it) } } }
        flushBatch(milestoneBuf) { batch -> db.withTransaction { batch.forEach { db.milestoneDao().upsert(it) } } }
        flushBatch(agentMsgBuf) { batch -> db.withTransaction { batch.forEach { db.agentMessageDao().upsert(it) } } }
        // v1.0.74: 朋友圈三表
        flushBatch(momentBuf) { batch -> db.withTransaction { batch.forEach { db.momentDao().insertMoment(it) } } }
        flushBatch(momentCommentBuf) { batch -> db.withTransaction { batch.forEach { db.momentDao().insertComment(it) } } }
        flushBatch(momentLikeBuf) { batch -> db.withTransaction { batch.forEach { db.momentDao().addLike(it) } } }
            } // 审计 0.5: MuseDb 大事务闭合(失败整体回滚,含清空)

            // C-10: memory/fact 依赖独立数据库,无法与 MuseDb 跨库原子。
            // 只在 MuseDb 事务成功(走到这里)后,各自在单一事务内"先清空旧数据再插入新数据",
            // 避免旧实现"插入→再清空→缓冲已空无法二次插入"导致的 memory/fact 数据丢失,
            // 也消除了在 MuseDb 事务内提前 flush 造成的提前提交。
            // 取舍:memory/fact 记录全部暂存内存后一次性落库,可接受——
            // 其数据量远小于 messages,且换来了正确的先后顺序与一致的提交边界。
            if (summaryBuf.isNotEmpty() || dailyBuf.isNotEmpty() || compiledBuf.isNotEmpty() || scopedCompiledBuf.isNotEmpty()) {
                memoryDb.withTransaction {
                    memoryDb.sessionSummaryDao().deleteAll()
                    memoryDb.dailyStateDao().deleteAll()
                    memoryDb.compiledSectionDao().deleteAll()
                    memoryDb.scopedCompiledSectionDao().deleteAll()
                    summaryBuf.forEach { memoryDb.sessionSummaryDao().upsert(it) }
                    dailyBuf.forEach { memoryDb.dailyStateDao().upsert(it) }
                    compiledBuf.forEach { memoryDb.compiledSectionDao().upsert(it) }
                    scopedCompiledBuf.forEach { memoryDb.scopedCompiledSectionDao().upsert(it) }
                }
            }
            if (factBuf.isNotEmpty()) {
                factDb.withTransaction {
                    factDb.factDao().deleteAll()
                    factDb.factDao().insertAll(factBuf.map { it.copy(id = 0) })
                }
            }

            // 恢复设置快照
            if (settingsSnapshot.isNotEmpty()) {
                settings.restoreSettingsSnapshot(settingsSnapshot)
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("BackupService", "流式导入失败,已回滚: ${e.message}", e)
            throw e
        }
        // v1.0.74 fix: 直插 messages 绕过 FTS 同步,启动时 ensureFtsIndexConsistent 的计数启发式
        // (消息数恰好相同)会跳过 rebuild,搜索索引停留在导入前。导入完成显式重建。
        resultOf { sessionRepository.rebuildFtsIndex() }
            .onError { msg, t -> Logger.w("BackupService", "导入后 FTS 重建失败: ${t?.message ?: msg}") }

        Logger.i("BackupService", "流式导入完成: $sessionCount 会话, $messageCount 消息")
        return sessionCount to messageCount
    }

    /** 分批插入 helper:非空时执行插入并清空 buffer。 */
    private suspend fun <T> flushBatch(buf: MutableList<T>, insert: suspend (List<T>) -> Unit) {
        if (buf.isEmpty()) return
        insert(buf.toList())
        buf.clear()
    }

    companion object {
        /** 流式导入每批插入条数(平衡事务开销与内存峰值)。 */
        private const val IMPORT_BATCH = 500

        /**
         * B-25: 单 JSON 备份最大体量上限(字节,64MB)。
         * 超过即视为恶意/损坏备份,导入时拒绝解析以防 OOM。
         */
        internal const val MAX_SINGLE_JSON_BACKUP_BYTES: Long = 64L * 1024 * 1024

        /**
         * B-25: 判断单 JSON 备份累计体量是否仍在上限内。
         * 逐行读入时以 [cumulativeUtf8Bytes] 累计已消费字节,追加 [additionalUtf8Bytes] 后判断是否超限。
         * @return true 未超限;false 已超限(调用方应中止并报错)。
         */
        internal fun withinSingleJsonLimit(cumulativeUtf8Bytes: Long, additionalUtf8Bytes: Long): Boolean =
            cumulativeUtf8Bytes <= MAX_SINGLE_JSON_BACKUP_BYTES - additionalUtf8Bytes

        /** B-25: 生成单 JSON 备份超限的明确错误信息(含当前体量字节与默认上限 64MB)。 */
        internal fun singleJsonTooLargeMessage(cumulativeUtf8Bytes: Long): String =
            "单 JSON 备份体量 ${(cumulativeUtf8Bytes + 1024 * 1024 - 1) / (1024 * 1024)}MB 超过上限 " +
                "${MAX_SINGLE_JSON_BACKUP_BYTES / (1024 * 1024)}MB,已拒绝导入"

        /**
         * 问题7.3: 设备相关设置 key 集合,恢复 settingsSnapshot 时跳过这些 key。
         *
         * - "theme_mode":主题模式可能跟随系统/设备状态,跨设备恢复无意义
         * - "bool:dynamic_color":Material You 动态色彩依赖设备壁纸,跨设备不适用
         * - "display_density"/"window_width_class":预留(当前快照未导出,但作为未来扩展防御)
         */
        private val DEVICE_SPECIFIC_KEYS = setOf(
            "theme_mode",
            "bool:dynamic_color",
            "display_density",
            "window_width_class",
        )
    }

    /**
     * Phase 8.9: 上传当前数据到云端(S3 / WebDAV)。
     * 同时上传带时间戳的归档版本 + muse-backup-latest.json(用于快速恢复)。
     * @return true 成功;false 失败或未配置云备份
     */
    /**
     * F-04: 云备份结果分类。区别于布尔值,失败原因决定通知文案与诊断日志:
     *  - [WRITE_FAILED]: 上传本身失败(网络/远端拒绝/鉴权);
     *  - [VERIFY_FAILED]: 上传成功但读回校验失败(大小不匹配/读回为空),数据可能不完整;
     *  - [NOT_CONFIGURED]: 未配置云备份,不打扰用户。
     */
    enum class CloudBackupOutcome { SUCCESS, WRITE_FAILED, VERIFY_FAILED, NOT_CONFIGURED }

    /**
     * F-04: 写入备份记录(auto_backup_log 表,诊断页可见)。
     * @param status success / write_failed / verify_failed
     */
    private suspend fun logCloudBackup(status: String, size: Long, messageCount: Long, error: String) {
        try {
            autoBackupLogDao.insert(
                AutoBackupLogEntity(
                    backupPath = "cloud",
                    fileSizeBytes = size,
                    status = status,
                    errorMessage = error,
                    messageCount = messageCount,
                ),
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 日志写入失败不阻断备份主流程,仅记录
            Logger.w("BackupService", "logCloudBackup failed: ${e.message}")
        }
    }

    /**
     * F-04: 云备份三段式收据 — 写入 + 读回校验 + 记录。
     *
     * 历史事故: 上传成功即返回 true, 无任何校验; 云端静默丢字节/半写时用户
     * 无从得知备份已损坏, 恢复后数据缺失。现流程:
     *  1. 写出 NDJSON(内存字节,与旧实现一致);
     *  2. 上传至云端;
     *  3. 上传成功后 [downloadLatestBackup] 读回并比对字节数(写入后读回校验);
     *  4. 结果(含失败原因)写入 auto_backup_log, 供备份页诊断展示。
     */
    suspend fun exportToCloud(): CloudBackupOutcome {
        val config = settings.cloudBackupConfigFlow.first()
        // ReturnCount 约束(阈值 2): 全程仅 1 个 return, 结果统一 when 汇总
        return when {
            !config.isConfigured -> CloudBackupOutcome.NOT_CONFIGURED
            else -> {
                val write = writeBackupToCloud(config)
                when {
                    write == null -> CloudBackupOutcome.WRITE_FAILED
                    !verifyCloudBackupWrite(config, write) -> CloudBackupOutcome.VERIFY_FAILED
                    else -> {
                        settings.saveCloudBackupConfig(config.copy(lastSyncAt = System.currentTimeMillis()))
                        CloudBackupOutcome.SUCCESS
                    }
                }
            }
        }
    }

    /** F-04: 一次云备份写入的规模信息(供读回校验与日志)。 */
    private data class CloudWrite(val bytes: Long, val messages: Long)

    /**
     * F-04: 写出 NDJSON(内存字节)并上传云端;失败记录 write_failed 日志。
     * @return 上传成功时的规模信息;null 表示写入/上传失败
     */
    private suspend fun writeBackupToCloud(config: CloudBackupConfig): CloudWrite? {
        // B-25: 复用 writeNdJson 流式产物 — 逐条序列化写入字节缓冲,不再 buildBackup + encodeToString
        // (Backup 全量对象 + 巨串 + 字节数组三份内存驻留)。云端上传 API 需整份字节,
        // 这里峰值仅一份 NDJSON 字节数组(明文或加密后),大幅降低万级消息的 OOM 风险。
        val plaintext: ByteArray
        val writeSummary: NdJsonExportSummary
        ByteArrayOutputStream().use { baos ->
            BufferedWriter(OutputStreamWriter(baos, Charsets.UTF_8)).use { writer ->
                writeSummary = writeNdJson(writer)
            }
            plaintext = baos.toByteArray()
        }
        // v1.x 诊断: 记录导出规模,便于排查"备份不完整/恢复后数据丢失"类问题
        Logger.i(
            "BackupService",
            "导出云端备份: ${writeSummary.sessions} 会话 / ${writeSummary.messages} 消息" +
                " / ${writeSummary.facts} 记忆 / ${writeSummary.groupChatMessages} 群聊消息",
        )
        val data = if (config.backupPassword.isNotEmpty()) {
            BackupCrypto.encrypt(plaintext, config.backupPassword)
        } else {
            plaintext
        }
        val uploaded = cloudBackupService.uploadBackupWithLatest(config, data)
        if (!uploaded) {
            Logger.w("BackupService", "云备份上传失败(写入阶段)")
            logCloudBackup(
                status = "write_failed",
                size = data.size.toLong(),
                messageCount = writeSummary.messages.toLong(),
                error = "upload failed",
            )
            return null
        }
        return CloudWrite(data.size.toLong(), writeSummary.messages.toLong())
    }

    /**
     * F-04: 写入后读回校验 — 下载刚上传的备份并比对字节数。
     * 校验结果(成功或 verify_failed)写入 auto_backup_log。
     */
    private suspend fun verifyCloudBackupWrite(config: CloudBackupConfig, write: CloudWrite): Boolean {
        val readBack = cloudBackupService.downloadLatestBackup(config)
        val verified = readBack != null && readBack.size.toLong() == write.bytes
        if (!verified) {
            Logger.w(
                "BackupService",
                "云备份读回校验失败: 上传 ${write.bytes} bytes, 读回 ${readBack?.size ?: -1} bytes",
            )
            logCloudBackup(
                status = "verify_failed",
                size = write.bytes,
                messageCount = write.messages,
                error = "read-back mismatch: uploaded ${write.bytes}, got ${readBack?.size ?: -1}",
            )
            return false
        }
        logCloudBackup(
            status = "success",
            size = write.bytes,
            messageCount = write.messages,
            error = "",
        )
        return true
    }

    /**
     * Phase 8.9: 从云端下载最新备份并导入。
     * @return 导入的会话数 + 消息数;null 表示无备份或下载失败
     */
    suspend fun importFromCloud(): Pair<Int, Int>? {
        val config = settings.cloudBackupConfigFlow.first()
        if (!config.isConfigured) return null
        val data = cloudBackupService.downloadLatestBackup(config) ?: return null
        return applyCloudBackupData(config, data)
    }

    /**
     * v1.132: 从云端下载指定归档版本并导入(供 CloudBackupPage "按版本恢复"使用)。
     * @param fileName 归档文件名(如 muse-backup-20250719-153000.json)
     * @return 导入的会话数 + 消息数;null 表示无备份或下载失败
     */
    suspend fun importFromCloudFile(fileName: String): Pair<Int, Int>? {
        val config = settings.cloudBackupConfigFlow.first()
        if (!config.isConfigured) return null
        val data = cloudBackupService.downloadBackup(config, fileName) ?: return null
        return applyCloudBackupData(config, data)
    }

    /**
     * v1.132: 把云端下载的(可能加密的)字节流解密、解析并导入到本地 DB。
     * 抽出公共逻辑供 [importFromCloud] / [importFromCloudFile] 复用。
     * @return 导入的会话数 + 消息数;null 表示解密/解析失败
     */
    private suspend fun applyCloudBackupData(config: CloudBackupConfig, data: ByteArray): Pair<Int, Int>? {
        val plaintext = if (BackupCrypto.isEncrypted(data)) {
            if (config.backupPassword.isEmpty()) {
                Logger.w("BackupService", "云端备份已加密但未设置备份密码,无法解密")
                return null
            }
            resultOf { BackupCrypto.decrypt(data, config.backupPassword) }
                .onError { msg, t -> Logger.w("BackupService", "云端备份解密失败(密码错误?)", t); return null }
                .getOrNull() ?: return null
        } else {
            data
        }

        // B-23: 不再整份 plaintext.toString + lineSequence(会把解密后明文翻倍驻留内存)。
        // 改用 BufferedReader 逐行流式读取,仅在真正需要全量解析的单 JSON 分支才整读。
        val rawReader = plaintext.inputStream().bufferedReader(Charsets.UTF_8)
        val firstLine = generateSequence { rawReader.readLine() }
            .firstOrNull { !it.isNullOrBlank() }
            ?: run {
                rawReader.close()
                return null
            }
        val isNdJson = resultOf {
            val obj = json.decodeFromString(JsonObject.serializer(), firstLine)
            obj["type"]?.let { (it as? JsonPrimitive)?.content } == "meta"
        }.getOrNull() ?: false

        return if (isNdJson) {
            // B-23: 首行已消费,继续从 reader 逐行流式导入,避免 NDJSON 整份字符串驻留。
            rawReader.use { reader ->
                val rest = generateSequence { reader.readLine() }
                    .takeWhile { line -> line != null }
                    .map { line -> line!! }
                resultOf { applyNdJsonStreaming(sequenceOf(firstLine) + rest) }
                    .onError { msg, t -> Logger.w("BackupService", "云端 NDJSON 流式导入失败", t); null }
                    .getOrNull()
            }
        } else {
            rawReader.use { reader ->
                // B-25: 云端正本已整份驻留内存(下载 + 解密),无法杜绝下载期 OOM;
                // B-23: 单 JSON 路径必须整份 decode 到内存,因此保留体量上限拦截以防解析 OOM,
                // 上限可经构造参数 [singleJsonMaxBytes] 调大以兼容更大合法备份(向后兼容默认 64MB)。
                val backup = resultOf {
                    json.decodeFromString(Backup.serializer(), readSingleJsonWithLimit(reader, firstLine))
                }.onError { msg, t ->
                    Logger.w("BackupService", "云端单 JSON 备份读取/解析失败,拒绝恢复: ${t?.message ?: msg}", t)
                    null
                }.getOrNull() ?: return null
                // v1.x 数据安全: 云端备份为空(0 会话 0 消息)时拒绝恢复,
                // 避免"空备份覆盖本地数据"导致对话全部丢失(用户反馈 WebDAV 恢复后对话全没了)。
                if (backup.sessions.isEmpty() && backup.messages.isEmpty()) {
                    Logger.w("BackupService", "云端备份为空(0 会话 0 消息),拒绝恢复以免覆盖本地数据")
                    return null
                }
                applyBackup(backup)
            }
        }
    }

    /**
     * Phase 8.9: 检查云端是否存在备份文件。
     */
    suspend fun hasCloudBackup(): Boolean {
        val config = settings.cloudBackupConfigFlow.first()
        if (!config.isConfigured) return false
        return cloudBackupService.hasBackup(config)
    }

    /**
     * Phase 8.9: 收集全部数据生成 [Backup](供本地导出和云端上传复用)。
     * v3: 包含所有 MuseDb 用户数据表 + DataStore 设置快照。
     */
    private suspend fun buildBackup(): Backup {
        val sessions = db.sessionDao().observeAll().first()
        val allMessages = sessions.flatMap { session ->
            db.messageDao().observeBySession(session.id).first()
        }
        // memory 数据(4 张表)
        val sessionSummaries = memoryDb.sessionSummaryDao().getAll()
        val compiledSections = memoryDb.compiledSectionDao().getAll()
        val scopedCompiledSections = memoryDb.scopedCompiledSectionDao().getAll()
        val dailyState = memoryDb.dailyStateDao().get()?.let { listOf(it) } ?: emptyList()
        val facts = factDb.factDao().getAll()
        // v3: 扩展表
        val assistants = db.assistantDao().getAll()
        val lorebooks = db.lorebookDao().getAll()
        val skills = db.skillDao().getAll()
        val artifacts = db.artifactDao().getAll()
        val quickMessages = db.quickMessageDao().getAll()
        val promptInjections = db.promptInjectionDao().getAll()
        val folders = db.folderDao().getAll()
        val groupChats = db.groupChatDao().getAll()
        val groupChatMessages = db.groupChatMessageDao().getAll()
        val scheduledTasks = db.scheduledTaskDao().getAll()
        val scheduledTaskExecutions = db.scheduledTaskExecutionDao().getAll()
        val knowledgeDocs = db.knowledgeDocDao().getAll()
        val knowledgeChunks = db.knowledgeChunkDao().getAll()
        val experiences = db.experienceDao().getAll()
        val milestones = db.milestoneDao().getAll()
        val agentMessages = db.agentMessageDao().getAll()
        // v1.0.74: 朋友圈三表
        val moments = db.momentDao().getAll()
        val momentComments = db.momentDao().getAllComments()
        val momentLikes = db.momentDao().getAllLikes()
        // 设置快照
        val settingsSnapshot = settings.exportSettingsSnapshot()

        return Backup(
            version = 3,
            exportedAt = System.currentTimeMillis(),
            sessions = sessions,
            messages = allMessages,
            sessionSummaries = sessionSummaries,
            dailyStates = dailyState,
            compiledSections = compiledSections,
            scopedCompiledSections = scopedCompiledSections,
            facts = facts,
            assistants = assistants,
            lorebooks = lorebooks,
            skills = skills,
            artifacts = artifacts,
            quickMessages = quickMessages,
            promptInjections = promptInjections,
            folders = folders,
            groupChats = groupChats,
            groupChatMessages = groupChatMessages,
            scheduledTasks = scheduledTasks,
            scheduledTaskExecutions = scheduledTaskExecutions,
            knowledgeDocs = knowledgeDocs,
            knowledgeChunks = knowledgeChunks,
            experiences = experiences,
            milestones = milestones,
            agentMessages = agentMessages,
            moments = moments,
            momentComments = momentComments,
            momentLikes = momentLikes,
            settingsSnapshot = settingsSnapshot,
        )
    }

    /**
     * 把 [Backup] 应用到所有 DB(供本地导入和云端恢复复用)。
     * v3: 恢复所有 MuseDb 用户数据表 + DataStore 设置快照。
     *
     * 问题7.1: 跨三个独立 DB 的事务中途失败会导致数据全丢。
     * 改造为"导入前快照 + 失败回滚":先 buildBackup() 拿当前数据快照,
     * 任一 DB 写入抛异常时用快照重新 apply 一次,尽力恢复导入前状态。
     * 问题7.2: 入口先调用 [migrateBackup] 做版本迁移(v1/v2 → v3)。
     * 问题7.3: 恢复 settingsSnapshot 时过滤掉设备相关 key。
     *
     * @return 导入的会话数 + 消息数
     */
    private suspend fun applyBackup(backup: Backup): Pair<Int, Int> {
        // 问题7.2: 版本迁移(v1/v2 → v3),Backup data class 字段都有默认值,补 version 即可
        val migrated = migrateBackup(backup)

        // 问题7.1: 导入前先快照当前数据作为回滚点(内存中,失败时用其恢复)
        val preImportSnapshot = resultOf { buildBackup() }
            .onError { msg, t -> Logger.w("BackupService", "导入前快照失败,无回滚安全网: ${t?.message ?: msg}") }
            .getOrNull()

        return try {
            applyBackupInternal(migrated)
        } catch (e: Exception) {
            // 问题7.1: 导入中途失败,尝试用导入前快照回滚,避免数据全丢
            Logger.w("BackupService", "applyBackup 失败,尝试回滚到导入前状态: ${e.message}", e)
            if (preImportSnapshot != null) {
                resultOf { applyBackupInternal(preImportSnapshot) }
                    .onError { msg, t -> Logger.w("BackupService", "回滚失败,数据可能仍处于不一致状态: $msg", t) }
            }
            throw e
        }
    }

    /**
     * 问题7.2: 备份版本迁移钩子。
     *
     * - v3: 当前版本,无需迁移
     * - v1/v2: 旧版备份仅含 sessions + messages + memory 数据(扩展表为空),
     *   Backup data class 新增字段都有默认值,补 version=3 即可
     * - 未知版本: 警告并按 v3 处理
     */
    private fun migrateBackup(backup: Backup): Backup = when (backup.version) {
        3 -> backup
        1, 2 -> {
            Logger.i("BackupService", "迁移备份 v${backup.version} → v3:补默认扩展表字段")
            backup.copy(version = 3)
        }
        else -> {
            Logger.w("BackupService", "未知备份版本 v${backup.version},按当前版本 v3 处理")
            backup.copy(version = 3)
        }
    }

    /**
     * 实际执行清空 + 插入的逻辑(供 [applyBackup] 与回滚复用,不再带回滚)。
     *
     * 注意:此方法跨三个独立 DB 各自开事务,无法做到原子性;调用方([applyBackup])负责回滚。
     */
    private suspend fun applyBackupInternal(backup: Backup): Pair<Int, Int> {
        // 1. 导入 MuseDb 主表(sessions + messages)
        db.withTransaction {
            db.messageDao().deleteAll()
            db.sessionDao().deleteAll()
            backup.sessions.forEach { db.sessionDao().insert(it) }
            backup.messages.forEach { db.messageDao().upsert(it) }

            // v3: 扩展表(在同一事务中清空 + 插入)
            db.assistantDao().deleteAll()
            db.lorebookDao().deleteAll()
            db.skillDao().deleteAll()
            db.artifactDao().deleteAll()
            db.quickMessageDao().deleteAll()
            db.promptInjectionDao().deleteAll()
            db.folderDao().deleteAll()
            db.groupChatMessageDao().deleteAll()
            db.groupChatDao().deleteAll()
            db.scheduledTaskExecutionDao().deleteAll()
            db.scheduledTaskDao().deleteAll()
            db.knowledgeChunkDao().deleteAll()
            db.knowledgeDocDao().deleteAll()
            db.experienceDao().deleteAll()
            db.milestoneDao().deleteAll()
            db.agentMessageDao().deleteAll()
            // v1.0.74: 朋友圈三表
            db.momentDao().deleteAllMoments()
            db.momentDao().deleteAllComments()
            db.momentDao().deleteAllLikes()

            backup.assistants.forEach { db.assistantDao().upsert(it) }
            backup.lorebooks.forEach { db.lorebookDao().upsert(it) }
            backup.skills.forEach { db.skillDao().upsert(it) }
            backup.artifacts.forEach { db.artifactDao().upsert(it) }
            backup.quickMessages.forEach { db.quickMessageDao().upsert(it) }
            backup.promptInjections.forEach { db.promptInjectionDao().upsert(it) }
            backup.folders.forEach { db.folderDao().insert(it) }
            backup.groupChats.forEach { db.groupChatDao().upsert(it) }
            backup.groupChatMessages.forEach { db.groupChatMessageDao().upsert(it) }
            backup.scheduledTasks.forEach { db.scheduledTaskDao().upsert(it) }
            backup.scheduledTaskExecutions.forEach { db.scheduledTaskExecutionDao().insert(it) }
            backup.knowledgeDocs.forEach { db.knowledgeDocDao().upsert(it) }
            if (backup.knowledgeChunks.isNotEmpty()) db.knowledgeChunkDao().insertAll(backup.knowledgeChunks)
            backup.experiences.forEach { db.experienceDao().upsert(it) }
            backup.milestones.forEach { db.milestoneDao().upsert(it) }
            backup.agentMessages.forEach { db.agentMessageDao().upsert(it) }
            // v1.0.74: 朋友圈三表(先删后插,防新旧混合)
            backup.moments.forEach { db.momentDao().insertMoment(it) }
            backup.momentComments.forEach { db.momentDao().insertComment(it) }
            backup.momentLikes.forEach { db.momentDao().addLike(it) }
        }

        // 2. 导入 memory 数据(MemoryDb — 3 张表)
        if (backup.sessionSummaries.isNotEmpty() || backup.dailyStates.isNotEmpty() ||
            backup.compiledSections.isNotEmpty() || backup.scopedCompiledSections.isNotEmpty()
        ) {
            memoryDb.withTransaction {
                memoryDb.sessionSummaryDao().deleteAll()
                memoryDb.dailyStateDao().deleteAll()
                memoryDb.compiledSectionDao().deleteAll()
                memoryDb.scopedCompiledSectionDao().deleteAll()
                backup.sessionSummaries.forEach { memoryDb.sessionSummaryDao().upsert(it) }
                backup.dailyStates.forEach { memoryDb.dailyStateDao().upsert(it) }
                backup.compiledSections.forEach { memoryDb.compiledSectionDao().upsert(it) }
                backup.scopedCompiledSections.forEach { memoryDb.scopedCompiledSectionDao().upsert(it) }
            }
        }

        // 3. 导入 facts(FactDb)
        if (backup.facts.isNotEmpty()) {
            factDb.withTransaction {
                factDb.factDao().deleteAll()
                val reset = backup.facts.map { it.copy(id = 0) }
                factDb.factDao().insertAll(reset)
            }
        }

        // 4. 恢复 DataStore 设置快照
        // 问题7.3: 过滤掉设备相关 key(theme_mode 跟随系统、dynamic_color 依赖设备 Material You 等),
        // 避免覆盖目标设备的本地偏好。bool:/int:/long: 前缀也匹配,如 "bool:dynamic_color"。
        if (backup.settingsSnapshot.isNotEmpty()) {
            val filtered = backup.settingsSnapshot.filterKeys { it !in DEVICE_SPECIFIC_KEYS }
            if (filtered.isNotEmpty()) {
                settings.restoreSettingsSnapshot(filtered)
            }
        }

        return backup.sessions.size to backup.messages.size
    }
}
