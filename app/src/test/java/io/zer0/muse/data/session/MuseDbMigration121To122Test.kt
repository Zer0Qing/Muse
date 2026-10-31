package io.zer0.muse.data.session

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 版本 121 → 122：清理旧小手机遗留数据。
 *
 * 直接调用生产环境的 [MuseDb.MIGRATION_121_122]（不是复制一份 SQL），断言：
 *  - 朋友圈/日记四张表被删除，且删得掉（旧库上确实存在过）；
 *  - `sessions.isMiniPhone = 1` 的会话与挂在它下面的消息被删；
 *  - 普通会话与消息一条不少、字段一字未改（这次迁移只清小手机数据）；
 *  - `isMiniPhone` 列本身**保留**（旧 SQLite 不支持 DROP COLUMN，列还在但已无写入方）；
 *  - 迁移可重复执行（幂等），不会因表已删而报错。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseDbMigration121To122Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** 按 v121 的关键结构建库：sessions / messages + 待删除的四张表。 */
    private fun openAt121(name: String): SupportSQLiteDatabase {
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(121) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE `sessions` (" +
                                    "`id` TEXT NOT NULL, `title` TEXT NOT NULL DEFAULT '', " +
                                    "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                                    "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                                    "`lastMessagePreview` TEXT NOT NULL DEFAULT '', " +
                                    "`assistantId` TEXT NOT NULL DEFAULT 'default', " +
                                    "`archived` INTEGER NOT NULL DEFAULT 0, " +
                                    "`isAgentSession` INTEGER NOT NULL DEFAULT 0, " +
                                    "`isMiniPhone` INTEGER NOT NULL DEFAULT 0, " +
                                    "`deletedAt` INTEGER DEFAULT NULL, " +
                                    "PRIMARY KEY(`id`))",
                            )
                            db.execSQL(
                                "CREATE TABLE `messages` (" +
                                    "`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL DEFAULT '', " +
                                    "`role` TEXT NOT NULL DEFAULT '', `content` TEXT NOT NULL DEFAULT '', " +
                                    "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                                    "`deletedAt` INTEGER DEFAULT NULL, " +
                                    "PRIMARY KEY(`id`))",
                            )
                            db.execSQL("CREATE TABLE `ai_moments` (`id` TEXT NOT NULL PRIMARY KEY, `content` TEXT NOT NULL)")
                            db.execSQL(
                                "CREATE TABLE `ai_moment_comments` (" +
                                    "`id` TEXT NOT NULL PRIMARY KEY, `momentId` TEXT NOT NULL)",
                            )
                            db.execSQL(
                                "CREATE TABLE `ai_moment_likes` (" +
                                    "`momentId` TEXT NOT NULL, `likerType` TEXT NOT NULL, `likerId` TEXT NOT NULL, " +
                                    "PRIMARY KEY(`momentId`, `likerType`, `likerId`))",
                            )
                            db.execSQL("CREATE TABLE `ai_diaries` (`date` TEXT NOT NULL PRIMARY KEY, `content` TEXT NOT NULL)")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        return helper.writableDatabase
    }

    private fun tableNames(db: SupportSQLiteDatabase): Set<String> {
        val names = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            while (cursor.moveToNext()) names += cursor.getString(0)
        }
        return names
    }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): Set<String> {
        val names = mutableSetOf<String>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) names += cursor.getString(cursor.getColumnIndexOrThrow("name"))
        }
        return names
    }

    private fun seed(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO sessions (id, title, assistantId, isMiniPhone) " +
                "VALUES ('s-main', '主会话', 'assistant-a', 0)",
        )
        db.execSQL(
            "INSERT INTO sessions (id, title, assistantId, isMiniPhone) " +
                "VALUES ('s-mini', '小手机私信', 'assistant-a', 1)",
        )
        db.execSQL("INSERT INTO messages (id, sessionId, role, content) VALUES ('m-main', 's-main', 'USER', '主会话消息')")
        db.execSQL("INSERT INTO messages (id, sessionId, role, content) VALUES ('m-mini', 's-mini', 'USER', '私信消息')")
        db.execSQL("INSERT INTO ai_moments (id, content) VALUES ('mo1', '旧动态')")
        db.execSQL("INSERT INTO ai_moment_comments (id, momentId) VALUES ('c1', 'mo1')")
        db.execSQL(
            "INSERT INTO ai_moment_likes (momentId, likerType, likerId) VALUES ('mo1', 'user', 'user')",
        )
        db.execSQL("INSERT INTO ai_diaries (date, content) VALUES ('2026-09-20', '旧日记')")
    }

    @Test
    fun migrationDropsLegacyTablesAndMiniPhoneRowsOnly() {
        val db = openAt121("migration_121_122.db")
        try {
            seed(db)
            // 前提:旧库上这些表/行确实存在，否则后面的「已删除」断言等于空断言
            assertTrue("前置条件:旧库应有四张朋友圈/日记表", tableNames(db).containsAll(LEGACY_TABLES))

            MuseDb.MIGRATION_121_122.migrate(db)

            val tables = tableNames(db)
            LEGACY_TABLES.forEach { table ->
                assertFalse("$table 应在 121→122 迁移中被删除", table in tables)
            }
            assertTrue("sessions 表必须还在", "sessions" in tables)
            assertTrue("messages 表必须还在", "messages" in tables)

            db.query("SELECT id FROM sessions ORDER BY id").use { cursor ->
                val ids = mutableListOf<String>()
                while (cursor.moveToNext()) ids += cursor.getString(0)
                assertEquals("只删 isMiniPhone = 1 的会话", listOf("s-main"), ids)
            }
            db.query("SELECT id, content FROM messages ORDER BY id").use { cursor ->
                assertTrue("普通会话的消息必须保留", cursor.moveToFirst())
                assertEquals("m-main", cursor.getString(0))
                assertEquals("普通消息正文不应被改写", "主会话消息", cursor.getString(1))
                assertFalse("小手机会话的消息应一并删除", cursor.moveToNext())
            }
            assertTrue(
                "isMiniPhone 列必须保留(旧 SQLite 不支持 DROP COLUMN，只停写不删列)",
                "isMiniPhone" in columnNames(db, "sessions"),
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun migrationIsIdempotent() {
        val db = openAt121("migration_121_122_idempotent.db")
        try {
            seed(db)

            MuseDb.MIGRATION_121_122.migrate(db)
            // 再跑一遍:表已不存在，DROP TABLE IF EXISTS 与条件删除都不应报错
            MuseDb.MIGRATION_121_122.migrate(db)

            db.query("SELECT COUNT(*) FROM sessions").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("重复迁移不应误删普通会话", 1, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM messages").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("重复迁移不应误删普通消息", 1, cursor.getInt(0))
            }
            assertFalse("ai_diaries 仍应不存在", tableNames(db).contains("ai_diaries"))
        } finally {
            db.close()
        }
    }

    private companion object {
        /** 旧小手机留下的四张表（表名取自各 Entity 的 @Entity(tableName=...)）。 */
        val LEGACY_TABLES = setOf("ai_moments", "ai_moment_comments", "ai_moment_likes", "ai_diaries")
    }
}
