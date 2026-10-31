package io.zer0.muse.data.session

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.6.15: 119 → 120 缓存读/写分列迁移。
 *
 * 直接调用生产环境的 [MuseDb.MIGRATION_119_120]（不是复制一份 DDL），断言：
 *  - 两列都补上了，类型与默认值跟实体声明一致（否则真机 Room 校验会抛
 *    "Migration didn't properly handle"）；
 *  - 旧行一个字段都没被改写：`cachedTokens` / `cached_tokens` 原样保留（不回填、不重算），
 *    新列在旧行为 NULL / 0；
 *  - 按文档的回退步骤（删两列 + 版本退回 119）后，旧数据仍然完整可读。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseDbMigration119To120Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun openAt119(name: String): SupportSQLiteDatabase {
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(119) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            // v119 的 messages / usage_records 关键列（不含本次新增列）
                            db.execSQL(
                                "CREATE TABLE `messages` (" +
                                    "`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL DEFAULT '', " +
                                    "`role` TEXT NOT NULL DEFAULT '', `content` TEXT NOT NULL DEFAULT '', " +
                                    "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                                    "`promptTokens` INTEGER DEFAULT NULL, " +
                                    "`completionTokens` INTEGER DEFAULT NULL, " +
                                    "`reasoningTokens` INTEGER DEFAULT NULL, " +
                                    "`cachedTokens` INTEGER DEFAULT NULL, " +
                                    "PRIMARY KEY(`id`))",
                            )
                            db.execSQL(
                                "CREATE TABLE `usage_records` (" +
                                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "`created_at` INTEGER NOT NULL DEFAULT 0, " +
                                    "`source` TEXT NOT NULL DEFAULT 'chat', " +
                                    "`model_id` TEXT NOT NULL DEFAULT '', " +
                                    "`provider_id` TEXT NOT NULL DEFAULT '', " +
                                    "`prompt_tokens` INTEGER NOT NULL DEFAULT 0, " +
                                    "`completion_tokens` INTEGER NOT NULL DEFAULT 0, " +
                                    "`reasoning_tokens` INTEGER NOT NULL DEFAULT 0, " +
                                    "`cached_tokens` INTEGER NOT NULL DEFAULT 0, " +
                                    "`first_token_ms` INTEGER NOT NULL DEFAULT 0, " +
                                    "`duration_ms` INTEGER NOT NULL DEFAULT 0)",
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        return helper.writableDatabase
    }

    private fun columnInfo(db: SupportSQLiteDatabase, table: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val type = cursor.getString(cursor.getColumnIndexOrThrow("type"))
                val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull"))
                val dflt = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                out[name] = "$type|notnull=$notNull|default=$dflt"
            }
        }
        return out
    }

    private fun seed(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO messages (id, sessionId, role, content, createdAt, promptTokens, " +
                "completionTokens, reasoningTokens, cachedTokens) " +
                "VALUES ('m1', 's1', 'ASSISTANT', '旧回复', 1000, 300, 50, 0, 700)",
        )
        db.execSQL(
            "INSERT INTO usage_records (created_at, source, model_id, provider_id, prompt_tokens, " +
                "completion_tokens, reasoning_tokens, cached_tokens) " +
                "VALUES (1000, 'chat', 'claude-sonnet-4', 'anthropic', 300, 50, 0, 700)",
        )
    }

    @Test
    fun migrationAddsCacheWriteColumnsWithoutTouchingLegacyValues() {
        val db = openAt119("migration_119_120.db")
        try {
            seed(db)

            // 执行生产迁移
            MuseDb.MIGRATION_119_120.migrate(db)

            val messageColumns = columnInfo(db, "messages")
            assertTrue("messages 应补上 cacheWriteTokens", messageColumns.containsKey("cacheWriteTokens"))
            assertTrue(
                "messages.cacheWriteTokens 必须可空且默认 NULL（旧行没有写入量记录）",
                messageColumns.getValue("cacheWriteTokens").contains("notnull=0"),
            )

            val usageColumns = columnInfo(db, "usage_records")
            assertTrue("usage_records 应补上 cache_write_tokens", usageColumns.containsKey("cache_write_tokens"))
            assertEquals(
                "usage_records.cache_write_tokens 应与实体一致：NOT NULL DEFAULT 0",
                "INTEGER|notnull=1|default=0",
                usageColumns.getValue("cache_write_tokens"),
            )

            // 旧值原样保留，新列为 NULL / 0
            db.query("SELECT cachedTokens, cacheWriteTokens FROM messages WHERE id = 'm1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧行 cachedTokens 不回填", 700, cursor.getInt(0))
                assertTrue("旧行写入量应为 NULL（未知），不能编成 0", cursor.isNull(1))
            }
            db.query("SELECT cached_tokens, cache_write_tokens FROM usage_records").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧流水 cached_tokens 不回填", 700, cursor.getInt(0))
                assertEquals("旧流水写入量默认 0", 0, cursor.getInt(1))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun rollbackToV119KeepsLegacyDataIntact() {
        val db = openAt119("migration_119_120_rollback.db")
        try {
            seed(db)
            MuseDb.MIGRATION_119_120.migrate(db)

            // 按迁移注释里的回退步骤：去掉两列 + 版本退回 119。
            // 这里走「建新表 → 拷数据 → 改名」的可移植路径：DROP COLUMN 需要 SQLite 3.35+，
            // Android 侧并不保证（本测试跑在 Robolectric 的较旧 SQLite 上就会直接报语法错）。
            db.execSQL(
                "CREATE TABLE `messages_rollback` (" +
                    "`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL DEFAULT '', " +
                    "`role` TEXT NOT NULL DEFAULT '', `content` TEXT NOT NULL DEFAULT '', " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`promptTokens` INTEGER DEFAULT NULL, " +
                    "`completionTokens` INTEGER DEFAULT NULL, " +
                    "`reasoningTokens` INTEGER DEFAULT NULL, " +
                    "`cachedTokens` INTEGER DEFAULT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "INSERT INTO `messages_rollback` (id, sessionId, role, content, createdAt, " +
                    "promptTokens, completionTokens, reasoningTokens, cachedTokens) " +
                    "SELECT id, sessionId, role, content, createdAt, " +
                    "promptTokens, completionTokens, reasoningTokens, cachedTokens FROM messages",
            )
            db.execSQL("DROP TABLE `messages`")
            db.execSQL("ALTER TABLE `messages_rollback` RENAME TO `messages`")

            db.execSQL(
                "CREATE TABLE `usage_records_rollback` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL DEFAULT 0, " +
                    "`source` TEXT NOT NULL DEFAULT 'chat', " +
                    "`model_id` TEXT NOT NULL DEFAULT '', " +
                    "`provider_id` TEXT NOT NULL DEFAULT '', " +
                    "`prompt_tokens` INTEGER NOT NULL DEFAULT 0, " +
                    "`completion_tokens` INTEGER NOT NULL DEFAULT 0, " +
                    "`reasoning_tokens` INTEGER NOT NULL DEFAULT 0, " +
                    "`cached_tokens` INTEGER NOT NULL DEFAULT 0, " +
                    "`first_token_ms` INTEGER NOT NULL DEFAULT 0, " +
                    "`duration_ms` INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL(
                "INSERT INTO `usage_records_rollback` (created_at, source, model_id, provider_id, " +
                    "prompt_tokens, completion_tokens, reasoning_tokens, cached_tokens, " +
                    "first_token_ms, duration_ms) " +
                    "SELECT created_at, source, model_id, provider_id, " +
                    "prompt_tokens, completion_tokens, reasoning_tokens, cached_tokens, " +
                    "first_token_ms, duration_ms FROM usage_records",
            )
            db.execSQL("DROP TABLE `usage_records`")
            db.execSQL("ALTER TABLE `usage_records_rollback` RENAME TO `usage_records`")

            db.version = 119

            assertEquals("回退后版本应回到 119", 119, db.version)
            assertTrue("新增列应已移除", !columnInfo(db, "messages").containsKey("cacheWriteTokens"))
            assertTrue("新增列应已移除", !columnInfo(db, "usage_records").containsKey("cache_write_tokens"))

            db.query("SELECT content, promptTokens, completionTokens, cachedTokens FROM messages").use { cursor ->
                assertTrue("旧消息必须还在", cursor.moveToFirst())
                assertEquals("旧回复", cursor.getString(0))
                assertEquals(300, cursor.getInt(1))
                assertEquals(50, cursor.getInt(2))
                assertEquals("回退不丢合并值", 700, cursor.getInt(3))
            }
            db.query("SELECT prompt_tokens, completion_tokens, cached_tokens FROM usage_records").use { cursor ->
                assertTrue("旧流水必须还在", cursor.moveToFirst())
                assertEquals(300, cursor.getInt(0))
                assertEquals(50, cursor.getInt(1))
                assertEquals(700, cursor.getInt(2))
            }
        } finally {
            db.close()
        }
    }
}
