package io.zer0.muse.data.session

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.agentdm.AgentMessageDao
import io.zer0.muse.data.agentdm.AgentMessageEntity
import io.zer0.muse.data.artifact.ArtifactDao
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.data.assistant.AssistantDao
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.audit.AuditLogDao
import io.zer0.muse.data.audit.AuditLogEntity
import io.zer0.muse.data.conversation.ConversationCommandReceiptDao
import io.zer0.muse.data.conversation.ConversationCommandReceiptEntity
import io.zer0.muse.data.conversation.ConversationDisplaySlotDao
import io.zer0.muse.data.conversation.ConversationDisplaySlotEntity
import io.zer0.muse.data.conversation.ConversationSelectionDao
import io.zer0.muse.data.conversation.ConversationSelectionEntity
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
import io.zer0.muse.data.milestone.MilestoneDao
import io.zer0.muse.data.milestone.MilestoneEntity
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
import io.zer0.muse.data.stats.AutoBackupLogDao
import io.zer0.muse.data.stats.AutoBackupLogEntity
import io.zer0.muse.data.stats.DbIntegrityLogDao
import io.zer0.muse.data.stats.DbIntegrityLogEntity
import io.zer0.muse.data.stats.StatsCacheDao
import io.zer0.muse.data.stats.StatsCacheEntity
import io.zer0.muse.ui.translate.TranslateHistoryDao
import io.zer0.muse.ui.translate.TranslateHistoryEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

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
 *   - 迁移链已覆盖标准升级路径;未知版本或降级由备份恢复流程处理，不执行 destructive migration
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
 * v1.0.x: 版本 97 → 98,assistants 删除 3 个死列 backgroundUrl/backgroundOpacity/
 *   useGradientBackground(助手背景图功能已整体撤掉,无消费端)。
 *   v2.6.9: 版本 118 → 119,新增主动消息三表(pending_thought / proactive_outcome / proactive_relation)。
 *   纯新增表,旧 ProactiveMessageConfig 保留不迁移。
 *   v2.6.8: 版本 117 → 118,新增 card_entities 表(卡片停靠 / 跨应用悬浮的身份与状态载体)。
 *   纯新增表,不动既有表;卡片从「消息尾巴 + card_data.json」升为一等实体(UUID 主键)。
 *   注：SQLite 3.35 才支持 DROP COLUMN，minSdk 26 的旧设备不支持，故走"建新表 → 搬数据 →
 *   换名"路径（与 MIGRATION_*_* 中 group_chat_messages/skills 的历史做法一致）。
 *   v2.6.16: 版本 120 → 121，messages 加 voiceUri / voiceDurationMs 两列（语音消息）。
 *   纯新增列，旧行 voiceUri 为 NULL、voiceDurationMs 为 0，读出来就是"非语音消息"；
 *   新版本代码不读旧列语义，旧数据一字未改。
 *   版本 121 → 122，删除旧小手机遗留的四张表（朋友圈/日记）与 isMiniPhone=1 的会话及其消息。
 *   该功能未对真实用户开放，库内无可保留业务数据；isMiniPhone 列保留（旧 SQLite 不支持 DROP COLUMN）。
 *   v2.7.x: 版本 134 → 135，新增 proactive_backlog_dispatch（主动消息派发账，一条牵挂最多一条派发）。
 *   纯新增表，不动 pending_thought / proactive_outcome 的任何行。
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
        // v1.0.74: 主动巡检日志
        io.zer0.muse.data.patrol.PatrolLogEntity::class,
        io.zer0.muse.data.stats.UsageRecordEntity::class,
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
        // 注:KnowledgeChunkFtsEntity 不在此列表 — FTS4 vtable 由 MuseDbMigrations.MIGRATION_38_39 raw SQL 创建,
        // DAO 全部 @SkipQueryVerification,Room 不感知该虚拟表存在(避免 KSP schema 验证报 vtable constructor failed)
        KnowledgeBaseEntity::class,
        // v2.4.6: 知识库文件夹(含空文件夹) — 文件夹成为可独立存在的一等公民,同时是检索隔离单位
        io.zer0.muse.data.knowledge.KnowledgeFolderEntity::class,
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
        // Overnight rebuild: 对话事件、回合、工具轮、parts 和分支头
        ConversationEventEntity::class,
        ConversationTurnEntity::class,
        ToolRoundEntity::class,
        MessagePartEntity::class,
        SessionBranchHeadEntity::class,
        ConversationCommandReceiptEntity::class,
        ConversationSelectionEntity::class,
        ConversationDisplaySlotEntity::class,
        io.zer0.muse.data.relationship.AssistantRelationshipEntity::class,
        io.zer0.muse.data.todo.SessionTodoEntity::class,
        // v2.6.8: 卡片实体(卡片停靠 / 跨应用悬浮的身份与状态载体)
        io.zer0.muse.data.card.CardEntity::class,
        // v2.6.9: 主动消息重建 — 牵挂账本 / 结果账 / 关系亲密度
        io.zer0.muse.data.proactive.PendingThoughtEntity::class,
        io.zer0.muse.data.proactive.ProactiveOutcomeEntity::class,
        io.zer0.muse.data.proactive.ProactiveRelationEntity::class,
        // v2.7.x: 主动消息 backlog 的派发账（一条牵挂最多一条派发）
        io.zer0.muse.data.proactive.ProactiveBacklogDispatchEntity::class,
        io.zer0.muse.data.proactive.ProactiveBacklogScheduleEntity::class,
        io.zer0.muse.proactive.SessionProactivePreferenceEntity::class,
        io.zer0.muse.data.terms.SessionTermEntity::class,
        // R2: 知识库 V2(原件/revision/epoch/租约四表;新装与迁移同构,不载入旧行)
        io.zer0.muse.data.knowledge.KnowledgeV2DocEntity::class,
        io.zer0.muse.data.knowledge.KnowledgeV2RevisionEntity::class,
        io.zer0.muse.data.knowledge.KnowledgeV2DatasetEpochEntity::class,
        io.zer0.muse.data.knowledge.KnowledgeV2RetentionLeaseEntity::class,
        // R4: 作业账本 + generation 化索引表(旧 knowledge_chunks/FTS 不动)
        io.zer0.muse.data.knowledge.KnowledgeJobEntity::class,
        io.zer0.muse.data.knowledge.KnowledgeJobItemEntity::class,
        io.zer0.muse.data.knowledge.KnowledgeGenerationEntity::class,
        io.zer0.muse.data.knowledge.KnowledgeGenChunkEntity::class,
        io.zer0.muse.data.knowledge.KnowledgeGenLexicalEntity::class,
        io.zer0.muse.data.knowledge.KnowledgeActiveGenerationEntity::class,
        // R8: 稳定目录身份与绑定(旧 knowledge_folders 保持原样)
        io.zer0.muse.data.knowledge.KnowledgeV2FolderEntity::class,
        io.zer0.muse.data.knowledge.KnowledgeV2BindingEntity::class,
        // M4a: 会话内运行环境安装卡（「以后再说」与安装状态必须跨重启保留）
        io.zer0.muse.data.runtime.RuntimeInstallCardEntity::class,
        // Agent 中枢：任务 / 运行 / 步骤 / 事件 / 审批 / 发布 / 绑定七表。
        // 全部是新增表，不动任何既有行 —— 中枢关掉时它们只是空表。
        io.zer0.muse.data.agentbot.BotTaskEntity::class,
        io.zer0.muse.data.agentbot.TaskRunEntity::class,
        io.zer0.muse.data.agentbot.TaskStepEntity::class,
        io.zer0.muse.data.agentbot.BotEventEntity::class,
        io.zer0.muse.data.agentbot.ApprovalRequestEntity::class,
        io.zer0.muse.data.agentbot.PublicationOutboxEntity::class,
        io.zer0.muse.data.agentbot.BotBindingEntity::class,
        // Bot 标注：表情 / 贴纸 / 引用定位。与 messages.reaction（用户反应）分开。
        io.zer0.muse.data.agentbot.BotAnnotationEntity::class,
        // 对话流动态交互卡片与可复用惯例。
        io.zer0.muse.data.agentbot.BotMessageCardEntity::class,
        io.zer0.muse.data.agentbot.BotRoutineEntity::class,
    ],
    version = MuseDb.LATEST_SCHEMA_VERSION,
    exportSchema = true,
)
@TypeConverters(QuickNoteConverters::class)
abstract class MuseDb : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun knowledgeV2Dao(): io.zer0.muse.data.knowledge.KnowledgeV2Dao
    abstract fun knowledgeJobDao(): io.zer0.muse.data.knowledge.KnowledgeJobDao
    abstract fun runtimeInstallCardDao(): io.zer0.muse.data.runtime.RuntimeInstallCardDao
    abstract fun knowledgeFolderV2Dao(): io.zer0.muse.data.knowledge.KnowledgeFolderV2Dao
    abstract fun messageFtsDao(): MessageFtsDao
    abstract fun artifactDao(): ArtifactDao
    abstract fun sessionProactivePreferenceDao(): io.zer0.muse.proactive.SessionProactivePreferenceDao
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

    // v2.4.6: 知识库文件夹
    abstract fun knowledgeFolderDao(): io.zer0.muse.data.knowledge.KnowledgeFolderDao
    abstract fun knowledgeChunkFtsDao(): KnowledgeChunkFtsDao
    abstract fun scheduledTaskExecutionDao(): ScheduledTaskExecutionDao
    abstract fun groupChatDao(): GroupChatDao
    abstract fun groupChatMessageDao(): GroupChatMessageDao

    // v2.x: 群聊记忆隔离(独立 fact store,不污染主记忆)
    abstract fun groupChatMemoryDao(): GroupChatMemoryDao

    abstract fun patrolLogDao(): io.zer0.muse.data.patrol.PatrolLogDao

    /** 用量流水（每次模型请求一行）。 */
    abstract fun usageRecordDao(): io.zer0.muse.data.stats.UsageRecordDao

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

    // Overnight rebuild: 对话影子事件与新投影存储
    abstract fun conversationEventDao(): ConversationEventDao
    abstract fun conversationTurnDao(): ConversationTurnDao
    abstract fun toolRoundDao(): ToolRoundDao
    abstract fun messagePartDao(): MessagePartDao
    abstract fun sessionBranchHeadDao(): SessionBranchHeadDao
    abstract fun conversationCommandReceiptDao(): ConversationCommandReceiptDao
    abstract fun conversationSelectionDao(): ConversationSelectionDao
    abstract fun conversationDisplaySlotDao(): ConversationDisplaySlotDao

    /** v2.6.7（P1-1）: 角色关系状态（亲密度 / 意愿档位 / 最近互动）。 */
    abstract fun assistantRelationshipDao(): io.zer0.muse.data.relationship.AssistantRelationshipDao

    /** v2.6.7: 会话待办持久化（跨会话台账）。 */
    abstract fun sessionTodoDao(): io.zer0.muse.data.todo.SessionTodoDao

    /** v2.6.8: 卡片实体（停靠 / 悬浮共用的身份与内容）。 */
    abstract fun cardEntityDao(): io.zer0.muse.data.card.CardEntityDao

    /** v2.6.9: 牵挂账本（「我有什么事想跟这个人说」）。 */
    abstract fun pendingThoughtDao(): io.zer0.muse.data.proactive.PendingThoughtDao

    /** v2.6.9: 牵挂账本的状态流转口（已说/过期/放弃）。 */
    abstract fun pendingThoughtWriteDao(): io.zer0.muse.data.proactive.PendingThoughtWriteDao

    /** v2.6.9: 主动消息结果账（点开 / 回复 / 划掉）。 */
    abstract fun proactiveOutcomeDao(): io.zer0.muse.data.proactive.ProactiveOutcomeDao

    /** v2.6.9: 关系亲密度与当日预算。 */
    abstract fun proactiveRelationDao(): io.zer0.muse.data.proactive.ProactiveRelationDao

    /** v2.7.x: 主动消息 backlog 的派发账（领取 / 回收 / 结果回填）。 */
    abstract fun proactiveBacklogDao(): io.zer0.muse.data.proactive.ProactiveBacklogDao

    abstract fun proactiveBacklogScheduleDao(): io.zer0.muse.data.proactive.ProactiveBacklogScheduleDao

    /** v2.7.x(E2): 会话术语表。 */
    abstract fun sessionTermDao(): io.zer0.muse.data.terms.SessionTermDao

    // ── Agent 中枢（7 张表）──
    /** 任务账本：用户交代的一件事。 */
    abstract fun botTaskDao(): io.zer0.muse.data.agentbot.BotTaskDao

    /** 执行轮次：一次 Task 可以有多轮 attempt。 */
    abstract fun taskRunDao(): io.zer0.muse.data.agentbot.TaskRunDao

    /** 步骤账本：记录可重放性与审批绑定。 */
    abstract fun taskStepDao(): io.zer0.muse.data.agentbot.TaskStepDao

    /** 事件账本：先落盘再唤醒，至少一次投递。 */
    abstract fun botEventDao(): io.zer0.muse.data.agentbot.BotEventDao

    /** 审批请求：用户没看见 ≠ 用户拒绝。 */
    abstract fun approvalRequestDao(): io.zer0.muse.data.agentbot.ApprovalRequestDao

    /** 发布发件箱：多 item 批次恢复时只重投未发出的。 */
    abstract fun publicationOutboxDao(): io.zer0.muse.data.agentbot.PublicationOutboxDao

    /** Bot 与助手 / canonical 会话的绑定。 */
    abstract fun botBindingDao(): io.zer0.muse.data.agentbot.BotBindingDao

    /** Bot 标注（表情 / 贴纸 / 引用定位）。 */
    abstract fun botAnnotationDao(): io.zer0.muse.data.agentbot.BotAnnotationDao

    /** 对话流动态交互卡片。 */
    abstract fun botMessageCardDao(): io.zer0.muse.data.agentbot.BotMessageCardDao

    /** 可复用惯例与触发规则。 */
    abstract fun botRoutineDao(): io.zer0.muse.data.agentbot.BotRoutineDao

    companion object {
        /** Single schema-version source; must match the latest exported JSON. */
        const val LATEST_SCHEMA_VERSION = 137

        @Volatile
        private var dbInstance: MuseDb? = null

        /** v1.0.53: FTS 兜底创建全局锁 — 多连接 onOpen 并发时串行化建表。 */
        private val FTS_CREATE_LOCK = Any()

        /** R-DB-02: 进程内 FTS 故障标志,建表失败后置位,供自愈/UI 排查。 */
        @Volatile
        private var knowledgeFtsBroken = false

        /** R-DB-02: 清掉残留影子表/主表后重建 knowledge_chunks_fts。 */
        fun repairKnowledgeChunkFtsTable() {
            synchronized(FTS_CREATE_LOCK) {
                val db = dbInstance?.openHelper?.writableDatabase
                    ?: error("MuseDb not initialized")
                rebuildKnowledgeChunkFtsTable(db)
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

        /**
         * 重建知识库 FTS4 并按 [MessageFtsManager] 回填 ngram。
         *
         * FTS4 默认 tokenizer 会把连续中文当成一个 token,直接写原文会让中文
         * 子串检索失效;重建必须与新增索引写入使用同一规范化文本。
         */
        internal fun rebuildKnowledgeChunkFtsTable(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
                dropKnowledgeFtsTables(db)
                createKnowledgeChunkFtsTable(db)
                val insert = db.compileStatement(
                    "INSERT INTO knowledge_chunks_fts(chunkId, doc_id, text_content) VALUES (?, ?, ?)",
                )
                db.query("SELECT id, doc_id, content FROM knowledge_chunks").use { cursor ->
                    while (cursor.moveToNext()) {
                        insert.clearBindings()
                        insert.bindString(1, cursor.getString(0))
                        insert.bindString(2, cursor.getString(1))
                        insert.bindString(3, MessageFtsManager.toNgram(cursor.getString(2)))
                        insert.executeInsert()
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        /**
         * R-DB-05: 探测当前 SQLite 是否支持 FTS5。
         *
         * 先读 `PRAGMA compile_options`：这条路**不建虚表**，因此不会触发
         * Robolectric 旧版 SQLite shadow 在后台线程释放语句缓存时抛
         * `no such module: fts5` —— 那条异常没人捕获，会毒化同一个测试 JVM 里
         * 下一个用 `runTest` 的类（`UncaughtExceptionsBeforeTest`，随机红且单跑必绿）。
         *
         * 顺序是刻意的，不降级：
         *  - 读到内容但不含 `ENABLE_FTS5` ⇒ 明确不支持（Robolectric 下正是这种，实测 8 行里没有它）；
         *  - 一行都没读到（老版本 Android 未实现该 PRAGMA）⇒ **退回原来的建表探针**，
         *    所以真机行为与改动前一致，不会因这个改动把支持的机型误判成不支持。
         * 注：`SELECT * FROM pragma_compile_options` 这种表函数形式在旧实现里不存在，
         *    实测会 `no such table`，所以只能用 PRAGMA 形式。
         */
        // internal 而非 private：两阶语义（先读编译选项、读不到才建表探针）需要被回归测试钉住 ——
        // 谁把它退回单步建表探针，那条随机红就会回来，而这个后果在测试里看不见（见 Fts5ProbeTest）。
        internal fun fts5Available(db: SupportSQLiteDatabase): Boolean {
            val options = runCatching {
                db.query("PRAGMA compile_options").use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                }
            }.getOrNull().orEmpty()
            if (options.isNotEmpty()) {
                return options.any { it.contains("ENABLE_FTS5", ignoreCase = true) }
            }
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

        /**
         * S3:整条迁移链的唯一注册表。
         *
         * 顺序即执行顺序。链的连续性、本次要跑几步、以及「旧迁移冻结、新版本只追加」这条规矩
         * 都以这里为唯一真源(见 `MuseDbMigrationChainTest`),所以不要再在别处直接 `addMigrations`。
         */
        internal fun migrations(context: Context): List<Migration> = listOf(
            MuseDbMigrations.MIGRATION_1_2, MuseDbMigrations.MIGRATION_2_3, MuseDbMigrations.MIGRATION_3_4, MuseDbMigrations.MIGRATION_4_5,
            MuseDbMigrations.MIGRATION_5_6, MuseDbMigrations.MIGRATION_6_7, MuseDbMigrations.MIGRATION_7_8, MuseDbMigrations.MIGRATION_8_9,
            MuseDbMigrations.MIGRATION_9_10, MuseDbMigrations.MIGRATION_10_11, MuseDbMigrations.MIGRATION_11_12, MuseDbMigrations.MIGRATION_12_13,
            MuseDbMigrations.MIGRATION_13_14, MuseDbMigrations.MIGRATION_14_15, MuseDbMigrations.MIGRATION_15_16, MuseDbMigrations.MIGRATION_16_17,
            MuseDbMigrations.MIGRATION_17_18, MuseDbMigrations.MIGRATION_18_19, MuseDbMigrations.MIGRATION_19_20, MuseDbMigrations.MIGRATION_20_21,
            MuseDbMigrations.MIGRATION_21_22, MuseDbMigrations.MIGRATION_22_23, MuseDbMigrations.MIGRATION_23_24,
            MuseDbMigrations.MIGRATION_24_25, MuseDbMigrations.MIGRATION_25_26, MuseDbMigrations.MIGRATION_26_27, MuseDbMigrations.MIGRATION_27_28,
            MuseDbMigrations.MIGRATION_28_29, MuseDbMigrations.MIGRATION_29_30, MuseDbMigrations.MIGRATION_30_31,
            MuseDbMigrations.MIGRATION_31_32,
            MuseDbMigrations.MIGRATION_32_33,
            MuseDbMigrations.MIGRATION_33_34,
            MuseDbMigrations.MIGRATION_34_35,
            MuseDbMigrations.MIGRATION_35_36,
            MuseDbMigrations.MIGRATION_36_37,
            MuseDbMigrations.MIGRATION_37_38,
            MuseDbMigrations.MIGRATION_38_39,
            MuseDbMigrations.MIGRATION_39_40,
            MuseDbMigrations.MIGRATION_40_41,
            MuseDbMigrations.MIGRATION_41_42,
            MuseDbMigrations.MIGRATION_42_43,
            MuseDbMigrations.MIGRATION_43_44,
            MuseDbMigrations.MIGRATION_44_45,
            MuseDbMigrations.MIGRATION_45_46,
            MuseDbMigrations.MIGRATION_46_47,
            MuseDbMigrations.MIGRATION_47_48,
            MuseDbMigrations.MIGRATION_48_49,
            MuseDbMigrations.MIGRATION_49_50,
            MuseDbMigrations.MIGRATION_50_51,
            MuseDbMigrations.MIGRATION_51_52,
            MuseDbMigrations.MIGRATION_52_53,
            MuseDbMigrations.MIGRATION_53_54,
            MuseDbMigrations.MIGRATION_54_55,
            MuseDbMigrations.MIGRATION_55_56,
            MuseDbMigrations.MIGRATION_56_57,
            MuseDbMigrations.MIGRATION_57_58,
            MuseDbMigrations.MIGRATION_58_59,
            MuseDbMigrations.MIGRATION_59_60,
            MuseDbMigrations.MIGRATION_60_61,
            MuseDbMigrations.MIGRATION_61_62,
            MuseDbMigrations.MIGRATION_62_63,
            MuseDbMigrations.MIGRATION_63_64,
            MuseDbMigrations.MIGRATION_64_65,
            MuseDbMigrations.MIGRATION_65_66,
            MuseDbMigrations.MIGRATION_66_67,
            MuseDbMigrations.MIGRATION_67_68,
            MuseDbMigrations.MIGRATION_68_69,
            // MuseDbMigrations.MIGRATION_68_74 为死代码(68→69 先匹配),保留注释供历史追溯,不删除避免迁移冲突
            // MuseDbMigrations.MIGRATION_68_74,
            MuseDbMigrations.MIGRATION_69_70,
            MuseDbMigrations.MIGRATION_70_71,
            MuseDbMigrations.MIGRATION_71_72,
            MuseDbMigrations.MIGRATION_72_73,
            MuseDbMigrations.MIGRATION_73_74,
            MuseDbMigrations.MIGRATION_74_75,
            migrate75To76(File(context.applicationContext.filesDir, "muse_images")),
            migrate76To77(),
            MuseDbMigrations.MIGRATION_77_78,
            MuseDbMigrations.MIGRATION_78_79,
            MuseDbMigrations.MIGRATION_79_80,
            MuseDbMigrations.MIGRATION_80_81,
            MuseDbMigrations.MIGRATION_81_82,
            MuseDbMigrations.MIGRATION_82_83,
            MuseDbMigrations.MIGRATION_83_84,
            MuseDbMigrations.MIGRATION_84_85,
            MuseDbMigrations.MIGRATION_85_86,
            MuseDbMigrations.MIGRATION_86_87,
            MuseDbMigrations.MIGRATION_87_88,
            MuseDbMigrations.MIGRATION_88_89,
            MuseDbMigrations.MIGRATION_89_90,
            MuseDbMigrations.MIGRATION_90_91,
            MuseDbMigrations.MIGRATION_91_92,
            MuseDbMigrations.MIGRATION_92_93,
            MuseDbMigrations.MIGRATION_93_94,
            MuseDbMigrations.MIGRATION_94_95,
            MuseDbMigrations.MIGRATION_95_96,
            MuseDbMigrations.MIGRATION_96_97,
            MuseDbMigrations.MIGRATION_97_98,
            MuseDbMigrations.MIGRATION_98_99,
            MuseDbMigrations.MIGRATION_99_100,
            MuseDbMigrations.MIGRATION_100_101,
            MuseDbMigrations.MIGRATION_101_102,
            MuseDbMigrations.MIGRATION_102_103,
            MuseDbMigrations.MIGRATION_103_104,
            MuseDbMigrations.MIGRATION_104_105,
            MuseDbMigrations.MIGRATION_105_106,
            MuseDbMigrations.MIGRATION_106_107,
            MuseDbMigrations.MIGRATION_107_108,
            MuseDbMigrations.MIGRATION_108_109,
            MuseDbMigrations.MIGRATION_109_110,
            MuseDbMigrations.MIGRATION_110_111,
            MuseDbMigrations.MIGRATION_111_112,
            MuseDbMigrations.MIGRATION_112_113,
            MuseDbMigrations.MIGRATION_113_114,
            MuseDbMigrations.MIGRATION_114_115,
            MuseDbMigrations.MIGRATION_115_116, MuseDbMigrations.MIGRATION_116_117, MuseDbMigrations.MIGRATION_117_118, MuseDbMigrations.MIGRATION_118_119,
            MuseDbMigrations.MIGRATION_119_120,
            MuseDbMigrations.MIGRATION_120_121,
            MuseDbMigrations.MIGRATION_121_122,
            MuseDbMigrations.MIGRATION_122_123,
            MuseDbMigrations.MIGRATION_123_124,
            MuseDbMigrations.MIGRATION_124_125,
            io.zer0.muse.data.knowledge.KnowledgeSchemaMigrations.MIGRATION_125_126,
            io.zer0.muse.data.knowledge.KnowledgeJobSchemaMigrations.MIGRATION_126_127,
            io.zer0.muse.data.knowledge.KnowledgeVectorSchemaMigrations.MIGRATION_127_128,
            io.zer0.muse.data.knowledge.KnowledgeFolderSchemaMigrations.MIGRATION_128_129,
            io.zer0.muse.data.knowledge.KnowledgeEvidenceSchemaMigrations.MIGRATION_129_130,
            MuseDbMigrations.MIGRATION_130_131,
            MuseDbMigrations.MIGRATION_131_132,
            MuseDbMigrations.MIGRATION_132_133,
            MuseDbMigrations.MIGRATION_133_134,
            MuseDbMigrations.MIGRATION_134_135,
            MuseDbMigrations.MIGRATION_135_136,
            MuseDbMigrations.MIGRATION_136_137,
        )

        // SpreadOperator:展开 126 个已包装的迁移对象。这里必须用 vararg 形式交给 Room 的
        // addMigrations,复制发生在本函数每次调用时(每个进程一次),代价是 126 个指针,
        // 比为此另写一套注册接口划算。
        @Suppress("SpreadOperator")
        fun get(context: Context): MuseDb {
            return dbInstance ?: synchronized(this) {
                dbInstance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MuseDb::class.java,
                    "muse.db",
                )
                    // S3:每一步都过一层旁路观察(进度发布 + 上次结果落盘)。
                    // 包装层原样调用被包装的迁移、异常原样抛出 —— 观察不许影响迁移本身。
                    .addMigrations(
                        *MigrationRegistry(
                            all = migrations(context),
                            onRecord = { MigrationRunStore.record(context, it) },
                        ).timed(),
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
                    // 禁止 destructive migration：降级必须在发布流程中使用备份恢复，不能静默清空用户库。
                    .build()
                    .also { dbInstance = it }
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
internal fun ensureMessageColumns(db: androidx.sqlite.db.SupportSQLiteDatabase) {
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
        // A5: 生成元数据列(消息信息弹层:模型/耗时/令牌)
        "durationMs INTEGER DEFAULT NULL",
        "promptTokens INTEGER DEFAULT NULL",
        "completionTokens INTEGER DEFAULT NULL",
        "reasoningTokens INTEGER DEFAULT NULL",
        "cachedTokens INTEGER DEFAULT NULL",
        // H11: 译文消息的源消息 id(原文对照折叠)
        "translationSourceId TEXT DEFAULT NULL",
        // v2.6.15: 缓存写入 token（与 cachedTokens 分列；旧行为 NULL = 未知）
        "cacheWriteTokens INTEGER DEFAULT NULL",
        // v2.6.16: 语音消息音频引用 + 时长（旧行 NULL / 0 = 非语音消息）
        "voiceUri TEXT DEFAULT NULL",
        "voiceDurationMs INTEGER NOT NULL DEFAULT 0",
    )
    columns.forEach { spec ->
        val name = spec.substringBefore(' ')
        if (name !in existing) {
            db.execSQL("ALTER TABLE messages ADD COLUMN $spec")
        }
    }
}

/** B8-06: 按当前 schema 补齐 usage_records 缺失列(幂等,已存在的列跳过)。 */
internal fun ensureUsageRecordColumns(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    val existing = mutableSetOf<String>()
    db.query("PRAGMA table_info(usage_records)").use { cursor ->
        while (cursor.moveToNext()) {
            existing.add(cursor.getString(1))
        }
    }
    val columns = listOf(
        // v2.6.15: 缓存写入 token(与 cached_tokens 分列;旧行为 0)
        "cache_write_tokens INTEGER NOT NULL DEFAULT 0",
    )
    columns.forEach { spec ->
        val name = spec.substringBefore(' ')
        if (name !in existing) {
            db.execSQL("ALTER TABLE usage_records ADD COLUMN $spec")
        }
    }
}

/** B8-06: 按当前 schema 补齐 sessions 缺失列(幂等,已存在的列跳过)。 */
internal fun ensureSessionColumns(db: androidx.sqlite.db.SupportSQLiteDatabase) {
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
        "isMiniPhone INTEGER NOT NULL DEFAULT 0",
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
internal fun ensureGroupChatMessageColumns(db: androidx.sqlite.db.SupportSQLiteDatabase) {
    val tableExists = db.query(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='group_chat_messages'",
    ).use { it.moveToFirst() }
    val expectedColumns = listOf(
        "id", "chatId", "senderType", "senderId", "senderName", "body",
        "imageBase64Json", "timestamp", "mood", "reasoning",
        "whisper_target_id", "reply_to_id", "messageType", "fileAttachmentsJson",
        "videoFileUri",
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
            fileAttachmentsJson TEXT NOT NULL DEFAULT '[]',
            videoFileUri TEXT DEFAULT NULL
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
            "videoFileUri" to "NULL",
        )
        val insertColumns = expectedColumns.joinToString(", ") { "`$it`" }
        val selectExpr = expectedColumns.joinToString(", ") { name ->
            if (name in existing) "`$name`" else fallback[name] ?: "''"
        }
        db.execSQL(
            "INSERT INTO group_chat_messages_new ($insertColumns) SELECT $selectExpr FROM group_chat_messages",
        )
        db.execSQL("DROP TABLE group_chat_messages")
        db.execSQL("ALTER TABLE group_chat_messages_new RENAME TO group_chat_messages")
    } else {
        db.execSQL("ALTER TABLE group_chat_messages_new RENAME TO group_chat_messages")
    }
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_group_chat_messages_chatId ON group_chat_messages(chatId)",
    )
}

/**
 * v1.0.62 fix: 确保 generation_checkpoints 和 group_chat_generation_ledger 表存在。
 * 兼容从 v1.0.60（DB version 73）升级的场景，这些表在 v1.0.60 的 schema 中不存在。
 */
internal fun ensureGenerationTables(db: androidx.sqlite.db.SupportSQLiteDatabase) {
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
        """.trimIndent(),
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
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_chat_generation_ledger_chatId` ON `group_chat_generation_ledger` (`chatId`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_chat_generation_ledger_status` ON `group_chat_generation_ledger` (`status`)")
}
