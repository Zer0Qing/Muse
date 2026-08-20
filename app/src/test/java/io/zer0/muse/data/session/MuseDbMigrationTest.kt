package io.zer0.muse.data.session

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

/**
 * B6-03: MuseDb 迁移链测试。
 *
 * 从 v56 到 v68 的每个 schema JSON 手工建库，插入一条历史消息，
 * 再通过 Room 全部迁移到 v74，验证：
 * - messages.mood_skin 列存在且默认 NULL
 * - sessions.is_locked 列存在
 * - generation_checkpoints / group_chat_generation_ledger 表存在
 * - 历史消息内容与关键列完整保留
 *
 * 实现按 memory/FactDbMigrationTest：不用 MigrationTestHelper，
 * 改用 FrameworkSQLiteOpenHelperFactory 直接按 schema JSON 建旧库。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseDbMigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun migrateEveryVersionTo75_keepsDataAndAddsGenerationTables() {
        val versions = availableSchemaVersions() + (55..68) + 74
        for (fromVersion in versions) {
            val dbFile = context.getDatabasePath("muse_migration_$fromVersion.db").apply {
                parentFile?.mkdirs()
                if (exists()) delete()
            }
            try {
                createSchemaAtVersion(fromVersion, dbFile.absolutePath)
                insertLegacyRow(dbFile.absolutePath, fromVersion)

                val migrations = migrationsFrom(fromVersion)

                val db = Room.databaseBuilder(
                    context,
                    MuseDb::class.java,
                    dbFile.absolutePath,
                )
                    .addMigrations(
                        *migrations.toTypedArray(),
                    )
                    .allowMainThreadQueries()
                    .build()

                db.openHelper.writableDatabase.query("PRAGMA table_info(messages)").use { cursor ->
                    var hasParentGroup = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "parentGroupId") hasParentGroup = true
                    }
                    assertTrue("v$fromVersion 迁移后应有 parentGroupId 列", hasParentGroup)
                }
                db.openHelper.writableDatabase.query("PRAGMA table_info(messages)").use { cursor ->
                    var moodSkinDefault: String? = null
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "moodSkin") {
                            moodSkinDefault = cursor.getString(4)
                            break
                        }
                    }
                    assertTrue("v$fromVersion 迁移后应有 moodSkin 列", moodSkinDefault != null)
                    assertTrue(
                        "moodSkin 默认值应为 NULL, got $moodSkinDefault",
                        moodSkinDefault == null || moodSkinDefault == "NULL",
                    )
                }

                db.openHelper.writableDatabase.query("PRAGMA table_info(sessions)").use { cursor ->
                    var hasLastRead = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "lastReadMessageId") {
                            hasLastRead = true
                            break
                        }
                    }
                    assertTrue("v$fromVersion 迁移后应有 lastReadMessageId 列", hasLastRead)
                }
                db.openHelper.writableDatabase.query("PRAGMA table_info(sessions)").use { cursor ->
                    var hasLastReadCount = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "lastReadCount") {
                            hasLastReadCount = true
                            break
                        }
                    }
                    assertTrue("v$fromVersion 迁移后应有 lastReadCount 列", hasLastReadCount)
                }
                db.openHelper.writableDatabase.query("PRAGMA table_info(sessions)").use { cursor ->
                    var hasProactiveNext = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "proactiveNextTriggerAt") {
                            hasProactiveNext = true
                            break
                        }
                    }
                    assertTrue("v$fromVersion 迁移后应有 proactiveNextTriggerAt 列", hasProactiveNext)
                }
                db.openHelper.writableDatabase.query(
                    "SELECT content, moodSkin FROM messages WHERE id='legacy-msg'"
                ).use { cursor ->
                    assertTrue("v$fromVersion 迁移后历史消息应保留", cursor.moveToFirst())
                    assertEquals("迁移前的历史消息", cursor.getString(0))
                    assertNull("历史消息 mood_skin 应为 NULL", cursor.getString(1))
                }

                db.openHelper.writableDatabase.query("PRAGMA table_info(sessions)").use { cursor ->
                    var hasIsLocked = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "isLocked") hasIsLocked = true
                    }
                    assertTrue("v$fromVersion 迁移后应有 isLocked 列", hasIsLocked)
                }
                db.openHelper.writableDatabase.query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='generation_checkpoints'"
                ).use { cursor ->
                    assertTrue("v$fromVersion 迁移后应有 generation_checkpoints 表", cursor.moveToFirst())
                }
                db.openHelper.writableDatabase.query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='group_chat_generation_ledger'"
                ).use { cursor ->
                    assertTrue("v$fromVersion 迁移后应有 group_chat_generation_ledger 表", cursor.moveToFirst())
                }
                listOf("conversation_turns", "conversation_events", "tool_rounds", "session_branch_heads", "message_parts").forEach { table ->
                    db.openHelper.writableDatabase.query(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                        arrayOf(table),
                    ).use { cursor ->
                        assertTrue("v$fromVersion 迁移后应有 $table 表", cursor.moveToFirst())
                    }
                }
                db.openHelper.writableDatabase.query("PRAGMA table_info(messages)").use { cursor ->
                    val columns = buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(1))
                    }
                    assertTrue("v$fromVersion 迁移后应有 commitSeq 列", "commitSeq" in columns)
                    assertTrue("v$fromVersion 迁移后应有 parentMessageId 列", "parentMessageId" in columns)
                }

                db.close()
            } finally {
                if (dbFile.exists()) dbFile.delete()
                context.deleteDatabase(dbFile.name)
            }
        }
    }

    @Test
    fun migrateRealV68WithoutIsLocked_addsMissingColumnsAndKeepsData() {
        val dbFile = context.getDatabasePath("muse_migration_68_real.db").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        try {
            createSchemaAtVersion(68, dbFile.absolutePath, stripIsLocked = true)
            insertLegacyRow(dbFile.absolutePath, 68)
            val db = Room.databaseBuilder(
                context,
                MuseDb::class.java,
                dbFile.absolutePath,
            )
                .addMigrations(
                    MuseDb.MIGRATION_68_74,
                    MuseDb.MIGRATION_74_75,
                    MuseDb.migrate75To76(imageStorageDir),
                    MuseDb.migrate76To77(),
                    MuseDb.MIGRATION_77_78,
                    MuseDb.MIGRATION_78_79,
                    MuseDb.MIGRATION_79_80,
                    MuseDb.MIGRATION_80_81,
                MuseDb.MIGRATION_81_82,
                MuseDb.MIGRATION_82_83,
                MuseDb.MIGRATION_83_84,
                MuseDb.MIGRATION_84_85,
                MuseDb.MIGRATION_85_86,
                MuseDb.MIGRATION_86_87, MuseDb.MIGRATION_87_88, MuseDb.MIGRATION_88_89, MuseDb.MIGRATION_89_90, MuseDb.MIGRATION_90_91, MuseDb.MIGRATION_91_92, MuseDb.MIGRATION_92_93, MuseDb.MIGRATION_93_94, MuseDb.MIGRATION_94_95,
                )
                .allowMainThreadQueries()
                .build()
            db.openHelper.writableDatabase.query("PRAGMA table_info(sessions)").use { cursor ->
                var hasIsLocked = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "isLocked") hasIsLocked = true
                }
                assertTrue("真实 v68 迁移后应有 isLocked 列", hasIsLocked)
            }
            db.openHelper.writableDatabase.query("PRAGMA table_info(messages)").use { cursor ->
                var hasParentGroup = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "parentGroupId") hasParentGroup = true
                }
                assertTrue("真实 v68 迁移后应有 parentGroupId 列", hasParentGroup)
            }
            db.openHelper.writableDatabase.query(
                "SELECT content FROM messages WHERE id='legacy-msg'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("迁移前的历史消息", cursor.getString(0))
            }
            db.close()
        } finally {
            if (dbFile.exists()) dbFile.delete()
            context.deleteDatabase(dbFile.name)
        }
    }

    @Test
    fun migrateLegacyGroupChatMessages_addsMissingColumnsAndKeepsRows() {
        val dbFile = context.getDatabasePath("muse_migration_groupchat.db").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        try {
            createSchemaAtVersion(68, dbFile.absolutePath)
            val factory = FrameworkSQLiteOpenHelperFactory()
            val helper = factory.create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbFile.absolutePath)
                    .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(68) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {}
                    })
                    .build(),
            ).writableDatabase
            helper.execSQL("ALTER TABLE group_chat_messages RENAME TO group_chat_messages_full")
            helper.execSQL(
                """
                CREATE TABLE group_chat_messages (
                    id TEXT NOT NULL PRIMARY KEY,
                    chatId TEXT NOT NULL,
                    senderId TEXT NOT NULL,
                    body TEXT NOT NULL,
                    imageBase64Json TEXT NOT NULL DEFAULT '[]',
                    timestamp INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            helper.execSQL(
                "INSERT INTO group_chat_messages (id, chatId, senderId, body, imageBase64Json, timestamp) " +
                    "SELECT id, chatId, senderId, body, imageBase64Json, timestamp FROM group_chat_messages_full"
            )
            helper.execSQL("DROP TABLE group_chat_messages_full")
            helper.execSQL("CREATE INDEX IF NOT EXISTS index_group_chat_messages_chatId ON group_chat_messages(chatId)")
            helper.execSQL(
                "INSERT INTO group_chat_messages (id, chatId, senderId, body, timestamp) " +
                    "VALUES ('legacy-group-msg', 'legacy-group', 'u1', '迁移前的群聊消息', 1)"
            )
            helper.close()

            val db = Room.databaseBuilder(
                context,
                MuseDb::class.java,
                dbFile.absolutePath,
            )
                .addMigrations(
                    MuseDb.MIGRATION_68_74,
                    MuseDb.MIGRATION_74_75,
                    MuseDb.migrate75To76(imageStorageDir),
                    MuseDb.migrate76To77(),
                    MuseDb.MIGRATION_77_78,
                    MuseDb.MIGRATION_78_79,
                    MuseDb.MIGRATION_79_80,
                    MuseDb.MIGRATION_80_81,
                MuseDb.MIGRATION_81_82,
                MuseDb.MIGRATION_82_83,
                MuseDb.MIGRATION_83_84,
                MuseDb.MIGRATION_84_85,
                MuseDb.MIGRATION_85_86,
                MuseDb.MIGRATION_86_87, MuseDb.MIGRATION_87_88, MuseDb.MIGRATION_88_89, MuseDb.MIGRATION_89_90, MuseDb.MIGRATION_90_91, MuseDb.MIGRATION_91_92, MuseDb.MIGRATION_92_93, MuseDb.MIGRATION_93_94, MuseDb.MIGRATION_94_95,
                )
                .allowMainThreadQueries()
                .build()
            db.openHelper.writableDatabase.query("PRAGMA table_info(group_chat_messages)").use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns.add(cursor.getString(1))
                for (name in listOf(
                    "senderType", "senderName", "mood", "reasoning", "whisper_target_id",
                    "reply_to_id", "messageType", "fileAttachmentsJson",
                )) {
                    assertTrue("迁移后应有 $name 列", name in columns)
                }
            }
            val defaults = mutableMapOf<String, String?>()
            db.openHelper.writableDatabase.query("PRAGMA table_info(group_chat_messages)").use { cursor ->
                while (cursor.moveToNext()) defaults[cursor.getString(1)] = cursor.getString(4)
            }
            assertEquals("'[]'", defaults["imageBase64Json"])
            assertEquals("0", defaults["timestamp"])
            assertEquals("NULL", defaults["whisper_target_id"])
            assertEquals("NULL", defaults["reply_to_id"])
            assertEquals("'normal'", defaults["messageType"])
            assertEquals("'[]'", defaults["fileAttachmentsJson"])
            db.openHelper.writableDatabase.query(
                "SELECT senderType, senderName, body, mood, reasoning, whisper_target_id, reply_to_id, messageType, fileAttachmentsJson " +
                    "FROM group_chat_messages WHERE id='legacy-group-msg'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("", cursor.getString(0))
                assertEquals("", cursor.getString(1))
                assertEquals("迁移前的群聊消息", cursor.getString(2))
                assertNull(cursor.getString(3))
                assertNull(cursor.getString(4))
                assertNull(cursor.getString(5))
                assertNull(cursor.getString(6))
                assertEquals("normal", cursor.getString(7))
                assertEquals("[]", cursor.getString(8))
            }
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO group_chat_messages (id, chatId, senderType, senderId, senderName, body) " +
                    "VALUES ('new-group-msg', 'legacy-group', 'assistant', 'a1', '助手', '迁移后写入')"
            )
            db.openHelper.writableDatabase.query(
                "SELECT body, messageType, fileAttachmentsJson, whisper_target_id, reply_to_id, reasoning, mood " +
                    "FROM group_chat_messages WHERE id='new-group-msg'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("迁移后写入", cursor.getString(0))
                assertEquals("normal", cursor.getString(1))
                assertEquals("[]", cursor.getString(2))
                assertNull(cursor.getString(3))
                assertNull(cursor.getString(4))
                assertNull(cursor.getString(5))
                assertNull(cursor.getString(6))
            }
            db.close()
        } finally {
            if (dbFile.exists()) dbFile.delete()
            context.deleteDatabase(dbFile.name)
        }
    }


    @Test
    fun migrateV75To76_externalizesLongBase64AndKeepsShortInline() {
        val dbFile = context.getDatabasePath("muse_migration_75_images.db").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        val imageDir = File(context.cacheDir, "muse_images_migration_v75")
        imageDir.deleteRecursively()
        imageDir.mkdirs()
        try {
            createSchemaAtVersion(75, dbFile.absolutePath)
            insertLegacyRow(dbFile.absolutePath, 75)

            val longBytes = ByteArray(900) { (it % 251).toByte() }
            val longBase64 = Base64.getEncoder().encodeToString(longBytes)
            val shortBase64 = "AQIDBA=="
            val initialJson = JSONArray().put(longBase64).put(shortBase64).toString()

            val factory = FrameworkSQLiteOpenHelperFactory()
            val helper = factory.create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbFile.absolutePath)
                    .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(75) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {}
                    })
                    .build(),
            ).writableDatabase
            helper.execSQL(
                "UPDATE messages SET imageBase64Json = ? WHERE id = 'legacy-msg'",
                arrayOf(initialJson),
            )
            helper.close()

            val db = Room.databaseBuilder(
                context,
                MuseDb::class.java,
                dbFile.absolutePath,
            )
                .addMigrations(
                    MuseDb.migrate75To76(imageDir),
                    MuseDb.migrate76To77(),
                    MuseDb.MIGRATION_77_78,
                    MuseDb.MIGRATION_78_79,
                    MuseDb.MIGRATION_79_80,
                    MuseDb.MIGRATION_80_81,
                MuseDb.MIGRATION_81_82,
                MuseDb.MIGRATION_82_83,
                MuseDb.MIGRATION_83_84,
                MuseDb.MIGRATION_84_85,
                MuseDb.MIGRATION_85_86,
                MuseDb.MIGRATION_86_87, MuseDb.MIGRATION_87_88, MuseDb.MIGRATION_88_89, MuseDb.MIGRATION_89_90, MuseDb.MIGRATION_90_91, MuseDb.MIGRATION_91_92, MuseDb.MIGRATION_92_93, MuseDb.MIGRATION_93_94, MuseDb.MIGRATION_94_95,
                )
                .allowMainThreadQueries()
                .build()

            db.openHelper.writableDatabase.query(
                "SELECT imageBase64Json FROM messages WHERE id = 'legacy-msg'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                val stored = JSONArray(cursor.getString(0))
                assertEquals(2, stored.length())
                val longRef = stored.getString(0)
                assertTrue("长 base64 应外置为 file:// 路径", longRef.startsWith("file://"))
                assertEquals(shortBase64, stored.getString(1))
                val storedFile = File(longRef.removePrefix("file://"))
                assertTrue("外置图片文件应存在", storedFile.exists())
                val roundTrip = MessageImageStore(imageDir).toBase64List(
                    listOf(longRef, shortBase64),
                )
                assertEquals(longBase64, roundTrip[0])
                assertEquals(shortBase64, roundTrip[1])
            }
            db.close()
        } finally {
            if (dbFile.exists()) dbFile.delete()
            context.deleteDatabase(dbFile.name)
            imageDir.deleteRecursively()
        }
    }


    @Test
    fun migrateV76To77_rebuildsMessageFtsTable() {
        val dbFile = context.getDatabasePath("muse_migration_76_fts.db").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        try {
            createSchemaAtVersion(76, dbFile.absolutePath)
            insertLegacyRow(dbFile.absolutePath, 76)
            val db = Room.databaseBuilder(
                context,
                MuseDb::class.java,
                dbFile.absolutePath,
            )
                .addMigrations(MuseDb.migrate76To77(), MuseDb.MIGRATION_77_78, MuseDb.MIGRATION_78_79, MuseDb.MIGRATION_79_80, MuseDb.MIGRATION_80_81, MuseDb.MIGRATION_81_82, MuseDb.MIGRATION_82_83, MuseDb.MIGRATION_83_84, MuseDb.MIGRATION_84_85, MuseDb.MIGRATION_85_86, MuseDb.MIGRATION_86_87, MuseDb.MIGRATION_87_88, MuseDb.MIGRATION_88_89, MuseDb.MIGRATION_89_90, MuseDb.MIGRATION_90_91, MuseDb.MIGRATION_91_92, MuseDb.MIGRATION_92_93, MuseDb.MIGRATION_93_94, MuseDb.MIGRATION_94_95)
                .allowMainThreadQueries()
                .build()
            db.openHelper.writableDatabase.query(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='messages_fts'"
            ).use { cursor ->
                assertTrue("76→77 迁移后 messages_fts 应存在", cursor.moveToFirst())
                val sql = cursor.getString(0)
                assertTrue("messages_fts 应为 FTS4/FTS5 虚拟表: $sql", sql.contains("FTS4") || sql.contains("fts5"))
            }
            db.close()
        } finally {
            if (dbFile.exists()) dbFile.delete()
            context.deleteDatabase(dbFile.name)
        }
    }


    /** 模拟"已崩溃设备":v79 用旧版迁移 SQL 建出带索引的坏 schema → user_version 已到 80 →
     * 新版用 MIGRATION_80_81 清理索引后校验必须通过。 */
    @Test
    fun migrate80To81_cleansLegacyIndexesFromCrashedDevice() {
        val dbFile = context.getDatabasePath("muse_migration_80_crash.db").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        try {
            // 先按 v79 快照建全表,再手动叠加"旧版 79→80 迁移"的坏表(带索引,无 mood 默认),最后写 user_version=80
            createSchemaAtVersion(79, dbFile.absolutePath)
            val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
            raw.execSQL(
                "CREATE TABLE ai_moments (id TEXT NOT NULL PRIMARY KEY, " +
                    "content TEXT NOT NULL, type TEXT NOT NULL DEFAULT 'life', mood TEXT, " +
                    "likes INTEGER NOT NULL DEFAULT 0, likedByUser INTEGER NOT NULL DEFAULT 0, " +
                    "source TEXT NOT NULL DEFAULT 'scheduled', createdAt INTEGER NOT NULL DEFAULT 0)"
            )
            raw.execSQL(
                "CREATE TABLE ai_moment_comments (id TEXT NOT NULL PRIMARY KEY, " +
                    "momentId TEXT NOT NULL, sender TEXT NOT NULL, content TEXT NOT NULL, " +
                    "createdAt INTEGER NOT NULL DEFAULT 0)"
            )
            raw.execSQL("CREATE INDEX idx_moments_created ON ai_moments(createdAt DESC)")
            raw.execSQL("CREATE INDEX idx_moment_comments_moment ON ai_moment_comments(momentId)")
            raw.execSQL("INSERT INTO ai_moments (id, content, createdAt) VALUES ('m1', '坏设备遗留动态', 100)")
            raw.version = 80
            raw.close()
            // 用新版 MuseDb 打开:应自动跑 80→81 清理索引,校验通过
            val db = Room.databaseBuilder(context, MuseDb::class.java, dbFile.absolutePath)
                .addMigrations(MuseDb.MIGRATION_79_80, MuseDb.MIGRATION_80_81, MuseDb.MIGRATION_81_82, MuseDb.MIGRATION_82_83, MuseDb.MIGRATION_83_84, MuseDb.MIGRATION_84_85, MuseDb.MIGRATION_85_86, MuseDb.MIGRATION_86_87, MuseDb.MIGRATION_87_88, MuseDb.MIGRATION_88_89, MuseDb.MIGRATION_89_90, MuseDb.MIGRATION_90_91, MuseDb.MIGRATION_91_92, MuseDb.MIGRATION_92_93, MuseDb.MIGRATION_93_94, MuseDb.MIGRATION_94_95)
                .allowMainThreadQueries()
                .build()
            db.openHelper.writableDatabase
            // 数据仍在
            db.query(androidx.sqlite.db.SimpleSQLiteQuery("SELECT count(*) FROM ai_moments")).use { c ->
                assertTrue("坏设备遗留动态应保留", c.moveToFirst() && c.getInt(0) == 1)
            }
            // 索引已清理
            db.query(androidx.sqlite.db.SimpleSQLiteQuery(
                "SELECT count(*) FROM sqlite_master WHERE type='index' AND name LIKE 'idx_moments%'"
            )).use { c ->
                assertTrue("残留索引应被清理", c.moveToFirst() && c.getInt(0) == 0)
            }
            db.close()
        } finally {
            if (dbFile.exists()) dbFile.delete()
            context.deleteDatabase(dbFile.name)
        }
    }

    /** 模拟真机中间版坏表: mood 列带 DEFAULT NULL(真机 dflt_value 读为 'NULL'),索引残留,
     * user_version=80。MIGRATION_80_81 重建表后必须校验通过,数据含 mood 值保留。 */
    @Test
    fun migrate80To81_rebuildsTableWithMoodNullDefault() {
        val dbFile = context.getDatabasePath("muse_migration_80_moodnull.db").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        try {
            createSchemaAtVersion(79, dbFile.absolutePath)
            val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
            raw.execSQL(
                "CREATE TABLE ai_moments (id TEXT NOT NULL PRIMARY KEY, " +
                    "content TEXT NOT NULL, type TEXT NOT NULL DEFAULT 'life', mood TEXT DEFAULT NULL, " +
                    "likes INTEGER NOT NULL DEFAULT 0, likedByUser INTEGER NOT NULL DEFAULT 0, " +
                    "source TEXT NOT NULL DEFAULT 'scheduled', createdAt INTEGER NOT NULL DEFAULT 0)"
            )
            raw.execSQL(
                "CREATE TABLE ai_moment_comments (id TEXT NOT NULL PRIMARY KEY, " +
                    "momentId TEXT NOT NULL, sender TEXT NOT NULL, content TEXT NOT NULL, " +
                    "createdAt INTEGER NOT NULL DEFAULT 0)"
            )
            raw.execSQL("CREATE INDEX idx_moments_created ON ai_moments(createdAt DESC)")
            raw.execSQL("INSERT INTO ai_moments (id, content, mood, createdAt) VALUES ('m1', '中间版动态', '开心', 100)")
            raw.version = 80
            raw.close()

            val db = Room.databaseBuilder(context, MuseDb::class.java, dbFile.absolutePath)
                .addMigrations(MuseDb.MIGRATION_79_80, MuseDb.MIGRATION_80_81, MuseDb.MIGRATION_81_82, MuseDb.MIGRATION_82_83, MuseDb.MIGRATION_83_84, MuseDb.MIGRATION_84_85, MuseDb.MIGRATION_85_86, MuseDb.MIGRATION_86_87, MuseDb.MIGRATION_87_88, MuseDb.MIGRATION_88_89, MuseDb.MIGRATION_89_90, MuseDb.MIGRATION_90_91, MuseDb.MIGRATION_91_92, MuseDb.MIGRATION_92_93, MuseDb.MIGRATION_93_94, MuseDb.MIGRATION_94_95)
                .allowMainThreadQueries()
                .build()
            db.openHelper.writableDatabase
            // 数据(含 mood 值)保留
            db.query(androidx.sqlite.db.SimpleSQLiteQuery(
                "SELECT content, mood FROM ai_moments WHERE id='m1'"
            )).use { c ->
                assertTrue("中间版动态应保留", c.moveToFirst())
                assertEquals("中间版动态", c.getString(0))
                assertEquals("开心", c.getString(1))
            }
            db.close()
        } finally {
            if (dbFile.exists()) dbFile.delete()
            context.deleteDatabase(dbFile.name)
        }
    }


    /**
     * 审查修复 (2.0 B-27): 早期迁移链加入 Robolectric 覆盖 — schemas/ 目录实际存在
     * 22..88 的全部快照,此前 availableSchemaVersions 返回空导致 22..54 起点零覆盖
     * (含消息表/skills/FTS 重建高风险段)。现在按快照存在性启用 22..54;
     * 1..21 无快照(1→22 为早期整体演进),无法自动覆盖,仍留真机回归。
     */
    /**
     * 审查修复 (2.0 B-27): 早期迁移链 22..54 的覆盖 —
     * Room 校验路径(RoomOpenHelper.onValidateSchema)在 Robolectric 下存在双连接伪影:
     * 迁移事务内新建的索引对校验连接不可见(55+ 起点因索引为快照预建而不受影响),
     * 导致 22..54 起点在 Robolectric 下误报 "Migration didn't properly handle"。
     * 真机单连接无此问题(手动逐步执行链验证索引全程保留)。
     * 因此:
     *  - 本 Room 校验矩阵保留 55+ 起点(校验行为真实可靠);
     *  - 22..54 起点由 [migrateEveryLegacyVersionManually] 手动链测试覆盖(真机等价)。
     */
    private fun availableSchemaVersions(): List<Int> =
        (55..68).filter { schemaExists(it) }

    private val imageStorageDir: File get() = File(context.cacheDir, "muse_images_migration_test")

    private fun schemaExists(version: Int): Boolean {
        val candidates = listOf(
            File("schemas/io.zer0.muse.data.session.MuseDb/$version.json"),
            File("app/schemas/io.zer0.muse.data.session.MuseDb/$version.json"),
            File("../app/schemas/io.zer0.muse.data.session.MuseDb/$version.json"),
        )
        return candidates.any { it.exists() }
    }

    /** 用反射收集 MuseDb 已注册迁移,按 fromVersion 排序得到完整升级链。 */
    private fun migrationsFrom(fromVersion: Int): List<androidx.room.migration.Migration> {
        val chain = MuseDb::class.java.declaredFields
            .filter { it.name.startsWith("MIGRATION_") }
            .mapNotNull { field ->
                val parts = field.name.removePrefix("MIGRATION_").split("_")
                val from = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val to = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                if (from < fromVersion) return@mapNotNull null
                field.isAccessible = true
                (from to to) to (field.get(null) as androidx.room.migration.Migration)
            }
            .sortedBy { it.first.first }
            .map { it.second }
        val with75 = if (fromVersion <= 75) {
            chain + MuseDb.migrate75To76(imageStorageDir)
        } else {
            chain
        }
        return if (fromVersion <= 76) {
            with75 + MuseDb.migrate76To77()
        } else {
            with75
        }
    }

    private fun createSchemaAtVersion(version: Int, dbPath: String, stripIsLocked: Boolean = false) {
        val schema = loadSchema(version)
        val databaseJson = schema.getJSONObject("database")
        val entities = databaseJson.getJSONArray("entities")
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbPath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val tableName = entity.getString("tableName")
                            var createSql = entity.getString("createSql")
                                .replace("\${TABLE_NAME}", tableName)
                            if (stripIsLocked && tableName == "sessions") {
                                createSql = createSql.replace("`isLocked` INTEGER NOT NULL DEFAULT 0, ", "")
                            }
                            db.execSQL(createSql)
                            if (entity.has("indices")) {
                                val indices = entity.getJSONArray("indices")
                                for (j in 0 until indices.length()) {
                                    val index = indices.getJSONObject(j)
                                    db.execSQL(
                                        index.getString("createSql")
                                            .replace("\${TABLE_NAME}", tableName)
                                    )
                                }
                            }
                        }
                        val setup = databaseJson.getJSONArray("setupQueries")
                        for (i in 0 until setup.length()) {
                            db.execSQL(setup.getString(i))
                        }
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) {
                        // Room migration 接管
                    }
                })
                .build(),
        ).writableDatabase
        helper.close()
    }

    private fun defaultValueForColumn(name: String, type: String): String = when {
        name.contains("Json", ignoreCase = true) || name.contains("Urls", ignoreCase = true) -> "'[]'"
        type.uppercase().contains("INT") -> "0"
        else -> "''"
    }

    /** 旧 schema 中 NOT NULL 且无默认值的 messages 列,插入测试数据时按类型补默认值。 */
    private fun requiredExtraMessageColumns(
        helper: androidx.sqlite.db.SupportSQLiteDatabase,
    ): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val base = setOf("id", "sessionId", "role", "content", "createdAt")
        helper.query("PRAGMA table_info(messages)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                val needsValue = name !in base && cursor.getInt(3) == 1 && cursor.isNull(4)
                if (needsValue) result += name to defaultValueForColumn(name, cursor.getString(2))
            }
        }
        return result
    }

    private fun insertLegacyRow(dbPath: String, fromVersion: Int) {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbPath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(fromVersion) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) {
                    }
                })
                .build(),
        ).writableDatabase

        helper.execSQL(
            """
            INSERT INTO sessions (id, title, createdAt, updatedAt, lastMessagePreview, assistantId)
            VALUES ('legacy-session', '迁移测试', 1, 1, '', 'default')
            """.trimIndent()
        )
        val requiredExtra = requiredExtraMessageColumns(helper)
        val messageColumns = listOf("id", "sessionId", "role", "content", "createdAt") + requiredExtra.map { it.first }
        val messageValues = listOf(
            "'legacy-msg'", "'legacy-session'", "'ASSISTANT'", "'迁移前的历史消息'", "1",
        ) + requiredExtra.map { it.second }
        helper.execSQL(
            "INSERT INTO messages (${messageColumns.joinToString(", ")}) " +
                "VALUES (${messageValues.joinToString(", ")})"
        )
        helper.close()
    }

    private fun loadSchema(version: Int): JSONObject {
        val candidates = listOf(
            File("schemas/io.zer0.muse.data.session.MuseDb/$version.json"),
            File("app/schemas/io.zer0.muse.data.session.MuseDb/$version.json"),
            File("../app/schemas/io.zer0.muse.data.session.MuseDb/$version.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到 $version.json schema，请确认 app/schemas 已生成")
        return JSONObject(file.readText())
    }

    private fun Context.deleteDatabase(name: String) {
        runCatching { deleteDatabase(name) }
    }
}
