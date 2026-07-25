package io.zer0.muse.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactEntity
import io.zer0.memory.summary.CompiledSectionEntity
import io.zer0.memory.summary.DailyStateEntity
import io.zer0.memory.summary.MemoryDb
import io.zer0.memory.summary.SessionSummaryEntity
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.MuseDb
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedWriter
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
class BackupService(
    private val db: MuseDb,
    private val memoryDb: MemoryDb,
    private val factDb: FactDb,
    private val cloudBackupService: CloudBackupService,
    private val settings: SettingsRepository,
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
    suspend fun import(context: Context, uri: Uri): Pair<Int, Int> {
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: error(context.getString(R.string.backup_cannot_read, uri))
        }
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }
            ?: error(context.getString(R.string.backup_format_unrecognized))
        val isNdJson = resultOf {
            val obj = json.decodeFromString(JsonObject.serializer(), firstLine)
            obj["type"]?.let { (it as? JsonPrimitive)?.content } == "meta"
        }.getOrNull() ?: false
        return if (isNdJson) {
            applyNdJsonStreaming(text)
        } else {
            val backup = resultOf { json.decodeFromString(Backup.serializer(), text) }
                .onError { msg, t -> Logger.w("BackupService", "单 JSON 备份解析失败", t) }
                .getOrNull()
                ?: error(context.getString(R.string.backup_format_unrecognized))
            applyBackup(backup)
        }
    }

    /**
     * Phase 11.2.2: 流式分片导出(NDJSON 格式)。
     *
     * 解决大数据量(10000+ 消息)一次性 JSON 序列化 OOM 问题。
     * 格式:首行 meta({type:"meta",version,counts...}),后续每行一条记录。
     *
     * @return 导出的会话数 + 消息数
     */
    suspend fun exportStreaming(context: Context, uri: Uri): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var sessionCount = 0
        var messageCount = 0
        context.contentResolver.openOutputStream(uri)?.use { os: OutputStream ->
            BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8)).use { writer ->
                // Step 1: 拉取所有数据
                val sessions = db.sessionDao().observeAll().first()
                val sessionSummaries = memoryDb.sessionSummaryDao().getAll()
                val compiledSections = memoryDb.compiledSectionDao().getAll()
                val dailyState = memoryDb.dailyStateDao().get()?.let { listOf(it) } ?: emptyList()
                val facts = factDb.factDao().getAll()
                // v3: 拉取扩展表
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
                }
                writer.write(meta.toString())
                writer.newLine()

                // Step 3: 写 sessions
                sessions.forEach { session ->
                    val line = buildJsonObject {
                        put("type", "session")
                        put("data", json.encodeToJsonElement(SessionEntity.serializer(), session))
                    }
                    writer.write(line.toString())
                    writer.newLine()
                    sessionCount++
                }

                // Step 4: 写 messages
                sessions.forEach { session ->
                    val messages = db.messageDao().observeBySession(session.id).first()
                    messages.forEach { msg ->
                        val line = buildJsonObject {
                            put("type", "message")
                            put("data", json.encodeToJsonElement(MessageEntity.serializer(), msg))
                        }
                        writer.write(line.toString())
                        writer.newLine()
                        messageCount++
                    }
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

                writer.flush()
                Logger.i("BackupService", "流式导出完成: $sessionCount 会话, $messageCount 消息")
            }
        } ?: error(context.getString(R.string.backup_cannot_write, uri))
        sessionCount to messageCount
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
    private suspend fun applyNdJsonStreaming(text: String): Pair<Int, Int> {
        // 1. 先清空所有表
        db.withTransaction {
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
        }
        memoryDb.withTransaction {
            memoryDb.sessionSummaryDao().deleteAll()
            memoryDb.dailyStateDao().deleteAll()
            memoryDb.compiledSectionDao().deleteAll()
        }
        factDb.withTransaction { factDb.factDao().deleteAll() }

        // 2. 逐行解析 + 分批插入
        val sessionBuf = mutableListOf<SessionEntity>()
        val messageBuf = mutableListOf<MessageEntity>()
        val summaryBuf = mutableListOf<SessionSummaryEntity>()
        val dailyBuf = mutableListOf<DailyStateEntity>()
        val compiledBuf = mutableListOf<CompiledSectionEntity>()
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
        var settingsSnapshot: Map<String, String> = emptyMap()
        var sessionCount = 0
        var messageCount = 0

        text.lineSequence().forEachIndexed { idx, line ->
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
                        if (summaryBuf.size >= IMPORT_BATCH) {
                            flushBatch(summaryBuf) { batch -> memoryDb.withTransaction { batch.forEach { memoryDb.sessionSummaryDao().upsert(it) } } }
                        }
                    }
                    "dailyState" -> obj["data"]?.let {
                        dailyBuf.add(json.decodeFromJsonElement(DailyStateEntity.serializer(), it))
                        if (dailyBuf.size >= IMPORT_BATCH) {
                            flushBatch(dailyBuf) { batch -> memoryDb.withTransaction { batch.forEach { memoryDb.dailyStateDao().upsert(it) } } }
                        }
                    }
                    "compiledSection" -> obj["data"]?.let {
                        compiledBuf.add(json.decodeFromJsonElement(CompiledSectionEntity.serializer(), it))
                        if (compiledBuf.size >= IMPORT_BATCH) {
                            flushBatch(compiledBuf) { batch -> memoryDb.withTransaction { batch.forEach { memoryDb.compiledSectionDao().upsert(it) } } }
                        }
                    }
                    "fact" -> obj["data"]?.let {
                        factBuf.add(json.decodeFromJsonElement(FactEntity.serializer(), it))
                        if (factBuf.size >= IMPORT_BATCH) {
                            flushBatch(factBuf) { batch -> factDb.withTransaction { factDb.factDao().insertAll(batch.map { it.copy(id = 0) }) } }
                        }
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

        // 3. flush 剩余 buffer
        if (sessionBuf.isNotEmpty()) {
            sessionCount += sessionBuf.size
            flushBatch(sessionBuf) { batch -> db.withTransaction { batch.forEach { db.sessionDao().insert(it) } } }
        }
        if (messageBuf.isNotEmpty()) {
            messageCount += messageBuf.size
            flushBatch(messageBuf) { batch -> db.withTransaction { batch.forEach { db.messageDao().upsert(it) } } }
        }
        flushBatch(summaryBuf) { batch -> memoryDb.withTransaction { batch.forEach { memoryDb.sessionSummaryDao().upsert(it) } } }
        flushBatch(dailyBuf) { batch -> memoryDb.withTransaction { batch.forEach { memoryDb.dailyStateDao().upsert(it) } } }
        flushBatch(compiledBuf) { batch -> memoryDb.withTransaction { batch.forEach { memoryDb.compiledSectionDao().upsert(it) } } }
        flushBatch(factBuf) { batch -> factDb.withTransaction { factDb.factDao().insertAll(batch.map { it.copy(id = 0) }) } }
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
        // 恢复设置快照
        if (settingsSnapshot.isNotEmpty()) {
            settings.restoreSettingsSnapshot(settingsSnapshot)
        }

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
    suspend fun exportToCloud(): Boolean {
        val config = settings.cloudBackupConfigFlow.first()
        if (!config.isConfigured) return false
        val backup = buildBackup()
        val plaintext = json.encodeToString(Backup.serializer(), backup).toByteArray(Charsets.UTF_8)
        val data = if (config.backupPassword.isNotEmpty()) {
            BackupCrypto.encrypt(plaintext, config.backupPassword)
        } else {
            plaintext
        }
        val ok = cloudBackupService.uploadBackupWithLatest(config, data)
        if (ok) {
            settings.saveCloudBackupConfig(config.copy(lastSyncAt = System.currentTimeMillis()))
        }
        return ok
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
        val text = plaintext.toString(Charsets.UTF_8)
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val isNdJson = resultOf {
            val obj = json.decodeFromString(JsonObject.serializer(), firstLine)
            obj["type"]?.let { (it as? JsonPrimitive)?.content } == "meta"
        }.getOrNull() ?: false
        return if (isNdJson) {
            resultOf { applyNdJsonStreaming(text) }
                .onError { msg, t -> Logger.w("BackupService", "云端 NDJSON 流式导入失败", t); null }
                .getOrNull()
        } else {
            val backup = resultOf { json.decodeFromString(Backup.serializer(), text) }
                .onError { msg, t -> Logger.w("BackupService", "云端备份 JSON 解析失败", t); return null }
                .getOrNull()
                ?: return null
            applyBackup(backup)
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
        }

        // 2. 导入 memory 数据(MemoryDb — 3 张表)
        if (backup.sessionSummaries.isNotEmpty() || backup.dailyStates.isNotEmpty() || backup.compiledSections.isNotEmpty()) {
            memoryDb.withTransaction {
                memoryDb.sessionSummaryDao().deleteAll()
                memoryDb.dailyStateDao().deleteAll()
                memoryDb.compiledSectionDao().deleteAll()
                backup.sessionSummaries.forEach { memoryDb.sessionSummaryDao().upsert(it) }
                backup.dailyStates.forEach { memoryDb.dailyStateDao().upsert(it) }
                backup.compiledSections.forEach { memoryDb.compiledSectionDao().upsert(it) }
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
