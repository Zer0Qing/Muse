package io.zer0.muse.data.session

import androidx.room.migration.Migration
import io.zer0.common.Logger
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
Room 迁移链第三段（46 个迁移）。
 */
internal object MigrationsC {
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
            db.execSQL(
                """
                UPDATE assistants SET messageCount = (
                    SELECT COUNT(*) FROM messages m
                    JOIN sessions s ON m.sessionId = s.id
                    WHERE s.assistantId = assistants.id AND m.role = 'ASSISTANT'
                )
                """.trimIndent(),
            )
            // assistants.lastUsedAt (关联会话的最大 updatedAt)
            db.execSQL(
                "UPDATE assistants SET lastUsedAt = " +
                    "COALESCE((SELECT MAX(updatedAt) FROM sessions " +
                    "WHERE assistantId = assistants.id), 0)",
            )
            // folders.sessionCount
            db.execSQL("UPDATE folders SET sessionCount = (SELECT COUNT(*) FROM sessions WHERE folderId = folders.id)")
            // group_chats.messageCount
            db.execSQL("UPDATE group_chats SET messageCount = (SELECT COUNT(*) FROM group_chat_messages WHERE chatId = group_chats.id)")
            // group_chats.lastActivityAt
            db.execSQL(
                "UPDATE group_chats SET lastActivityAt = " +
                    "COALESCE((SELECT MAX(timestamp) FROM group_chat_messages " +
                    "WHERE chatId = group_chats.id), 0)",
            )
            // group_chats.lastMessagePreview (最后一条消息内容,截断到 50 字)
            // 注意: group_chat_messages 表的内容列是 body 不是 content
            db.execSQL(
                """
                UPDATE group_chats SET lastMessagePreview = COALESCE(
                    (SELECT substr(body, 1, 50) FROM group_chat_messages
                     WHERE chatId = group_chats.id ORDER BY timestamp DESC LIMIT 1),
                    ''
                )
                """.trimIndent(),
            )
            // messages.contentLength
            db.execSQL("UPDATE messages SET contentLength = length(content)")

