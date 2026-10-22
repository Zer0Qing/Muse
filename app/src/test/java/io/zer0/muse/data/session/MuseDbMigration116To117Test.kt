package io.zer0.muse.data.session

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.6.7: 116 → 117 迁移的行为验证（会话待办持久化）。
 *
 * 用原生 SQLite 直接执行与 [MuseDb.MIGRATION_116_117] 完全相同的 DDL，断言：
 *  - `session_todos` 表存在，且六列齐全；
 *  - `sessionId` 上有唯一索引（每会话一份清单的约束）；
 *  - 默认值符合实体声明（否则真机 Room schema 校验会抛
 *    "Migration didn't properly handle: session_todos"）；
 *  - 既有表不受影响（纯新增表迁移不该动别的表）。
 *
 * 不依赖 room-testing（未在依赖里），与既有 111→112 测试同一模式。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseDbMigration116To117Test {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun columnsOf(db: android.database.sqlite.SQLiteDatabase, table: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        db.rawQuery("PRAGMA table_info(`$table`)", null).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow("name"))
                val type = c.getString(c.getColumnIndexOrThrow("type"))
                val notNull = c.getInt(c.getColumnIndexOrThrow("notnull"))
                val dflt = c.getString(c.getColumnIndexOrThrow("dflt_value"))
                out[name] = "$type|notnull=$notNull|default=$dflt"
            }
        }
        return out
    }

    @Test
    fun migration116To117_createsSessionTodosTableWithExpectedColumns() {
        val dbName = "migration_116_117_test.db"
        context.deleteDatabase(dbName)
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(dbName).also { it.parentFile?.mkdirs() },
            null,
        )
        try {
            // 1. 还原 v116 里会被本次迁移「碰到」的邻居表，确认迁移不会误伤
            db.execSQL(
                """
                CREATE TABLE ai_moments (
                    id TEXT NOT NULL PRIMARY KEY,
                    content TEXT NOT NULL DEFAULT '',
                    visibleToJson TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent(),
            )
            db.execSQL("INSERT INTO ai_moments (id, content) VALUES ('m1', 'legacy moment')")
            db.version = 116

            // 2. 执行与 MIGRATION_116_117 完全相同的 DDL
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

            // 3. 列齐全
            val cols = columnsOf(db, "session_todos")
            assertEquals(
                "列集合必须与实体完全一致",
                setOf("id", "sessionId", "todosJson", "hasOpenItems", "createdAt", "updatedAt"),
                cols.keys,
            )

            // 4. 关键列的默认值与实体声明一致
            assertTrue("sessionId 默认空串", cols.getValue("sessionId").contains("default=''"))
            assertTrue("todosJson 默认空串", cols.getValue("todosJson").contains("default=''"))
            assertTrue("hasOpenItems 默认 0", cols.getValue("hasOpenItems").contains("default=0"))
            assertTrue("createdAt 默认 0", cols.getValue("createdAt").contains("default=0"))
            assertTrue("updatedAt 默认 0", cols.getValue("updatedAt").contains("default=0"))

            // 5. 唯一索引存在
            val indexes = mutableListOf<String>()
            db.rawQuery("PRAGMA index_list(`session_todos`)", null).use { c ->
                while (c.moveToNext()) indexes += c.getString(c.getColumnIndexOrThrow("name"))
            }
            assertTrue(
                "sessionId 必须有唯一索引，实际: $indexes",
                indexes.contains("index_session_todos_sessionId"),
            )

            // 6. 邻居表与数据不受影响
            db.rawQuery("SELECT content FROM ai_moments WHERE id = 'm1'", null).use { c ->
                assertTrue("既有行必须保留", c.moveToFirst())
                assertEquals("legacy moment", c.getString(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun uniqueIndexRejectsTwoRowsForTheSameSession() {
        val dbName = "migration_116_117_unique.db"
        context.deleteDatabase(dbName)
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(dbName).also { it.parentFile?.mkdirs() },
            null,
        )
        try {
            db.execSQL(
                "CREATE TABLE `session_todos` (" +
                    "`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL DEFAULT '', " +
                    "`todosJson` TEXT NOT NULL DEFAULT '', `hasOpenItems` INTEGER NOT NULL DEFAULT 0, " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX `index_session_todos_sessionId` ON `session_todos` (`sessionId`)",
            )
            db.execSQL(
                "INSERT INTO session_todos (id, sessionId) VALUES ('a', 's1')",
            )

            val threw = try {
                db.execSQL("INSERT INTO session_todos (id, sessionId) VALUES ('b', 's1')")
                false
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                // 期望的约束冲突：唯一索引生效的直接证据
                e.message?.let { assertTrue("应为唯一约束冲突", it.contains("UNIQUE", ignoreCase = true)) }
                true
            }

            assertTrue("同一会话不允许两条待办记录（否则 upsert 语义会失效）", threw)
            assertNotNull(
                "原记录必须还在",
                db.rawQuery("SELECT id FROM session_todos WHERE sessionId='s1'", null).use {
                    assertTrue(it.moveToFirst())
                    it.getString(0)
                },
            )
        } finally {
            db.close()
        }
    }
}
