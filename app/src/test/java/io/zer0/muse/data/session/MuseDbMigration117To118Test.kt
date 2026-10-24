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
 * v2.6.8: 117 → 118 迁移的行为验证（卡片实体表）。
 *
 * 与 [MuseDbMigration116To117Test] 同一模式：用原生 SQLite 直接执行与
 * [MuseDb.MIGRATION_117_118] 完全相同的 DDL，断言：
 *  - `card_entities` 表存在，十二列齐全；
 *  - 列默认值与 [io.zer0.muse.data.card.CardEntity] 声明逐项一致（否则真机 Room
 *    schema 校验会抛 "Migration didn't properly handle: card_entities"）；
 *  - `dockSlot` 唯一索引允许任意多行 NULL（仅消息流），但拒绝重复的非 NULL 槽位；
 *  - `originMessageId` 普通索引存在；
 *  - 既有邻居表与数据不受影响（纯新增表迁移不该动别的表）。
 *
 * 不依赖 room-testing 的 MigrationTestHelper；Room 打开路径的端到端校验见
 * [MuseDbMigrationTest.migrateV113To117Starts_validateThroughRoom]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseDbMigration117To118Test {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** 与 MIGRATION_117_118 逐字一致的建表 + 索引 DDL。 */
    private fun applyMigrationDdl(db: android.database.sqlite.SQLiteDatabase) {
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

    private fun openFresh(dbName: String): android.database.sqlite.SQLiteDatabase {
        context.deleteDatabase(dbName)
        return android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(dbName).also { it.parentFile?.mkdirs() },
            null,
        )
    }

    @Test
    fun migration117To118_createsCardEntitiesTableWithExpectedColumns() {
        val db = openFresh("migration_117_118_test.db")
        try {
            // 1. 还原 v117 里会被本次迁移「碰到」的邻居表，确认迁移不会误伤
            db.execSQL(
                """
                CREATE TABLE session_todos (
                    id TEXT NOT NULL PRIMARY KEY,
                    sessionId TEXT NOT NULL DEFAULT '',
                    todosJson TEXT NOT NULL DEFAULT '',
                    hasOpenItems INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL("INSERT INTO session_todos (id, sessionId) VALUES ('t1', 's1')")
            db.version = 117

            // 2. 执行与 MIGRATION_117_118 完全相同的 DDL
            applyMigrationDdl(db)

            // 3. 列集合 / 类型 / 可空性 / 默认值
            assertCardEntityColumns(db)

            // 4. 两个索引存在，且 dockSlot 那个是唯一索引
            assertCardEntityIndexes(db)

            // 5. 邻居表与数据不受影响
            db.rawQuery("SELECT sessionId FROM session_todos WHERE id = 't1'", null).use { c ->
                assertTrue("既有行必须保留", c.moveToFirst())
                assertEquals("s1", c.getString(0))
            }
        } finally {
            db.close()
        }
    }

    /** 列集合、类型与默认值逐项对照规格 §3.1。 */
    private fun assertCardEntityColumns(db: android.database.sqlite.SQLiteDatabase) {
        val cols = columnsOf(db, "card_entities")
        assertEquals(
            "列集合必须与 CardEntity 完全一致",
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
            ),
            cols.keys.toList(),
        )
        val expectedTypes = mapOf(
            "id" to "TEXT|notnull=1",
            "legacyCardId" to "TEXT|notnull=0",
            "originSessionId" to "TEXT|notnull=0",
            "originMessageId" to "TEXT|notnull=0",
            "title" to "TEXT|notnull=1",
            "code" to "TEXT|notnull=1",
            "isSvg" to "INTEGER|notnull=1",
            "dataJson" to "TEXT|notnull=0",
            "dockSlot" to "INTEGER|notnull=0",
            "dockedAt" to "INTEGER|notnull=0",
            "createdAt" to "INTEGER|notnull=1",
            "updatedAt" to "INTEGER|notnull=1",
        )
        expectedTypes.forEach { (column, prefix) ->
            assertTrue("$column 类型/可空性应为 $prefix，实际 ${cols.getValue(column)}", cols.getValue(column).startsWith(prefix))
        }
        assertTrue("title 默认空串", cols.getValue("title").contains("default=''"))
        assertTrue("code 默认空串", cols.getValue("code").contains("default=''"))
        assertTrue("isSvg 默认 0", cols.getValue("isSvg").contains("default=0"))
        assertTrue("createdAt 默认 0", cols.getValue("createdAt").contains("default=0"))
        assertTrue("updatedAt 默认 0", cols.getValue("updatedAt").contains("default=0"))
        listOf("legacyCardId", "originSessionId", "originMessageId", "dataJson", "dockSlot", "dockedAt")
            .forEach { column ->
                // 显式 DEFAULT NULL 在 PRAGMA 里表现为字符串 "NULL"（不是 NULL），与 Room 快照的默认值写法一致。
                assertEquals("$column 默认值应为 NULL", "NULL", columnDefault(db, column))
            }
    }

    /** 两个索引存在且唯一性正确（dockSlot 唯一，originMessageId 普通）。 */
    private fun assertCardEntityIndexes(db: android.database.sqlite.SQLiteDatabase) {
        val indexes = mutableMapOf<String, Boolean>()
        db.rawQuery("PRAGMA index_list(`card_entities`)", null).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow("name"))
                val unique = c.getInt(c.getColumnIndexOrThrow("unique")) == 1
                if (!name.startsWith("sqlite_autoindex")) indexes[name] = unique
            }
        }
        assertEquals(
            "索引集合必须与实体 @Index 声明一致",
            setOf("index_card_entities_dockSlot", "index_card_entities_originMessageId"),
            indexes.keys,
        )
        assertEquals("dockSlot 索引必须唯一", true, indexes["index_card_entities_dockSlot"])
        assertEquals("originMessageId 索引非唯一（一条消息可承载多张卡）", false, indexes["index_card_entities_originMessageId"])
    }

    private fun columnDefault(db: android.database.sqlite.SQLiteDatabase, column: String): String? {
        var dflt: String? = null
        db.rawQuery("PRAGMA table_info(`card_entities`)", null).use { c ->
            while (c.moveToNext()) {
                if (c.getString(c.getColumnIndexOrThrow("name")) == column) {
                    dflt = c.getString(c.getColumnIndexOrThrow("dflt_value"))
                }
            }
        }
        return dflt
    }

    @Test
    fun uniqueDockSlotIndexAllowsManyNullsButRejectsDuplicateSlots() {
        val db = openFresh("migration_117_118_unique.db")
        try {
            applyMigrationDdl(db)

            // 未停靠的卡（dockSlot = NULL）可以有任意多张 —— 唯一索引不参与 NULL 比较
            repeat(5) { index ->
                db.execSQL("INSERT INTO card_entities (id, title) VALUES ('inline-$index', '卡 $index')")
            }
            db.rawQuery("SELECT COUNT(*) FROM card_entities WHERE dockSlot IS NULL", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("多张仅消息流的卡必须能共存", 5, c.getInt(0))
            }

            // 相同槽位必须被拒
            db.execSQL("UPDATE card_entities SET dockSlot = 0 WHERE id = 'inline-0'")
            val threw = try {
                db.execSQL("UPDATE card_entities SET dockSlot = 0 WHERE id = 'inline-1'")
                false
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                e.message?.let { assertTrue("应为唯一约束冲突", it.contains("UNIQUE", ignoreCase = true)) }
                true
            }
            assertTrue("同一 dockSlot 不允许两张卡（否则停靠顺序会出双）", threw)

            // 不同槽位正常写入，且按槽位升序读出的顺序稳定
            db.execSQL("UPDATE card_entities SET dockSlot = 2 WHERE id = 'inline-2'")
            db.execSQL("UPDATE card_entities SET dockSlot = 1 WHERE id = 'inline-3'")
            val order = mutableListOf<String>()
            db.rawQuery(
                "SELECT id FROM card_entities WHERE dockSlot IS NOT NULL ORDER BY dockSlot ASC",
                null,
            ).use { c ->
                while (c.moveToNext()) order += c.getString(0)
            }
            assertEquals("停靠卡应按槽位升序", listOf("inline-0", "inline-3", "inline-2"), order)

            // 清槽位后回到「仅消息流」，行仍在
            db.execSQL("UPDATE card_entities SET dockSlot = NULL WHERE id = 'inline-0'")
            db.rawQuery("SELECT title FROM card_entities WHERE id = 'inline-0'", null).use { c ->
                assertTrue("拖回只清槽位，不删行", c.moveToFirst())
                assertNotNull(c.getString(0))
            }
        } finally {
            db.close()
        }
    }
}
