package io.zer0.muse.data.agentbot

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.MuseDbMigrations
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v2.7.x: MuseDb 139 → 140 迁移契约（agent_watchers / agent_goals / cards 刷新字段）。
 *
 * 三条证据互相独立：
 *  1. 直接执行迁移 DDL，逐列核对新表与卡片新列的类型、NOT NULL 与默认值 ——
 *     列漂移会让真机 Room 校验报 "Migration didn't properly handle"；
 *  2. 用 139 版 schema JSON 建库后交给 Room 打开，核对 room_master_table 的
 *     identity_hash 等于 140 快照 —— 证明 Room 真的做过实体级校验；
 *  3. 状态常量与数据库锁值一致，避免 UI / 引擎用错字符串。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Wave2SchemaMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** 与 [MuseDbMigrations.MIGRATION_139_140] 完全一致的 DDL 通过真实迁移执行。 */
    @Test
    fun migration139To140_createsWatcherGoalTablesAndCardColumns() {
        val dbName = "migration_139_140_wave2.db"
        deleteDatabaseFiles(dbName)
        val dbFile = context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }
        createSchemaAtVersion(139, dbFile.absolutePath)
        val helper = openHelper(dbName, version = 139) { }
        try {
            val db = helper.writableDatabase
            db.execSQL(
                "INSERT INTO card_entities (id, title, code, isSvg, createdAt, updatedAt) " +
                    "VALUES ('card-legacy', '迁移前卡片', '<div/>', 0, 1, 2)",
            )

            MuseDbMigrations.MIGRATION_139_140.migrate(db)

            val watcherColumns = columnsOf(db, "agent_watchers")
            assertEquals(
                "列集合与顺序必须与 AgentWatcherEntity 完全一致",
                listOf(
                    "watcherId",
                    "botId",
                    "originSessionId",
                    "title",
                    "instruction",
                    "script",
                    "scriptKind",
                    "computeTarget",
                    "intervalMinutes",
                    "webhookUrl",
                    "remoteWatcherId",
                    "lastFingerprint",
                    "lastResult",
                    "lastCheckedAt",
                    "nextCheckAt",
                    "status",
                    "lastError",
                    "createdAt",
                    "updatedAt",
                ),
                watcherColumns.map { it.name },
            )
            watcherColumns.forEach { column ->
                assertEquals(
                    "agent_watchers.${column.name} 的类型/可空性必须与实体一致",
                    expectedWatcherTypeAndNullability(column.name),
                    "${column.type}|notnull=${column.notNull}",
                )
            }
            assertEquals(
                "agent_watchers 默认值必须与实体 @ColumnInfo/defaultValue 逐项一致",
                mapOf(
                    "botId" to "''",
                    "originSessionId" to "''",
                    "title" to "''",
                    "instruction" to "''",
                    "script" to "''",
                    "scriptKind" to "'python'",
                    "computeTarget" to "'local'",
                    "intervalMinutes" to "30",
                    "webhookUrl" to "''",
                    "remoteWatcherId" to "''",
                    "lastFingerprint" to "''",
                    "lastResult" to "''",
                    "lastCheckedAt" to "0",
                    "nextCheckAt" to "0",
                    "status" to "'ACTIVE'",
                    "lastError" to "''",
                    "createdAt" to "0",
                    "updatedAt" to "0",
                ),
                watcherColumns.filter { it.defaultValue != null }.associate { it.name to it.defaultValue },
            )

            val goalColumns = columnsOf(db, "agent_goals")
            assertEquals(
                "列集合与顺序必须与 AgentGoalEntity 完全一致",
                listOf(
                    "goalId",
                    "botId",
                    "originSessionId",
                    "title",
                    "description",
                    "milestonesJson",
                    "progress",
                    "status",
                    "dueAt",
                    "createdAt",
                    "updatedAt",
                ),
                goalColumns.map { it.name },
            )
            goalColumns.forEach { column ->
                assertEquals(
                    "agent_goals.${column.name} 的类型/可空性必须与实体一致",
                    expectedGoalTypeAndNullability(column.name),
                    "${column.type}|notnull=${column.notNull}",
                )
            }
            assertEquals(
                "agent_goals 默认值必须与实体 @ColumnInfo/defaultValue 逐项一致",
                mapOf(
                    "botId" to "''",
                    "originSessionId" to "''",
                    "title" to "''",
                    "description" to "''",
                    "milestonesJson" to "'[]'",
                    "progress" to "0",
                    "status" to "'ACTIVE'",
                    "dueAt" to "0",
                    "createdAt" to "0",
                    "updatedAt" to "0",
                ),
                goalColumns.filter { it.defaultValue != null }.associate { it.name to it.defaultValue },
            )

            val cardColumns = columnsOf(db, "card_entities")
            assertEquals(
                "卡片新列必须追加在既有列之后，顺序与 CardEntity 一致",
                listOf(
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
                    "spacePinned",
                    "spaceCategory",
                    "refreshIntervalMinutes",
                    "refreshPrompt",
                    "lastRefreshedAt",
                ),
                cardColumns.map { it.name },
            )
            assertEquals(
                "卡片新列默认值必须与实体一致",
                mapOf(
                    "spacePinned" to "0",
                    "spaceCategory" to "''",
                    "refreshIntervalMinutes" to "0",
                    "refreshPrompt" to "''",
                    "lastRefreshedAt" to "0",
                ),
                cardColumns.filter { it.name in setOf("spacePinned", "spaceCategory", "refreshIntervalMinutes", "refreshPrompt", "lastRefreshedAt") }
                    .associate { it.name to it.defaultValue },
            )

            // 存量卡片必须保留，并读出新列的默认值（未钉入、不自动刷新）。
            db.query(
                "SELECT title, spacePinned, spaceCategory, refreshIntervalMinutes, refreshPrompt, lastRefreshedAt " +
                    "FROM card_entities WHERE id = 'card-legacy'",
            ).use { cursor ->
                assertTrue("迁移不得丢失既有卡片", cursor.moveToFirst())
                assertEquals("迁移前卡片", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals("", cursor.getString(2))
                assertEquals(0, cursor.getInt(3))
                assertEquals("", cursor.getString(4))
                assertEquals(0L, cursor.getLong(5))
            }

            // 只写主键时全部默认值生效。
            db.execSQL("INSERT INTO agent_watchers (watcherId) VALUES ('watcher-minimal')")
            db.query(
                "SELECT botId, scriptKind, computeTarget, intervalMinutes, status, lastCheckedAt, nextCheckAt " +
                    "FROM agent_watchers WHERE watcherId = 'watcher-minimal'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("", cursor.getString(0))
                assertEquals("python", cursor.getString(1))
                assertEquals("local", cursor.getString(2))
                assertEquals(30, cursor.getInt(3))
                assertEquals("ACTIVE", cursor.getString(4))
                assertEquals(0L, cursor.getLong(5))
                assertEquals(0L, cursor.getLong(6))
            }
            db.execSQL("INSERT INTO agent_goals (goalId) VALUES ('goal-minimal')")
            db.query(
                "SELECT milestonesJson, progress, status, dueAt FROM agent_goals WHERE goalId = 'goal-minimal'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("[]", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals("ACTIVE", cursor.getString(2))
                assertEquals(0L, cursor.getLong(3))
            }

            // 邻居表与既有数据必须原样保留（只加表加列，不触碰别的表）。
            db.query("SELECT COUNT(*) FROM card_entities").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("既有卡片行不得丢失", 1, cursor.getInt(0))
            }
        } finally {
            helper.close()
            deleteDatabaseFiles(dbName)
        }
    }

    /** Room 实际打开 140，核对 identity_hash 与最新实体快照一致。 */
    @Test
    fun roomValidatesMigratedWave2Schema() {
        val dbName = "migration_139_140_room_validate.db"
        deleteDatabaseFiles(dbName)
        val dbFile = context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }
        createSchemaAtVersion(139, dbFile.absolutePath)

        val room = Room.databaseBuilder(context.applicationContext, MuseDb::class.java, dbName)
            .addMigrations(MuseDbMigrations.MIGRATION_139_140)
            .allowMainThreadQueries()
            .build()
        try {
            val db = room.openHelper.writableDatabase
            assertEquals("Room 必须把库升到 140", 140, db.version)
            assertEquals("LATEST_SCHEMA_VERSION 必须与迁移终点一致", 140, MuseDb.LATEST_SCHEMA_VERSION)
            val expected = loadSchema(140).getJSONObject("database").getString("identityHash")
            db.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { cursor ->
                assertTrue("Room 必须写入迁移后的 identity_hash", cursor.moveToFirst())
                assertEquals(
                    "identity_hash 必须等于 140 快照（证明 Room 已校验并通过全部实体）",
                    expected,
                    cursor.getString(0),
                )
            }
            listOf("agent_watchers", "agent_goals").forEach { table ->
                db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { cursor ->
                    assertTrue("Room 打开后 $table 必须存在", cursor.moveToFirst())
                    assertNotNull(cursor.getString(0))
                }
            }
            db.query("PRAGMA table_info(`card_entities`)").use { cursor ->
                val names = mutableSetOf<String>()
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) names += cursor.getString(nameIndex)
                assertTrue(
                    "Room 校验后的 card_entities 缺少 Spaces 刷新列: ${names - setOf("spacePinned", "spaceCategory", "refreshIntervalMinutes", "refreshPrompt", "lastRefreshedAt")}",
                    names.containsAll(
                        setOf(
                            "spacePinned",
                            "spaceCategory",
                            "refreshIntervalMinutes",
                            "refreshPrompt",
                            "lastRefreshedAt",
                        ),
                    ),
                )
            }
        } finally {
            room.close()
            deleteDatabaseFiles(dbName)
        }
    }

    /** 状态常量与数据库锁值保持一致，避免引擎/UI 用错字符串。 */
    @Test
    fun wireValuesMatchLockedSchema() {
        assertEquals(
            setOf("ACTIVE", "PAUSED", "ENDED", "ERROR"),
            AgentWatcherStatus.ALL.toSet(),
        )
        assertEquals(
            setOf("ACTIVE", "PAUSED", "ACHIEVED", "ABANDONED"),
            AgentGoalStatus.ALL.toSet(),
        )
        assertTrue(AgentWatcherStatus.isValid("ACTIVE"))
        assertTrue(!AgentWatcherStatus.isValid("UNKNOWN"))
        assertTrue(AgentGoalStatus.isValid("ACHIEVED"))
        assertTrue(!AgentGoalStatus.isValid("UNKNOWN"))
    }

    private data class ColumnInfo(
        val name: String,
        val type: String,
        val notNull: Int,
        val defaultValue: String?,
    )

    private fun columnsOf(db: SupportSQLiteDatabase, table: String): List<ColumnInfo> {
        val out = mutableListOf<ColumnInfo>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            while (cursor.moveToNext()) {
                out += ColumnInfo(
                    name = cursor.getString(nameIndex),
                    type = cursor.getString(typeIndex),
                    notNull = cursor.getInt(notNullIndex),
                    defaultValue = cursor.getString(defaultIndex),
                )
            }
        }
        return out
    }

    private fun expectedWatcherTypeAndNullability(column: String): String = when (column) {
        "intervalMinutes", "lastCheckedAt", "nextCheckAt", "createdAt", "updatedAt" -> "INTEGER|notnull=1"
        else -> "TEXT|notnull=1"
    }

    private fun expectedGoalTypeAndNullability(column: String): String = when (column) {
        "progress", "dueAt", "createdAt", "updatedAt" -> "INTEGER|notnull=1"
        else -> "TEXT|notnull=1"
    }

    private fun openHelper(
        dbName: String,
        version: Int,
        onCreate: (SupportSQLiteDatabase) -> Unit,
    ): SupportSQLiteOpenHelper {
        val dbFile = context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) = onCreate(db)

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
    }

    /** 用 139 快照还原全部实体表与 setupQueries，供 Room 迁移校验使用。 */
    private fun createSchemaAtVersion(version: Int, dbPath: String) {
        val databaseJson = loadSchema(version).getJSONObject("database")
        val entities = databaseJson.getJSONArray("entities")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbPath)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            for (i in 0 until entities.length()) {
                                val entity = entities.getJSONObject(i)
                                val tableName = entity.getString("tableName")
                                db.execSQL(
                                    entity.getString("createSql").replace("\${TABLE_NAME}", tableName),
                                )
                                if (entity.has("indices")) {
                                    val indices = entity.getJSONArray("indices")
                                    for (j in 0 until indices.length()) {
                                        db.execSQL(
                                            indices.getJSONObject(j).getString("createSql")
                                                .replace("\${TABLE_NAME}", tableName),
                                        )
                                    }
                                }
                            }
                            val setupQueries = databaseJson.getJSONArray("setupQueries")
                            for (i in 0 until setupQueries.length()) {
                                db.execSQL(setupQueries.getString(i))
                            }
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        helper.writableDatabase
        helper.close()
    }

    private fun loadSchema(version: Int): JSONObject = JSONObject(
        File("schemas/io.zer0.muse.data.session.MuseDb/$version.json").readText(),
    )

    private fun deleteDatabaseFiles(dbName: String) {
        val dbFile = context.getDatabasePath(dbName)
        listOf(dbFile, File("${dbFile.absolutePath}-wal"), File("${dbFile.absolutePath}-shm"))
            .forEach { if (it.exists()) it.delete() }
    }
}
