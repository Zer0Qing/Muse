package io.zer0.muse.data.session

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
Room 迁移链第一段（44 个迁移）。
 *
 * 从 `MuseDbMigrations` 按行数均分而来——原文件 3,196 行，
 * 超过 2,000 行的软上限。分段依据是「行数均衡」，不是版本区间
 * （迁移在源码里并非按版本号顺序排列）。
 */
internal object MigrationsA {
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
}
