package io.zer0.muse.data.session

import android.database.Cursor
import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FTS5 能力探针的两阶语义（回归测试）。
 *
 * 为什么要专门钉这件事：第 149 轮把探针从"直接建 fts5 虚表"改成"先读 `PRAGMA compile_options`"，
 * 是因为旧写法会让 Robolectric 的 SQLite shadow 在后台线程释放语句缓存时抛
 * `no such module: fts5`，那条异常没人捕获 → **同一个测试 JVM 里下一个用 runTest 的类随机红**
 * （单跑又必绿，极难归因）。
 * 如果后来人觉得"读编译选项多此一举"退回建表探针，这个后果在本仓任何功能测试里都看不见，
 * 所以直接把"不许执行建表语句"写成断言。
 */
class Fts5ProbeTest {

    private fun cursorWith(rows: List<String>): Cursor {
        val cursor = mockk<Cursor>()
        var index = -1
        every { cursor.moveToNext() } answers {
            index += 1
            index < rows.size
        }
        every { cursor.getString(0) } answers { rows[index] }
        every { cursor.close() } returns Unit
        return cursor
    }

    private fun dbReturning(options: List<String>?): SupportSQLiteDatabase {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        if (options == null) {
            every { db.query("PRAGMA compile_options") } throws SQLiteException("no such pragma")
        } else {
            every { db.query("PRAGMA compile_options") } returns cursorWith(options)
        }
        return db
    }

    @Test
    fun `编译选项里有 FTS5 时判支持，且不建任何虚表`() {
        val db = dbReturning(listOf("ENABLE_FTS5", "THREADSAFE=1"))
        assertTrue(MuseDb.fts5Available(db))
        verify(exactly = 0) { db.execSQL(any<String>()) }
    }

    @Test
    fun `编译选项读得到但没有 FTS5 时判不支持，同样不建虚表`() {
        // 这就是 Robolectric 下的真实返回值（实测 8 行，没有 ENABLE_FTS5）
        val db = dbReturning(
            listOf(
                "ENABLE_COLUMN_METADATA",
                "ENABLE_FTS3",
                "ENABLE_FTS3_PARENTHESIS",
                "ENABLE_MEMORY_MANAGEMENT",
                "ENABLE_RTREE",
                "OMIT_DEPRECATED",
                "SYSTEM_MALLOC",
                "THREADSAFE=1",
            ),
        )
        assertFalse(MuseDb.fts5Available(db))
        verify(exactly = 0) { db.execSQL(any<String>()) }
    }

    @Test
    fun `编译选项一条都读不到时才退回建表探针（真机老版本不被误判）`() {
        val db = dbReturning(emptyList())
        assertTrue(MuseDb.fts5Available(db))
        verify(exactly = 1) { db.execSQL(match { it.contains("USING fts5") }) }
        // 探针一共发 3 条与 temp 表相关的语句（前置 DROP、CREATE、收尾 DROP）；
        // 断言"建过虚表"这件事即可，不必钉死 3 —— 钉数字是我为省事猜出来的，第一次就猜错了。
        verify(atLeast = 1) { db.execSQL(match { it.contains("muse_fts5_probe") }) }
    }

    @Test
    fun `PRAGMA 本身抛错时也算读不到，走建表探针而不是直接判不支持`() {
        val db = dbReturning(null)
        assertTrue(MuseDb.fts5Available(db))
        verify(atLeast = 1) { db.execSQL(match { it.contains("muse_fts5_probe") }) }
    }

    @Test
    fun `建表探针失败时判不支持而不是抛出去`() {
        val db = dbReturning(emptyList())
        every { db.execSQL(match { it.contains("USING fts5") }) } throws SQLiteException("no such module: fts5")
        assertFalse("探针失败必须被吞成 false（当年 R-DB-05 的设计意图）", MuseDb.fts5Available(db))
    }
}
