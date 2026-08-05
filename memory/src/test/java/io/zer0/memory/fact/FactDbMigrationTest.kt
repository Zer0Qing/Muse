package io.zer0.memory.fact

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Phase 2.2: FactDb 迁移测试范例。
 *
 * 验证 [FactDb.MIGRATION_7_8] 的行为:
 *  - 历史数据迁移后 scope 字段默认为 "main"(向后兼容)
 *  - scope 索引存在
 *  - 已有列内容迁移后完整保留
 *
 * 推进到 v9+ 时,可按本文件追加 migrate8To9() 用例。
 *
 * 运行方式: `./gradlew :memory:testDebugUnitTest --tests "*FactDbMigrationTest*"`
 *
 * 实现说明 (2026-07-28 修复):
 *  原 MigrationTestHelper 在 Room 2.8 + Robolectric 下抛 IllegalArgumentException
 *  ("driver configured to open 'X' but 'path/X' was requested",issue 325404127)。
 *  改用直接 FrameworkSQLiteOpenHelper 创建 v7 schema 数据库,然后通过
 *  Room.databaseBuilder + addMigrations 触发迁移并验证数据。
 *
 *  v7 schema 严格对齐 memory/schemas/io.zer0.memory.fact.FactDb/7.json:
 *  - id INTEGER PRIMARY KEY AUTOINCREMENT
 *  - tags / importance / category / confidence / source / last_hit_at 均带 DEFAULT
 *  - 索引名: idx_facts_time / idx_facts_session / idx_facts_importance / idx_facts_category
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33]) // Robolectric: 用 API 33 的 SQLite 实现,兼容 minSdk 26
class FactDbMigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * v7 schema 的 CREATE TABLE — 严格对齐 7.json 中的 createSql。
     * 注意:id 是 AUTOINCREMENT,tags/importance/category/confidence/source/last_hit_at 都有 DEFAULT。
     */
    private val V7_CREATE_FACTS = """
        CREATE TABLE IF NOT EXISTS `facts` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `fact` TEXT NOT NULL,
            `tags` TEXT NOT NULL DEFAULT '[]',
            `time` TEXT,
            `session_id` TEXT,
            `created_at` TEXT NOT NULL,
            `importance` INTEGER NOT NULL DEFAULT 0,
            `category` TEXT NOT NULL DEFAULT 'general',
            `confidence` REAL NOT NULL DEFAULT 1.0,
            `source` TEXT NOT NULL DEFAULT 'inferred',
            `expires_at` TEXT,
            `last_confirmed_at` TEXT,
            `last_hit_at` TEXT DEFAULT NULL
        )
    """.trimIndent()

    /**
     * v7 schema 的 FTS4 表 — 对齐 7.json:
     *   CREATE VIRTUAL TABLE ... USING FTS4(`fact_id` INTEGER NOT NULL, `content_ngram` TEXT NOT NULL)
     */
    private val V7_CREATE_FTS = """
        CREATE VIRTUAL TABLE IF NOT EXISTS `facts_fts` USING FTS4(
            `fact_id` INTEGER NOT NULL,
            `content_ngram` TEXT NOT NULL
        )
    """.trimIndent()

    /**
     * v7 schema 的 4 个索引 — 对齐 7.json 中的 indices 字段。
     */
    private val V7_INDICES = listOf(
        "CREATE INDEX IF NOT EXISTS `idx_facts_time` ON `facts` (`time`)",
        "CREATE INDEX IF NOT EXISTS `idx_facts_session` ON `facts` (`session_id`)",
        "CREATE INDEX IF NOT EXISTS `idx_facts_importance` ON `facts` (`importance`)",
        "CREATE INDEX IF NOT EXISTS `idx_facts_category` ON `facts` (`category`)",
    )

    @Test
    fun migrate7To8_addsScopeColumnWithDefaultMain() {
        val dbFile = context.getDatabasePath(DB_NAME).apply { parentFile?.mkdirs() }
        if (dbFile.exists()) dbFile.delete()
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            configuration = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(V7_CREATE_FACTS)
                        db.execSQL(V7_CREATE_FTS)
                        V7_INDICES.forEach { db.execSQL(it) }
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) {
                        // 由 Room migration 接管
                    }
                })
                .build(),
        ).writableDatabase

        helper.execSQL(
            """
            INSERT INTO facts (
                id, fact, tags, time, session_id, created_at,
                importance, category, confidence, source,
                expires_at, last_confirmed_at, last_hit_at
            ) VALUES (
                1, '用户偏好深色模式', '["preference"]', NULL, NULL, '2026-07-28T00:00:00Z',
                1, 'preference', 0.9, 'user_explicit',
                NULL, NULL, NULL
            )
            """.trimIndent()
        )
        helper.close()

        val db = Room.databaseBuilder(
            context,
            FactDb::class.java,
            dbFile.absolutePath,
        )
            // v1.0.56: 补全 7→10 迁移链(Room 需要完整路径;此前只注册 7_8,
            //   测试建库后实际要求迁到当前版本 10;v1.0.62 延续到 11)
            .addMigrations(
                FactDb.MIGRATION_7_8,
                FactDb.MIGRATION_8_9,
                FactDb.MIGRATION_9_10,
                FactDb.MIGRATION_10_11,
            )
            .allowMainThreadQueries()
            .build()

        db.openHelper.writableDatabase.query("SELECT scope FROM facts WHERE id = 1").use { cursor ->
            assertTrue("期望查询到 1 行迁移后的历史数据", cursor.moveToFirst())
            assertEquals("历史事实的 scope 应默认为 main", "main", cursor.getString(0))
            assertTrue("应只有 1 行", cursor.isLast)
        }

        db.openHelper.writableDatabase
            .query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_facts_scope'")
            .use { cursor ->
                assertTrue("index_facts_scope 索引应存在", cursor.moveToFirst())
            }

        db.close()
    }

    @Test
    fun migrate7To8_preservesExistingColumns() {
        val dbFile = context.getDatabasePath(DB_NAME).apply { parentFile?.mkdirs() }
        if (dbFile.exists()) dbFile.delete()
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            configuration = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(V7_CREATE_FACTS)
                        db.execSQL(V7_CREATE_FTS)
                        V7_INDICES.forEach { db.execSQL(it) }
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
            INSERT INTO facts (
                id, fact, tags, time, session_id, created_at,
                importance, category, confidence, source,
                expires_at, last_confirmed_at, last_hit_at
            ) VALUES (
                42, '生日: 1990-05-20', '["identity","event"]', '1990-05-20T00:00:00Z',
                'session-abc', '2026-07-28T10:00:00Z',
                2, 'identity', 1.0, 'user_explicit',
                '2027-07-28T00:00:00Z', '2026-07-28T11:00:00Z', '2026-07-28T12:00:00Z'
            )
            """.trimIndent()
        )
        helper.close()

        val db = Room.databaseBuilder(
            context,
            FactDb::class.java,
            dbFile.absolutePath,
        )
            // v1.0.56: 补全 7→10 迁移链(Room 需要完整路径)
            .addMigrations(
                FactDb.MIGRATION_7_8,
                FactDb.MIGRATION_8_9,
                FactDb.MIGRATION_9_10,
                FactDb.MIGRATION_10_11,
            )
            .allowMainThreadQueries()
            .build()

        db.openHelper.writableDatabase.query(
            """
            SELECT fact, importance, category, confidence, source, expires_at, last_confirmed_at, last_hit_at, scope
            FROM facts WHERE id = 42
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("生日: 1990-05-20", cursor.getString(0))
            assertEquals(2, cursor.getInt(1))
            assertEquals("identity", cursor.getString(2))
            assertEquals(1.0f, cursor.getFloat(3), 0.001f)
            assertEquals("user_explicit", cursor.getString(4))
            assertEquals("2027-07-28T00:00:00Z", cursor.getString(5))
            assertEquals("2026-07-28T11:00:00Z", cursor.getString(6))
            assertEquals("2026-07-28T12:00:00Z", cursor.getString(7))
            assertEquals("main", cursor.getString(8)) // 新增字段,默认值
        }

        db.close()
    }

    private companion object {
        const val DB_NAME = "facts_test.db"
    }
}
