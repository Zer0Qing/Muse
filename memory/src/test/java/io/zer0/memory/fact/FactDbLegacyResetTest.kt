package io.zer0.memory.fact

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * R-DB-03 + v2.2.1 版本守卫回归。
 *
 * 覆盖:
 *  - 早期 v1/v2 库归档重建并打提示标记(既有行为)
 *  - 当前版本(v14)库重开不得被归档(守卫写死 13 的历史缺陷回归测试)
 *  - 更高版本库仍应归档
 *  - 误归档备份的一次性恢复(且无归档标记时绝不恢复)
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class FactDbLegacyResetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `legacy v1 database is archived and flagged for ui hint`() {
        val name = "facts_test_v1.db"
        createDatabase(name, version = 1)

        FactDb.create(context, name)

        assertFalse(context.getDatabasePath(name).exists())
        assertTrue(context.getDatabasePath("$name.bak").exists())
        assertTrue(MemoryLegacyReset.consume(context))
        cleanup(name)
    }

    @Test
    fun `legacy v2 database is archived and flagged for ui hint`() {
        val name = "facts_test_v2.db"
        createDatabase(name, version = 2)

        FactDb.create(context, name)

        assertFalse(context.getDatabasePath(name).exists())
        assertTrue(context.getDatabasePath("$name.bak").exists())
        assertTrue(MemoryLegacyReset.consume(context))
        cleanup(name)
    }

    @Test
    fun `current version database is not archived`() {
        val name = "facts_test_v11.db"
        createDatabase(name, version = 11)

        FactDb.create(context, name)

        assertTrue(context.getDatabasePath(name).exists())
        assertFalse(context.getDatabasePath("$name.bak").exists())
        assertFalse(MemoryLegacyReset.consume(context))
        cleanup(name)
    }

    @Test
    fun `current database survives reopen without archive and keeps facts`() {
        val name = "facts_test_current_reopen.db"
        // 第一次打开并写入一条事实(模拟正常使用)
        val db1 = FactDb.create(context, name)
        db1.openHelper.writableDatabase.execSQL(
            "INSERT INTO facts (fact, tags, created_at) VALUES ('回归测试事实', '[]', '2026-09-27T20:00:00')",
        )
        assertEquals(1, countFacts(name))
        db1.close()

        // 再次打开(模拟 App 重启):当前版本真库不得被版本守卫误归档重建。
        val db2 = FactDb.create(context, name)
        assertEquals("当前版本库重开不得被归档清空", 1, countFacts(name))
        assertFalse(
            File(context.getDatabasePath(name).parentFile, "$name.pre-destructive.bak").exists(),
        )
        db2.close()
        cleanup(name)
    }

    @Test
    fun `newer version database is archived`() {
        val name = "facts_test_v${FACT_DB_VERSION + 1}.db"
        val db = FactDb.create(context, name)
        db.openHelper.writableDatabase.version = FACT_DB_VERSION + 1
        db.close()

        FactDb.create(context, name)

        assertTrue(
            File(context.getDatabasePath(name).parentFile, "$name.pre-destructive.bak").exists(),
        )
        assertTrue(MemoryLegacyReset.consume(context))
        cleanup(name)
    }

    @Test
    fun `misarchived backup is recovered once on open`() {
        val name = "facts_test_recover.db"
        val bakName = "$name.pre-destructive.bak"
        // 制造"受害者"现场: 主库空(真实落盘的 v14 空库) + 误归档备份有数据 + 未消费的归档标记
        createEmptyDb(name)
        val bak = FactDb.create(context, bakName)
        bak.openHelper.writableDatabase.execSQL(
            "INSERT INTO facts (fact, tags, created_at) VALUES ('被误归档的事实', '[]', '2026-09-27T20:00:00')",
        )
        bak.close()
        MemoryLegacyReset.mark(context, name)

        // 打开:应触发一次性恢复——事实找回、备份被迁回、恢复条数记入提示
        val reopened = FactDb.create(context, name)
        assertEquals(1, countFacts(name))
        assertFalse(context.getDatabasePath(bakName).exists())
        assertEquals(1, MemoryLegacyReset.consumeRecovered(context))
        reopened.close()
        cleanup(name)
        runCatching { context.getDatabasePath(bakName).delete() }
    }

    @Test
    fun `recovery does not run without pending archive flag`() {
        // 无归档标记时:即使存在非空备份也不恢复(防"删除全部记忆后旧数据诈尸")
        val name = "facts_test_no_recover.db"
        val bakName = "$name.pre-destructive.bak"
        createEmptyDb(name)
        val bak = FactDb.create(context, bakName)
        bak.openHelper.writableDatabase.execSQL(
            "INSERT INTO facts (fact, tags, created_at) VALUES ('不该复活', '[]', '2026-09-27T20:00:00')",
        )
        bak.close()

        val reopened = FactDb.create(context, name)

        assertEquals(0, countFacts(name))
        assertTrue(context.getDatabasePath(bakName).exists())
        reopened.close()
        cleanup(name)
        runCatching { context.getDatabasePath(bakName).delete() }
    }

    /** 创建并落盘一个真实的 v14 空库(Room 惰性建库,需触碰一次才会落盘 schema)。 */
    private fun createEmptyDb(name: String) {
        val db = FactDb.create(context, name)
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM facts").use { it.moveToFirst() }
        db.close()
    }

    private fun countFacts(name: String): Int = SQLiteDatabase.openDatabase(
        context.getDatabasePath(name).absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY,
    ).use { db ->
        db.rawQuery("SELECT COUNT(*) FROM facts", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun createDatabase(name: String, version: Int) {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.version = version
        } finally {
            db.close()
        }
    }

    private fun cleanup(name: String) {
        listOf(
            name,
            "$name.bak",
            "$name-wal",
            "$name-shm",
            "$name.bak-wal",
            "$name.bak-shm",
            "$name.pre-destructive.bak",
            "$name.pre-destructive.bak-wal",
            "$name.pre-destructive.bak-shm",
            "$name.replaced-by-recovery.bak",
        ).forEach {
            runCatching { context.getDatabasePath(it).delete() }
        }
    }
}
