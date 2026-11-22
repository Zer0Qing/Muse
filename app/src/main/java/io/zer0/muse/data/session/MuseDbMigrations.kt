package io.zer0.muse.data.session

import androidx.room.migration.Migration
import io.zer0.common.Logger
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * `MuseDb` 的 Room 迁移链：127 个版本迁移，每个一档。
 *
 * 从 `MuseDb` 的 `companion object` 整块搬出——它们与数据库本体无关，
 * 只依赖 schema 知识；放在同一个文件里会让 `MuseDb.kt` 超过 4000 行。
 *
 * 注册仍由 `MuseDb.migrations(context)` 汇总，**这里不是唯一真源**：
 * 新增迁移要在 `MuseDb` 的 `migrations()` 里登记。
 */
internal object MuseDbMigrations {
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
     * 审查修复 (2.0 B-27): MIGRATION_88_89 — 修正两类历史 schema 漂移。
     *
     * 1. index_scheduled_tasks_enabled_next_run_at 只在 21_22 创建,从 v22+ 升级的表
     *    从未获得该索引(getDueTasks 查询无索引可走),迁移测试 22..54 矩阵暴露。
     *    无条件重建(IF NOT EXISTS 幂等,经 21_22 升级的 v21 用户不受影响)。
     * 2. messages.imageUrlsJson 默认值声明漂移: v55(1.0.30) 时代 Entity 给该列补了
     *    @ColumnInfo(defaultValue="[]"),但 SQLite ALTER 无法改列默认值且当时未重建表 →
     *    从 <55 升级的表该列一直无 DEFAULT '[]'(迁移测试 "Migration didn't properly
     *    handle: messages")。运行时无碍(Room 生成的 SQL 总是显式写列),但声明不一致会
     *    持续触发 MigrationTestHelper 校验失败,并影响未来依赖 schema 一致性的工具。
     *    仅当该列缺失 DEFAULT '[]' 时重建 messages 表(55+ 起点已一致,直接跳过,
     *    避免大库无谓复制);重建列定义与当前 Entity 完全一致(含 FK 与 4 个索引)。
     * MIGRATION_29_30 已同步补 DEFAULT '[]'(未来 <30 升级路径直接一致)。
     */
    val MIGRATION_88_89 = object : Migration(88, 89) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. 无条件补齐 scheduled_tasks 索引(幂等)
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_scheduled_tasks_enabled_next_run_at` " +
                    "ON `scheduled_tasks` (`enabled`, `next_run_at`)",
            )
            // 2. 条件重建 messages(imageUrlsJson 缺 DEFAULT '[]' 时)
            var imageUrlsDefault = ""
            db.query("PRAGMA table_info(messages)").use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "imageUrlsJson") {
                        // SQLite dflt_value 返回 SQL 字面量(带引号,如 '[]')
                        imageUrlsDefault = cursor.getString(4)?.trim('\'') ?: ""
                        break
                    }
                }
            }
            if (imageUrlsDefault == "[]") return
            // 重建表: 列定义与 Entity 完全一致(30 列 + FK),随后重建 4 个索引
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `messages_new` (
                    `id` TEXT NOT NULL,
                    `sessionId` TEXT NOT NULL,
                    `role` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `reasoning` TEXT,
                    `thinkingSignature` TEXT DEFAULT NULL,
                    `thinkingEncryptedContent` TEXT DEFAULT NULL,
                    `modelId` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `imageUrlsJson` TEXT NOT NULL DEFAULT '[]',
                    `favorite` INTEGER NOT NULL DEFAULT 0,
                    `favoriteTag` TEXT DEFAULT NULL,
                    `citationUrlsJson` TEXT NOT NULL DEFAULT '[]',
                    `ragCitationsJson` TEXT NOT NULL DEFAULT '[]',
                    `imageBase64Json` TEXT NOT NULL DEFAULT '[]',
                    `videoFileUri` TEXT DEFAULT NULL,
                    `artifactIdsJson` TEXT NOT NULL DEFAULT '[]',
                    `moodSkin` TEXT DEFAULT NULL,
                    `mood` TEXT DEFAULT NULL,
                    `reflection` TEXT DEFAULT NULL,
                    `contentLength` INTEGER NOT NULL DEFAULT 0,
                    `deletedAt` INTEGER DEFAULT NULL,
                    `reaction` TEXT DEFAULT NULL,
                    `variantGroupId` TEXT DEFAULT NULL,
                    `variantIndex` INTEGER NOT NULL DEFAULT 0,
                    `variantCount` INTEGER NOT NULL DEFAULT 1,
                    `parentGroupId` TEXT DEFAULT NULL,
                    `attachmentsJson` TEXT NOT NULL DEFAULT '[]',
                    `toolCallInfoJson` TEXT DEFAULT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `messages_new` (
                    `id`,`sessionId`,`role`,`content`,`reasoning`,`thinkingSignature`,
                    `thinkingEncryptedContent`,`modelId`,`createdAt`,`imageUrlsJson`,
                    `favorite`,`favoriteTag`,`citationUrlsJson`,`ragCitationsJson`,
                    `imageBase64Json`,`videoFileUri`,`artifactIdsJson`,`moodSkin`,`mood`,
                    `reflection`,`contentLength`,`deletedAt`,`reaction`,`variantGroupId`,
                    `variantIndex`,`variantCount`,`parentGroupId`,`attachmentsJson`,`toolCallInfoJson`
                )
                SELECT
                    `id`,`sessionId`,`role`,`content`,`reasoning`,`thinkingSignature`,
                    `thinkingEncryptedContent`,`modelId`,`createdAt`,`imageUrlsJson`,
                    `favorite`,`favoriteTag`,`citationUrlsJson`,`ragCitationsJson`,
                    `imageBase64Json`,`videoFileUri`,`artifactIdsJson`,`moodSkin`,`mood`,
                    `reflection`,`contentLength`,`deletedAt`,`reaction`,`variantGroupId`,
                    `variantIndex`,`variantCount`,`parentGroupId`,`attachmentsJson`,`toolCallInfoJson`
                FROM `messages`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `messages`")
            db.execSQL("ALTER TABLE `messages_new` RENAME TO `messages`")
            // 重建索引(DROP TABLE 会删除所有索引)
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_sessionId` ON `messages` (`sessionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_role` ON `messages` (`role`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_sessionId_createdAt` ON `messages` (`sessionId`, `createdAt`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_messages_sessionId_createdAt_role` ON `messages` (`sessionId`, `createdAt`, `role`)",
            )
        }
    }

