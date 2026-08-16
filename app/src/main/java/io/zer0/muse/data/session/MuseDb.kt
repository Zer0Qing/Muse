package io.zer0.muse.data.session

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import io.zer0.muse.data.artifact.ArtifactDao
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.data.assistant.AssistantDao
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.experience.ExperienceDao
import io.zer0.muse.data.experience.ExperienceEntity
import io.zer0.muse.data.groupchat.GroupChatDao
import io.zer0.muse.data.groupchat.GroupChatEntity
import io.zer0.muse.data.groupchat.GroupChatGenerationLedgerEntity
import io.zer0.muse.data.groupchat.GroupChatMemoryDao
import io.zer0.muse.data.groupchat.GroupChatMemoryEntity
import io.zer0.muse.data.groupchat.GroupChatMessageDao
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.data.knowledge.KnowledgeBaseDao
import io.zer0.muse.data.knowledge.KnowledgeBaseEntity
import io.zer0.muse.data.knowledge.KnowledgeChunkDao
import io.zer0.muse.data.knowledge.KnowledgeChunkEntity
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsDao
import io.zer0.muse.data.knowledge.KnowledgeChunkFtsSelfHealer
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.data.lorebook.LorebookDao
import io.zer0.muse.data.lorebook.LorebookEntity
import io.zer0.muse.data.promptinjection.PromptInjectionDao
import io.zer0.muse.data.promptinjection.PromptInjectionEntity
import io.zer0.muse.data.quickmsg.QuickMessageDao
import io.zer0.muse.data.quickmsg.QuickMessageEntity
import io.zer0.muse.data.quicknote.QuickNoteConverters
import io.zer0.muse.data.quicknote.QuickNoteDao
import io.zer0.muse.data.quicknote.QuickNoteEntity
import io.zer0.muse.data.schedule.ScheduledTaskDao
import io.zer0.muse.data.schedule.ScheduledTaskEntity
import io.zer0.muse.data.schedule.ScheduledTaskExecutionDao
import io.zer0.muse.data.schedule.ScheduledTaskExecutionEntity
import io.zer0.muse.data.skill.SkillDao
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.milestone.MilestoneDao
import io.zer0.muse.data.milestone.MilestoneEntity
import io.zer0.muse.data.agentdm.AgentMessageDao
import io.zer0.muse.data.agentdm.AgentMessageEntity
import io.zer0.muse.data.audit.AuditLogDao
import io.zer0.muse.data.audit.AuditLogEntity
import io.zer0.muse.data.stats.AutoBackupLogDao
import io.zer0.muse.data.stats.AutoBackupLogEntity
import io.zer0.muse.data.stats.DbIntegrityLogDao
import io.zer0.muse.data.stats.DbIntegrityLogEntity
import io.zer0.muse.data.stats.StatsCacheDao
import io.zer0.muse.data.stats.StatsCacheEntity
import io.zer0.muse.ui.translate.TranslateHistoryDao
import io.zer0.muse.ui.translate.TranslateHistoryEntity
import io.zer0.common.AppJson
import io.zer0.common.Logger
import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * muse app 层 Room 数据库。
 *
 * 包含会话 + 消息 + Assistant + Lorebook + QuickMessage + PromptInjection 六张表
 * (与 :memory 模块的 memory.db 独立,职责分离)。
 * - sessions: 会话元数据(标题/时间戳/预览/绑定的 Assistant)
 * - messages: 持久化的聊天消息(按 sessionId 关联)
 * - assistants: Assistant 多人格配置(Phase 8.2)
 * - lorebooks: 关键词触发的世界书条目(Phase 8.5)
 * - quick_messages: 快捷消息模板(Phase 8.5)
 * - prompt_injections: 模式注入提示词(Phase 8.5)
 *
 * Phase 8.2: 版本 1 → 2,新增 assistants 表 + sessions.assistantId + sessions.pinned。
 * Phase 8.3: 版本 2 → 3,messages 加 favorite 字段(默认 0)。
 * Phase 8.4: 版本 3 → 4,messages 加 citationUrlsJson 字段(默认 "[]")。
 * Phase 8.5: 版本 4 → 5,新增 lorebooks / quick_messages / prompt_injections 三张表。
 * Phase 8.6: 版本 5 → 6,messages 加 imageBase64Json 字段(默认 "[]",多模态)。
 * Phase 8.8: 版本 6 → 7,新增 skills 表(Kotlin 直实现 skill,不用 QuickJS)。
 * Phase 9.1: 版本 7 → 8,新增 folders 表 + sessions.folderId 字段(M13 文件夹分组)。
 * Phase 10.3: 版本 8 → 9,新增 messages_fts FTS4 虚拟表(中文 ngram 全文索引)。
 *   - 迁移只建空表,首次启动由 SessionRepository.ensureFtsIndexConsistent 全量 rebuild
 *   - 索引内容由 MessageFtsManager.toNgram 预处理(CJK 2-gram 滑窗 + ASCII 小写词)
 *   - 搜索时 MessageFtsManager.toMatchQuery 转 MATCH 表达式(引号转义 + AND 语义)
 * P1-7: 版本 10 → 11,新增 scheduled_task_executions 表(定时任务执行历史)。
 *   - 迁移链已覆盖标准升级路径;未知版本降级时由 fallbackToDestructiveMigrationOnDowngrade 处理
 * v0.45: 版本 11 → 12,sessions 加 archived 字段(归档功能)。
 * v1.43: 版本 15 → 16,新增 artifacts 表 + messages.artifactIdsJson 字段(会话产物)。
 * v1.0.23 hotfix: 版本 41 → 42,修复"假 v41"数据库 integrity check 崩溃(防御性补字段,无 schema 变更)。
 * v1.0.17: 版本 43 → 44,新增 translate_history 表(翻译历史持久化)。
 * v1.0.17: 版本 44 → 45,新增 quick_notes 表(快速记录 Room 持久化 + 回收站)。
 *   替代原 QuickNoteStore 的 JSON 文件存储;QuickNoteStore 保留作为迁移源 + 兼容格式化。
 *   tags 列通过 QuickNoteConverters 逗号分隔 TypeConverter 处理 List<String>。
 * v1.0.17: 版本 45 → 46,scheduled_tasks 加 retry_count + max_retries 列(执行失败重试策略)。
 * v1.0.18: 版本 47 → 48,quick_notes 加 folder/content_type/attachments_json/reminder_at/
 *   encrypted/encrypted_content 6 列(快速记录 9 项增强:分类/富文本/附件/提醒/加密/分页等)。
 * v1.0.30 gap4.3: 版本 46 → 47,translate_history 加 favorite 列(翻译收藏夹)。
 * v1.0.19: 版本 48 → 49,assistants 加 summary/useAssistantName/allowGroupChat 3 列
 *   (Assistant 字段补齐;tags 分组已由 tagsJson 承载)。
 * v2.x: 版本 49 → 50,新建 group_chat_memories 表(群聊记忆隔离)。
 */