            // ── 3. 新建统计缓存表 ──
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `stats_cache` (
                    `key` TEXT NOT NULL,
                    `value` TEXT NOT NULL DEFAULT '{}',
                    `updatedAt` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`key`)
                )
                """.trimIndent(),
            )

            // ── 4. 新建完整性校验日志表 ──
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `db_integrity_log` (
                    `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    `status` TEXT NOT NULL DEFAULT 'ok',
                    `details` TEXT NOT NULL DEFAULT '',
                    `dbSizeBytes` INTEGER NOT NULL DEFAULT 0,
                    `checkedAt` INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )

            // ── 5. 新建自动备份日志表 ──
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `auto_backup_log` (
                    `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    `backupPath` TEXT NOT NULL DEFAULT '',
                    `fileSizeBytes` INTEGER NOT NULL DEFAULT 0,
                    `status` TEXT NOT NULL DEFAULT 'success',
                    `errorMessage` TEXT NOT NULL DEFAULT '',
                    `messageCount` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
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
     *
     * 审查修复 (2.0 B-27): 幂等化 — 标准 v34 快照(Entity 已声明两列)不含"31_32 漏加"缺陷,
     * 从标准 v34 起点升级会重复 ALTER → duplicate column 崩溃(迁移测试 22..54 矩阵暴露);
     * 真实"漏加设备"(31_32 实际未执行成功)列确实缺失,此处照常补齐。两者兼容。
     */
    val MIGRATION_34_35 = object : Migration(34, 35) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val existing = mutableSetOf<String>()
            db.query("PRAGMA table_info(sessions)").use { cursor ->
                while (cursor.moveToNext()) existing.add(cursor.getString(1))
            }
            if ("parentSessionId" !in existing) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN parentSessionId TEXT DEFAULT NULL")
            }
            if ("childCount" !in existing) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN childCount INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    /**
     * Phase 2 2B: MIGRATION_35_36 — 新建 milestones 表(关系里程碑)。
     */
    val MIGRATION_35_36 = object : Migration(35, 36) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
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
                """.trimIndent(),
            )
        }
    }

    /**
     * 既有工具系统 port: MIGRATION_36_37 — agent_messages table (DM system).
     */
    val MIGRATION_36_37 = object : Migration(36, 37) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS agent_messages (
                    id TEXT NOT NULL PRIMARY KEY,
                    from_agent_id TEXT NOT NULL,
                    to_agent_id TEXT NOT NULL,
                    content TEXT NOT NULL,
                    is_read INTEGER NOT NULL DEFAULT 0,
                    reply_to_id TEXT,
                    created_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * P2-4: MIGRATION_37_38 — 新建 audit_log 表(审计日志)。
     *
     * 仅 createTable,不修改既有表结构。环形缓冲策略由 AuditLogger 维护。
     */
    val MIGRATION_37_38 = object : Migration(37, 38) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS audit_log (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER NOT NULL,
                    category TEXT NOT NULL DEFAULT '',
                    action TEXT NOT NULL DEFAULT '',
                    target TEXT NOT NULL DEFAULT '',
                    detail TEXT NOT NULL DEFAULT '',
                    success INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent(),
            )
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
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS knowledge_bases (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    doc_count INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
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
            db.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS knowledge_chunks_fts
                USING fts4(chunkId, doc_id, text_content)
                """.trimIndent(),
            )

            // 5. assistants 加 2 列
            db.execSQL("ALTER TABLE assistants ADD COLUMN knowledgeBaseIdsJson TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE assistants ADD COLUMN ragConfigOverride TEXT DEFAULT NULL")

            // 6. 插入默认 KB
            db.compileStatement(
                """
                INSERT OR IGNORE INTO knowledge_bases (id, name, description, created_at, updated_at, doc_count)
                VALUES ('default', '默认知识库', '所有未指定知识库的文档默认归入此处', 0, 0, 0)
                """.trimIndent(),
            ).use { it.executeInsert() }

            // 7. 标记 internal 文档(替代原 fileType="devdoc" + id.startsWith("devdoc-") 双重硬编码)
            db.execSQL("UPDATE knowledge_docs SET is_internal = 1 WHERE file_type = 'devdoc' OR id LIKE 'devdoc-%'")

            // 8. 为现存 chunk 同步建立 FTS 索引(用 INSERT INTO ... SELECT)
            // Android 16 (SDK 36) 起禁止 execSQL 执行 DML,改用 compileStatement 不适用于 SELECT INSERT 组合,
            // 这里用 SupportSQLiteDatabase.execSQL(原始 SQL) — Room 的 SupportSQLiteDatabase.execSQL 对 INSERT 仍兼容。
            try {
                db.execSQL(
                    """
                    INSERT INTO knowledge_chunks_fts(chunkId, doc_id, text_content)
                    SELECT id, doc_id, content FROM knowledge_chunks
                    WHERE content != ''
                    """.trimIndent(),
                )
            } catch (e: Exception) {
                // FTS 同步失败不阻塞迁移,后续重索引会补齐
                io.zer0.common.Logger.w("MuseDb", "FTS 同步失败,稍后重索引补齐", e)
            }

            // 9. 更新默认 KB 的 doc_count(冗余字段)
            db.execSQL(
                """
                UPDATE knowledge_bases
                SET doc_count = (SELECT COUNT(*) FROM knowledge_docs WHERE kb_id = 'default' AND is_internal = 0)
                WHERE id = 'default'
                """.trimIndent(),
            )

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
            db.execSQL(
                """
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
                """.trimIndent(),
            )
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
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS translate_history (
                    id TEXT NOT NULL PRIMARY KEY,
                    source_text TEXT NOT NULL,
                    translated_text TEXT NOT NULL,
                    source_language TEXT NOT NULL,
                    target_language TEXT NOT NULL,
                    style TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            // 索引: 按时间倒序查询历史(对应 @Index 注解)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_translate_history_created_at ON translate_history(created_at)")
            // 索引: 按语言对查询(对应 @Index 注解)
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "index_translate_history_source_language_target_language " +
                    "ON translate_history(source_language, target_language)",
            )
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
            db.execSQL(
                """
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
                """.trimIndent(),
            )
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
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS group_chat_memories (
                    id TEXT NOT NULL PRIMARY KEY,
                    groupChatId TEXT NOT NULL,
                    assistantId TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    expiresAt INTEGER
                )
                """.trimIndent(),
            )
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
            db.execSQL(
                """
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
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO group_chats_new (id, name, description, memberIdsJson, teamId, pinned,
                    createdAt, updatedAt, lastMessagePreview, messageCount, lastActivityAt,
                    discussionMode, autoMaxRounds, host_id, shared_docs_json, member_private_context_json)
                SELECT id, name, description, memberIdsJson, teamId, pinned,
                    createdAt, updatedAt, lastMessagePreview, messageCount, lastActivityAt,
                    discussionMode, autoMaxRounds, host_id, shared_docs_json, member_private_context_json
                FROM group_chats
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE group_chats")
            db.execSQL("ALTER TABLE group_chats_new RENAME TO group_chats")

            // ── 重建 group_chat_messages(whisper_target_id / reply_to_id 默认值 NULL) ──
            db.execSQL(
                """
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
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO group_chat_messages_new (id, chatId, senderType, senderId, senderName,
                    body, imageBase64Json, timestamp, mood, reasoning,
                    whisper_target_id, reply_to_id, messageType)
                SELECT id, chatId, senderType, senderId, senderName,
                    body, imageBase64Json, timestamp, mood, reasoning,
                    whisper_target_id, reply_to_id, messageType
                FROM group_chat_messages
                """.trimIndent(),
            )
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
                """.trimIndent(),
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
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_subagent_threads_parentSessionId ON subagent_threads(parentSessionId)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_subagent_threads_status ON subagent_threads(status)",
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
    val MIGRATION_60_61 = object : Migration(60, 61) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureMessageColumns(db)
        }
    }

    /**
     * B5-01: MIGRATION_61_62 — 新增 generation_checkpoints 表。
     */
    val MIGRATION_61_62 = object : Migration(61, 62) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `generation_checkpoints` (" +
                    "`assistantMessageId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
                    "`userMessageId` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`assistantMessageId`), " +
                    "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_generation_checkpoints_sessionId` ON `generation_checkpoints` (`sessionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_generation_checkpoints_createdAt` ON `generation_checkpoints` (`createdAt`)")
        }
    }

    /**
     * B5-02: MIGRATION_62_63 — 新增 group_chat_generation_ledger 表。
     */
    val MIGRATION_62_63 = object : Migration(62, 63) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `group_chat_generation_ledger` (" +
                    "`id` TEXT NOT NULL, `chatId` TEXT NOT NULL, `mode` TEXT NOT NULL, " +
                    "`round` INTEGER NOT NULL, `memberIndex` INTEGER NOT NULL, " +
                    "`memberIdsJson` TEXT NOT NULL DEFAULT '[]', " +
                    "`status` TEXT NOT NULL DEFAULT 'running', " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`chatId`) REFERENCES `group_chats`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_group_chat_generation_ledger_chatId` ON `group_chat_generation_ledger` (`chatId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_group_chat_generation_ledger_status` ON `group_chat_generation_ledger` (`status`)",
            )
        }
    }

    /**
     * B6-03: MIGRATION_63_64 — messages 加 mood_skin 列(情绪皮肤标识)。
     */
    val MIGRATION_63_64 = object : Migration(63, 64) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureMessageColumns(db)
        }
    }

    /**
     * B7-03/B7-05: MIGRATION_64_65 — sessions 加 lastReadMessageId / sortOrder。
     */
    val MIGRATION_64_65 = object : Migration(64, 65) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureSessionColumns(db)
        }
    }

    /**
     * B7-03: MIGRATION_65_66 — sessions 加 lastReadCount,用于会话列表未读数徽标。
     */
    val MIGRATION_65_66 = object : Migration(65, 66) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureSessionColumns(db)
        }
    }

    /**
     * B8-01: MIGRATION_66_67 — sessions 加 proactiveNextTriggerAt,支持会话级主动消息排期。
     */
    val MIGRATION_66_67 = object : Migration(66, 67) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureSessionColumns(db)
        }
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
};