    /**
     * A5: MIGRATION_89_90 — messages 表补生成元数据列。
     *
     * 消息信息弹层(模型/耗时/令牌)需要持久化 provider 实测用量与耗时:
     * durationMs(生成总耗时)/promptTokens/completionTokens/reasoningTokens/cachedTokens。
     * 走 ensureMessageColumns 幂等补齐(与 60_61/63_64 等同一助手,已存在的列跳过),
     * 兼容 88_89 重建表路径(重建后列丢失,此处重新补上)。
     */
    val MIGRATION_89_90 = object : Migration(89, 90) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureMessageColumns(db)
        }
    }

    /**
     * H11: MIGRATION_90_91 — messages 表补翻译源消息列。
     *
     * 译文消息(独立 ASSISTANT 消息)通过 translationSourceId 指向被翻译的源消息,
     * UI 提供"查看原文"折叠对照。走 ensureMessageColumns 幂等补齐。
     */
    val MIGRATION_90_91 = object : Migration(90, 91) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureMessageColumns(db)
        }
    }

    /**
     * v92: MIGRATION_91_92 — messages 表加 seq 单调序列列(消息排序稳定主键)。
     *
     * - ADD COLUMN seq INTEGER NOT NULL DEFAULT 0(SQLite O(1) 元数据操作,无重写)
     * - 回填旧数据 seq = rowid: rowid 是插入顺序的稳定序列,回填后旧消息按原序排列
     * - 索引 idx_messages_sessionId_seq 加速会话内按 seq 排序
     */
    val MIGRATION_91_92 = object : Migration(91, 92) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN seq INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE messages SET seq = rowid WHERE seq = 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_sessionId_seq ON messages(sessionId, seq)")
        }
    }

    /** v92→v93: 新建对话事件、回合和工具轮表，不改写旧消息。 */
    val MIGRATION_92_93 = object : Migration(92, 93) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS conversation_turns (
                    turnId TEXT NOT NULL PRIMARY KEY,
                    sessionId TEXT NOT NULL,
                    inputUserMessageId TEXT NOT NULL,
                    assistantMessageId TEXT NOT NULL,
                    phase TEXT NOT NULL,
                    streamId TEXT DEFAULT NULL,
                    generationSerial INTEGER NOT NULL DEFAULT 0,
                    startedAt INTEGER NOT NULL,
                    finishedAt INTEGER DEFAULT NULL,
                    updatedAt INTEGER NOT NULL,
                    FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_conversation_turns_session_started ON conversation_turns(sessionId, startedAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_conversation_turns_session_phase ON conversation_turns(sessionId, phase)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_conversation_turns_assistant ON conversation_turns(assistantMessageId)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS conversation_events (
                    sessionId TEXT NOT NULL,
                    eventSeq INTEGER NOT NULL,
                    eventId TEXT NOT NULL,
                    turnId TEXT NOT NULL,
                    streamId TEXT DEFAULT NULL,
                    type TEXT NOT NULL,
                    payloadJson TEXT NOT NULL,
                    payloadHash TEXT NOT NULL,
                    payloadLength INTEGER NOT NULL,
                    provider TEXT DEFAULT NULL,
                    modelId TEXT DEFAULT NULL,
                    sequenceInStream INTEGER NOT NULL DEFAULT 0,
                    generationSerial INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL,
                    PRIMARY KEY(sessionId, eventSeq),
                    FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_conversation_events_session_turn_seq " +
                    "ON conversation_events(sessionId, turnId, eventSeq)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_conversation_events_turn_stream ON conversation_events(turnId, streamId)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_conversation_events_event_id ON conversation_events(eventId)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tool_rounds (
                    id TEXT NOT NULL PRIMARY KEY,
                    turnId TEXT NOT NULL,
                    roundIndex INTEGER NOT NULL,
                    toolCallId TEXT NOT NULL,
                    toolName TEXT NOT NULL,
                    argsJson TEXT NOT NULL,
                    resultJson TEXT DEFAULT NULL,
                    status TEXT NOT NULL,
                    startedAt INTEGER NOT NULL,
                    finishedAt INTEGER DEFAULT NULL,
                    errorDetail TEXT DEFAULT NULL,
                    FOREIGN KEY(turnId) REFERENCES conversation_turns(turnId) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_tool_rounds_turn_round ON tool_rounds(turnId, roundIndex)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_tool_rounds_call ON tool_rounds(toolCallId)")
        }
    }

    /** v93→v94: 增加兼容提交序和显式父消息引用，建立分支头表。 */
    val MIGRATION_93_94 = object : Migration(93, 94) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN commitSeq INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN parentMessageId TEXT DEFAULT NULL")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_sessionId_commitSeq ON messages(sessionId, commitSeq)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_parentMessageId ON messages(parentMessageId)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS session_branch_heads (
                    sessionId TEXT NOT NULL PRIMARY KEY,
                    headMessageId TEXT DEFAULT NULL,
                    nextCommitSeq INTEGER NOT NULL DEFAULT 1,
                    projectionVersion INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL,
                    FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
        }
    }

    /** v94→v95: 新建结构化 message parts，不回填既有消息。 */
    val MIGRATION_94_95 = object : Migration(94, 95) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS message_parts (
                    messageId TEXT NOT NULL,
                    partIndex INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    text TEXT NOT NULL,
                    metadataJson TEXT NOT NULL DEFAULT '{}',
                    createdAt INTEGER NOT NULL,
                    PRIMARY KEY(messageId, partIndex),
                    FOREIGN KEY(messageId) REFERENCES messages(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_message_parts_message_index ON message_parts(messageId, partIndex)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_message_parts_kind ON message_parts(kind)")
        }
    }

    /** v95→v96: sessions/消息级联删除补索引(审计 D-P2,消除全表扫描)。 */
    val MIGRATION_95_96 = object : Migration(95, 96) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_parentGroupId ON messages(parentGroupId)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_sessions_assistantId_updatedAt " +
                    "ON sessions(assistantId, updatedAt)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_sessions_deletedAt_archived_updatedAt " +
                    "ON sessions(deletedAt, archived, updatedAt)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_parentSessionId ON sessions(parentSessionId)")
        }
    }

    /** v96→v97: U-26 助手启用/停用 — assistants 表新增 enabled 列(默认启用)。 */
    val MIGRATION_96_97 = object : Migration(96, 97) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE assistants ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
        }
    }

    /** v97→v98: v1.138 小手机私信空间 — sessions 加 isMiniPhone 列。
     *
     * 小手机第一页改为"私信空间",只展示 isMiniPhone=true 的会话(AI 主动私信 + 小手机内新建对话)。
     * 已有会话不受影响(isMiniPhone 默认 0)。
     */
    val MIGRATION_97_98 = object : Migration(97, 98) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 幂等:64→72 段的迁移会经 ensureSessionColumns 提前补上 isMiniPhone(其列清单已含该列),
            // 若这里再无条件 ALTER 会 duplicate column 崩溃 —— 仅在缺列时添加。
            val existing = mutableSetOf<String>()
            db.query("PRAGMA table_info(sessions)").use { cursor ->
                while (cursor.moveToNext()) existing.add(cursor.getString(1))
            }
            if ("isMiniPhone" !in existing) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN isMiniPhone INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    /**
     * v98→v99: 补 scheduled_tasks.created_by 列。
     *
     * 该列从 v1.0.18 起就在 @Entity(defaultValue "user"),但建表(9→10)与后续所有
     * ALTER 都没带它 —— 从 v9 手写建表迁移上来的真实旧库缺此列,Room 迁移后 schema 校验
     * ("Migration didn't properly handle: scheduled_tasks") 会崩溃。
     * 全新安装走 createAllTables 反而正常,所以只在升级路径暴露。
     * 升版本到 99 而不是改旧迁移:已停在 v98 的库不会再跑 v97→98,只能靠新的 v98→99 补上。
     * 幂等:PRAGMA 判存在后再 ADD,避免重复列。
     */
    val MIGRATION_98_99 = object : Migration(98, 99) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val existing = mutableSetOf<String>()
            db.query("PRAGMA table_info(scheduled_tasks)").use { cursor ->
                while (cursor.moveToNext()) existing.add(cursor.getString(1))
            }
            if ("created_by" !in existing) {
                db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN created_by TEXT NOT NULL DEFAULT 'user'")
            }
        }
    }

    /**
     * v99→v100: 群聊归档 — group_chats 表新增 isArchived 列(默认 0)。
     *
     * 归档后群聊从主列表隐藏,可在归档列表查看/恢复;旧数据默认未归档。
     * 幂等:PRAGMA 判存在后再 ADD,避免重复列。
     */
    val MIGRATION_99_100 = object : Migration(99, 100) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val existing = mutableSetOf<String>()
            db.query("PRAGMA table_info(group_chats)").use { cursor ->
                while (cursor.moveToNext()) existing.add(cursor.getString(1))
            }
            if ("isArchived" !in existing) {
                db.execSQL("ALTER TABLE group_chats ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    /**
     * v100→v101: 群聊发言长度档位 — group_chats 表新增 replyLengthMode 列(默认 'standard')。
     *
     * brief/standard/detailed 三档控制成员发言长度;旧群聊默认标准档。
     * 幂等:PRAGMA 判存在后再 ADD,避免重复列。
     */
    val MIGRATION_100_101 = object : Migration(100, 101) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val existing = mutableSetOf<String>()
            db.query("PRAGMA table_info(group_chats)").use { cursor ->
                while (cursor.moveToNext()) existing.add(cursor.getString(1))
            }
            if ("replyLengthMode" !in existing) {
                db.execSQL("ALTER TABLE group_chats ADD COLUMN replyLengthMode TEXT NOT NULL DEFAULT 'standard'")
            }
        }
    }

    /**
     * v101→v102: 群聊观察者成员 — group_chats 表新增 observer_ids_json 列(默认 '[]')。
     *
     * 观察者不参与默认轮转发言(静观讨论),但保留在成员列表中。
     * 幂等:PRAGMA 判存在后再 ADD,避免重复列。
     */
    val MIGRATION_101_102 = object : Migration(101, 102) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val existing = mutableSetOf<String>()
            db.query("PRAGMA table_info(group_chats)").use { cursor ->
                while (cursor.moveToNext()) existing.add(cursor.getString(1))
            }
            if ("observer_ids_json" !in existing) {
                db.execSQL("ALTER TABLE group_chats ADD COLUMN observer_ids_json TEXT NOT NULL DEFAULT '[]'")
            }
        }
    }

    /**
     * v2.x: worldbook_entries 加 wholeWord 列(全词匹配,默认 false)。
     * 幂等:PRAGMA 判存在后再 ADD。
     */
    val MIGRATION_102_103 = object : Migration(102, 103) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val existing = mutableSetOf<String>()
            db.query("PRAGMA table_info(worldbook_entries)").use { cursor ->
                while (cursor.moveToNext()) existing.add(cursor.getString(1))
            }
            if ("wholeWord" !in existing) {
                db.execSQL("ALTER TABLE worldbook_entries ADD COLUMN wholeWord INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    /**
     * v103→v104: group_chat_messages 加 videoFileUri 列(群聊视频生成结果持久化)。
     *
     * 与单聊 messages.videoFileUri(迁移 87→88)对齐。此前群聊无视频列,
     * generate_video 只能被群聊工具策略屏蔽;补列后群聊内生成的视频可随消息落库并渲染。
     * 幂等:PRAGMA 判存在后再 ADD,避免重复列。
     */
    val MIGRATION_103_104 = object : Migration(103, 104) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val existing = mutableSetOf<String>()
            db.query("PRAGMA table_info(group_chat_messages)").use { cursor ->
                while (cursor.moveToNext()) existing.add(cursor.getString(1))
            }
            if ("videoFileUri" !in existing) {
                db.execSQL("ALTER TABLE group_chat_messages ADD COLUMN videoFileUri TEXT DEFAULT NULL")
            }
        }
    }

    /**
     * v104→v105: sessions 加 warmupPending 列(导入预热标记,默认 0)。
     *
     * 外部导入 / 备份恢复的会话置 1:首轮对话以全量历史 + token 预算构建上下文一次
     * (见 WarmupHistory),成功回复后由会话仓储清除。幂等:PRAGMA 判存在后再 ADD。
     */
    val MIGRATION_104_105 = object : Migration(104, 105) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val existing = mutableSetOf<String>()
            db.query("PRAGMA table_info(sessions)").use { cursor ->
                while (cursor.moveToNext()) existing.add(cursor.getString(1))
            }
            if ("warmupPending" !in existing) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN warmupPending INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    /**
     * v105→v106: 默认助手保留全局记忆；其他助手默认隔离。
     *
     * 旧版 useGlobalMemory 对所有助手默认开启，升级时关闭非 default 助手的全局共享，
     * 避免全局画像/置顶记忆意外串入其他助手。各助手自己的 facts 和近期会话仍保留。
     * 使用 compileStatement 执行 DML，兼容 Android 16 对 execSQL DML 的限制。
     */
    val MIGRATION_105_106 = object : Migration(105, 106) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.compileStatement(
                "UPDATE assistants SET useGlobalMemory = 0 WHERE id != 'default' AND useGlobalMemory != 0",
            ).use { statement ->
                statement.executeUpdateDelete()
            }
        }
    }

    /** v106→v107: 统一知识库 FTS4 的中文 ngram 索引格式并回填既有分块。 */
    val MIGRATION_106_107 = object : Migration(106, 107) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MuseDb.rebuildKnowledgeChunkFtsTable(db)
        }
    }

    /**
     * v107→v108: 知识库文件夹表 — 让文件夹(含空文件夹)可独立存在。
     *
     * 仅新建表,不动既有数据:既有文档的 folderPath 仍在 metadataJson 里(单一真源),
     * 文件夹树由「文档 metadata + 本表」合并得出。
     */
    val MIGRATION_107_108 = object : Migration(107, 108) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v2.4.6: 知识库文件夹表 + 助手「KB+文件夹」作用域列
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS knowledge_folders (
                    id TEXT NOT NULL PRIMARY KEY,
                    kb_id TEXT NOT NULL,
                    path TEXT NOT NULL,
                    name TEXT NOT NULL,
                    created_at INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_folders_kb_id ON knowledge_folders (kb_id)")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_knowledge_folders_kb_id_path ON knowledge_folders (kb_id, path)",
            )
            // v2.4.6: 助手绑定从「KB id」升级为「KB+文件夹路径」作用域(旧数据零迁移,默认空数组)
            db.execSQL(
                "ALTER TABLE assistants ADD COLUMN knowledgeFolderScopesJson TEXT NOT NULL DEFAULT '[]'",
            )
        }
    }

    /**
     * v108→v109: 消息表加译文映射列。
     *
     * v2.4.6: 译文从「另开一条助手消息」改为「挂在源消息上的可展开块」,
     * 需要在 messages 表存语言名 → 译文的 JSON。仅新增列,不动既有数据。
     */
    val MIGRATION_108_109 = object : Migration(108, 109) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN translationsJson TEXT NOT NULL DEFAULT '{}'")
        }
    }

    /**
     * v2.5.3 (P3-2): 会话级配置快照。
     *
     * sessions 加 configSnapshotJson（可空，旧会话为 NULL = 未拍摄，读取时回退助手当前值）。
     */
    val MIGRATION_109_110 = object : Migration(109, 110) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN configSnapshotJson TEXT DEFAULT NULL")
        }
    }

    /**
     * v2.6.2（用户反馈）: 助手人格定义 + 思考链约束。
     * - personaDefinition: 可编辑的人格定义段
     * - thinkingChain: 自定义思考过程约束
     */
    val MIGRATION_110_111 = object : Migration(110, 111) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE assistants ADD COLUMN personaDefinition TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE assistants ADD COLUMN thinkingChain TEXT NOT NULL DEFAULT ''")
        }
    }

    /**
     * v2.6.4 P2-2（审计报告）: per-KB 检索参数覆盖。
     *
     * 旧实现 RAG 配置全局一份，不同库不能有不同 chunk/topK/阈值，
     * 也不能给不同库配不同 embedding 模型。新增 5 个可空覆盖列，
     * 全部 NULL / 空串时完全继承全局配置（存量数据行为不变）。
     */
    val MIGRATION_111_112 = object : Migration(111, 112) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE knowledge_bases ADD COLUMN chunk_size_override INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE knowledge_bases ADD COLUMN chunk_overlap_override INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE knowledge_bases ADD COLUMN top_k_override INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE knowledge_bases ADD COLUMN threshold_override REAL DEFAULT NULL")
            db.execSQL(
                "ALTER TABLE knowledge_bases ADD COLUMN embedding_model_override TEXT NOT NULL DEFAULT ''",
            )
        }
    }

    /**
     * v2.6.6: 用量流水表 + 会话输入草稿。
     *
     * 1) 新建 usage_records：每次模型请求一行，统计页改为直接聚合本表，
     *    不再遍历消息解 JSON；非对话类请求（压缩/标题/翻译/记忆/OCR）的用量也首次可见。
     * 2) 用 messages 上已有的 token 字段回填对话类历史记录，老用户数据不丢。
     *    messages 的 token 字段可能为 NULL，用 COALESCE 兜底，且只回填有实际用量的行。
     * 3) sessions 加 input_draft：切换会话时各自保留未发送的输入。
     */
    val MIGRATION_112_113 = object : Migration(112, 113) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS usage_records (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    created_at INTEGER NOT NULL DEFAULT 0,
                    source TEXT NOT NULL,
                    model_id TEXT NOT NULL DEFAULT '',
                    provider_id TEXT NOT NULL DEFAULT '',
                    prompt_tokens INTEGER NOT NULL DEFAULT 0,
                    completion_tokens INTEGER NOT NULL DEFAULT 0,
                    reasoning_tokens INTEGER NOT NULL DEFAULT 0,
                    cached_tokens INTEGER NOT NULL DEFAULT 0,
                    first_token_ms INTEGER NOT NULL DEFAULT 0,
                    duration_ms INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_usage_records_created_at ON usage_records(created_at)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_usage_records_source_created_at " +
                    "ON usage_records(source, created_at)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_usage_records_model_id_created_at " +
                    "ON usage_records(model_id, created_at)",
            )

            // 回填：只回填确实有用量的 assistant 消息，避免产生大量零值行。
            db.execSQL(
                """
                INSERT INTO usage_records (
                    created_at, source, model_id, provider_id,
                    prompt_tokens, completion_tokens, reasoning_tokens, cached_tokens
                )
                SELECT createdAt, 'chat', '', '',
                       COALESCE(promptTokens, 0), COALESCE(completionTokens, 0),
                       COALESCE(reasoningTokens, 0), COALESCE(cachedTokens, 0)
                FROM messages
                WHERE role = 'ASSISTANT'
                  AND (COALESCE(promptTokens, 0) + COALESCE(completionTokens, 0)) > 0
                """.trimIndent(),
            )

            db.execSQL("ALTER TABLE sessions ADD COLUMN input_draft TEXT NOT NULL DEFAULT ''")
        }
    }

    /**
     * v2.6.7（P1-1）: 113 → 114 建角色关系表。
     *
     * 不往 `assistants` 加列：该表已 50+ 列且属“角色配置”，
     * 关系是运行时演化的状态（亲密度会变），混进去会让备份与配置对比变脏。
     *
     * 不在这里預填行：没有关系记录 = 默认值（亲密度 0 / normal 档），
     * 业务层读不到时现场初始化，避免为每个助手写入空行。
     */
    val MIGRATION_113_114 = object : Migration(113, 114) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS assistant_relationship (
                    assistantId TEXT NOT NULL PRIMARY KEY,
                    relationLabel TEXT NOT NULL DEFAULT '',
                    affinity INTEGER NOT NULL DEFAULT 0,
                    lastInteractionAt INTEGER NOT NULL DEFAULT 0,
                    proactiveLevel TEXT NOT NULL DEFAULT 'normal',
                    proactiveEnabled INTEGER NOT NULL DEFAULT 1,
                    dailyProactiveLimit INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * v2.6.9: 115 → 116 朋友圈支持可见范围。
     *
     * 默认空串 = 公开，旧数据行为不变（不需要回填）。
     */
    val MIGRATION_115_116 = object : Migration(115, 116) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE ai_moments ADD COLUMN visibleToJson TEXT NOT NULL DEFAULT ''")
        }
    }

    /**
     * v2.6.7: 116 → 117 会话待办持久化。
     *
     * 新表 `session_todos`：此前待办只存进程内存，切会话即丢。
     * 纯新增表，不动既有表结构；旧数据无待办可回填（内存态本就未落盘）。
     *
     * 列定义必须与 [io.zer0.muse.data.todo.SessionTodoEntity] 完全一致，
     * 否则真机 Room schema 校验会抛 "Migration didn't properly handle"。
     */
    val MIGRATION_116_117 = object : Migration(116, 117) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `session_todos` (" +
                    "`id` TEXT NOT NULL, " +
                    "`sessionId` TEXT NOT NULL DEFAULT '', " +
                    "`todosJson` TEXT NOT NULL DEFAULT '', " +
                    "`hasOpenItems` INTEGER NOT NULL DEFAULT 0, " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_session_todos_sessionId` ON `session_todos` (`sessionId`)",
            )
        }
    }

    /**
     * v2.6.8: 117 → 118 卡片实体表。
     *
     * 卡片此前只有「消息文本尾巴 + card_data.json」两处存放，脱离消息流后无处安放，
     * 身份也只是进程内的 `c_xxx`。新表把卡片升为一等实体：UUID 主键、内容、停靠状态全在行内。
     *
     * 纯新增表，不动既有表结构；存量卡片由上层回填流程按 [CardEntity.legacyCardId] 匹配，
     * 回填失败就保持「仅消息流」，迁移本身不猜数据。
     *
     * 列定义与索引名必须与 [io.zer0.muse.data.card.CardEntity] 完全一致，
     * 否则真机 Room schema 校验会抛 "Migration didn't properly handle: card_entities"。
     *
     * dockSlot 的唯一索引把 NULL 当作互不相等，因此「多张未停靠卡」不冲突；
     * 只有两个相同的非 NULL 槽位才会被拒。
     */
    /**
     * v2.6.9: 118 → 119 主动消息重建。
     *
     * 三张纯新增表，不动既有表，因此对存量数据零风险：
     *  - pending_thought     牵挂账本（「我有什么事想跟这个人说」）
     *  - proactive_outcome   结果账（点开/回复/划掉，驱动长期学习）
     *  - proactive_relation  关系亲密度与当日预算
     *
     * 旧的 ProactiveMessageConfig 继续留在 DataStore 里不做迁移：
     * 新链路只读其中的 enabled / 时段 / 助手 id，其余参数（间隔、概率、
     * 每日上限）在新模型里没有对应物，强行映射只会把旧毛病带进来。
     * 老用户升级后账本是空的，会先安静几天 —— 这是设计意图，不是故障。
     */
    val MIGRATION_118_119 = object : Migration(118, 119) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `pending_thought` (" +
                    "`id` TEXT NOT NULL, " +
                    "`assistantId` TEXT NOT NULL DEFAULT '', " +
                    "`kind` TEXT NOT NULL DEFAULT 'FOLLOW_UP', " +
                    "`seed` TEXT NOT NULL DEFAULT '', " +
                    "`source` TEXT NOT NULL DEFAULT 'CONVERSATION', " +
                    "`sourceRef` TEXT DEFAULT NULL, " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`expiresAt` INTEGER DEFAULT NULL, " +
                    "`urgency` TEXT NOT NULL DEFAULT 'WINDOW', " +
                    "`weight` REAL NOT NULL DEFAULT 0.5, " +
                    "`lastSurfacedAt` INTEGER DEFAULT NULL, " +
                    "`surfaceCount` INTEGER NOT NULL DEFAULT 0, " +
                    "`status` TEXT NOT NULL DEFAULT 'OPEN', " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_pending_thought_dispatch` " +
                    "ON `pending_thought` (`assistantId`, `status`, `expiresAt`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_pending_thought_sourceRef` " +
                    "ON `pending_thought` (`sourceRef`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `proactive_outcome` (" +
                    "`id` TEXT NOT NULL, " +
                    "`thoughtId` TEXT DEFAULT NULL, " +
                    "`messageId` TEXT DEFAULT NULL, " +
                    "`assistantId` TEXT NOT NULL DEFAULT '', " +
                    "`sessionId` TEXT NOT NULL DEFAULT '', " +
                    "`kind` TEXT NOT NULL DEFAULT 'FOLLOW_UP', " +
                    "`sentAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`openedAt` INTEGER DEFAULT NULL, " +
                    "`repliedAt` INTEGER DEFAULT NULL, " +
                    "`dismissedAt` INTEGER DEFAULT NULL, " +
                    "`state` TEXT NOT NULL DEFAULT 'PENDING', " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_proactive_outcome_assistant` " +
                    "ON `proactive_outcome` (`assistantId`, `sentAt`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_proactive_outcome_thought` " +
                    "ON `proactive_outcome` (`thoughtId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_proactive_outcome_state` " +
                    "ON `proactive_outcome` (`state`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `proactive_relation` (" +
                    "`assistantId` TEXT NOT NULL, " +
                    "`intimacy` REAL NOT NULL DEFAULT 0.15, " +
                    "`cooldownUntil` INTEGER NOT NULL DEFAULT 0, " +
                    "`intensity` TEXT NOT NULL DEFAULT 'STANDARD', " +
                    "`budgetDate` TEXT NOT NULL DEFAULT '', " +
                    "`spentToday` INTEGER NOT NULL DEFAULT 0, " +
                    "`earnedToday` REAL NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`assistantId`))",
            )
        }
    }

    /**
     * v2.6.15: 119 → 120 缓存读/写分列。
     *
     * 背景：拆分前 messages.cachedTokens / usage_records.cached_tokens 一个列装两种量
     * （缓存读 + 缓存写），而缓存写入单价更高（Anthropic 为输入价的 1.25 倍），
     * 合在一起就无法单独计价。
     *
     * 本迁移**只加列，不改也不回填旧值**：
     *  - messages.cacheWriteTokens INTEGER DEFAULT NULL（NULL = 未知，旧行永远为 NULL）
     *  - usage_records.cache_write_tokens INTEGER NOT NULL DEFAULT 0
     * 旧行里 cachedTokens / cached_tokens 的存量值保持原样，读取时按「读」解释（不回填、不重算）。
     *
     * 回退（降级）方案：两列均为纯新增，旧版本代码不读它们，因此数据本身不会损坏。
     * 若要把库降回 v119 交给旧版本使用：
     *  1) 先备份 muse.db（设置 → 数据管理 → 导出备份，或直接拷文件）；
     *  2) SQLite 3.35+：`ALTER TABLE messages DROP COLUMN cacheWriteTokens`、
     *     `ALTER TABLE usage_records DROP COLUMN cache_write_tokens`；
     *     低于 3.35 则「建新表 → 拷数据 → 改名」重建这两张表（messages 上的索引需一并重建）；
     *  3) `PRAGMA user_version = 119`。
     * 只装旧版本、不做第 2、3 步会因「库版本比代码新」在启动时被 Room 拒绝——
     * 这是版本校验，不是数据损坏。
     */
    val MIGRATION_119_120 = object : Migration(119, 120) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 幂等补齐：88_89 等重建表路径会丢列，这里重新补上
            ensureMessageColumns(db)
            ensureUsageRecordColumns(db)
        }
    }

    /**
     * v2.6.16: 120 → 121 语音消息。
     *
     * messages 加两列：
     *  - voiceUri TEXT，可空 —— 音频文件引用（VoiceMessageStore 的 file:// 路径），
     *    NULL 表示非语音消息
     *  - voiceDurationMs INTEGER NOT NULL DEFAULT 0 —— 时长（毫秒），0 表示未知
     *
     * 两列均为纯新增，旧行不需要回填：NULL / 0 已被读取路径解释为"非语音消息"。
     * 幂等：ensureMessageColumns 已按 PRAGMA 判存在，88_89 等重建表路径丢列时同样能补上。
     */
    val MIGRATION_120_121 = object : Migration(120, 121) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureMessageColumns(db)
        }
    }

    /**
     * 版本 121 → 122：清理旧小手机遗留数据。
     *
     * 旧小手机的 UI 与调度链已整体移除，数据层还留着两类痕迹，这里一次删干净：
     *  - 朋友圈 / 日记四张表（`ai_moments` / `ai_moment_comments` / `ai_moment_likes` / `ai_diaries`）
     *  - `sessions.isMiniPhone = 1` 的私信会话，以及挂在它们下面的消息
     *
     * 该功能从未对真实用户开放，库里不存在需要保留的业务数据，因此是删除而非归档。
     *
     * 列不删：SQLite 3.35 才支持 `DROP COLUMN`，minSdk 26 的旧设备没有这个能力。
     * `sessions.isMiniPhone` 保留为历史兼容列（NOT NULL DEFAULT 0，已无写入方），
     * 下次结构变更时一并移除。
     *
     * messages 表没有 isMiniPhone 列，只能按所属会话反查；先删消息再删会话，
     * 避免会话行没了之后留一批无主消息（备份/统计仍会扫 messages 全表）。
     *
     * 幂等：`DROP TABLE IF EXISTS` + 条件删除，重复执行不报错。
     */
    val MIGRATION_121_122 = object : Migration(121, 122) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `ai_moment_likes`")
            db.execSQL("DROP TABLE IF EXISTS `ai_moment_comments`")
            db.execSQL("DROP TABLE IF EXISTS `ai_moments`")
            db.execSQL("DROP TABLE IF EXISTS `ai_diaries`")
            db.execSQL(
                "DELETE FROM `messages` WHERE `sessionId` IN " +
                    "(SELECT `id` FROM `sessions` WHERE `isMiniPhone` = 1)",
            )
            db.execSQL("DELETE FROM `sessions` WHERE `isMiniPhone` = 1")
        }
    }

    val MIGRATION_122_123 = object : Migration(122, 123) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `conversation_command_receipts` (" +
                    "`commandId` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, " +
                    "`payloadJson` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
                    "`messageId` TEXT NOT NULL, `turnId` TEXT DEFAULT NULL, " +
                    "`acceptedAt` INTEGER NOT NULL, `resultJson` TEXT NOT NULL, " +
                    "PRIMARY KEY(`commandId`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `conversation_selections` (" +
                    "`selectionSetId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, " +
                    "`revision` INTEGER NOT NULL, `userSelectionJson` TEXT NOT NULL, " +
                    "`assistantSelectionJson` TEXT NOT NULL, PRIMARY KEY(`selectionSetId`))",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_conversation_selections_sessionId_revision` " +
                    "ON `conversation_selections` (`sessionId`, `revision`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `conversation_display_slots` (" +
                    "`slotId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                    "`parentSlotId` TEXT DEFAULT NULL, `logicalPosition` INTEGER NOT NULL, " +
                    "`messageId` TEXT NOT NULL, PRIMARY KEY(`slotId`), " +
                    "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_conversation_display_slots_sessionId_logicalPosition` " +
                    "ON `conversation_display_slots` (`sessionId`, `logicalPosition`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_conversation_display_slots_parentSlotId` " +
                    "ON `conversation_display_slots` (`parentSlotId`)",
            )
        }
    }

    val MIGRATION_123_124 = object : Migration(123, 124) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `session_proactive_preferences` (" +
                    "`sessionId` TEXT NOT NULL, `muted` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`sessionId`), " +
                    "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_session_proactive_preferences_sessionId` " +
                    "ON `session_proactive_preferences` (`sessionId`)",
            )
        }
    }

    /**
     * M4a: 会话内运行环境安装卡。
     *
     * 只建表不动既有行。列与索引必须和 `RuntimeInstallCardEntity` 逐项一致 ——
     * 名字对不上时 Room 在升级后的 schema 校验阶段会直接报 unexpected，而不是静默通过。
     */
    val MIGRATION_131_132 = object : Migration(131, 132) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `runtime_install_cards` (" +
                    "`messageId` TEXT NOT NULL, `runtimeId` TEXT NOT NULL, `version` TEXT NOT NULL, " +
                    "`state` TEXT NOT NULL, `progress` INTEGER NOT NULL DEFAULT 0, " +
                    "`errorKind` TEXT, `dismissed` INTEGER NOT NULL DEFAULT 0, " +
                    "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`messageId`))",
            )
        }
    }

    /**
     * Bot 标注表（表情 / 贴纸 / 引用定位）。
     *
     * 与 `messages.reaction` 分开：那一列是**用户**的反应，单值；
     * 主 Bot 的标注可能多条、可能带资源、可能指向句子。共用一列会互相覆盖。
     * 纯新增表，旧 reaction 列无损。
     */
    val MIGRATION_133_134 = object : Migration(133, 134) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bot_annotations` (" +
                    "`id` TEXT NOT NULL, `botId` TEXT NOT NULL DEFAULT '', " +
                    "`actorType` TEXT NOT NULL DEFAULT 'bot', " +
                    "`originSessionId` TEXT NOT NULL DEFAULT '', " +
                    "`targetMessageId` TEXT NOT NULL DEFAULT '', " +
                    "`targetSentenceId` TEXT NOT NULL DEFAULT '', " +
                    "`targetContentHash` TEXT NOT NULL DEFAULT '', " +
                    "`kind` TEXT NOT NULL DEFAULT 'REACTION', " +
                    "`assetRef` TEXT NOT NULL DEFAULT '', " +
                    "`status` TEXT NOT NULL DEFAULT 'ACTIVE', " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_annotations_targetMessageId` ON `bot_annotations`(`targetMessageId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_annotations_botId_originSessionId` ON `bot_annotations`(`botId`,`originSessionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_annotations_status` ON `bot_annotations`(`status`)")
        }
    }

    /**
     * Agent 中枢七表。
     *
     * 纯新增,不动任何既有行:中枢功能关着时这七张表就是空的,普通对话
     * 完全不受影响。列名与默认值必须逐项对齐各 Entity —— 名字不一致时
     * Room 会在升级后的 schema 校验阶段直接报 unexpected,而不是静默通过。
     */
    val MIGRATION_132_133 = object : Migration(132, 133) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bot_tasks` (" +
                    "`taskId` TEXT NOT NULL, `botId` TEXT NOT NULL DEFAULT '', " +
                    "`originSessionId` TEXT NOT NULL DEFAULT '', " +
                    "`requestMessageId` TEXT NOT NULL DEFAULT '', " +
                    "`goal` TEXT NOT NULL DEFAULT '', `acceptanceCriteria` TEXT NOT NULL DEFAULT '', " +
                    "`resourceScope` TEXT NOT NULL DEFAULT '', `permissionScopeId` TEXT NOT NULL DEFAULT '', " +
                    "`revision` INTEGER NOT NULL DEFAULT 1, " +
                    "`status` TEXT NOT NULL DEFAULT 'QUEUED', `priority` INTEGER NOT NULL DEFAULT 0, " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`taskId`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_tasks_botId_status` ON `bot_tasks`(`botId`,`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_tasks_originSessionId` ON `bot_tasks`(`originSessionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_tasks_updatedAt` ON `bot_tasks`(`updatedAt`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bot_task_runs` (" +
                    "`runId` TEXT NOT NULL, `taskId` TEXT NOT NULL DEFAULT '', " +
                    "`attempt` INTEGER NOT NULL DEFAULT 1, `threadId` TEXT NOT NULL DEFAULT '', " +
                    "`taskRevision` INTEGER NOT NULL DEFAULT 1, " +
                    "`status` TEXT NOT NULL DEFAULT 'QUEUED', `leaseOwner` TEXT NOT NULL DEFAULT '', " +
                    "`leaseUntil` INTEGER NOT NULL DEFAULT 0, `fenceToken` INTEGER NOT NULL DEFAULT 0, " +
                    "`cancelRequestedAt` INTEGER NOT NULL DEFAULT 0, `startedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`finishedAt` INTEGER NOT NULL DEFAULT 0, `checkpointRef` TEXT NOT NULL DEFAULT '', " +
                    "`errorCode` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`runId`))",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bot_task_runs_taskId_attempt` ON `bot_task_runs`(`taskId`,`attempt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_task_runs_status` ON `bot_task_runs`(`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_task_runs_leaseUntil` ON `bot_task_runs`(`leaseUntil`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bot_task_steps` (" +
                    "`stepId` TEXT NOT NULL, `runId` TEXT NOT NULL DEFAULT '', " +
                    "`stepIndex` INTEGER NOT NULL DEFAULT 0, `toolName` TEXT NOT NULL DEFAULT '', " +
                    "`argumentsHash` TEXT NOT NULL DEFAULT '', `baseHash` TEXT NOT NULL DEFAULT '', " +
                    "`approvalId` TEXT NOT NULL DEFAULT '', " +
                    "`sideEffect` TEXT NOT NULL DEFAULT 'READ_ONLY', " +
                    "`startedAt` INTEGER NOT NULL DEFAULT 0, `finishedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`stepId`))",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bot_task_steps_runId_stepIndex` ON `bot_task_steps`(`runId`,`stepIndex`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_task_steps_approvalId` ON `bot_task_steps`(`approvalId`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bot_events` (" +
                    "`eventId` TEXT NOT NULL, `botId` TEXT NOT NULL DEFAULT '', " +
                    "`originSessionId` TEXT NOT NULL DEFAULT '', `taskId` TEXT NOT NULL DEFAULT '', " +
                    "`runId` TEXT NOT NULL DEFAULT '', `kind` TEXT NOT NULL DEFAULT '', " +
                    "`sequence` INTEGER NOT NULL DEFAULT 0, `payloadRef` TEXT NOT NULL DEFAULT '', " +
                    "`dedupeKey` TEXT NOT NULL DEFAULT '', `createdAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`consumedAt` INTEGER NOT NULL DEFAULT 0, `leaseOwner` TEXT NOT NULL DEFAULT '', " +
                    "`leaseUntil` INTEGER NOT NULL DEFAULT 0, `fenceToken` INTEGER NOT NULL DEFAULT 0, " +
                    "`schemaVersion` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`eventId`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_events_botId_sequence` ON `bot_events`(`botId`,`sequence`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_events_taskId` ON `bot_events`(`taskId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bot_events_dedupeKey` ON `bot_events`(`dedupeKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_events_consumedAt` ON `bot_events`(`consumedAt`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bot_approvals` (" +
                    "`approvalId` TEXT NOT NULL, `botId` TEXT NOT NULL DEFAULT '', " +
                    "`originSessionId` TEXT NOT NULL DEFAULT '', `taskId` TEXT NOT NULL DEFAULT '', " +
                    "`runId` TEXT NOT NULL DEFAULT '', `stepId` TEXT NOT NULL DEFAULT '', " +
                    "`taskRevision` INTEGER NOT NULL DEFAULT 1, `toolCallId` TEXT NOT NULL DEFAULT '', " +
                    "`toolName` TEXT NOT NULL DEFAULT '', `argumentsRef` TEXT NOT NULL DEFAULT '', " +
                    "`argumentsHash` TEXT NOT NULL DEFAULT '', `resourceVersion` TEXT NOT NULL DEFAULT '', " +
                    "`risk` TEXT NOT NULL DEFAULT '', `status` TEXT NOT NULL DEFAULT 'PENDING', " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, `readyAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`expiresAt` INTEGER NOT NULL DEFAULT 0, `resolvedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`decisionActor` TEXT NOT NULL DEFAULT '', `decisionReason` TEXT NOT NULL DEFAULT '', " +
                    "PRIMARY KEY(`approvalId`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_approvals_botId_status` ON `bot_approvals`(`botId`,`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_approvals_taskId` ON `bot_approvals`(`taskId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_approvals_expiresAt` ON `bot_approvals`(`expiresAt`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bot_publications` (" +
                    "`id` TEXT NOT NULL, `publicationId` TEXT NOT NULL DEFAULT '', " +
                    "`botId` TEXT NOT NULL DEFAULT '', `originSessionId` TEXT NOT NULL DEFAULT '', " +
                    "`causeEventIds` TEXT NOT NULL DEFAULT '', `requestRevision` INTEGER NOT NULL DEFAULT 0, " +
                    "`taskRevision` INTEGER NOT NULL DEFAULT 1, `itemIndex` INTEGER NOT NULL DEFAULT 0, " +
                    "`kind` TEXT NOT NULL DEFAULT 'TEXT', `payloadRef` TEXT NOT NULL DEFAULT '', " +
                    "`status` TEXT NOT NULL DEFAULT 'PENDING', `messageId` TEXT NOT NULL DEFAULT '', " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bot_publications_publicationId_itemIndex` ON `bot_publications`(`publicationId`,`itemIndex`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_publications_botId_status` ON `bot_publications`(`botId`,`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_publications_messageId` ON `bot_publications`(`messageId`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `bot_bindings` (" +
                    "`botId` TEXT NOT NULL, `assistantId` TEXT NOT NULL DEFAULT '', " +
                    "`canonicalAgentSessionId` TEXT NOT NULL DEFAULT '', " +
                    "`enabled` INTEGER NOT NULL DEFAULT 1, " +
                    "`schemaVersion` INTEGER NOT NULL DEFAULT 1, " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`botId`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_bindings_canonicalAgentSessionId` ON `bot_bindings`(`canonicalAgentSessionId`)")
        }
    }

    /**
     * v2.7.x(E2): 会话术语表。
     *
     * 只建表,不动任何既有行。列名与默认值逐项对齐 `SessionTermEntity`,
     * 索引名沿用 Room 的命名规则(`index_<表>_<列...>`)—— 名字不一致时
     * Room 会在升级后校验阶段报"unexpected index",而不是安静通过。
     */
    val MIGRATION_130_131 = object : Migration(130, 131) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 置顶记忆注入独立开关：默认 1（保持现有行为，升级用户不会突然失去置顶注入）。
            db.execSQL(
                "ALTER TABLE `assistants` ADD COLUMN `enablePinnedInjection` INTEGER NOT NULL DEFAULT 1",
            )
        }
    }

    val MIGRATION_124_125 = object : Migration(124, 125) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `session_terms` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`term` TEXT NOT NULL, `meaning` TEXT, " +
                    "`session_id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL, " +
                    "`scope` TEXT NOT NULL DEFAULT 'session', " +
                    "`occurrences` INTEGER NOT NULL DEFAULT 1, " +
                    "`confirmed` INTEGER NOT NULL DEFAULT 0, " +
                    "`first_seen_at` INTEGER NOT NULL, `last_seen_at` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_terms_assistant_id` ON `session_terms` (`assistant_id`)")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_session_terms_scope_session_id_term` " +
                    "ON `session_terms` (`scope`, `session_id`, `term`)",
            )
        }
    }

    val MIGRATION_117_118 = object : Migration(117, 118) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `card_entities` (" +
                    "`id` TEXT NOT NULL, " +
                    "`legacyCardId` TEXT DEFAULT NULL, " +
                    "`originSessionId` TEXT DEFAULT NULL, " +
                    "`originMessageId` TEXT DEFAULT NULL, " +
                    "`title` TEXT NOT NULL DEFAULT '', " +
                    "`code` TEXT NOT NULL DEFAULT '', " +
                    "`isSvg` INTEGER NOT NULL DEFAULT 0, " +
                    "`dataJson` TEXT DEFAULT NULL, " +
                    "`dockSlot` INTEGER DEFAULT NULL, " +
                    "`dockedAt` INTEGER DEFAULT NULL, " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_card_entities_dockSlot` ON `card_entities` (`dockSlot`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_card_entities_originMessageId` ON `card_entities` (`originMessageId`)",
            )
        }
    }

    /**
     * v2.6.7（P2-4）: 114 → 115 评论支持嵌套回复。
     *
     * 旧数据全为 NULL，语义等同“顶层评论”，无需回填。
     */
    val MIGRATION_114_115 = object : Migration(114, 115) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE ai_moment_comments ADD COLUMN parentCommentId TEXT DEFAULT NULL")
        }
    }

    /**
     * Phase 8.2: v1 → v2 迁移.
     * - 新建 assistants 表
     * - sessions 加 assistantId(默认 'default') + pinned(默认 0)字段
     * - 插入默认 Assistant(id='default')
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 新建 assistants 表
            db.execSQL(
                """
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
                """.trimIndent(),
            )
            // 插入默认 Assistant
            // Android 16 (SDK 36) 起禁止 SQLiteDatabase.execSQL 执行 DML,只允许 DDL;
            // 改用 compileStatement + executeInsert(SQLiteStatement 方法,Android 16 仍允许)
            db.compileStatement(
                """
                INSERT OR IGNORE INTO assistants (id, name, sortIndex, createdAt, updatedAt)
                VALUES ('default', '默认助手', 0, 0, 0)
                """.trimIndent(),
            ).use { it.executeInsert() }
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
            db.execSQL(
                """
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
                """.trimIndent(),
            )
            db.execSQL(
                """
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
                """.trimIndent(),
            )
            db.execSQL(
                """
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
                """.trimIndent(),
            )
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
            db.execSQL(
                """
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
                """.trimIndent(),
            )
        }
    }

    /**
     * Phase 9.1 (M13): v7 → v8 迁移。
     * - 新建 folders 表(会话文件夹分组)
     * - sessions 加 folderId 字段(默认 '',与 SessionEntity @ColumnInfo(defaultValue="") 对齐)
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS folders (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    sortIndex INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0,
                    expanded INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent(),
            )
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
                    ")",
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
            db.execSQL(
                """
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
                """.trimIndent(),
            )
            // knowledge_docs 表
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS knowledge_docs (
                    id TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL DEFAULT '',
                    file_path TEXT NOT NULL DEFAULT '',
                    file_type TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * P1-7: v10 → v11 迁移。
     * - 新建 scheduled_task_executions 表(定时任务执行历史)
     *
     * 注意: 迁移链已覆盖标准升级路径;未知版本或降级由备份恢复流程处理，不执行 destructive migration。
     * 此迁移仅用于 v10 → v11 的标准升级路径,DDL 用 execSQL(Room 迁移标准做法)。
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // scheduled_task_executions 表(SQL 必须与 ScheduledTaskExecutionEntity @Entity 注解生成的完全一致)
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS scheduled_task_executions (
                    id TEXT NOT NULL PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    executed_at INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'success',
                    reply_summary TEXT NOT NULL DEFAULT '',
                    error_message TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent(),
            )
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
     * 迁移链已覆盖标准升级路径;未知版本或降级由备份恢复流程处理，不执行 destructive migration。
     */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // group_chats 表(SQL 必须与 GroupChatEntity @Entity 注解生成的完全一致)
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS group_chats (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    memberIdsJson TEXT NOT NULL,
                    teamId TEXT,
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            // group_chat_messages 表(SQL 必须与 GroupChatMessageEntity @Entity 注解生成的完全一致)
            db.execSQL(
                """
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
                """.trimIndent(),
            )
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
            db.execSQL(
                """
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
                """.trimIndent(),
            )
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
            db.execSQL(
                """
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
                """.trimIndent(),
            )
            // 2. 数据迁移(DML,Android 16 必须用 query() 而非 execSQL)
            //    MIGRATION_6_7 建表时已包含 category/createdAt/updatedAt 列,直接 INSERT 即可。
            //    若旧表异常缺列,放弃迁移(技能可重新导入)。
            //    (原 catch 分支用 COALESCE 补缺列是死代码:SQLite 对不存在的列在解析
            //    阶段即报错,COALESCE 无法补救,故移除。)
            try {
                db.query(
                    """
                    INSERT INTO skills_new
                        (id, name, description, parametersJson, requiredJson,
                         implementationKotlin, enabled, category, createdAt, updatedAt)
                    SELECT
                        id, name, description, parametersJson, requiredJson,
                        implementationKotlin, enabled, category, createdAt, updatedAt
                    FROM skills
                    """.trimIndent(),
                ).use { /* 执行 INSERT,关闭 Cursor */ }
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
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_knowledge_chunks_doc_id_chunk_index ON knowledge_chunks(doc_id, chunk_index)",
            )
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
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "index_quick_messages_scope_assistantId_enabled " +
                    "ON quick_messages(scope, assistantId, enabled)",
            )
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
            db.execSQL(
                """
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
                """.trimIndent(),
            )
            // 2. 数据迁移(DML,Android 16 必须用 query() 而非 execSQL)
            try {
                db.query(
                    """
                    INSERT INTO skills_new
                        (id, name, description, parametersJson, requiredJson,
                         implementationKotlin, enabled, category, createdAt, updatedAt)
                    SELECT
                        id, name, description, parametersJson, requiredJson,
                        implementationKotlin, enabled, category, createdAt, updatedAt
                    FROM skills
                    """.trimIndent(),
                ).use { /* 执行 INSERT,关闭 Cursor */ }
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
                "ALTER TABLE assistants ADD COLUMN regexRulesJson TEXT NOT NULL DEFAULT '[]'",
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
            db.execSQL(
                """
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
                """.trimIndent(),
            )
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
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `messages_new` (
                    `id` TEXT NOT NULL,
                    `sessionId` TEXT NOT NULL,
                    `role` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `reasoning` TEXT,
                    `modelId` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `imageUrlsJson` TEXT NOT NULL DEFAULT '[]',
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
                """.trimIndent(),
            )
            // 2. 复制数据(列顺序与建表一致)
            db.execSQL(
                """
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
                """.trimIndent(),
            )
            // 3. 替换旧表
            db.execSQL("DROP TABLE `messages`")
            db.execSQL("ALTER TABLE `messages_new` RENAME TO `messages`")
            // 4. 重建索引(DROP TABLE 会删除所有索引)
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_sessionId` ON `messages` (`sessionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_role` ON `messages` (`role`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_sessionId_createdAt` ON `messages` (`sessionId`, `createdAt`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_messages_sessionId_createdAt_role` ON `messages` (`sessionId`, `createdAt`, `role`)",
            )
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
}