@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        ArtifactEntity::class,
        AssistantEntity::class,
        LorebookEntity::class,
        QuickMessageEntity::class,
        PromptInjectionEntity::class,
        SkillEntity::class,
        FolderEntity::class,
        ScheduledTaskEntity::class,
        KnowledgeDocEntity::class,
        KnowledgeChunkEntity::class,
        ScheduledTaskExecutionEntity::class,
        GroupChatEntity::class,
        GroupChatMessageEntity::class,
        // v2.x: 群聊记忆隔离(独立 fact store,不污染主记忆)
        GroupChatMemoryEntity::class,
        // v1.0.72: AI 朋友圈动态 + 评论(v1.0.73: + 点赞记录)
        io.zer0.muse.data.moment.MomentEntity::class,
        io.zer0.muse.data.moment.MomentCommentEntity::class,
        io.zer0.muse.data.moment.MomentLikeEntity::class,
        // v1.0.74: AI 日记本
        io.zer0.muse.data.diary.DiaryEntity::class,
        // v1.0.74: 主动巡检日志
        io.zer0.muse.data.patrol.PatrolLogEntity::class,
        ExperienceEntity::class,
        // v1.107 冗余设计: 统计缓存 / 完整性日志 / 自动备份日志
        StatsCacheEntity::class,
        DbIntegrityLogEntity::class,
        AutoBackupLogEntity::class,
        // Phase 2 2B: 里程碑表
        MilestoneEntity::class,
        AgentMessageEntity::class,
        // P2-4: 审计日志表
        AuditLogEntity::class,
        // v1.133: 多知识库 + FTS4 混合检索
        // 注:KnowledgeChunkFtsEntity 不在此列表 — FTS4 vtable 由 MIGRATION_38_39 raw SQL 创建,
        // DAO 全部 @SkipQueryVerification,Room 不感知该虚拟表存在(避免 KSP schema 验证报 vtable constructor failed)
        KnowledgeBaseEntity::class,
        // v1.0.15: 消息发送 outbox(持久化发送队列,进程被杀后恢复未发送消息)
        MessageOutboxEntity::class,
        // v1.0.17: 翻译历史持久化
        TranslateHistoryEntity::class,
        // v1.0.17: 快速记录(替代 JSON 文件存储 + 回收站)
        QuickNoteEntity::class,
        // P1-2: Worldbook 动态世界书(常驻/关键词/正则/深度注入,独立于 Lorebook)
        io.zer0.muse.worldbook.WorldBookEntryEntity::class,
        // v1.0.53 Phase 1: 子 agent 线程账本(持久化版,替代旧内存版 SubagentThreadStore)
        io.zer0.muse.data.subagent.SubagentThreadEntity::class,
        // B5-01: 流式生成检查点(进程被杀后恢复中断消息)
        GenerationCheckpointEntity::class,
        // B5-02: 群聊生成账本(进程被杀后按断点重放)
        GroupChatGenerationLedgerEntity::class,
    ],
    version = 88,
    exportSchema = true,
)
@TypeConverters(QuickNoteConverters::class)
abstract class MuseDb : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun messageFtsDao(): MessageFtsDao
    abstract fun artifactDao(): ArtifactDao
    abstract fun assistantDao(): AssistantDao
    abstract fun lorebookDao(): LorebookDao
    abstract fun quickMessageDao(): QuickMessageDao
    abstract fun promptInjectionDao(): PromptInjectionDao
    abstract fun skillDao(): SkillDao
    abstract fun folderDao(): FolderDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun knowledgeDocDao(): KnowledgeDocDao
    abstract fun knowledgeChunkDao(): KnowledgeChunkDao
    // v1.133: 多知识库 + FTS4
    abstract fun knowledgeBaseDao(): KnowledgeBaseDao
    abstract fun knowledgeChunkFtsDao(): KnowledgeChunkFtsDao
    abstract fun scheduledTaskExecutionDao(): ScheduledTaskExecutionDao
    abstract fun groupChatDao(): GroupChatDao
    abstract fun groupChatMessageDao(): GroupChatMessageDao
    // v2.x: 群聊记忆隔离(独立 fact store,不污染主记忆)
    abstract fun groupChatMemoryDao(): GroupChatMemoryDao

    /** v1.0.72: AI 朋友圈 DAO。 */
    abstract fun momentDao(): io.zer0.muse.data.moment.MomentDao
    abstract fun diaryDao(): io.zer0.muse.data.diary.DiaryDao
    abstract fun patrolLogDao(): io.zer0.muse.data.patrol.PatrolLogDao
    // v1.98: 经验库
    abstract fun experienceDao(): ExperienceDao
    // Phase 2 2B: 里程碑
    abstract fun milestoneDao(): MilestoneDao
    abstract fun agentMessageDao(): AgentMessageDao
    // P2-4: 审计日志
    abstract fun auditLogDao(): AuditLogDao
    // v1.134 P1-1/P1-2: 孤儿组件接入所需 DAO
    abstract fun autoBackupLogDao(): AutoBackupLogDao
    abstract fun statsCacheDao(): StatsCacheDao
    // P3-3: 数据库完整性校验 DAO(IntegrityChecker 使用)
    abstract fun integrityLogDao(): DbIntegrityLogDao
    // v1.0.15: 消息发送 outbox DAO
    abstract fun messageOutboxDao(): MessageOutboxDao
    // v1.0.17: 翻译历史 DAO
    abstract fun translateHistoryDao(): TranslateHistoryDao
    // v1.0.17: 快速记录 DAO(替代 JSON 文件存储 + 回收站)
    abstract fun quickNoteDao(): QuickNoteDao
    // P1-2: Worldbook 动态世界书 DAO
    abstract fun worldBookDao(): io.zer0.muse.worldbook.WorldBookDao
    // v1.0.53 Phase 1: 子 agent 线程 DAO(持久化版)
    abstract fun subagentThreadDao(): io.zer0.muse.data.subagent.SubagentThreadDao
    // B5-01: 流式生成检查点 DAO
    abstract fun generationCheckpointDao(): GenerationCheckpointDao
    // B5-02: 群聊生成账本 DAO
    abstract fun groupChatGenerationLedgerDao(): io.zer0.muse.data.groupchat.GroupChatGenerationLedgerDao

    companion object {
        @Volatile
        private var INSTANCE: MuseDb? = null

        /** v1.0.53: FTS 兜底创建全局锁 — 多连接 onOpen 并发时串行化建表。 */
        private val FTS_CREATE_LOCK = Any()

        /** R-DB-02: 进程内 FTS 故障标志,建表失败后置位,供自愈/UI 排查。 */
        @Volatile
        private var knowledgeFtsBroken = false

        /** R-DB-02: 清掉残留影子表/主表后重建 knowledge_chunks_fts。 */
        fun repairKnowledgeChunkFtsTable() {
            synchronized(FTS_CREATE_LOCK) {
                val db = INSTANCE?.openHelper?.writableDatabase
                    ?: error("MuseDb not initialized")
                dropKnowledgeFtsTables(db)
                createKnowledgeChunkFtsTable(db)
                knowledgeFtsBroken = false
                io.zer0.common.Logger.i("MuseDb", "knowledge_chunks_fts 已重建")
            }
        }

        private fun dropKnowledgeFtsTables(db: SupportSQLiteDatabase) {
            val names = mutableListOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'knowledge_chunks_fts%'")
                .use { cursor ->
                    while (cursor.moveToNext()) names.add(cursor.getString(0))
                }
            names.forEach { name ->
                db.execSQL("DROP TABLE IF EXISTS `$name`")
            }
        }

        private fun createKnowledgeChunkFtsTable(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS knowledge_chunks_fts " +
                    "USING fts4(chunkId, doc_id, text_content)",
            )
        }

        /** R-DB-05: 探测当前 SQLite 是否支持 FTS5(temp 表探针)。 */
        private fun fts5Available(db: SupportSQLiteDatabase): Boolean {
            val ok = runCatching {
                db.execSQL("DROP TABLE IF EXISTS temp.muse_fts5_probe")
                db.execSQL("CREATE VIRTUAL TABLE temp.muse_fts5_probe USING fts5(x)")
                true
            }.getOrDefault(false)
            runCatching { db.execSQL("DROP TABLE IF EXISTS temp.muse_fts5_probe") }
            return ok
        }

        private fun dropMessageFtsTables(db: SupportSQLiteDatabase) {
            val names = mutableListOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'messages_fts%'")
                .use { cursor -> while (cursor.moveToNext()) names.add(cursor.getString(0)) }
            names.forEach { name -> db.execSQL("DROP TABLE IF EXISTS `$name`") }
            val triggers = mutableListOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'messages_fts_%'")
                .use { cursor -> while (cursor.moveToNext()) triggers.add(cursor.getString(0)) }
            triggers.forEach { name -> db.execSQL("DROP TRIGGER IF EXISTS `$name`") }
        }

        private fun createMessageFtsTable(db: SupportSQLiteDatabase, useFts5: Boolean) {
            db.execSQL(MessageFtsDdl.createSql(useFts5))
            if (useFts5) {
                MessageFtsDdl.fts5TriggerSqls.forEach { db.execSQL(it) }
            }
            MessageFtsRuntime.useFts5 = useFts5
        }

        private fun detectMessageFtsMode(db: SupportSQLiteDatabase): Boolean {
            val sql = db.query("SELECT sql FROM sqlite_master WHERE type='table' AND name='messages_fts'").use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: return false
            return sql.contains("fts5", ignoreCase = true)
        }

        /** R-DB-05: 76→77 迁移 — 重建 messages_fts,优先 FTS5,失败回退 FTS4。 */
        fun migrate76To77(): Migration = object : Migration(76, 77) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val useFts5 = fts5Available(db)
                dropMessageFtsTables(db)
                createMessageFtsTable(db, useFts5)
            }
        }

        /** v1.0.72: messages 加 tool_call_info_json 列(工具调用卡片持久化)。 */
        val MIGRATION_77_78 = object : Migration(77, 78) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN toolCallInfoJson TEXT DEFAULT NULL")
            }
        }

        /** v1.0.72: sessions 加 ignore_memory 列(本会话不参考记忆)。 */
        val MIGRATION_78_79 = object : Migration(78, 79) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN ignoreMemory INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v1.0.72: AI 朋友圈表(动态 + 评论)。
         * 注意: 建表 SQL 必须与 Room 生成的表完全一致(mood 无默认值,不建额外索引),
         * 否则真机上迁移校验失败崩溃。 */
        val MIGRATION_79_80 = object : Migration(79, 80) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS ai_moments (
                        id TEXT NOT NULL PRIMARY KEY,
                        content TEXT NOT NULL,
                        type TEXT NOT NULL DEFAULT 'life',
                        mood TEXT,
                        likes INTEGER NOT NULL DEFAULT 0,
                        likedByUser INTEGER NOT NULL DEFAULT 0,
                        source TEXT NOT NULL DEFAULT 'scheduled',
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )""",
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS ai_moment_comments (
                        id TEXT NOT NULL PRIMARY KEY,
                        momentId TEXT NOT NULL,
                        sender TEXT NOT NULL,
                        content TEXT NOT NULL,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )""",
                )
            }
        }

        /** v1.0.73: 兜底修复历史坏表。已发布版本(72/中间版)建出的 ai_moments 存在两类差异:
         * 1) 残留索引 idx_moments_created / idx_moment_comments_moment
         * 2) mood 列默认值不统一(有的无默认 'undefined',有的 DEFAULT NULL 读为 'NULL')
         * 方案: 重建 ai_moments 表,统一为与 Entity 完全一致的 schema(无 mood 默认、无索引),数据全部保留。 */
        val MIGRATION_80_81 = object : Migration(80, 81) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS idx_moments_created")
                db.execSQL("DROP INDEX IF EXISTS idx_moment_comments_moment")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS ai_moments_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        content TEXT NOT NULL,
                        type TEXT NOT NULL DEFAULT 'life',
                        mood TEXT,
                        likes INTEGER NOT NULL DEFAULT 0,
                        likedByUser INTEGER NOT NULL DEFAULT 0,
                        source TEXT NOT NULL DEFAULT 'scheduled',
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )""",
                )
                db.execSQL(
                    """INSERT INTO ai_moments_new (id, content, type, mood, likes, likedByUser, source, createdAt)
                        SELECT id, content, type, mood, likes, likedByUser, source, createdAt FROM ai_moments""",
                )
                db.execSQL("DROP TABLE IF EXISTS ai_moments")
                db.execSQL("ALTER TABLE ai_moments_new RENAME TO ai_moments")
            }
        }

        /** v1.0.73: 朋友圈支持用户发布 + AI 配图 — ai_moments 加 sender/imageUrl 列。
         * 注意: 放在 80→81 重建之后,统一 ADD COLUMN,保证任何迁移路径最终 schema 一致。 */
        val MIGRATION_81_82 = object : Migration(81, 82) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_moments ADD COLUMN senderType TEXT NOT NULL DEFAULT 'assistant'")
                db.execSQL("ALTER TABLE ai_moments ADD COLUMN senderName TEXT NOT NULL DEFAULT 'Muse'")
                db.execSQL("ALTER TABLE ai_moments ADD COLUMN senderAvatar TEXT")
                db.execSQL("ALTER TABLE ai_moments ADD COLUMN imageUrl TEXT")
            }
        }

        /** v1.0.73: 多助手朋友圈 — ai_moments 加 senderId;评论加发送者身份;
         * 新建 ai_moment_likes 表(用户 + 助手互赞)。 */
        val MIGRATION_82_83 = object : Migration(82, 83) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_moments ADD COLUMN senderId TEXT")
                db.execSQL("ALTER TABLE ai_moment_comments ADD COLUMN senderId TEXT")
                db.execSQL("ALTER TABLE ai_moment_comments ADD COLUMN senderName TEXT")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS ai_moment_likes (
                        momentId TEXT NOT NULL,
                        likerType TEXT NOT NULL,
                        likerId TEXT NOT NULL,
                        likerName TEXT NOT NULL,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(momentId, likerType, likerId)
                    )""",
                )
            }
        }

        /** v1.0.73: 9 宫格多图 — ai_moments 加 imagesJson(JSON 数组)。 */
        val MIGRATION_83_84 = object : Migration(83, 84) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_moments ADD COLUMN imagesJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /** v1.0.74: AI 日记本 — 新建 ai_diaries 表(日期主键)。 */
        val MIGRATION_84_85 = object : Migration(84, 85) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS ai_diaries (
                        date TEXT NOT NULL PRIMARY KEY,
                        content TEXT NOT NULL,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )""",
                )
            }
        }

        /** v1.0.74: 主动巡检日志 — 新建 patrol_logs 表。 */
        val MIGRATION_85_86 = object : Migration(85, 86) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS patrol_logs (
                        id TEXT NOT NULL PRIMARY KEY,
                        timestamp INTEGER NOT NULL,
                        action TEXT NOT NULL,
                        summary TEXT NOT NULL
                    )""",
                )
            }
        }

        /**
         * v1.0.74 fix (审计 CRITICAL-0.1): assistants 表缺 capabilitiesJson 列迁移。
         * AssistantEntity 新增 capabilitiesJson(v1.200) 已进 Room schema,
         * 但迁移链没有对应 ALTER,老用户升级时 Room schema 校验必崩
         * "Migration didn't properly handle: assistants"。
         */
        val MIGRATION_86_87 = object : Migration(86, 87) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // schema 86.json 的 assistants 表已含 capabilitiesJson(从 Entity 导出),
                // 但经真实迁移链升级上来的老库没有;先查列是否存在,避免重复列崩溃。
                var hasCapabilities = false
                db.query("PRAGMA table_info(assistants)").use { cursor ->
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "capabilitiesJson") {
                            hasCapabilities = true
                            break
                        }
                    }
                }
                if (!hasCapabilities) {
                    db.execSQL("ALTER TABLE assistants ADD COLUMN capabilitiesJson TEXT NOT NULL DEFAULT '[]'")
                }
            }
        }

        /**
         * 审计修复 (S-02): messages 表补 videoFileUri 列(视频生成结果持久化)。
         *
         * 此前 generate_video 的 videoFileUri 只存在于内存 UIMessage,
         * 重启/切页后视频永久丢失;MessageEntity 新增字段后补迁移。
         */
        val MIGRATION_87_88 = object : Migration(87, 88) {
            override fun migrate(db: SupportSQLiteDatabase) {
                var hasVideoFileUri = false
                db.query("PRAGMA table_info(messages)").use { cursor ->
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "videoFileUri") {
                            hasVideoFileUri = true
                            break
                        }
                    }
                }
                if (!hasVideoFileUri) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN videoFileUri TEXT DEFAULT NULL")
                }
            }
        }

        /**
         * Phase 8.2: v1 → v2 迁移。
         * - 新建 assistants 表
         * - sessions 加 assistantId(默认 'default') + pinned(默认 0)字段
         * - 插入默认 Assistant(id='default')
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 新建 assistants 表
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS assistants (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        sortIndex INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        modelId TEXT,
                        temperature REAL,
                        topP REAL,
                        maxTokens INTEGER,
                        contextMessageSize INTEGER NOT NULL DEFAULT 20,
                        reasoningLevel TEXT NOT NULL DEFAULT 'AUTO',
                        systemPrompt TEXT NOT NULL DEFAULT '',
                        messageTemplate TEXT NOT NULL DEFAULT '',
                        presetMessagesJson TEXT NOT NULL DEFAULT '[]',
                        toolIdsJson TEXT NOT NULL DEFAULT '[]',
                        mcpServerIdsJson TEXT NOT NULL DEFAULT '[]',
                        streamOutput INTEGER NOT NULL DEFAULT 1,
                        memoryEnabled INTEGER NOT NULL DEFAULT 1,
                        useGlobalMemory INTEGER NOT NULL DEFAULT 1,
                        enableRecentChatsReference INTEGER NOT NULL DEFAULT 1,
                        enableTimeReminder INTEGER NOT NULL DEFAULT 1,
                        avatarEmoji TEXT NOT NULL DEFAULT '',
                        avatarImageUrl TEXT NOT NULL DEFAULT '',
                        backgroundUrl TEXT NOT NULL DEFAULT '',
                        backgroundOpacity REAL NOT NULL DEFAULT 1.0,
                        useGradientBackground INTEGER NOT NULL DEFAULT 0,
                        tagsJson TEXT NOT NULL DEFAULT '[]',
                        quickMessageIdsJson TEXT NOT NULL DEFAULT '[]',
                        lorebookIdsJson TEXT NOT NULL DEFAULT '[]',
                        modeInjectionIdsJson TEXT NOT NULL DEFAULT '[]',
                        skillIdsJson TEXT NOT NULL DEFAULT '[]',
                        customHeadersJson TEXT NOT NULL DEFAULT '{}',
                        customBodiesJson TEXT NOT NULL DEFAULT '{}',
                        capabilitiesJson TEXT NOT NULL DEFAULT '[]'
                    )
                """.trimIndent())
                // 插入默认 Assistant
                // Android 16 (SDK 36) 起禁止 SQLiteDatabase.execSQL 执行 DML,只允许 DDL;
                // 改用 compileStatement + executeInsert(SQLiteStatement 方法,Android 16 仍允许)
                db.compileStatement("""
                    INSERT OR IGNORE INTO assistants (id, name, sortIndex, createdAt, updatedAt)
                    VALUES ('default', '默认助手', 0, 0, 0)
                """.trimIndent()).use { it.executeInsert() }
                // sessions 加字段
                db.execSQL("ALTER TABLE sessions ADD COLUMN assistantId TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("ALTER TABLE sessions ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Phase 8.3: v2 → v3 迁移。
         * - messages 加 favorite 字段(默认 0)
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Phase 8.4: v3 → v4 迁移。
         * - messages 加 citationUrlsJson 字段(默认 "[]",JSON 字符串)
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN citationUrlsJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /**
         * Phase 8.5: v4 → v5 迁移。
         * - 新建 lorebooks 表(关键词触发的世界书)
         * - 新建 quick_messages 表(快捷消息模板)
         * - 新建 prompt_injections 表(模式注入提示词)
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS lorebooks (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        keywordsJson TEXT NOT NULL DEFAULT '[]',
                        content TEXT NOT NULL DEFAULT '',
                        priority INTEGER NOT NULL DEFAULT 0,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        caseSensitive INTEGER NOT NULL DEFAULT 0,
                        insertionPosition TEXT NOT NULL DEFAULT 'after_system',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS quick_messages (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        content TEXT NOT NULL DEFAULT '',
                        scope TEXT NOT NULL DEFAULT 'global',
                        assistantId TEXT NOT NULL DEFAULT '',
                        sortIndex INTEGER NOT NULL DEFAULT 0,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS prompt_injections (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        displayName TEXT NOT NULL DEFAULT '',
                        content TEXT NOT NULL DEFAULT '',
                        priority INTEGER NOT NULL DEFAULT 0,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        insertionPosition TEXT NOT NULL DEFAULT 'after_system',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        /**
         * Phase 8.6: v5 → v6 迁移。
         * - messages 加 imageBase64Json 字段(默认 "[]",JSON 字符串)
         *   用于多模态:USER 发图(本地图片 base64)/ Gemini 绘图输出(ASSISTANT)
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imageBase64Json TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /**
         * Phase 8.8: v6 → v7 迁移。
         * - 新建 skills 表(Kotlin 直实现 skill,不用 QuickJS)
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS skills (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        parametersJson TEXT NOT NULL DEFAULT '{}',
                        requiredJson TEXT NOT NULL DEFAULT '[]',
                        implementationKotlin TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        category TEXT NOT NULL DEFAULT 'custom',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        /**
         * Phase 9.1 (M13): v7 → v8 迁移。
         * - 新建 folders 表(会话文件夹分组)
         * - sessions 加 folderId 字段(默认 '',与 SessionEntity @ColumnInfo(defaultValue="") 对齐)
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS folders (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        sortIndex INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        expanded INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
                // sessions 加 folderId 字段(默认 '',与 SessionEntity @ColumnInfo(defaultValue="") 对齐)
                db.execSQL("ALTER TABLE sessions ADD COLUMN folderId TEXT DEFAULT ''")
            }
        }

        /**
         * Phase 10.3: v8 → v9 迁移。
         * - 新建 messages_fts FTS4 虚拟表(中文 ngram 全文索引)
         *
         * 注意:
         * - SQL 必须与 @Fts4 注解生成的完全一致(含反引号 + 列顺序),否则 Room schema 校验失败。
         * - 迁移只建空表,不填数据。首次启动由 SessionRepository.ensureFtsIndexConsistent
         *   比较两表 count 后全量 rebuild(用 MessageFtsManager.toNgram 转换)。
         * - 不在迁移里 INSERT SELECT: SQL 无法调用 Kotlin ngram 函数,直接塞原文会导致
         *   索引/查询不一致(MATCH 不到)。rebuild 必须在 Kotlin 层做。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS4(" +
                        "`message_id` TEXT, `content_ngram` TEXT" +
                        ")"
                )
            }
        }

        /**
         * v9 → v10 迁移。
         * - 新建 scheduled_tasks 表(定时任务)
         * - 新建 knowledge_docs 表(知识库文档)
         *
         * 注意: skills 表已在 v6→v7 迁移中创建,此处不重复。
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // scheduled_tasks 表(SQL 必须与 ScheduledTaskEntity @Entity 注解生成的完全一致)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS scheduled_tasks (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        prompt TEXT NOT NULL DEFAULT '',
                        assistant_id TEXT NOT NULL DEFAULT 'default',
                        interval TEXT NOT NULL DEFAULT 'daily',
                        cron_expr TEXT NOT NULL DEFAULT '',
                        enabled INTEGER NOT NULL DEFAULT 1,
                        next_run_at INTEGER NOT NULL DEFAULT 0,
                        last_run_at INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                // knowledge_docs 表
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS knowledge_docs (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL DEFAULT '',
                        file_path TEXT NOT NULL DEFAULT '',
                        file_type TEXT NOT NULL DEFAULT '',
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        /**
         * P1-7: v10 → v11 迁移。
         * - 新建 scheduled_task_executions 表(定时任务执行历史)
         *
         * 注意: 迁移链已覆盖标准升级路径;未知版本降级时由 fallbackToDestructiveMigrationOnDowngrade 处理。
         * 此迁移仅用于 v10 → v11 的标准升级路径,DDL 用 execSQL(Room 迁移标准做法)。
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // scheduled_task_executions 表(SQL 必须与 ScheduledTaskExecutionEntity @Entity 注解生成的完全一致)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS scheduled_task_executions (
                        id TEXT NOT NULL PRIMARY KEY,
                        task_id TEXT NOT NULL,
                        executed_at INTEGER NOT NULL,
                        status TEXT NOT NULL DEFAULT 'success',
                        reply_summary TEXT NOT NULL DEFAULT '',
                        error_message TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                // 索引: 按 task_id 查询执行历史(对应 @Index 注解)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_task_executions_task_id ON scheduled_task_executions(task_id)")
            }
        }

        /**
         * v0.45: v11 → v12 迁移。
         * - sessions 加 archived 字段(默认 0,归档功能)
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v1.28: v12 → v13 迁移。
         * - sessions 加 isAgentSession 字段(默认 0,区分 Agent Tab 会话与任务会话)
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN isAgentSession INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v1.30: v13 → v14 迁移。
         * - 新建 group_chats 表(多 Agent 群聊元数据)
         * - 新建 group_chat_messages 表(群聊消息)
         *
         * 群聊功能:用户在群聊中发消息后,GroupChatScheduler 串行触发各 Agent 成员轮转发言。
         * 迁移链已覆盖标准升级路径;未知版本降级时由 fallbackToDestructiveMigrationOnDowngrade 处理。
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // group_chats 表(SQL 必须与 GroupChatEntity @Entity 注解生成的完全一致)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS group_chats (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        memberIdsJson TEXT NOT NULL,
                        teamId TEXT,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                // group_chat_messages 表(SQL 必须与 GroupChatMessageEntity @Entity 注解生成的完全一致)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS group_chat_messages (
                        id TEXT NOT NULL PRIMARY KEY,
                        chatId TEXT NOT NULL,
                        senderType TEXT NOT NULL,
                        senderId TEXT NOT NULL,
                        senderName TEXT NOT NULL,
                        body TEXT NOT NULL,
                        timestamp INTEGER NOT NULL DEFAULT 0,
                        mood TEXT
                    )
                """.trimIndent())
                // 索引: 按 chatId 查询消息(对应 @Index 注解)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_group_chat_messages_chatId ON group_chat_messages(chatId)")
            }
        }

        /**
         * v1.41: v14 → v15 迁移。
         * - group_chat_messages 加 imageBase64Json 字段(默认 "[]",JSON 字符串)
         *   用于群聊多模态:用户发送图片附件。
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE group_chat_messages ADD COLUMN imageBase64Json TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /**
         * v1.43: v15 → v16 迁移。
         * - 新建 artifacts 表(会话产物:代码/文档/HTML/SVG/图片等)
         * - messages 加 artifactIdsJson 字段(默认 "[]",JSON 字符串)
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS artifacts (
                        id TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        messageId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        type TEXT NOT NULL,
                        content TEXT NOT NULL DEFAULT '',
                        language TEXT,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(sessionId) REFERENCES sessions(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_sessionId ON artifacts(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_messageId ON artifacts(messageId)")
                db.execSQL("ALTER TABLE messages ADD COLUMN artifactIdsJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /**
         * v1.44: v16 → v17 迁移。
 * - group_chats 加 pinned 字段(默认 0),支持群聊置顶
 *
 * v1.46: v17 → v18 迁移。
 * - group_chat_messages 加 reasoning 字段(可选),支持群聊消息思考过程展示
 */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE group_chats ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v1.46: v17 → v18 迁移。
         * - group_chat_messages 加 reasoning 字段(可选),支持群聊消息思考过程展示
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE group_chat_messages ADD COLUMN reasoning TEXT")
            }
        }

        /**
         * v1.49: v18 → v19 迁移。
         *
         * 修复:旧版 MIGRATION_6_7 建表语句可能与当前 SkillEntity schema 不一致
         * (category 列缺失或 defaultValue 不匹配),导致 Room schema 校验崩溃
         * "Migration didn't properly handle: skills"。
         *
         * 方案:重建 skills 表,确保 schema 与当前 SkillEntity 完全一致。
         * - CREATE TABLE skills_new(完整 schema)
         * - INSERT INTO skills_new SELECT ... FROM skills(用 COALESCE 补缺失列)
         * - DROP TABLE skills
         * - ALTER TABLE skills_new RENAME TO skills
         *
         * Android 16 (SDK 36) 限制:execSQL 禁止 DML(INSERT/UPDATE/DELETE),
         * 数据迁移用 db.query() 执行 INSERT INTO ... SELECT(DDL 用 execSQL)。
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 建新表(DDL,execSQL 可用)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS skills_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        parametersJson TEXT NOT NULL DEFAULT '{}',
                        requiredJson TEXT NOT NULL DEFAULT '[]',
                        implementationKotlin TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        category TEXT NOT NULL DEFAULT 'custom',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                // 2. 数据迁移(DML,Android 16 必须用 query() 而非 execSQL)
                //    MIGRATION_6_7 建表时已包含 category/createdAt/updatedAt 列,直接 INSERT 即可。
                //    若旧表异常缺列,放弃迁移(技能可重新导入)。
                //    (原 catch 分支用 COALESCE 补缺列是死代码:SQLite 对不存在的列在解析
                //    阶段即报错,COALESCE 无法补救,故移除。)
                try {
                    db.query("""
                        INSERT INTO skills_new
                            (id, name, description, parametersJson, requiredJson,
                             implementationKotlin, enabled, category, createdAt, updatedAt)
                        SELECT
                            id, name, description, parametersJson, requiredJson,
                            implementationKotlin, enabled, category, createdAt, updatedAt
                        FROM skills
                    """.trimIndent()).use { /* 执行 INSERT,关闭 Cursor */ }
                } catch (e: Exception) {
                    // 旧表异常:放弃数据迁移,skills 表将重建为空(内置 skill 会在启动时重新写入)
                    // v1.71: 记录日志,便于排查用户 skill 丢失问题
                    Logger.w("MuseDb", "skills 表迁移失败,用户自定义 skill 将丢失", e)
                }
                // 3. 替换旧表(DDL)
                db.execSQL("DROP TABLE skills")
                db.execSQL("ALTER TABLE skills_new RENAME TO skills")
            }
        }

        /**
         * v1.53: MIGRATION_19_20 — 为 messages 表补索引,优化长会话查询性能。
         *
         * 新增两个复合索引(DDL,execSQL 可用):
         *  - index_messages_sessionId_createdAt:覆盖 observeBySession(ORDER BY createdAt)
         *    + deleteFromCreatedAt(WHERE sessionId AND createdAt >= ?)
         *  - index_messages_sessionId_createdAt_role:覆盖带 role 过饰的查询
         *
         * 原 v19 仅有 index_messages_sessionId 单列索引,长会话(几千条)下
         * ORDER BY createdAt 需 filesort,补复合索引后可走索引顺序扫描。
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_sessionId_createdAt ON messages(sessionId, createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_sessionId_createdAt_role ON messages(sessionId, createdAt, role)")
            }
        }

        /**
         * v1.54: MIGRATION_20_21 — RAG 体系化:知识库分块表 + 文档索引追踪字段。
         *
         * 1. 新建 knowledge_chunks 表(存分块文本 + embedding 向量 JSON)
         * 2. knowledge_docs 加 chunk_count + embedding_model 字段(ALTER TABLE ADD COLUMN,DDL)
         *
         * embedding 以 JSON 浮点数组字符串存储(如 "[0.012, -0.034, ...]"),
         * 检索时一次性加载到内存做余弦相似度遍历(规模 <1 万 chunk 性能足够)。
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 新建 knowledge_chunks 表
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS knowledge_chunks (
                        id TEXT NOT NULL PRIMARY KEY,
                        doc_id TEXT NOT NULL,
                        content TEXT NOT NULL DEFAULT '',
                        embedding TEXT NOT NULL DEFAULT '',
                        chunk_index INTEGER NOT NULL DEFAULT 0,
                        token_count INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_chunks_doc_id ON knowledge_chunks(doc_id)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_knowledge_chunks_doc_id_chunk_index ON knowledge_chunks(doc_id, chunk_index)")
                // 2. knowledge_docs 加索引追踪字段(ALTER TABLE ADD COLUMN 是 DDL,execSQL 可用)
                db.execSQL("ALTER TABLE knowledge_docs ADD COLUMN chunk_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE knowledge_docs ADD COLUMN embedding_model TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v1.81: MIGRATION_21_22 — 为 messages.role 和 scheduled_tasks(enabled, next_run_at) 补索引。
         *
         * 对应阶段3(M-SESS5)和阶段4(M-SC1)新增的 @Index 注解:
         *  - index_messages_role: 加速 WHERE role=? 无 sessionId 前缀的查询
         *  - index_scheduled_tasks_enabled_next_run_at: 覆盖 getDueTasks 的 WHERE enabled AND next_run_at <= ? 查询
         *
         * DDL(CREATE INDEX),Android 16 允许 execSQL 执行。
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_role ON messages(role)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scheduled_tasks_enabled_next_run_at ON scheduled_tasks(enabled, next_run_at)")
            }
        }

        /**
         * v1.82: MIGRATION_22_23 — 为 prompt_injections 和 quick_messages 补索引。
         *
         * 对应阶段4(M-PID1/M-QM1)新增的 @Index 注解:
         *  - index_prompt_injections_mode_enabled: 加速 getEnabledByMode(WHERE mode=? AND enabled=1)
         *  - index_quick_messages_scope_assistantId_enabled: 加速 observeForAssistant
         *    (WHERE scope='global' OR (scope='assistant' AND assistantId=?)) AND enabled=1)
         *
         * DDL(CREATE INDEX),Android 16 允许 execSQL 执行。
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_prompt_injections_mode_enabled ON prompt_injections(mode, enabled)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_quick_messages_scope_assistantId_enabled ON quick_messages(scope, assistantId, enabled)")
            }
        }

        /**
         * v1.83: MIGRATION_23_24 — 为 skills 和 knowledge_docs 补索引。
         *
         * 对应第5步(M-SD1/L-KE1)新增的 @Index 注解:
         *  - index_skills_enabled: 加速 listEnabled(WHERE enabled=1)
         *  - index_skills_category: 加速 observeAll(ORDER BY category ASC)
         *  - index_knowledge_docs_updated_at: 加速 observeAll(ORDER BY updated_at DESC)
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skills_enabled ON skills(enabled)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skills_category ON skills(category)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_docs_updated_at ON knowledge_docs(updated_at)")
            }
        }

        /**
         * v1.85: MIGRATION_24_25 — 修复 skills 表 implementationKotlin 列缺失 DEFAULT ''。
         *
         * 问题:第5步(v1.83)给 SkillEntity.implementationKotlin 加了 @ColumnInfo(defaultValue = ""),
         * 但 MIGRATION_6_7 / MIGRATION_18_19 建表时 implementationKotlin TEXT NOT NULL 无 DEFAULT,
         * MIGRATION_23_24 只加索引未重建表,导致 Room schema 校验崩溃
         * "Migration didn't properly handle: skills"。
         *
         * 方案:重建 skills 表,给 implementationKotlin 加 DEFAULT ''。
         * - CREATE TABLE skills_new(完整 schema,implementationKotlin 加 DEFAULT '')
         * - INSERT INTO skills_new SELECT ... FROM skills
         * - DROP TABLE skills(索引随表一起删除)
         * - ALTER TABLE skills_new RENAME TO skills
         * - 重建索引(index_skills_enabled, index_skills_category)
         */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 建新表(DDL,implementationKotlin 加 DEFAULT '')
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS skills_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        parametersJson TEXT NOT NULL DEFAULT '{}',
                        requiredJson TEXT NOT NULL DEFAULT '[]',
                        implementationKotlin TEXT NOT NULL DEFAULT '',
                        enabled INTEGER NOT NULL DEFAULT 1,
                        category TEXT NOT NULL DEFAULT 'custom',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                // 2. 数据迁移(DML,Android 16 必须用 query() 而非 execSQL)
                try {
                    db.query("""
                        INSERT INTO skills_new
                            (id, name, description, parametersJson, requiredJson,
                             implementationKotlin, enabled, category, createdAt, updatedAt)
                        SELECT
                            id, name, description, parametersJson, requiredJson,
                            implementationKotlin, enabled, category, createdAt, updatedAt
                        FROM skills
                    """.trimIndent()).use { /* 执行 INSERT,关闭 Cursor */ }
                } catch (e: Exception) {
                    // 旧表异常:放弃数据迁移,skills 表将重建为空(内置 skill 会在启动时重新写入)
                    Logger.w("MuseDb", "skills 表迁移失败,用户自定义 skill 将丢失", e)
                }
                // 3. 替换旧表(DDL)
                db.execSQL("DROP TABLE skills")
                db.execSQL("ALTER TABLE skills_new RENAME TO skills")
                // 4. 重建索引(MIGRATION_23_24 加的索引随 DROP TABLE 一起删除了)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skills_enabled ON skills(enabled)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skills_category ON skills(category)")
            }
        }

        /**
         * v1.97: MIGRATION_25_26 — 为 assistants 表加 regexRulesJson 字段(正则替换规则)。
         *
         * 新字段默认值 '[]'(空 JSON 数组),与 AssistantEntity.regexRulesJson 的 @ColumnInfo(defaultValue) 对齐。
         * 旧版助手迁移后无规则,行为与原版一致(无替换)。
         */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE assistants ADD COLUMN regexRulesJson TEXT NOT NULL DEFAULT '[]'"
                )
            }
        }

        /**
         * v1.98: MIGRATION_26_27 — 新增 experiences 表(经验库)。
         *
         * 存储用户在对话中积累的经验性知识(最佳实践/踩坑教训/工作流),
         * 与普通记忆(fact)区分:fact 记录"用户是谁",experience 记录"如何做某事"。
         * 当 experienceEnabled=true 时注入到 system prompt 供 AI 参考。
         */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS experiences (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL,
                        category TEXT NOT NULL DEFAULT '通用',
                        tagsJson TEXT NOT NULL DEFAULT '[]',
                        source TEXT NOT NULL DEFAULT 'manual',
                        sessionId TEXT,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        /**
         * v1.103: MIGRATION_27_28 — 为 messages 表补齐 mood / reflection 列。
         *
         * 之前 MessageEntity 缺这两个字段,任务会话/Agent 聊天(用 messages 表)
         * 落盘时 mood/reflection 被丢弃,切页/重载后 MOOD 卡片消失。
         * 群聊表(group_chat_messages)在 MIGRATION_13_14 建表时已含 mood 列,
         * 本次只补 messages 表。reflection 列两表此前都没有,一并补上。
         */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v1.105 修复:加 DEFAULT NULL,与 Entity @ColumnInfo(defaultValue="NULL") 对齐
                // (原 "ALTER TABLE ADD COLUMN mood TEXT" 无 DEFAULT,SQLite dflt_value=null,
                //  Room 期望 "NULL",验证不匹配崩溃)
                db.execSQL("ALTER TABLE messages ADD COLUMN mood TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN reflection TEXT DEFAULT NULL")
            }
        }

        /**
         * v1.104 U7: MIGRATION_28_29 — 为 messages 表加 favoriteTag 列(收藏分组标签)。
         *
         * NULL 表示未分组;旧用户升级后所有现有收藏的 favoriteTag 都为 NULL,
         * UI 上归入"全部"和"未分组"两个 chip 都能看到。
         */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v1.105 修复:加 DEFAULT NULL,与 Entity @ColumnInfo(defaultValue="NULL") 对齐
                db.execSQL("ALTER TABLE messages ADD COLUMN favoriteTag TEXT DEFAULT NULL")
            }
        }

        /**
         * v1.106: MIGRATION_29_30 — 重建 messages 表,修复历史 ALTER TABLE 未带 DEFAULT NULL
         * 导致的 schema 验证崩溃。
         *
         * 背景:MIGRATION_27_28(mood/reflection)和 MIGRATION_28_29(favoriteTag)原用
         * "ALTER TABLE ADD COLUMN x TEXT" 无 DEFAULT 子句,SQLite 的 PRAGMA table_info
         * 对这些列返回 dflt_value=null;而 Entity 声明 @ColumnInfo(defaultValue="NULL"),
         * Room 期望 dflt_value="NULL"。两者不匹配 → IllegalStateException 崩溃。
         *
         * 本 migration 用"建新表 + 复制数据 + 替换"重建 messages 表,确保三列都有
         * DEFAULT NULL。已升级到 v29 的崩溃用户走此 migration 修复。
         * MIGRATION_27_28/28_29 已同步加 DEFAULT NULL,未来升级用户无需走 29_30。
         */
        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 建新表(列定义与 onCreate 完全一致,含 DEFAULT NULL)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `messages_new` (
                        `id` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `reasoning` TEXT,
                        `modelId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `imageUrlsJson` TEXT NOT NULL,
                        `favorite` INTEGER NOT NULL DEFAULT 0,
                        `favoriteTag` TEXT DEFAULT NULL,
                        `citationUrlsJson` TEXT NOT NULL DEFAULT '[]',
                        `imageBase64Json` TEXT NOT NULL DEFAULT '[]',
                        `artifactIdsJson` TEXT NOT NULL DEFAULT '[]',
                        `mood` TEXT DEFAULT NULL,
                        `reflection` TEXT DEFAULT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                // 2. 复制数据(列顺序与建表一致)
                db.execSQL("""
                    INSERT INTO `messages_new` (
                        `id`,`sessionId`,`role`,`content`,`reasoning`,`modelId`,
                        `createdAt`,`imageUrlsJson`,`favorite`,`favoriteTag`,
                        `citationUrlsJson`,`imageBase64Json`,`artifactIdsJson`,
                        `mood`,`reflection`
                    )
                    SELECT
                        `id`,`sessionId`,`role`,`content`,`reasoning`,`modelId`,
                        `createdAt`,`imageUrlsJson`,`favorite`,`favoriteTag`,
                        `citationUrlsJson`,`imageBase64Json`,`artifactIdsJson`,
                        `mood`,`reflection`
                    FROM `messages`
                """.trimIndent())
                // 3. 替换旧表
                db.execSQL("DROP TABLE `messages`")
                db.execSQL("ALTER TABLE `messages_new` RENAME TO `messages`")
                // 4. 重建索引(DROP TABLE 会删除所有索引)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_sessionId` ON `messages` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_role` ON `messages` (`role`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_sessionId_createdAt` ON `messages` (`sessionId`, `createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_sessionId_createdAt_role` ON `messages` (`sessionId`, `createdAt`, `role`)")
            }
        }

        /**
         * v1.107: MIGRATION_30_31 — 全库冗余设计。
         *
         * 四个方向:
         *  1. 反规范化冗余字段: sessions/assistants/folders/group_chats/messages 加冗余列
         *  2. 统计聚合缓存表: 新建 stats_cache
         *  3. 数据完整性冗余: messages.contentLength + 新建 db_integrity_log
         *  4. 备份容灾冗余: 新建 auto_backup_log
         *
         * 所有冗余字段加 DEFAULT,与 Entity @ColumnInfo 对齐,避免 schema 验证崩溃。
         * 回填用关联子查询,一次性从现有数据计算出初始值。
         */
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── 1. 反规范化: ALTER TABLE 加冗余字段 ──
                db.execSQL("ALTER TABLE sessions ADD COLUMN messageCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE assistants ADD COLUMN messageCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE assistants ADD COLUMN lastUsedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folders ADD COLUMN sessionCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE group_chats ADD COLUMN lastMessagePreview TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE group_chats ADD COLUMN messageCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE group_chats ADD COLUMN lastActivityAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN contentLength INTEGER NOT NULL DEFAULT 0")

                // ── 2. 回填冗余字段初始值(关联子查询) ──
                // sessions.messageCount
                db.execSQL("UPDATE sessions SET messageCount = (SELECT COUNT(*) FROM messages WHERE sessionId = sessions.id)")
                // assistants.messageCount (该 Assistant 关联会话中的 ASSISTANT 消息数)
                db.execSQL("""
                    UPDATE assistants SET messageCount = (
                        SELECT COUNT(*) FROM messages m
                        JOIN sessions s ON m.sessionId = s.id
                        WHERE s.assistantId = assistants.id AND m.role = 'ASSISTANT'
                    )
                """.trimIndent())
                // assistants.lastUsedAt (关联会话的最大 updatedAt)
                db.execSQL("UPDATE assistants SET lastUsedAt = COALESCE((SELECT MAX(updatedAt) FROM sessions WHERE assistantId = assistants.id), 0)")
                // folders.sessionCount
                db.execSQL("UPDATE folders SET sessionCount = (SELECT COUNT(*) FROM sessions WHERE folderId = folders.id)")
                // group_chats.messageCount
                db.execSQL("UPDATE group_chats SET messageCount = (SELECT COUNT(*) FROM group_chat_messages WHERE chatId = group_chats.id)")
                // group_chats.lastActivityAt
                db.execSQL("UPDATE group_chats SET lastActivityAt = COALESCE((SELECT MAX(timestamp) FROM group_chat_messages WHERE chatId = group_chats.id), 0)")
                // group_chats.lastMessagePreview (最后一条消息内容,截断到 50 字)
                // 注意: group_chat_messages 表的内容列是 body 不是 content
                db.execSQL("""
                    UPDATE group_chats SET lastMessagePreview = COALESCE(
                        (SELECT substr(body, 1, 50) FROM group_chat_messages
                         WHERE chatId = group_chats.id ORDER BY timestamp DESC LIMIT 1),
                        ''
                    )
                """.trimIndent())
                // messages.contentLength
                db.execSQL("UPDATE messages SET contentLength = length(content)")

                // ── 3. 新建统计缓存表 ──
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `stats_cache` (
                        `key` TEXT NOT NULL,
                        `value` TEXT NOT NULL DEFAULT '{}',
                        `updatedAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`key`)
                    )
                """.trimIndent())

                // ── 4. 新建完整性校验日志表 ──
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `db_integrity_log` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `status` TEXT NOT NULL DEFAULT 'ok',
                        `details` TEXT NOT NULL DEFAULT '',
                        `dbSizeBytes` INTEGER NOT NULL DEFAULT 0,
                        `checkedAt` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                // ── 5. 新建自动备份日志表 ──
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `auto_backup_log` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `backupPath` TEXT NOT NULL DEFAULT '',
                        `fileSizeBytes` INTEGER NOT NULL DEFAULT 0,
                        `status` TEXT NOT NULL DEFAULT 'success',
                        `errorMessage` TEXT NOT NULL DEFAULT '',
                        `messageCount` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        /**
         * v2.0: MIGRATION_31_32 — 软删除(deletedAt 列)。
         * sessions 和 messages 添加 deletedAt 列,支持回收站功能。
         */
        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN deletedAt INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN deletedAt INTEGER DEFAULT NULL")
            }
        }

        /**
         * 功能1: MIGRATION_32_33 — 消息表情回应(reaction 列)。
         * messages 加 reaction 字段(null = 无回应)。
         */
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reaction TEXT DEFAULT NULL")
            }
        }

        /**
         * 功能2: MIGRATION_33_34 — assistants 表加 providerId 字段。
         * 用于每助手独立模型绑定时指定模型所属的 Provider,避免跨 Provider 模型名冲突。
         */
        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE assistants ADD COLUMN providerId TEXT DEFAULT NULL")
            }
        }

        /**
         * v1.122: MIGRATION_34_35 — 补齐 sessions 表 parentSessionId + childCount 列。
         * MIGRATION_31_32 漏加了 parentSessionId(分支父会话)和 childCount(分支计数),此处补加。
         */
        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN parentSessionId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE sessions ADD COLUMN childCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Phase 2 2B: MIGRATION_35_36 — 新建 milestones 表(关系里程碑)。
         */
        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS milestones (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        type TEXT NOT NULL DEFAULT 'auto',
                        condition_type TEXT NOT NULL,
                        trigger_value INTEGER NOT NULL DEFAULT 0,
                        title TEXT NOT NULL,
                        message TEXT NOT NULL,
                        assistant_id TEXT,
                        session_id TEXT,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        dismissed_at INTEGER
                    )
                """.trimIndent())
            }
        }

        /**
         * 既有工具系统 port: MIGRATION_36_37 — agent_messages table (DM system).
         */
        val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS agent_messages (
                        id TEXT NOT NULL PRIMARY KEY,
                        from_agent_id TEXT NOT NULL,
                        to_agent_id TEXT NOT NULL,
                        content TEXT NOT NULL,
                        is_read INTEGER NOT NULL DEFAULT 0,
                        reply_to_id TEXT,
                        created_at INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        /**
         * P2-4: MIGRATION_37_38 — 新建 audit_log 表(审计日志)。
         *
         * 仅 createTable,不修改既有表结构。环形缓冲策略由 AuditLogger 维护。
         */
        val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS audit_log (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        timestamp INTEGER NOT NULL,
                        category TEXT NOT NULL DEFAULT '',
                        action TEXT NOT NULL DEFAULT '',
                        target TEXT NOT NULL DEFAULT '',
                        detail TEXT NOT NULL DEFAULT '',
                        success INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
            }
        }

        /**
         * v1.133: MIGRATION_38_39 — RAG 体系化升级(多知识库 + BLOB embedding + FTS4 + 增量更新 + 元数据)。
         *
         * 改动:
         *  1. 新建 knowledge_bases 表
         *  2. knowledge_docs 加 4 列:kb_id / is_internal / content_hash / metadata_json
         *  3. knowledge_chunks 加 2 列:embedding_blob / metadata_json
         *  4. 新建 knowledge_chunks_fts 虚拟表(FTS4)
         *  5. assistants 加 2 列:knowledgeBaseIdsJson / ragConfigOverride
         *  6. 插入默认 KB(id="default")
         *  7. 把现存文档 kb_id 设为 "default",is_internal 按 fileType="devdoc" / id.startsWith("devdoc-") 标记
         *  8. 为现存 chunk 同步建立 FTS 索引
         *  9. 更新默认 KB 的 doc_count
         * 10. messages 加 ragCitationsJson 列(持久化 RAG 引用列表)
         *
         * 不做数据迁移:旧 embedding JSON 列保留,新代码读取时 BLOB 优先 fallback JSON。
         */
        val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 新建 knowledge_bases 表
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS knowledge_bases (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0,
                        doc_count INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_bases_updated_at ON knowledge_bases(updated_at)")

                // 2. knowledge_docs 加 4 列(ALTER TABLE ADD COLUMN 是 DDL,SQLite 支持)
                db.execSQL("ALTER TABLE knowledge_docs ADD COLUMN kb_id TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("ALTER TABLE knowledge_docs ADD COLUMN is_internal INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE knowledge_docs ADD COLUMN content_hash TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE knowledge_docs ADD COLUMN metadata_json TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_docs_kb_id ON knowledge_docs(kb_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_docs_content_hash ON knowledge_docs(content_hash)")

                // 3. knowledge_chunks 加 2 列
                db.execSQL("ALTER TABLE knowledge_chunks ADD COLUMN embedding_blob BLOB DEFAULT NULL")
                db.execSQL("ALTER TABLE knowledge_chunks ADD COLUMN metadata_json TEXT NOT NULL DEFAULT '{}'")

                // 4. 新建 FTS4 虚拟表
                // 列名用 text_content 而非 content,避开 FTS4 内部 content 列占位符冲突
                // 列名用 doc_id 而非 docId:FTS4 保留列 docid(rowid 别名,大小写不敏感),docId 会导致 vtable constructor failed
                // 不指定 tokenizer:中文检索由调用方做 ngram 预处理后再写入(按 MessageFtsManager 模式)
                db.execSQL("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS knowledge_chunks_fts
                    USING fts4(chunkId, doc_id, text_content)
                """.trimIndent())

                // 5. assistants 加 2 列
                db.execSQL("ALTER TABLE assistants ADD COLUMN knowledgeBaseIdsJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE assistants ADD COLUMN ragConfigOverride TEXT DEFAULT NULL")

                // 6. 插入默认 KB
                db.compileStatement("""
                    INSERT OR IGNORE INTO knowledge_bases (id, name, description, created_at, updated_at, doc_count)
                    VALUES ('default', '默认知识库', '所有未指定知识库的文档默认归入此处', 0, 0, 0)
                """.trimIndent()).use { it.executeInsert() }

                // 7. 标记 internal 文档(替代原 fileType="devdoc" + id.startsWith("devdoc-") 双重硬编码)
                db.execSQL("UPDATE knowledge_docs SET is_internal = 1 WHERE file_type = 'devdoc' OR id LIKE 'devdoc-%'")

                // 8. 为现存 chunk 同步建立 FTS 索引(用 INSERT INTO ... SELECT)
                // Android 16 (SDK 36) 起禁止 execSQL 执行 DML,改用 compileStatement 不适用于 SELECT INSERT 组合,
                // 这里用 SupportSQLiteDatabase.execSQL(原始 SQL) — Room 的 SupportSQLiteDatabase.execSQL 对 INSERT 仍兼容。
                try {
                    db.execSQL("""
                        INSERT INTO knowledge_chunks_fts(chunkId, doc_id, text_content)
                        SELECT id, doc_id, content FROM knowledge_chunks
                        WHERE content != ''
                    """.trimIndent())
                } catch (e: Exception) {
                    // FTS 同步失败不阻塞迁移,后续重索引会补齐
                    io.zer0.common.Logger.w("MuseDb", "FTS 同步失败,稍后重索引补齐", e)
                }

                // 9. 更新默认 KB 的 doc_count(冗余字段)
                db.execSQL("""
                    UPDATE knowledge_bases
                    SET doc_count = (SELECT COUNT(*) FROM knowledge_docs WHERE kb_id = 'default' AND is_internal = 0)
                    WHERE id = 'default'
                """.trimIndent())

                // 10. messages 加 ragCitationsJson 列(持久化 RAG 引用列表)
                db.execSQL("ALTER TABLE messages ADD COLUMN ragCitationsJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /**
         * v1.134 P2-1: MIGRATION_39_40 — 为 scheduled_tasks 加 dedicated_session_id 列。
         *
         * 背景:原 ScheduledTaskRunner.executeTask 每次执行都新建会话,长期运行的 daily
         * 任务会产生大量分散会话。新增 dedicated_session_id 字段,首次执行时创建专用
         * 会话并写入此字段,后续执行复用同一会话(会话聚合模式)。
         *
         * 字段为空串表示未启用聚合(向后兼容,旧任务首次执行后才会填充)。
         */
        val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE scheduled_tasks ADD COLUMN dedicated_session_id TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /**
         * v1.137: MIGRATION_40_41 — 为 scheduled_tasks 增加复杂自动化字段。
         *
         * 新增:
         *  - condition_json: 条件触发配置
         *  - action_type / action_config_json: 动作类型与配置
         *  - next_task_ids_json: 链式任务 ID 列表
         *  - parent_task_id: 父任务 ID(链式溯源)
         */
        val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN condition_json TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN action_type TEXT NOT NULL DEFAULT 'ai_prompt'")
                db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN action_config_json TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN next_task_ids_json TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN parent_task_id TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v1.0.23 hotfix: MIGRATION_41_42 — 修复"假 v41"数据库 integrity check 崩溃。
         *
         * 问题根因:
         *  某个中间开发版本改了 ScheduledTaskEntity 但未升 MuseDb version(仍标 41),
         *  导致装过该中间版的设备数据库 schema hash 为 f651a76c...(非标准 v41 的 8edc5eb8...),
         *  升级到正确 v41 APK 后 Room 因 version 相同跳过 migration,直接 integrity check 失败崩溃。
         *
         * 修复策略:
         *  提升 version 到 42,强制 Room 走 migration 路径(绕过 v41 的 integrity check)。
         *  migration 中防御性检测 scheduled_tasks 表的字段完整性 — 逐列 PRAGMA table_info 检查,
         *  缺失则 ALTER TABLE ADD COLUMN 补上。
         *  这样无论设备上是"真 v41"(字段齐全,无需 ALTER)还是"假 v41"(字段缺失,逐个补上),
         *  migration 后 schema 都与 v42(= 正确 v41)一致,integrity check 通过。
         *
         * 数据安全:
         *  - "真 v41"用户:所有字段存在,migration 空跑,数据零丢失
         *  - "假 v41"用户:补上缺失字段(默认值填充),现有数据保留
         */
        val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 防御性修复:检测 scheduled_tasks 表字段完整性,补上可能缺失的列
                val existingColumns = mutableSetOf<String>()
                db.query("PRAGMA table_info(scheduled_tasks)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) {
                        existingColumns.add(cursor.getString(nameIndex))
                    }
                }
                // v1.134: dedicated_session_id
                if ("dedicated_session_id" !in existingColumns) {
                    db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN dedicated_session_id TEXT NOT NULL DEFAULT ''")
                }
                // v1.137: 5 个自动化字段
                if ("condition_json" !in existingColumns) {
                    db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN condition_json TEXT NOT NULL DEFAULT ''")
                }
                if ("action_type" !in existingColumns) {
                    db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN action_type TEXT NOT NULL DEFAULT 'ai_prompt'")
                }
                if ("action_config_json" !in existingColumns) {
                    db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN action_config_json TEXT NOT NULL DEFAULT ''")
                }
                if ("next_task_ids_json" !in existingColumns) {
                    db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN next_task_ids_json TEXT NOT NULL DEFAULT ''")
                }
                if ("parent_task_id" !in existingColumns) {
                    db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN parent_task_id TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v1.0.15: 消息发送 outbox 表(持久化发送队列)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS message_outbox (
                        id TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        text TEXT NOT NULL,
                        imageBase64Json TEXT NOT NULL DEFAULT '[]',
                        userMessageId TEXT NOT NULL,
                        assistantMessageId TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(sessionId) REFERENCES sessions(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_message_outbox_sessionId ON message_outbox(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_message_outbox_createdAt ON message_outbox(createdAt)")
            }
        }

        /**
         * v1.0.17: MIGRATION_43_44 — 新增 translate_history 表(翻译历史持久化)。
         *
         * 翻译历史此前仅内存保留(MAX_HISTORY=50),进程被杀即丢失。
         * 迁移仅建表 + 索引,无数据迁移(旧版历史本就不持久化)。
         */
        val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // translate_history 表(SQL 必须与 TranslateHistoryEntity @Entity 注解生成的完全一致)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS translate_history (
                        id TEXT NOT NULL PRIMARY KEY,
                        source_text TEXT NOT NULL,
                        translated_text TEXT NOT NULL,
                        source_language TEXT NOT NULL,
                        target_language TEXT NOT NULL,
                        style TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                """.trimIndent())
                // 索引: 按时间倒序查询历史(对应 @Index 注解)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_translate_history_created_at ON translate_history(created_at)")
                // 索引: 按语言对查询(对应 @Index 注解)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_translate_history_source_language_target_language ON translate_history(source_language, target_language)")
            }
        }

        /**
         * v1.0.17: MIGRATION_44_45 — 新增 quick_notes 表(快速记录 Room 持久化 + 回收站)。
         *
         * 替代 QuickNoteStore 的 JSON 文件存储;App 启动时由 QuickNoteStore.migrateToRoom
         * 把旧数据导入 Room(通过 SharedPreferences 标志 quick_notes_migrated 保证幂等)。
         *
         * tags 列存逗号分隔字符串(由 QuickNoteConverters TypeConverter 处理 List<String> ↔ String)。
         */
        val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // quick_notes 表(SQL 必须与 QuickNoteEntity @Entity 注解生成的完全一致)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS quick_notes (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL,
                        tags TEXT NOT NULL,
                        pinned INTEGER NOT NULL DEFAULT 0,
                        deleted INTEGER NOT NULL DEFAULT 0,
                        deleted_at INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                // 索引: 覆盖 observeActive / observeTrash 的 WHERE + ORDER BY
                db.execSQL("CREATE INDEX IF NOT EXISTS index_quick_notes_deleted_updated_at ON quick_notes(deleted, updated_at)")
                // 索引: 覆盖 ORDER BY pinned DESC, updated_at DESC
                db.execSQL("CREATE INDEX IF NOT EXISTS index_quick_notes_pinned_updated_at ON quick_notes(pinned, updated_at)")
            }
        }

        /**
         * v1.0.17: MIGRATION_45_46 — 为 scheduled_tasks 增加重试字段。
         *
         * 新增:
         *  - retry_count: 当前重试次数(默认 0,失败递增,成功/达上限重置)
         *  - max_retries: 最大重试次数(默认 3)
         *
         * 用于 executeTask 中的指数退避重试策略。
         */
        val MIGRATION_45_46 = object : Migration(45, 46) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN max_retries INTEGER NOT NULL DEFAULT 3")
            }
        }

        /**
         * v1.0.30 gap4.3: MIGRATION_46_47 — translate_history 加 favorite 列(翻译收藏夹)。
         *
         * 新字段默认 0(未收藏),用户可在翻译历史中点击星标收藏常用翻译。
         * 索引 index_translate_history_favorite 覆盖 observeFavorites 查询
         * (WHERE favorite=1 ORDER BY created_at DESC)。
         *
         * 注: v1.0.17 已用 MIGRATION_45_46 为 scheduled_tasks 加重试字段,
         * 故本次翻译收藏夹采用 46 → 47。
         */
        val MIGRATION_46_47 = object : Migration(46, 47) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE translate_history ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_translate_history_favorite ON translate_history(favorite)")
            }
        }

        /**
         * v1.0.18: MIGRATION_47_48 — 快速记录增强(9 项)。
         *
         * 为 quick_notes 表添加 6 列:
         *  - folder: 分类/文件夹(默认 '')
         *  - content_type: 内容类型 plain/markdown(默认 'plain')
         *  - attachments_json: 图片附件路径 JSON 数组(默认 '')
         *  - reminder_at: 提醒时间戳(默认 0 = 无提醒)
         *  - encrypted: 加密标记(默认 0)
         *  - encrypted_content: 加密内容密文(默认 '')
         *
         * 新增 folder 列索引,覆盖 observeByFolder 查询。
         */
        val MIGRATION_47_48 = object : Migration(47, 48) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE quick_notes ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE quick_notes ADD COLUMN content_type TEXT NOT NULL DEFAULT 'plain'")
                db.execSQL("ALTER TABLE quick_notes ADD COLUMN attachments_json TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE quick_notes ADD COLUMN reminder_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE quick_notes ADD COLUMN encrypted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE quick_notes ADD COLUMN encrypted_content TEXT NOT NULL DEFAULT ''")
                // 索引: 覆盖 observeByFolder(WHERE folder = ?) + observeFolders(DISTINCT folder)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_quick_notes_folder ON quick_notes(folder)")
            }
        }

        /**
         * v2.x: MIGRATION_49_50 — 新建 group_chat_memories 表(群聊记忆隔离)。
         *
         * 背景:群聊消息含多个 Agent 发言,直接写入助手主记忆会污染主对话上下文。
         * 本表存储群聊消息摘要,与 [io.zer0.memory] 模块的主记忆系统完全隔离。
         * SystemPromptAssembler 注入时用 `<group_chat_memory>` 标签与主记忆 `<long_term_memory>` 区分。
         *
         * 索引:
         *  - assistantId:覆盖 [GroupChatMemoryDao.getByAssistant] 查询(SystemPromptAssembler 注入)
         *  - groupChatId:覆盖 [GroupChatMemoryDao.getByGroupChat] 查询 + 级联删除
         *  - createdAt:覆盖 [GroupChatMemoryDao.deleteOlderThan] 清理
         */
        val MIGRATION_49_50 = object : Migration(49, 50) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS group_chat_memories (
                        id TEXT NOT NULL PRIMARY KEY,
                        groupChatId TEXT NOT NULL,
                        assistantId TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        expiresAt INTEGER
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_group_chat_memories_assistantId ON group_chat_memories(assistantId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_group_chat_memories_groupChatId ON group_chat_memories(groupChatId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_group_chat_memories_createdAt ON group_chat_memories(createdAt)")
            }
        }

        /**
         * v2.x: MIGRATION_50_51 — 群聊讨论模式字段。
         *
         * 为 group_chats 表添加 3 列:
         *  - discussion_mode: 讨论模式(round_robin/auto/debate/host,默认 round_robin)
         *  - auto_max_rounds: Auto 模式最大连续对话轮数(默认 5)
         *  - host_id: 主持人模式的 AI id(默认 NULL)
         *
         * 所有新列均带默认值,保持向后兼容;现有群聊自动获得 round_robin 模式。
         */
        val MIGRATION_50_51 = object : Migration(50, 51) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE group_chats ADD COLUMN discussionMode TEXT NOT NULL DEFAULT 'round_robin'")
                db.execSQL("ALTER TABLE group_chats ADD COLUMN autoMaxRounds INTEGER NOT NULL DEFAULT 5")
                db.execSQL("ALTER TABLE group_chats ADD COLUMN host_id TEXT DEFAULT NULL")
            }
        }

        /**
         * v2.x: MIGRATION_51_52 — 群聊消息增强字段(悄悄话 / 引用回复 / 消息类型)。
         */
        val MIGRATION_51_52 = object : Migration(51, 52) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE group_chat_messages ADD COLUMN whisper_target_id TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE group_chat_messages ADD COLUMN reply_to_id TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE group_chat_messages ADD COLUMN messageType TEXT NOT NULL DEFAULT 'normal'")
            }
        }

        /**
         * v2.x: MIGRATION_52_53 — 群聊上下文管理字段(群共享文档 + AI 专属上下文)。
         *
         * 为 group_chats 表添加 2 列:
         *  - shared_docs_json: 群共享文档列表 JSON(默认 "[]"),所有成员可见
         *  - member_private_context_json: 成员专属上下文 Map JSON(默认 "{}"),按 assistantId 隔离
         *
         * 所有新列均带默认值,保持向后兼容;现有群聊自动获得空列表/空映射。
         */
        val MIGRATION_52_53 = object : Migration(52, 53) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE group_chats ADD COLUMN shared_docs_json TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE group_chats ADD COLUMN member_private_context_json TEXT NOT NULL DEFAULT '{}'")
            }
        }

        /**
         * v1.0.24: MIGRATION_53_54 — 修正群聊表列默认值。
         *
         * 早期迁移(50_51 / 51_52)在 ALTER TABLE ADD COLUMN 时遗漏了 DEFAULT NULL,
         * 导致已迁移数据库中 host_id / whisper_target_id / reply_to_id 的默认值为
         * 'undefined'(Room 内部表示),与实体声明的 defaultValue = "NULL" 不匹配,
         * 启动时抛出 IllegalStateException。
         *
         * SQLite 不支持 ALTER COLUMN,必须重建表:
         *  1. 创建临时表(带正确 DEFAULT NULL)
         *  2. 复制全部数据
         *  3. DROP 旧表
         *  4. RENAME 临时表为正式表名
         *
         * 同时重建 group_chats 与 group_chat_messages 两张表。
         */
        val MIGRATION_53_54 = object : Migration(53, 54) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── 重建 group_chats(host_id 默认值 NULL) ──
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS group_chats_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        memberIdsJson TEXT NOT NULL,
                        teamId TEXT,
                        pinned INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        lastMessagePreview TEXT NOT NULL DEFAULT '',
                        messageCount INTEGER NOT NULL DEFAULT 0,
                        lastActivityAt INTEGER NOT NULL DEFAULT 0,
                        discussionMode TEXT NOT NULL DEFAULT 'round_robin',
                        autoMaxRounds INTEGER NOT NULL DEFAULT 5,
                        host_id TEXT DEFAULT NULL,
                        shared_docs_json TEXT NOT NULL DEFAULT '[]',
                        member_private_context_json TEXT NOT NULL DEFAULT '{}'
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO group_chats_new (id, name, description, memberIdsJson, teamId, pinned,
                        createdAt, updatedAt, lastMessagePreview, messageCount, lastActivityAt,
                        discussionMode, autoMaxRounds, host_id, shared_docs_json, member_private_context_json)
                    SELECT id, name, description, memberIdsJson, teamId, pinned,
                        createdAt, updatedAt, lastMessagePreview, messageCount, lastActivityAt,
                        discussionMode, autoMaxRounds, host_id, shared_docs_json, member_private_context_json
                    FROM group_chats
                """.trimIndent())
                db.execSQL("DROP TABLE group_chats")
                db.execSQL("ALTER TABLE group_chats_new RENAME TO group_chats")

                // ── 重建 group_chat_messages(whisper_target_id / reply_to_id 默认值 NULL) ──
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS group_chat_messages_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        chatId TEXT NOT NULL,
                        senderType TEXT NOT NULL,
                        senderId TEXT NOT NULL,
                        senderName TEXT NOT NULL,
                        body TEXT NOT NULL,
                        imageBase64Json TEXT NOT NULL DEFAULT '[]',
                        timestamp INTEGER NOT NULL DEFAULT 0,
                        mood TEXT,
                        reasoning TEXT,
                        whisper_target_id TEXT DEFAULT NULL,
                        reply_to_id TEXT DEFAULT NULL,
                        messageType TEXT NOT NULL DEFAULT 'normal'
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO group_chat_messages_new (id, chatId, senderType, senderId, senderName,
                        body, imageBase64Json, timestamp, mood, reasoning,
                        whisper_target_id, reply_to_id, messageType)
                    SELECT id, chatId, senderType, senderId, senderName,
                        body, imageBase64Json, timestamp, mood, reasoning,
                        whisper_target_id, reply_to_id, messageType
                    FROM group_chat_messages
                """.trimIndent())
                db.execSQL("DROP TABLE group_chat_messages")
                db.execSQL("ALTER TABLE group_chat_messages_new RENAME TO group_chat_messages")

                // 重建索引(随表 DROP 一起消失)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_group_chat_messages_chatId ON group_chat_messages(chatId)")
            }
        }

        /**
         * v1.0.19: MIGRATION_48_49 — Assistant 字段补齐。
         *
         * 为 assistants 表添加 3 列:
         *  - summary: 一句话简介(默认 '',用于群聊花名册/卡片副标题)
         *  - useAssistantName: 聊天界面用助手名替换模型名(默认 0 = false)
         *  - allowGroupChat: 是否允许加入群聊(默认 1 = true)
         *
         * 注: tags 分组功能已由 tagsJson 字段承载,不重复添加。
         * 所有新列均带默认值,保持向后兼容;现有 Assistant 行自动获得默认值。
         */
        val MIGRATION_48_49 = object : Migration(48, 49) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE assistants ADD COLUMN summary TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE assistants ADD COLUMN useAssistantName INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE assistants ADD COLUMN allowGroupChat INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * v1.0.30: MIGRATION_54_55 — 消息表加变体字段(variantGroupId/variantIndex/variantCount)。
         *
         * 用于持久化"重新生成"产生的多版本 assistant 回复。
         * ALTER TABLE ADD COLUMN 全用 NULL/0/1 默认值,兼容已有数据。
         */
        val MIGRATION_54_55 = object : Migration(54, 55) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN variantGroupId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN variantIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN variantCount INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * v1.0.47: MIGRATION_55_56 — 消息表加附件字段(attachmentsJson)。
         *
         * 用于结构化持久化原始文件元数据(文件名/MIME/大小/提取文本),
         * 替代之前文档解析后合并进 content、原始文件元数据丢弃的方式。
         * 默认 '[]'(空数组),兼容已有数据。
         */
        val MIGRATION_55_56 = object : Migration(55, 56) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN attachmentsJson TEXT NOT NULL DEFAULT '[]'")
                // v1.0.47: lorebooks 表加 wholeWord 列(全词匹配模式,默认 false)
                db.execSQL("ALTER TABLE lorebooks ADD COLUMN wholeWord INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v1.0.47 P3: MIGRATION_56_57 — sessions 表加 skillIdsJson 列(会话级 skill 覆盖)。
         *
         * "[]" 表示继承 Assistant 的 skillIdsJson(默认行为不变);
         * 非空数组表示覆盖 Assistant,仅启用指定 skill。
         * 默认 '[]' 兼容已有数据。
         */
        val MIGRATION_56_57 = object : Migration(56, 57) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN skillIdsJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /**
         * P1-2: MIGRATION_57_58 — 新建 worldbook_entries 表(动态世界书)。
         *
         * 与现有 lorebooks 表独立,支持 Lorebook 不具备的高级特性:
         *  - alwaysActive 常驻激活 / scanDepth 多层扫描 / isRegex 正则关键词
         *  - injectTarget(system/user/assistant) + injectPosition(prepend/append/at_depth) + insertionDepth
         *  - assistantId 绑定特定助手(NULL = 全局)
         *
         * 所有列带 DEFAULT,与 [io.zer0.muse.worldbook.WorldBookEntryEntity] 的 @ColumnInfo(defaultValue=...) 对齐。
         * assistantId 列用 DEFAULT NULL(sqlite 中 TEXT 列默认可为 NULL)。
         */
        val MIGRATION_57_58 = object : Migration(57, 58) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS worldbook_entries (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        keywordsJson TEXT NOT NULL DEFAULT '[]',
                        content TEXT NOT NULL DEFAULT '',
                        priority INTEGER NOT NULL DEFAULT 50,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        caseSensitive INTEGER NOT NULL DEFAULT 0,
                        isRegex INTEGER NOT NULL DEFAULT 0,
                        alwaysActive INTEGER NOT NULL DEFAULT 0,
                        scanDepth INTEGER NOT NULL DEFAULT 3,
                        injectTarget TEXT NOT NULL DEFAULT 'system',
                        injectPosition TEXT NOT NULL DEFAULT 'append',
                        insertionDepth INTEGER NOT NULL DEFAULT 0,
                        assistantId TEXT DEFAULT NULL,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v1.0.53 Phase 1: MIGRATION_58_59 — 新建 subagent_threads 表(子 agent 线程账本持久化)。
         *
         * 由子 agent 线程账本持久化实现,替代旧 tools/SubagentThreadStore.kt(内存版)。
         * 两条 subagent 路径共享:
         *  - 路径 A: SubagentTool + SkillExecutor.delegateAgent nonBlocking(子助手委派)
         *  - 路径 B: SubagentRunSkill + SubagentRunner(被动子 agent)
         *
         * 列说明见 [io.zer0.muse.data.subagent.SubagentThreadEntity];子会话历史(消息)
         * 走 JSONL 文件 filesDir/subagent_sessions/<threadId>.jsonl(由 SubagentSessionStore 管理),
         * 不入 Room(避免每轮工具结果都写表,且与主会话列表隔离)。
         *
         * 所有列带 DEFAULT,与 Entity 的 @ColumnInfo(defaultValue=...) / Kotlin 默认值对齐。
         * childSessionId / label / lastRunStatus / lastSummary 允许 NULL。
         */
        val MIGRATION_58_59 = object : Migration(58, 59) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS subagent_threads (
                        threadId TEXT NOT NULL PRIMARY KEY,
                        parentSessionId TEXT NOT NULL,
                        childSessionId TEXT DEFAULT NULL,
                        childSessionPath TEXT NOT NULL,
                        assistantId TEXT NOT NULL,
                        label TEXT DEFAULT NULL,
                        access TEXT NOT NULL DEFAULT 'read',
                        status TEXT NOT NULL DEFAULT 'open',
                        runCount INTEGER NOT NULL DEFAULT 0,
                        lastRunStatus TEXT DEFAULT NULL,
                        lastSummary TEXT DEFAULT NULL,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_subagent_threads_parentSessionId ON subagent_threads(parentSessionId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_subagent_threads_status ON subagent_threads(status)"
                )
            }
        }

    /**
     * v1.0.53: MIGRATION_59_60 — assistants 表加 toolModelId 列(per-assistant 工具模型)。
     * 工具调用轮次优先用助手自己的工具模型,未设置时回退全局。
     */
    val MIGRATION_59_60 = object : Migration(59, 60) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE assistants ADD COLUMN toolModelId TEXT NOT NULL DEFAULT ''")
        }
    }


    /**
     * B5-03: MIGRATION_60_61 — messages 加 thinking_signature / thinking_encrypted_content。
     */
    val MIGRATION_60_61 = object : Migration(60, 61) { override fun migrate(db: SupportSQLiteDatabase) { ensureMessageColumns(db) }
    }
    /**
     * B5-01: MIGRATION_61_62 — 新增 generation_checkpoints 表。
     */
    val MIGRATION_61_62 = object : Migration(61, 62) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `generation_checkpoints` (`assistantMessageId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `userMessageId` TEXT NOT NULL, `content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`assistantMessageId`), FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_generation_checkpoints_sessionId` ON `generation_checkpoints` (`sessionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_generation_checkpoints_createdAt` ON `generation_checkpoints` (`createdAt`)")
        }
    }
    /**
     * B5-02: MIGRATION_62_63 — 新增 group_chat_generation_ledger 表。
     */
    val MIGRATION_62_63 = object : Migration(62, 63) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `group_chat_generation_ledger` (`id` TEXT NOT NULL, `chatId` TEXT NOT NULL, `mode` TEXT NOT NULL, `round` INTEGER NOT NULL, `memberIndex` INTEGER NOT NULL, `memberIdsJson` TEXT NOT NULL DEFAULT '[]', `status` TEXT NOT NULL DEFAULT 'running', `createdAt` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`), FOREIGN KEY(`chatId`) REFERENCES `group_chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_chat_generation_ledger_chatId` ON `group_chat_generation_ledger` (`chatId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_chat_generation_ledger_status` ON `group_chat_generation_ledger` (`status`)")
        }
    }
    /**
     * B6-03: MIGRATION_63_64 — messages 加 mood_skin 列(情绪皮肤标识)。
     */
    val MIGRATION_63_64 = object : Migration(63, 64) { override fun migrate(db: SupportSQLiteDatabase) { ensureMessageColumns(db) }
    }
    /**
     * B7-03/B7-05: MIGRATION_64_65 — sessions 加 lastReadMessageId / sortOrder。
     */
    val MIGRATION_64_65 = object : Migration(64, 65) { override fun migrate(db: SupportSQLiteDatabase) { ensureSessionColumns(db) }
    }
    /**
     * B7-03: MIGRATION_65_66 — sessions 加 lastReadCount,用于会话列表未读数徽标。
     */
    val MIGRATION_65_66 = object : Migration(65, 66) { override fun migrate(db: SupportSQLiteDatabase) { ensureSessionColumns(db) }
    }
    /**
     * B8-01: MIGRATION_66_67 — sessions 加 proactiveNextTriggerAt,支持会话级主动消息排期。
     */
    val MIGRATION_66_67 = object : Migration(66, 67) { override fun migrate(db: SupportSQLiteDatabase) { ensureSessionColumns(db) }
    }
    /**
     * B8-06: MIGRATION_67_68 — 修复历史库 messages/sessions 缺列问题。
     *
     * 旧版本若曾在迁移中途失败或使用不一致 schema,Room 会报
     * "Migration didn't properly handle: messages"。本迁移按 PRAGMA 检查,
     * 只补缺失列,已存在的列保持不动。
     */
    val MIGRATION_67_68 = object : Migration(67, 68) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureMessageColumns(db)
            ensureSessionColumns(db)
            ensureGroupChatMessageColumns(db)
        }
    }

    /**
     * v1.0.62 fix: 兼容从 v1.0.60（DB version 73）升级的场景：
     * 确保 generation_checkpoints 和 group_chat_generation_ledger 表存在。
     */
    val MIGRATION_68_74 = object : Migration(68, 74) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureGenerationTables(db)
            ensureSessionColumns(db)
            ensureMessageColumns(db)
            ensureGroupChatMessageColumns(db)
        }
    }

    /** v1.0.74: 68→69 补链(68_74 直跳仍保留,供老设备)。 */
    val MIGRATION_68_69 = object : Migration(68, 69) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureSessionColumns(db)
            ensureMessageColumns(db)
        }
    }

    /** v1.0.74: 补齐 69-72 版本缺口 — 若这些版本曾发布(灰度/测试),升级时 Room 找不到迁移会启动崩溃。
     *  空迁移 + ensure 幂等补列,与 68_74 共用防御逻辑。 */
    val MIGRATION_69_70 = object : Migration(69, 70) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureSessionColumns(db)
            ensureMessageColumns(db)
        }
    }
    val MIGRATION_70_71 = object : Migration(70, 71) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureSessionColumns(db)
            ensureMessageColumns(db)
        }
    }
    val MIGRATION_71_72 = object : Migration(71, 72) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureSessionColumns(db)
            ensureMessageColumns(db)
        }
    }
    val MIGRATION_72_73 = object : Migration(72, 73) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureSessionColumns(db)
            ensureMessageColumns(db)
        }
    }
    /**
     * P0 对话树: messages 表加 parentGroupId(助手变体所属的用户提问变体组)。
     * 旧库升级时通过 ensureMessageColumns 幂等补列,不影响存量数据。
     */
    val MIGRATION_74_75 = object : Migration(74, 75) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureMessageColumns(db)
            ensureGroupChatMessageColumns(db)
        }
    }
    val MIGRATION_73_74 = object : Migration(73, 74) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureGenerationTables(db)
            ensureGroupChatMessageColumns(db)
        }
    }

    /**
     * R-DB-04: 75→76 — messages 表存量 base64 图片外置到 filesDir/muse_images/。
     * 复用 MessageImageStore.toPersistable:长 base64 落盘并改为 file:// 引用,
     * 短 base64 保持内联(与 v1.134 新写入行为一致);失败时回退原值,幂等可重入。
     */
    fun migrate75To76(storageDir: File): Migration = object : Migration(75, 76) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val store = MessageImageStore(storageDir)
            val jsonSerializer = ListSerializer(String.serializer())
            db.query(
                "SELECT id, imageBase64Json FROM messages " +
                    "WHERE imageBase64Json IS NOT NULL AND imageBase64Json != '' AND imageBase64Json != '[]'",
            ).use { cursor ->
                val idIdx = cursor.getColumnIndex("id")
                val jsonIdx = cursor.getColumnIndex("imageBase64Json")
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIdx)
                    val json = cursor.getString(jsonIdx)
                    val base64List = runCatching {
                        AppJson.decodeFromString(jsonSerializer, json)
                    }.getOrNull()
                    if (base64List.isNullOrEmpty()) continue
                    val persistable = store.toPersistable(id, base64List)
                    if (persistable != base64List) {
                        val updated = AppJson.encodeToString(jsonSerializer, persistable)
                        db.execSQL(
                            "UPDATE messages SET imageBase64Json = ? WHERE id = ?",
                            arrayOf(updated, id),
                        )
                    }
                }
            }
        }
    }



    fun get(context: Context): MuseDb {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MuseDb::class.java,
                    "muse.db",
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                        MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
                        MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21,
                        MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24,
                        MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28,
                        MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31,
                        MIGRATION_31_32,
                        MIGRATION_32_33,
                        MIGRATION_33_34,
                        MIGRATION_34_35,
                        MIGRATION_35_36,
                        MIGRATION_36_37,
                        MIGRATION_37_38,
                        MIGRATION_38_39,
                        MIGRATION_39_40,
                        MIGRATION_40_41,
                        MIGRATION_41_42,
                        MIGRATION_42_43,
                        MIGRATION_43_44,
                        MIGRATION_44_45,
                        MIGRATION_45_46,
                        MIGRATION_46_47,
                        MIGRATION_47_48,
                        MIGRATION_48_49,
                        MIGRATION_49_50,
                        MIGRATION_50_51,
                        MIGRATION_51_52,
                        MIGRATION_52_53,
                        MIGRATION_53_54,
                        MIGRATION_54_55,
                        MIGRATION_55_56,
                        MIGRATION_56_57,
                        MIGRATION_57_58,
                        MIGRATION_58_59,
                        MIGRATION_59_60,
                        MIGRATION_60_61,
                        MIGRATION_61_62,
                        MIGRATION_62_63,
                        MIGRATION_63_64,
                        MIGRATION_64_65,
                        MIGRATION_65_66,
                        MIGRATION_66_67,
                        MIGRATION_67_68,
                        MIGRATION_68_69,
                        MIGRATION_68_74,
                        MIGRATION_69_70,
                        MIGRATION_70_71,
                        MIGRATION_71_72,
                        MIGRATION_72_73,
                        MIGRATION_74_75,
                        MIGRATION_73_74,
                        migrate75To76(File(context.applicationContext.filesDir, "muse_images")),
                        migrate76To77(),
                        MIGRATION_77_78,
                        MIGRATION_78_79,
                        MIGRATION_79_80,
                        MIGRATION_80_81,
                        MIGRATION_81_82,
                        MIGRATION_82_83,
                        MIGRATION_83_84,
                        MIGRATION_84_85,
                        MIGRATION_85_86,
                        MIGRATION_86_87,
                        MIGRATION_87_88,
                    )
                    // 启用外键约束(artifacts 表的 ON DELETE CASCADE 依赖此设置)
                    // onOpen 不在 onCreate 事务内,可以执行此类命令;onCreate 内禁止 PRAGMA
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            // R-DB-02: 全新安装显式建 FTS 表(双保险第一层)。
                            synchronized(FTS_CREATE_LOCK) {
                                try {
                                    createKnowledgeChunkFtsTable(db)
                                    // R-DB-05: 全新安装建 messages_fts(FTS5 优先,FTS4 回退)。
                                    try {
                                        createMessageFtsTable(db, fts5Available(db))
                                    } catch (e: Exception) {
                                        io.zer0.common.Logger.e("MuseDb", "创建 messages_fts 失败: ${e.message}", e)
                                    }
                                } catch (e: Exception) {
                                    knowledgeFtsBroken = true
                                    io.zer0.common.Logger.e("MuseDb", "创建 knowledge_chunks_fts 失败: ${e.message}", e)
                                }
                            }
                        }

                        override fun onOpen(db: SupportSQLiteDatabase) {
                            db.setForeignKeyConstraintsEnabled(true)
                            // R-DB-02: onOpen 仅做校验;缺失时清影子表并重建,失败置进程内标志。
                            try {
                                synchronized(FTS_CREATE_LOCK) {
                                    val exists = db.query(
                                        "SELECT name FROM sqlite_master WHERE type='table' AND name='knowledge_chunks_fts'",
                                    ).use { it.moveToFirst() }
                                    if (!exists) {
                                        dropKnowledgeFtsTables(db)
                                        try {
                                            createKnowledgeChunkFtsTable(db)
                                            knowledgeFtsBroken = false
                                            io.zer0.common.Logger.i("MuseDb", "knowledge_chunks_fts 自愈重建成功")
                                        } catch (e2: Exception) {
                                            knowledgeFtsBroken = true
                                            io.zer0.common.Logger.e("MuseDb", "knowledge_chunks_fts 重建失败: ${e2.message}", e2)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                knowledgeFtsBroken = true
                                io.zer0.common.Logger.e("MuseDb", "knowledge_chunks_fts 校验失败: ${e.message}", e)
                            }
                            // R-DB-05: 校验 messages_fts;缺失时按能力重建,FTS5 优先。
                            try {
                                synchronized(FTS_CREATE_LOCK) {
                                    val messageFtsExists = db.query(
                                        "SELECT name FROM sqlite_master WHERE type='table' AND name='messages_fts'",
                                    ).use { it.moveToFirst() }
                                    if (!messageFtsExists) {
                                        dropMessageFtsTables(db)
                                        createMessageFtsTable(db, fts5Available(db))
                                    } else {
                                        MessageFtsRuntime.useFts5 = detectMessageFtsMode(db)
                                    }
                                }
                            } catch (e: Exception) {
                                io.zer0.common.Logger.e("MuseDb", "messages_fts 校验失败: ${e.message}", e)
                            }
                            // v1.107: WAL 模式由 setJournalMode(WRITE_AHEAD_LOGGING) 启用,这里不重复设置
                            // v1.107: 被动 checkpoint,合并 WAL 日志到主数据库
                            //   注意: PRAGMA wal_checkpoint 返回结果集,必须用 query 而非 execSQL
                            db.query("PRAGMA wal_checkpoint(PASSIVE)").use { it.moveToFirst() }
                        }
                    })
                    // v1.107: 显式设置 WAL 日志模式(冗余容灾:读写并发 + 崩溃恢复)
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    // 审计修复 (0.3): 降级破坏性迁移前归档旧库(.bak),避免测试版回滚/渠道回退时
                    // 整库静默销毁、对话与记忆不可恢复。onDestructiveMigration 在破坏前于旧库上回调。
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onDestructiveMigration(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            super.onDestructiveMigration(db)
                            try {
                                val ctx = context.applicationContext
                                val dbFile = ctx.getDatabasePath("muse.db")
                                if (dbFile.exists()) {
                                    val bak = java.io.File(dbFile.parentFile, "muse.db.pre-destructive.bak")
                                    runCatching { if (bak.exists()) bak.delete() }
                                    runCatching {
                                        val wal = java.io.File(dbFile.parentFile, "muse.db-wal")
                                        val shm = java.io.File(dbFile.parentFile, "muse.db-shm")
                                        // 先 checkpoint 合并 WAL,再复制主库
                                        runCatching { db.query("PRAGMA wal_checkpoint(PASSIVE)").use { it.moveToFirst() } }
                                        dbFile.copyTo(bak, overwrite = true)
                                        runCatching { if (wal.exists()) wal.delete() }
                                        runCatching { if (shm.exists()) shm.delete() }
                                    }
                                }
                            } catch (e: Exception) {
                                io.zer0.common.Logger.w("MuseDb", "降级归档失败(不影响重建): ${e.message}")
                            }
                        }
                    })
                    // 降级:防止从更高版本降到当前版本时崩溃(升级时不销毁,避免数据丢失)
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
                    .also {
                        KnowledgeChunkFtsSelfHealer.install {
                            MuseDb.repairKnowledgeChunkFtsTable()
                        }
                    }
            }
        }
    }
}

/** B8-06: 按当前 schema 补齐 messages 缺失列(幂等,已存在的列跳过)。 */
private fun ensureMessageColumns(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    val existing = mutableSetOf<String>()
    db.query("PRAGMA table_info(messages)").use { cursor ->
        while (cursor.moveToNext()) {
            existing.add(cursor.getString(1))
        }
    }
    val columns = listOf(
        "thinkingSignature TEXT DEFAULT NULL",
        "thinkingEncryptedContent TEXT DEFAULT NULL",
        "moodSkin TEXT DEFAULT NULL",
        "mood TEXT DEFAULT NULL",
        "reflection TEXT DEFAULT NULL",
        "contentLength INTEGER NOT NULL DEFAULT 0",
        "deletedAt INTEGER DEFAULT NULL",
        "reaction TEXT DEFAULT NULL",
        "variantGroupId TEXT DEFAULT NULL",
        "variantIndex INTEGER NOT NULL DEFAULT 0",
        "variantCount INTEGER NOT NULL DEFAULT 1",
        "parentGroupId TEXT DEFAULT NULL",
        "attachmentsJson TEXT NOT NULL DEFAULT '[]'",
        "artifactIdsJson TEXT NOT NULL DEFAULT '[]'",
        "imageBase64Json TEXT NOT NULL DEFAULT '[]'",
        "ragCitationsJson TEXT NOT NULL DEFAULT '[]'",
        "citationUrlsJson TEXT NOT NULL DEFAULT '[]'",
        "imageUrlsJson TEXT NOT NULL DEFAULT '[]'",
        "favoriteTag TEXT DEFAULT NULL",
    )
    columns.forEach { spec ->
        val name = spec.substringBefore(' ')
        if (name !in existing) {
            db.execSQL("ALTER TABLE messages ADD COLUMN $spec")
        }
    }
}

/** B8-06: 按当前 schema 补齐 sessions 缺失列(幂等,已存在的列跳过)。 */
private fun ensureSessionColumns(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    val existing = mutableSetOf<String>()
    db.query("PRAGMA table_info(sessions)").use { cursor ->
        while (cursor.moveToNext()) {
            existing.add(cursor.getString(1))
        }
    }
    val columns = listOf(
        "assistantId TEXT NOT NULL DEFAULT 'default'",
        "pinned INTEGER NOT NULL DEFAULT 0",
        "folderId TEXT DEFAULT ''",
        "archived INTEGER NOT NULL DEFAULT 0",
        "isAgentSession INTEGER NOT NULL DEFAULT 0",
        "isLocked INTEGER NOT NULL DEFAULT 0",
        "messageCount INTEGER NOT NULL DEFAULT 0",
        "deletedAt INTEGER DEFAULT NULL",
        "parentSessionId TEXT DEFAULT NULL",
        "childCount INTEGER NOT NULL DEFAULT 0",
        "lastReadMessageId TEXT DEFAULT NULL",
        "lastReadCount INTEGER NOT NULL DEFAULT 0",
        "sortOrder INTEGER NOT NULL DEFAULT 0",
        "proactiveNextTriggerAt INTEGER DEFAULT NULL",
        "skillIdsJson TEXT NOT NULL DEFAULT '[]'",
    )
    columns.forEach { spec ->
        val name = spec.substringBefore(' ')
        if (name !in existing) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN $spec")
        }
    }
}

/**
 * 按当前 schema 补齐 group_chat_messages 缺失列。
 *
 * 旧版本升级链可能跳过群聊消息增强迁移(悄悄话/引用回复/消息类型/附件),
 * 导致 Room 校验报 "Migration didn't properly handle: group_chat_messages"。
 * 缺列或关键默认值不符时重建表并复制数据,已满足当前 schema 时直接跳过。
 */
private fun ensureGroupChatMessageColumns(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    val tableExists = db.query(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='group_chat_messages'",
    ).use { it.moveToFirst() }
    val expectedColumns = listOf(
        "id", "chatId", "senderType", "senderId", "senderName", "body",
        "imageBase64Json", "timestamp", "mood", "reasoning",
        "whisper_target_id", "reply_to_id", "messageType", "fileAttachmentsJson",
    )
    val existing = mutableMapOf<String, String?>()
    if (tableExists) {
        db.query("PRAGMA table_info(group_chat_messages)").use { cursor ->
            while (cursor.moveToNext()) {
                existing[cursor.getString(1)] = cursor.getString(4)
            }
        }
    }

    val expectedDefaults = mapOf(
        "imageBase64Json" to "'[]'",
        "timestamp" to "'0'",
        "whisper_target_id" to "NULL",
        "reply_to_id" to "NULL",
        "messageType" to "'normal'",
        "fileAttachmentsJson" to "'[]'",
    )
    fun defaultMatches(name: String): Boolean {
        val expected = expectedDefaults[name] ?: return true
        val actual = existing[name]
        return if (expected == "NULL") {
            actual == null || actual.equals("NULL", ignoreCase = true)
        } else if (name == "timestamp") {
            actual == "0" || actual == "'0'"
        } else {
            actual?.equals(expected, ignoreCase = true) == true
        }
    }
    val complete = tableExists &&
        expectedColumns.all { it in existing } &&
        expectedDefaults.keys.all(::defaultMatches)
    if (complete) return

    val createSql = """
        CREATE TABLE IF NOT EXISTS group_chat_messages_new (
            id TEXT NOT NULL PRIMARY KEY,
            chatId TEXT NOT NULL,
            senderType TEXT NOT NULL,
            senderId TEXT NOT NULL,
            senderName TEXT NOT NULL,
            body TEXT NOT NULL,
            imageBase64Json TEXT NOT NULL DEFAULT '[]',
            timestamp INTEGER NOT NULL DEFAULT 0,
            mood TEXT,
            reasoning TEXT,
            whisper_target_id TEXT DEFAULT NULL,
            reply_to_id TEXT DEFAULT NULL,
            messageType TEXT NOT NULL DEFAULT 'normal',
            fileAttachmentsJson TEXT NOT NULL DEFAULT '[]'
        )
    """.trimIndent()
    db.execSQL("DROP TABLE IF EXISTS group_chat_messages_new")
    db.execSQL(createSql)
    if (tableExists) {
        val fallback = mapOf(
            "id" to "''", "chatId" to "''", "senderType" to "''", "senderId" to "''",
            "senderName" to "''", "body" to "''", "imageBase64Json" to "'[]'", "timestamp" to "0",
            "mood" to "NULL", "reasoning" to "NULL", "whisper_target_id" to "NULL",
            "reply_to_id" to "NULL", "messageType" to "'normal'", "fileAttachmentsJson" to "'[]'",
        )
        val insertColumns = expectedColumns.joinToString(", ") { "`$it`" }
        val selectExpr = expectedColumns.joinToString(", ") { name ->
            if (name in existing) "`$name`" else fallback[name] ?: "''"
        }
        db.execSQL(
            "INSERT INTO group_chat_messages_new ($insertColumns) SELECT $selectExpr FROM group_chat_messages"
        )
        db.execSQL("DROP TABLE group_chat_messages")
        db.execSQL("ALTER TABLE group_chat_messages_new RENAME TO group_chat_messages")
    } else {
        db.execSQL("ALTER TABLE group_chat_messages_new RENAME TO group_chat_messages")
    }
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_group_chat_messages_chatId ON group_chat_messages(chatId)"
    )
}

/**
 * v1.0.62 fix: 确保 generation_checkpoints 和 group_chat_generation_ledger 表存在。
 * 兼容从 v1.0.60（DB version 73）升级的场景，这些表在 v1.0.60 的 schema 中不存在。
 */
private fun ensureGenerationTables(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `generation_checkpoints` (
            `assistantMessageId` TEXT NOT NULL,
            `sessionId` TEXT NOT NULL,
            `userMessageId` TEXT NOT NULL,
            `content` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`assistantMessageId`),
            FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent()
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_generation_checkpoints_sessionId` ON `generation_checkpoints` (`sessionId`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_generation_checkpoints_createdAt` ON `generation_checkpoints` (`createdAt`)")
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `group_chat_generation_ledger` (
            `id` TEXT NOT NULL,
            `chatId` TEXT NOT NULL,
            `mode` TEXT NOT NULL,
            `round` INTEGER NOT NULL,
            `memberIndex` INTEGER NOT NULL,
            `memberIdsJson` TEXT NOT NULL DEFAULT '[]',
            `status` TEXT NOT NULL DEFAULT 'running',
            `createdAt` INTEGER NOT NULL DEFAULT 0,
            `updatedAt` INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`chatId`) REFERENCES `group_chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent()
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_chat_generation_ledger_chatId` ON `group_chat_generation_ledger` (`chatId`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_chat_generation_ledger_status` ON `group_chat_generation_ledger` (`status`)")
}
