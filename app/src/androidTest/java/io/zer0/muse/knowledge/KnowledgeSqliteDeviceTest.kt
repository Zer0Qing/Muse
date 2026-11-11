package io.zer0.muse.knowledge

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R10 设备面:真实 Android SQLite FTS4 能力验证(计划 R10「Android 真实 SQLite 验证
 * FTS4 MATCH/matchinfo、中文 ngram、scope SQL 和缺表重建；不拿桌面实验替代」)。
 *
 * 必须在真实设备/模拟器执行(`:app:connectedDebugAndroidTest`)。
 * 未在真机跑通前不得声称 FTS4 排名已验证(R0/R6 已记录该边界)。
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeSqliteDeviceTest {

    @Test
    fun fts4SupportsMatchAndMatchinfo() {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL("CREATE VIRTUAL TABLE k USING fts4(text_content)")
            db.execSQL("INSERT INTO k(text_content) VALUES (?)", arrayOf("alpha beta"))
            db.rawQuery("SELECT matchinfo(k, 'pcx') FROM k WHERE k MATCH ?", arrayOf("alpha")).use { cursor ->
                assertTrue("MATCH 应命中", cursor.moveToFirst())
                assertTrue("matchinfo 应返回非空 blob", cursor.getBlob(0).isNotEmpty())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun fts4ChineseExactStringMatches() {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL("CREATE VIRTUAL TABLE k USING fts4(text_content)")
            db.execSQL("INSERT INTO k(text_content) VALUES (?)", arrayOf("知识库检索使用倒排索引"))
            db.rawQuery("SELECT count(*) FROM k WHERE k MATCH ?", arrayOf("知识库检索使用倒排索引")).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun fts4MissingTableCanBeRebuilt() {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL("CREATE VIRTUAL TABLE k USING fts4(text_content)")
            db.execSQL("DROP TABLE k")
            db.execSQL("CREATE VIRTUAL TABLE k USING fts4(text_content)")
            db.execSQL("INSERT INTO k(text_content) VALUES (?)", arrayOf("rebuilt"))
            db.rawQuery("SELECT count(*) FROM k WHERE k MATCH ?", arrayOf("rebuilt")).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            db.close()
        }
    }
}
