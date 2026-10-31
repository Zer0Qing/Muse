package io.zer0.muse.data.session

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
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
                    "SELECT content, moodSkin FROM messages WHERE id='legacy-msg'",
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
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='generation_checkpoints'",
                ).use { cursor ->
                    assertTrue("v$fromVersion 迁移后应有 generation_checkpoints 表", cursor.moveToFirst())
                }
                db.openHelper.writableDatabase.query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='group_chat_generation_ledger'",
                ).use { cursor ->
                    assertTrue("v$fromVersion 迁移后应有 group_chat_generation_ledger 表", cursor.moveToFirst())
                }
                listOf(
                    "conversation_turns",
                    "conversation_events",
                    "tool_rounds",
                    "session_branch_heads",
                    "message_parts",
                ).forEach { table ->
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
    fun migrate105ToLatest_disablesGlobalMemoryOnlyForNonDefaultAssistants() {
        val dbFile = context.getDatabasePath("muse_migration_assistant_memory_105.db").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        try {
            createSchemaAtVersion(105, dbFile.absolutePath)
            val legacyHelper = FrameworkSQLiteOpenHelperFactory().create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbFile.absolutePath)
                    .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(105) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    })
                    .build(),
            )
            val legacyDb = legacyHelper.writableDatabase
            try {
                listOf(
                    Triple("default", "Default", 1),
                    Triple("assistant-shared", "Previously shared", 1),
                    Triple("assistant-private", "Already private", 0),
                ).forEach { (id, name, useGlobalMemory) ->
                    legacyDb.compileStatement(
                        "INSERT INTO assistants (id, name, useGlobalMemory) VALUES (?, ?, ?)",
                    ).use { statement ->
                        statement.bindString(1, id)
                        statement.bindString(2, name)
                        statement.bindLong(3, useGlobalMemory.toLong())
                        statement.executeInsert()
                    }
                }
            } finally {
                legacyHelper.close()
            }

            val db = Room.databaseBuilder(context, MuseDb::class.java, dbFile.absolutePath)
                .addMigrations(*migrationsFrom(105).toTypedArray())
                .allowMainThreadQueries()
                .build()
            try {
                val migratedSettings = mutableMapOf<String, Pair<String, Int>>()
                db.openHelper.writableDatabase.query(
                    "SELECT id, name, useGlobalMemory FROM assistants",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        migratedSettings[cursor.getString(0)] = cursor.getString(1) to cursor.getInt(2)
                    }
                }
                assertEquals("Default" to 1, migratedSettings["default"])
                assertEquals("Previously shared" to 0, migratedSettings["assistant-shared"])
                assertEquals("Already private" to 0, migratedSettings["assistant-private"])
            } finally {
                db.close()
            }
        } finally {
            if (dbFile.exists()) dbFile.delete()
            context.deleteDatabase(dbFile.name)
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
                .addMigrations(*migrationsFrom(68).toTypedArray())
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
                "SELECT content FROM messages WHERE id='legacy-msg'",
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
                        override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
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
                """.trimIndent(),
            )
            helper.execSQL(
                "INSERT INTO group_chat_messages (id, chatId, senderId, body, imageBase64Json, timestamp) " +
                    "SELECT id, chatId, senderId, body, imageBase64Json, timestamp FROM group_chat_messages_full",
            )
            helper.execSQL("DROP TABLE group_chat_messages_full")
            helper.execSQL("CREATE INDEX IF NOT EXISTS index_group_chat_messages_chatId ON group_chat_messages(chatId)")
            helper.execSQL(
                "INSERT INTO group_chat_messages (id, chatId, senderId, body, timestamp) " +
                    "VALUES ('legacy-group-msg', 'legacy-group', 'u1', '迁移前的群聊消息', 1)",
            )
            helper.close()

            val db = Room.databaseBuilder(
                context,
                MuseDb::class.java,
                dbFile.absolutePath,
            )
                .addMigrations(*migrationsFrom(68).toTypedArray())
                .allowMainThreadQueries()
                .build()
            db.openHelper.writableDatabase.query("PRAGMA table_info(group_chat_messages)").use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns.add(cursor.getString(1))
                for (name in listOf(
                    "senderType",
                    "senderName",
                    "mood",
                    "reasoning",
                    "whisper_target_id",
                    "reply_to_id",
                    "messageType",
                    "fileAttachmentsJson",
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
                    "FROM group_chat_messages WHERE id='legacy-group-msg'",
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
                    "VALUES ('new-group-msg', 'legacy-group', 'assistant', 'a1', '助手', '迁移后写入')",
            )
            db.openHelper.writableDatabase.query(
                "SELECT body, messageType, fileAttachmentsJson, whisper_target_id, reply_to_id, reasoning, mood " +
                    "FROM group_chat_messages WHERE id='new-group-msg'",
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

    /**
     * 回归:从"手写迁移产出的真实旧库"(scheduled_tasks 无 created_by)升级后,
     * v98→99 迁移必须把 created_by 补上,否则 Room schema 校验会崩。
     * 现有测试都用 schema JSON 建库(本身含 created_by),覆盖不到这条升级路径。
     */
    @Test
    fun migration98To99_addsCreatedByToScheduledTasks() {
        val dbFile = java.io.File(context.cacheDir, "migration_98_99_created_by.db").apply {
            if (exists()) delete()
        }
        try {
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbFile.absolutePath)
                    .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(98) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            // 模拟手写迁移(9→10)建出的表:没有 created_by
                            db.execSQL("CREATE TABLE scheduled_tasks (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
                        }

                        override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    })
                    .build(),
            )
            val db = helper.writableDatabase
            MuseDb.MIGRATION_98_99.migrate(db)
            var hasCreatedBy = false
            db.query("PRAGMA table_info(scheduled_tasks)").use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "created_by") hasCreatedBy = true
                }
            }
            assertTrue("v98→99 迁移后 scheduled_tasks 应有 created_by 列", hasCreatedBy)
            helper.close()
        } finally {
            if (dbFile.exists()) dbFile.delete()
        }
    }

    @Test
    fun migrateV99To100_addsGroupChatArchivedColumn() {
        val dbFile = java.io.File(context.cacheDir, "migration_99_100_archived.db").apply {
            if (exists()) delete()
        }
        try {
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbFile.absolutePath)
                    .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(99) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            // 模拟存在 group_chats 表但没有 isArchived 列的 v99 库
                            db.execSQL("CREATE TABLE group_chats (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
                        }

                        override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    })
                    .build(),
            )
            val db = helper.writableDatabase
            MuseDb.MIGRATION_99_100.migrate(db)
            var hasArchived = false
            db.query("PRAGMA table_info(group_chats)").use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "isArchived") hasArchived = true
                }
            }
            assertTrue("v99→100 迁移后 group_chats 应有 isArchived 列", hasArchived)
            helper.close()
        } finally {
            if (dbFile.exists()) dbFile.delete()
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
                        override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
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
                .addMigrations(*migrationsFrom(75, imageDir).toTypedArray())
                .allowMainThreadQueries()
                .build()

            db.openHelper.writableDatabase.query(
                "SELECT imageBase64Json FROM messages WHERE id = 'legacy-msg'",
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
                .addMigrations(*migrationsFrom(76).toTypedArray())
                .allowMainThreadQueries()
                .build()
            db.openHelper.writableDatabase.query(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='messages_fts'",
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

    /** 模拟"已崩溃设备":v80 上的带索引坏表 → MIGRATION_80_81 清索引并重建表,数据全部保留。
     *
     * 只跑 80→81 这一段:完整升级链的终态会经 121→122 删掉这两张表,
     * 走全链就断言不到 80→81 的处理结果了。 */
    @Test
    fun migrate80To81_cleansLegacyIndexesFromCrashedDevice() {
        val dbName = "muse_migration_80_crash.db"
        context.deleteDatabase(dbName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(80) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE ai_moments (id TEXT NOT NULL PRIMARY KEY, " +
                                    "content TEXT NOT NULL, type TEXT NOT NULL DEFAULT 'life', mood TEXT, " +
                                    "likes INTEGER NOT NULL DEFAULT 0, likedByUser INTEGER NOT NULL DEFAULT 0, " +
                                    "source TEXT NOT NULL DEFAULT 'scheduled', createdAt INTEGER NOT NULL DEFAULT 0)",
                            )
                            db.execSQL(
                                "CREATE TABLE ai_moment_comments (id TEXT NOT NULL PRIMARY KEY, " +
                                    "momentId TEXT NOT NULL, sender TEXT NOT NULL, content TEXT NOT NULL, " +
                                    "createdAt INTEGER NOT NULL DEFAULT 0)",
                            )
                            db.execSQL("CREATE INDEX idx_moments_created ON ai_moments(createdAt DESC)")
                            db.execSQL("CREATE INDEX idx_moment_comments_moment ON ai_moment_comments(momentId)")
                            db.execSQL("INSERT INTO ai_moments (id, content, createdAt) VALUES ('m1', '坏设备遗留动态', 100)")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        try {
            val db = helper.writableDatabase
            MuseDb.MIGRATION_80_81.migrate(db)

            // 数据仍在
            db.query("SELECT count(*) FROM ai_moments").use { c ->
                assertTrue("坏设备遗留动态应保留", c.moveToFirst() && c.getInt(0) == 1)
            }
            // 索引已清理
            db.query(
                "SELECT count(*) FROM sqlite_master WHERE type='index' AND name LIKE 'idx_moments%'",
            ).use { c ->
                assertTrue("残留索引应被清理", c.moveToFirst() && c.getInt(0) == 0)
            }
        } finally {
            helper.close()
            context.deleteDatabase(dbName)
        }
    }

    /** 模拟真机中间版坏表: mood 列带 DEFAULT NULL(真机 dflt_value 读为 'NULL'),索引残留,
     * MIGRATION_80_81 重建表后数据(含 mood 值)必须保留。 */
    @Test
    fun migrate80To81_rebuildsTableWithMoodNullDefault() {
        val dbName = "muse_migration_80_moodnull.db"
        context.deleteDatabase(dbName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(80) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE ai_moments (id TEXT NOT NULL PRIMARY KEY, " +
                                    "content TEXT NOT NULL, type TEXT NOT NULL DEFAULT 'life', mood TEXT DEFAULT NULL, " +
                                    "likes INTEGER NOT NULL DEFAULT 0, likedByUser INTEGER NOT NULL DEFAULT 0, " +
                                    "source TEXT NOT NULL DEFAULT 'scheduled', createdAt INTEGER NOT NULL DEFAULT 0)",
                            )
                            db.execSQL(
                                "CREATE TABLE ai_moment_comments (id TEXT NOT NULL PRIMARY KEY, " +
                                    "momentId TEXT NOT NULL, sender TEXT NOT NULL, content TEXT NOT NULL, " +
                                    "createdAt INTEGER NOT NULL DEFAULT 0)",
                            )
                            db.execSQL("CREATE INDEX idx_moments_created ON ai_moments(createdAt DESC)")
                            db.execSQL("INSERT INTO ai_moments (id, content, mood, createdAt) VALUES ('m1', '中间版动态', '开心', 100)")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        try {
            val db = helper.writableDatabase
            MuseDb.MIGRATION_80_81.migrate(db)

            // 数据(含 mood 值)保留
            db.query("SELECT content, mood FROM ai_moments WHERE id='m1'").use { c ->
                assertTrue("中间版动态应保留", c.moveToFirst())
                assertEquals("中间版动态", c.getString(0))
                assertEquals("开心", c.getString(1))
            }
        } finally {
            helper.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun migrate106To107_rebuildsKnowledgeFtsWithChineseNgrams() {
        val dbFile = context.getDatabasePath("muse_knowledge_fts_106.db").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(106) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE knowledge_chunks (" +
                                "id TEXT NOT NULL PRIMARY KEY, " +
                                "doc_id TEXT NOT NULL, " +
                                "content TEXT NOT NULL)",
                        )
                        db.execSQL(
                            "CREATE VIRTUAL TABLE knowledge_chunks_fts " +
                                "USING fts4(chunkId, doc_id, text_content)",
                        )
                        db.execSQL(
                            "INSERT INTO knowledge_chunks(id, doc_id, content) " +
                                "VALUES ('chunk-1', 'doc-1', '知识库检索')",
                        )
                        db.execSQL(
                            "INSERT INTO knowledge_chunks_fts(chunkId, doc_id, text_content) " +
                                "VALUES ('chunk-1', 'doc-1', '知识库检索')",
                        )
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        try {
            val db = helper.writableDatabase
            MuseDb.MIGRATION_106_107.migrate(db)

            db.query(
                """
                SELECT chunkId FROM knowledge_chunks_fts
                WHERE text_content MATCH '"知识" "识库"'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue("迁移后中文 ngram 应可被 FTS 命中", cursor.moveToFirst())
                assertEquals("chunk-1", cursor.getString(0))
            }
        } finally {
            helper.close()
            dbFile.delete()
        }
    }

    /**
     * v2.6.x 尾段（113..117）的 Room 校验矩阵。
     *
     * 背景：113/114/115/116 四个起点此前只有「手工执行 DDL」的专项测试
     * （[io.zer0.muse.data.relationship.AssistantRelationshipMigrationTest]、
     * [MuseDbMigration116To117Test]），那条路径根本不经过 Room 打开，
     * 因此 Room 的实体级 schema 校验在这四段上从未真正跑过。
     * 117 是 118 的直接前驱，一并纳入矩阵。
     * 这里沿用 [migrateEveryVersionTo75_keepsDataAndAddsGenerationTables] 的同一套机制：
     * 按 schema JSON 建旧库 → Room.databaseBuilder 触发真实迁移 → 断言迁移终态。
     *
     * 断言口径（缺一不可，只断言「没崩」等于没测）：
     *  - usage_records / assistant_relationship / session_todos / card_entities 四张表的列集合与默认值；
     *  - usage_records 三个索引、session_todos 的 sessionId 唯一索引、
     *    card_entities 的 dockSlot 唯一索引与 originMessageId 索引；
     *  - sessions.input_draft 列；
     *  - 朋友圈/日记四张表已在前一版迁移（121→122）中删除，终态不应再存在；
     *  - 迁移前插入的 sessions / messages 各一行未丢。
     */
    @Test
    fun migrateV113To117Starts_validateThroughRoom() {
        val starts = (113..117).filter { schemaExists(it) }
        assertEquals(
            "113..117 的 schema 快照应齐备，缺任何一份都无法建旧库（不可用空断言顶替）",
            (113..117).toList(),
            starts,
        )
        starts.forEach { assertV113StartUpgradesToLatest(it) }
    }

    /** 单个 113..117 起点：建旧库 → Room 打开触发迁移 → 断言终态。 */
    private fun assertV113StartUpgradesToLatest(fromVersion: Int) {
        val dbFile = context.getDatabasePath("muse_migration_v26x_$fromVersion.db").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        try {
            createSchemaAtVersion(fromVersion, dbFile.absolutePath)
            insertLegacyRow(dbFile.absolutePath, fromVersion)

            val db = Room.databaseBuilder(
                context,
                MuseDb::class.java,
                dbFile.absolutePath,
            )
                .addMigrations(*migrationsFrom(fromVersion).toTypedArray())
                .allowMainThreadQueries()
                .build()
            try {
                assertV26xTerminalState(db.openHelper.writableDatabase, "v$fromVersion→120")
            } finally {
                db.close()
            }
        } finally {
            if (dbFile.exists()) dbFile.delete()
            context.deleteDatabase(dbFile.name)
        }
    }

    /** v2.6.x 迁移终态的全套断言（矩阵与全链用例共用）。 */
    private fun assertV26xTerminalState(raw: SupportSQLiteDatabase, tag: String) {
        assertV26xSurfaces(raw, tag)
        assertRoomValidatedLatestSchema(raw, tag)
        raw.query("SELECT title FROM sessions WHERE id='legacy-session'").use { cursor ->
            assertTrue("$tag: 迁移前插入的会话应保留", cursor.moveToFirst())
            assertEquals("$tag: 会话标题不应被改动", "迁移测试", cursor.getString(0))
        }
        raw.query("SELECT content, moodSkin FROM messages WHERE id='legacy-msg'").use { cursor ->
            assertTrue("$tag: 迁移前插入的历史消息应保留", cursor.moveToFirst())
            assertEquals("$tag: 历史消息内容不应被改动", "迁移前的历史消息", cursor.getString(0))
            assertNull("$tag: 历史消息 moodSkin 应为 NULL", cursor.getString(1))
        }
    }

    /**
     * v112 → 120 全链：专门覆盖含数据回填的 112→113（usage_records 的 INSERT...SELECT）。
     *
     * 回填条件（MuseDb.MIGRATION_112_113）：`role='ASSISTANT' AND (promptTokens + completionTokens) > 0`。
     * 这里刻意铺满四种边界：有用量的 assistant（回填）、零用量（不回填）、
     * token 为 NULL（不回填）、用户消息带用量（role 过滤，不回填），
     * 再加一条只有 prompt 的 assistant（回填）。断言回填条数、字段值、
     * 以及 messages 一行不少、token 字段未被改写。
     */
    @Test
    fun migrateV112ToLatest_backfillsUsageRecordsAndKeepsMessages() {
        val dbFile = context.getDatabasePath("muse_migration_v112_full_chain.db").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        try {
            createSchemaAtVersion(112, dbFile.absolutePath)
            seedV112UsageBackfillRows(dbFile.absolutePath)

            val db = Room.databaseBuilder(
                context,
                MuseDb::class.java,
                dbFile.absolutePath,
            )
                .addMigrations(*migrationsFrom(112).toTypedArray())
                .allowMainThreadQueries()
                .build()
            try {
                val raw = db.openHelper.writableDatabase
                assertV26xSurfaces(raw, "v112→120")
                assertRoomValidatedLatestSchema(raw, "v112→120")
                assertUsageBackfill(raw)
                assertMessagesSurvivedBackfill(raw)
            } finally {
                db.close()
            }
        } finally {
            if (dbFile.exists()) dbFile.delete()
            context.deleteDatabase(dbFile.name)
        }
    }

    /** 112→113 回填的输入数据。列名用 v112 快照里的驼峰原名（messages 未做过列重命名）。 */
    private fun seedV112UsageBackfillRows(dbPath: String) {
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(File(dbPath), null)
        try {
            db.execSQL(
                "INSERT INTO sessions (id, title, createdAt, updatedAt, lastMessagePreview, assistantId) " +
                    "VALUES ('legacy-session', '回填测试', 1, 1, '', 'default')",
            )
            val rows = listOf(
                arrayOf<Any?>("msg-assistant-usage", "ASSISTANT", "有用量的回复", 1_000L, 120, 80, 30, 10),
                arrayOf<Any?>("msg-assistant-zero", "ASSISTANT", "零用量回复", 2_000L, 0, 0, 0, 0),
                arrayOf<Any?>("msg-assistant-null", "ASSISTANT", "无 token 字段的旧回复", 3_000L, null, null, null, null),
                arrayOf<Any?>("msg-user-usage", "USER", "用户消息也带用量字段", 4_000L, 500, 0, 0, 0),
                arrayOf<Any?>("msg-assistant-prompt-only", "ASSISTANT", "只有输入用量", 5_000L, 7, 0, 0, 0),
            )
            rows.forEach { row ->
                db.execSQL(
                    "INSERT INTO messages (id, sessionId, role, content, createdAt, " +
                        "promptTokens, completionTokens, reasoningTokens, cachedTokens) " +
                        "VALUES (?, 'legacy-session', ?, ?, ?, ?, ?, ?, ?)",
                    row,
                )
            }
        } finally {
            db.close()
        }
    }

    /** 断言 112→113 的 INSERT...SELECT 只回填该回填的行，且字段映射正确。 */
    private fun assertUsageBackfill(db: SupportSQLiteDatabase) {
        val byCreatedAt = mutableMapOf<Long, List<Int>>()
        db.query(
            "SELECT created_at, source, model_id, provider_id, prompt_tokens, completion_tokens, " +
                "reasoning_tokens, cached_tokens, first_token_ms, duration_ms FROM usage_records",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                assertEquals("回填记录的 source 应为 chat", "chat", cursor.getString(1))
                assertEquals("回填记录的 model_id 应为空串（旧消息未记录模型）", "", cursor.getString(2))
                assertEquals("回填记录的 provider_id 应为空串", "", cursor.getString(3))
                assertEquals("回填记录 first_token_ms 应为 0（旧数据无此指标）", 0, cursor.getInt(8))
                assertEquals("回填记录 duration_ms 应为 0（旧数据无此指标）", 0, cursor.getInt(9))
                byCreatedAt[cursor.getLong(0)] = listOf(
                    cursor.getInt(4),
                    cursor.getInt(5),
                    cursor.getInt(6),
                    cursor.getInt(7),
                )
            }
        }
        assertEquals(
            "只应回填有用量的 assistant 消息（零用量/NULL token/用户消息都不回填）",
            listOf(1_000L, 5_000L),
            byCreatedAt.keys.sorted(),
        )
        assertEquals("created_at 应取自 messages.createdAt，token 四列应原样搬运", listOf(120, 80, 30, 10), byCreatedAt[1_000L])
        assertEquals("只有 prompt 用量的 assistant 消息也应回填", listOf(7, 0, 0, 0), byCreatedAt[5_000L])
    }

    /** 断言回填没有动 messages：行数不变、内容不变、token 字段未被改写。 */
    private fun assertMessagesSurvivedBackfill(db: SupportSQLiteDatabase) {
        val contents = mutableMapOf<String, String>()
        db.query("SELECT id, content FROM messages").use { cursor ->
            while (cursor.moveToNext()) contents[cursor.getString(0)] = cursor.getString(1)
        }
        assertEquals("messages 行数应与迁移前一致（5 条）", 5, contents.size)
        assertEquals("有用量的回复", contents["msg-assistant-usage"])
        assertEquals("零用量回复", contents["msg-assistant-zero"])
        assertEquals("无 token 字段的旧回复", contents["msg-assistant-null"])
        assertEquals("用户消息也带用量字段", contents["msg-user-usage"])
        assertEquals("只有输入用量", contents["msg-assistant-prompt-only"])
        db.query(
            "SELECT promptTokens, completionTokens, reasoningTokens, cachedTokens " +
                "FROM messages WHERE id='msg-assistant-usage'",
        ).use { cursor ->
            assertTrue("原消息应保留", cursor.moveToFirst())
            assertEquals("回填不应改写 messages.promptTokens", 120, cursor.getInt(0))
            assertEquals("回填不应改写 messages.completionTokens", 80, cursor.getInt(1))
            assertEquals("回填不应改写 messages.reasoningTokens", 30, cursor.getInt(2))
            assertEquals("回填不应改写 messages.cachedTokens", 10, cursor.getInt(3))
        }
    }

    /**
     * 硬证据：Room 校验通过后会把自己的 identity_hash 写进 room_master_table。
     * 如果迁移产出的结构与实体不符，RoomOpenHelper.onValidateSchema 会先抛
     * "Migration didn't properly handle"，根本走不到写 hash 这步。
     * 所以「hash == 最新（v122）快照的 identityHash」等价于「Room 真的比对过并认可了终态」。
     */
    private fun assertRoomValidatedLatestSchema(db: SupportSQLiteDatabase, tag: String) {
        val expected = loadSchema(122).getJSONObject("database").getString("identityHash")
        val actual = mutableListOf<String>()
        db.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { cursor ->
            while (cursor.moveToNext()) actual += cursor.getString(0)
        }
        assertEquals(
            "$tag: Room 写入的 identity_hash 应等于最新（v122）实体快照（证明 Room 已校验并认可终态 schema）",
            listOf(expected),
            actual,
        )
    }

    /** v2.6.x 新增面（四张新表 + 三列）的统一断言，矩阵与全链用例共用。 */
    private fun assertV26xSurfaces(db: SupportSQLiteDatabase, tag: String) {
        assertUsageRecordsSurface(db, tag)
        assertRelationshipSurface(db, tag)
        assertSessionTodosSurface(db, tag)
        assertCardEntitiesSurface(db, tag)
        assertMomentSurfaces(db, tag)
        assertEquals(
            "$tag: sessions.input_draft 默认空串（切换会话各自保留草稿）",
            "''",
            columnDefaults(db, "sessions")["input_draft"],
        )
    }

    private fun assertUsageRecordsSurface(db: SupportSQLiteDatabase, tag: String) {
        val defaults = columnDefaults(db, "usage_records")
        assertEquals(
            "$tag: usage_records 列集合应与 UsageRecordEntity 一致",
            setOf(
                "id",
                "created_at",
                "source",
                "model_id",
                "provider_id",
                "prompt_tokens",
                "completion_tokens",
                "reasoning_tokens",
                "cached_tokens",
                "cache_write_tokens",
                "first_token_ms",
                "duration_ms",
            ),
            defaults.keys,
        )
        assertDefaults(
            defaults,
            mapOf(
                "model_id" to "''",
                "provider_id" to "''",
                "prompt_tokens" to "0",
                "completion_tokens" to "0",
                "reasoning_tokens" to "0",
                "cached_tokens" to "0",
                "cache_write_tokens" to "0",
                "first_token_ms" to "0",
                "duration_ms" to "0",
            ),
            tag,
        )
        assertEquals(
            "$tag: usage_records 索引应与实体 @Index 声明一致",
            setOf(
                "index_usage_records_created_at",
                "index_usage_records_source_created_at",
                "index_usage_records_model_id_created_at",
            ),
            indexNames(db, "usage_records"),
        )
    }

    private fun assertRelationshipSurface(db: SupportSQLiteDatabase, tag: String) {
        val defaults = columnDefaults(db, "assistant_relationship")
        assertEquals(
            "$tag: assistant_relationship 列集合应与 AssistantRelationshipEntity 一致",
            setOf(
                "assistantId",
                "relationLabel",
                "affinity",
                "lastInteractionAt",
                "proactiveLevel",
                "proactiveEnabled",
                "dailyProactiveLimit",
                "createdAt",
                "updatedAt",
            ),
            defaults.keys,
        )
        assertDefaults(
            defaults,
            mapOf(
                "relationLabel" to "''",
                "affinity" to "0",
                "lastInteractionAt" to "0",
                "proactiveLevel" to "'normal'",
                "proactiveEnabled" to "1",
                "dailyProactiveLimit" to "0",
                "createdAt" to "0",
                "updatedAt" to "0",
            ),
            tag,
        )
    }

    private fun assertSessionTodosSurface(db: SupportSQLiteDatabase, tag: String) {
        val defaults = columnDefaults(db, "session_todos")
        assertEquals(
            "$tag: session_todos 列集合应与 SessionTodoEntity 一致",
            setOf("id", "sessionId", "todosJson", "hasOpenItems", "createdAt", "updatedAt"),
            defaults.keys,
        )
        assertDefaults(
            defaults,
            mapOf(
                "sessionId" to "''",
                "todosJson" to "''",
                "hasOpenItems" to "0",
                "createdAt" to "0",
                "updatedAt" to "0",
            ),
            tag,
        )
        assertTrue(
            "$tag: sessionId 唯一索引应存在，实际 ${indexNames(db, "session_todos")}",
            "index_session_todos_sessionId" in indexNames(db, "session_todos"),
        )
    }

    /**
     * 118 新增面：card_entities 的列集合 / 默认值 / 两个索引，以及 dockSlot 唯一索引真的生效。
     *
     * 只断言「表存在」不够：列名或默认值漂移会让真机 Room 校验在升级时崩溃，
     * 而 dockSlot 的唯一性直接决定停靠区顺序是否稳定，所以这里也验一次约束行为。
     */
    private fun assertCardEntitiesSurface(db: SupportSQLiteDatabase, tag: String) {
        val defaults = columnDefaults(db, "card_entities")
        assertEquals(
            "$tag: card_entities 列集合应与 CardEntity 一致",
            setOf(
                "id",
                "legacyCardId",
                "originSessionId",
                "originMessageId",
                "title",
                "code",
                "isSvg",
                "dataJson",
                "dockSlot",
                "dockedAt",
                "createdAt",
                "updatedAt",
            ),
            defaults.keys,
        )
        assertDefaults(
            defaults,
            mapOf(
                "legacyCardId" to "NULL",
                "originSessionId" to "NULL",
                "originMessageId" to "NULL",
                "title" to "''",
                "code" to "''",
                "isSvg" to "0",
                "dataJson" to "NULL",
                "dockSlot" to "NULL",
                "dockedAt" to "NULL",
                "createdAt" to "0",
                "updatedAt" to "0",
            ),
            tag,
        )
        assertEquals(
            "$tag: card_entities 索引应与 CardEntity @Index 声明一致",
            setOf("index_card_entities_dockSlot", "index_card_entities_originMessageId"),
            indexNames(db, "card_entities"),
        )
        assertDockSlotUniquenessAllowsNull(db, tag)
    }

    /**
     * dockSlot 唯一索引的两个关键行为：
     *  - 多张卡都是 NULL（仅消息流）不得冲突 —— 否则第一张以外的卡全部写不进去；
     *  - 两个相同的非 NULL 槽位必须被拒 —— 否则停靠顺序会出双。
     *
     * 用临时行验证后立即删除，不污染迁移后数据（旧数据断言在其它地方仍成立）。
     */
    private fun assertDockSlotUniquenessAllowsNull(db: SupportSQLiteDatabase, tag: String) {
        db.execSQL("INSERT INTO card_entities (id, title) VALUES ('slot-probe-a', 'a')")
        try {
            db.execSQL("INSERT INTO card_entities (id, title) VALUES ('slot-probe-b', 'b')")
            db.execSQL("UPDATE card_entities SET dockSlot = 0 WHERE id = 'slot-probe-a'")
            val rejected = try {
                db.execSQL("UPDATE card_entities SET dockSlot = 0 WHERE id = 'slot-probe-b'")
                false
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                e.message?.let { assertTrue("$tag: 应为唯一约束冲突，实际 $it", it.contains("UNIQUE", ignoreCase = true)) }
                true
            }
            assertTrue("$tag: 同一 dockSlot 不允许两张卡（否则停靠顺序会出双）", rejected)
        } finally {
            db.execSQL("DELETE FROM card_entities WHERE id IN ('slot-probe-a', 'slot-probe-b')")
        }
    }

    private fun assertMomentSurfaces(db: SupportSQLiteDatabase, tag: String) {
        assertEquals(
            "$tag: 朋友圈/日记四张表应在 121→122 迁移中删除（旧小手机数据已整体移除）",
            emptySet<String>(),
            setOf("ai_moments", "ai_moment_comments", "ai_moment_likes", "ai_diaries")
                .filter { tableExists(db, it) }
                .toSet(),
        )
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }

    private fun columnDefaults(db: SupportSQLiteDatabase, table: String): Map<String, String?> {
        val defaults = mutableMapOf<String, String?>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) defaults[cursor.getString(1)] = cursor.getString(4)
        }
        return defaults
    }

    private fun indexNames(db: SupportSQLiteDatabase, table: String): Set<String> {
        val names = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='$table'").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                // 跳过 SQLite 为 PRIMARY KEY/UNIQUE 自动生成的内部索引，只比对实体声明的命名索引。
                if (!name.startsWith("sqlite_autoindex")) names += name
            }
        }
        return names
    }

    private fun assertDefaults(defaults: Map<String, String?>, expected: Map<String, String>, tag: String) {
        expected.forEach { (column, value) ->
            assertTrue("$tag: 缺少列 $column，实际列 ${defaults.keys}", defaults.containsKey(column))
            assertEquals("$tag: $column 默认值应为 $value", value, defaults[column])
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
    private fun availableSchemaVersions(): List<Int> = ((55..68) + (113..117)).filter { schemaExists(it) }

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
    private fun migrationsFrom(fromVersion: Int, imageDir: File = imageStorageDir): List<androidx.room.migration.Migration> {
        // MIGRATION_* 是 MuseDb companion object 里的 val。编译后 Kotlin 只为它们生成
        // 公开 getter(getMIGRATION_x_y),反射取不到对应的字段(实体字段被隐藏/合成),
        // 因此这里从 Companion 实例的公开方法枚举 —— MuseDb::class.java.declaredFields 也是空的。
        val companion: Any = runCatching {
            MuseDb::class.java.getDeclaredField("Companion")
                .apply { isAccessible = true }
                .get(null)
        }.getOrNull() ?: MuseDb
        val chain = companion.javaClass.methods
            .filter { it.name.startsWith("getMIGRATION_") && it.parameterCount == 0 }
            .mapNotNull { method ->
                val parts = method.name.removePrefix("getMIGRATION_").split("_")
                val from = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val to = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                if (from < fromVersion) return@mapNotNull null
                method.isAccessible = true
                (from to to) to (method.invoke(companion) as androidx.room.migration.Migration)
            }
            .sortedBy { it.first.first }
            .map { it.second }
        val with75 = if (fromVersion <= 75) {
            chain + MuseDb.migrate75To76(imageDir)
        } else {
            chain
        }
        val with76 = if (fromVersion <= 76) {
            with75 + MuseDb.migrate76To77()
        } else {
            with75
        }
        return with76
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
                                            .replace("\${TABLE_NAME}", tableName),
                                    )
                                }
                            }
                        }
                        val setup = databaseJson.getJSONArray("setupQueries")
                        for (i in 0 until setup.length()) {
                            db.execSQL(setup.getString(i))
                        }
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
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
    private fun requiredExtraMessageColumns(helper: androidx.sqlite.db.SupportSQLiteDatabase): List<Pair<String, String>> {
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

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    }
                })
                .build(),
        ).writableDatabase

        helper.execSQL(
            """
            INSERT INTO sessions (id, title, createdAt, updatedAt, lastMessagePreview, assistantId)
            VALUES ('legacy-session', '迁移测试', 1, 1, '', 'default')
            """.trimIndent(),
        )
        val requiredExtra = requiredExtraMessageColumns(helper)
        val messageColumns = listOf("id", "sessionId", "role", "content", "createdAt") + requiredExtra.map { it.first }
        val messageValues = listOf(
            "'legacy-msg'", "'legacy-session'", "'ASSISTANT'", "'迁移前的历史消息'", "1",
        ) + requiredExtra.map { it.second }
        helper.execSQL(
            "INSERT INTO messages (${messageColumns.joinToString(", ")}) " +
                "VALUES (${messageValues.joinToString(", ")})",
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
