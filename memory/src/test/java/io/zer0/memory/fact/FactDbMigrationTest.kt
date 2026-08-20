package io.zer0.memory.fact

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

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
 *
 * R-DOC-01: 测试数据库改用 Files.createTempDirectory,避免在仓库根目录 cwd 泄漏 facts_test.db。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33]) // Robolectric: 用 API 33 的 SQLite 实现,兼容 minSdk 26
class FactDbMigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun newDbFile(): File {
        val dir = Files.createTempDirectory("facts-migration").toFile()
        tempDirs += dir
        return File(dir, DB_NAME)
    }

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
        val dbFile = newDbFile()
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
            // v12: 延续到 12(实体归一化键列)
            .addMigrations(
                FactDb.MIGRATION_7_8,
                FactDb.MIGRATION_8_9,
                FactDb.MIGRATION_9_10,
                FactDb.MIGRATION_10_11,
                FactDb.MIGRATION_11_12,
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
        val dbFile = newDbFile()
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
            // v12: 延续到 12(实体归一化键列)
            .addMigrations(
                FactDb.MIGRATION_7_8,
                FactDb.MIGRATION_8_9,
                FactDb.MIGRATION_9_10,
                FactDb.MIGRATION_10_11,
                FactDb.MIGRATION_11_12,
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

    /**
     * v12: 老用户从 v11 升级 — entity_key 列新增,历史数据完整保留。
     * 用户安装新版本后记忆数据必须正常: 现有事实不丢、字段不变、新列可空。
     */
    @Test
    fun migrate11To12_addsEntityKeyColumnPreservingData() {
        val dbFile = newDbFile()
        val factory = FrameworkSQLiteOpenHelperFactory()
        // v11 schema: facts + fts + 全部旧列(pinned_at 最后)
        val V11_CREATE_FACTS = """
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
                `last_hit_at` TEXT DEFAULT NULL,
                `scope` TEXT NOT NULL DEFAULT 'main',
                `space_id` TEXT NOT NULL DEFAULT 'default',
                `pinned_at` TEXT DEFAULT NULL
            )
        """.trimIndent()
        val V11_CREATE_FTS = """
            CREATE VIRTUAL TABLE IF NOT EXISTS `facts_fts` USING FTS4(
                `fact_id` INTEGER NOT NULL,
                `content_ngram` TEXT NOT NULL
            )
        """.trimIndent()
        val V11_CREATE_SPACES = """
            CREATE TABLE IF NOT EXISTS `memory_spaces` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `name` TEXT NOT NULL,
                `icon` TEXT,
                `description` TEXT NOT NULL DEFAULT '',
                `created_at` TEXT NOT NULL,
                `sort_index` INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()
        val V11_CREATE_LINKS = """
            CREATE TABLE IF NOT EXISTS `memory_links` (
                `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                `source_fact_id` INTEGER NOT NULL,
                `target_fact_id` INTEGER NOT NULL,
                `source_title` TEXT NOT NULL,
                `target_title` TEXT NOT NULL,
                `link_type` TEXT NOT NULL DEFAULT 'related_to',
                `weight` REAL NOT NULL DEFAULT 0.5,
                `space_id` TEXT NOT NULL DEFAULT 'default',
                `scope` TEXT NOT NULL DEFAULT 'main',
                `created_at` TEXT NOT NULL
            )
        """.trimIndent()
        val helper = factory.create(
            configuration = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(11) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(V11_CREATE_FACTS)
                        db.execSQL(V11_CREATE_FTS)
                        db.execSQL(V11_CREATE_SPACES)
                        db.execSQL(V11_CREATE_LINKS)
                        // v9 索引
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_facts_scope` ON `facts` (`scope`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_facts_space_id` ON `facts` (`space_id`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_memory_spaces_sort` ON `memory_spaces` (`sort_index`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_memory_links_source` ON `memory_links` (`source_fact_id`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_memory_links_target` ON `memory_links` (`target_fact_id`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_memory_links_space` ON `memory_links` (`space_id`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_memory_links_scope` ON `memory_links` (`scope`)")
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

        // 模拟老用户已有 2 条记忆(含置顶)与 1 条 FTS 索引
        helper.execSQL(
            """
            INSERT INTO facts (
                id, fact, tags, time, session_id, created_at,
                importance, category, confidence, source,
                expires_at, last_confirmed_at, last_hit_at,
                scope, space_id, pinned_at
            ) VALUES (
                1, '用户喜欢深色模式', '["preference"]', NULL, NULL, '2026-08-01T00:00:00Z',
                1, 'preference', 0.9, 'user_explicit',
                NULL, NULL, '2026-08-10T00:00:00Z',
                'main', 'default', '2026-08-15T00:00:00Z'
            )
            """.trimIndent()
        )
        helper.execSQL(
            """
            INSERT INTO facts (
                id, fact, tags, time, session_id, created_at,
                importance, category, confidence, source,
                expires_at, last_confirmed_at, last_hit_at,
                scope, space_id, pinned_at
            ) VALUES (
                2, '下周三交论文初稿', '["学业","计划"]', '2026-08-26T09:00:00Z', 'sess-x', '2026-08-18T10:00:00Z',
                1, 'goal', 1.0, 'user_explicit',
                '2026-08-27T00:00:00Z', NULL, NULL,
                'main', 'work', NULL
            )
            """.trimIndent()
        )
        helper.execSQL(
            "INSERT INTO facts_fts(fact_id, content_ngram) VALUES (1, '用户 喜欢 深色 模式'), (2, '下周 周三 交论 论文 初稿')"
        )
        helper.close()

        // 触发 11→12 迁移
        val db = Room.databaseBuilder(context, FactDb::class.java, dbFile.absolutePath)
            .addMigrations(FactDb.MIGRATION_11_12)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase

        // 1. entity_key 列存在
        db.openHelper.writableDatabase.query("PRAGMA table_info(facts)").use { cursor ->
            val columns = mutableListOf<String>()
            while (cursor.moveToNext()) columns.add(cursor.getString(1))
            assertTrue("entity_key 列应存在", "entity_key" in columns)
        }

        // 2. 历史数据完整保留(含 scope/space_id/pinned_at/expires_at/last_hit_at)
        db.openHelper.writableDatabase.query(
            """
            SELECT fact, importance, scope, space_id, pinned_at, expires_at, last_hit_at, entity_key
            FROM facts WHERE id = 1
            """.trimIndent()
        ).use { cursor ->
            assertTrue("老数据应保留", cursor.moveToFirst())
            assertEquals("用户喜欢深色模式", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals("main", cursor.getString(2))
            assertEquals("default", cursor.getString(3))
            assertEquals("2026-08-15T00:00:00Z", cursor.getString(4))
            assertNull("expires_at 为 NULL", cursor.getString(5))
            assertEquals("2026-08-10T00:00:00Z", cursor.getString(6))
            assertNull("老数据 entity_key 为 NULL(待反思任务回填)", cursor.getString(7))
        }

        // 3. 第二条(work 空间)同样保留
        db.openHelper.writableDatabase.query("SELECT space_id, expires_at FROM facts WHERE id = 2").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("work", cursor.getString(0))
            assertEquals("2026-08-27T00:00:00Z", cursor.getString(1))
        }

        // 4. 索引 idx_facts_entity_key 已建
        db.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_facts_entity_key'"
        ).use { cursor ->
            assertTrue("idx_facts_entity_key 索引应存在", cursor.moveToFirst())
        }

        db.close()
    }

    private companion object {
        const val DB_NAME = "facts_test.db"
    }
}
