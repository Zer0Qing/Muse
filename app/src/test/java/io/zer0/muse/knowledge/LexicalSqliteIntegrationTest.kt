package io.zer0.muse.knowledge

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * R6 步骤 2 的 SQLite 能力验证(计划 R6:「FTS4 用真实 MATCH/matchinfo 取候选…
 * 不用不合法 SQL 降级掩饰」)。
 *
 * R0 已记录边界:Robolectric 测试环境不提供 FTS4 vtable(其 SQLite 构建未启用 FTS4
 * 模块)。本测试在**本机环境不支持 FTS4** 时以 assumption 跳过并记录原因 ——
 * 这不是把失败藏成绿:真实 Android 设备上的 MATCH/matchinfo 验证归 R10
 * (`KnowledgeSqliteDeviceTest`),R10 未跑通前不得声称 FTS4 排名已验证。
 *
 * 支持 FTS4 的环境(真机 instrumentation 或启用 FTS4 的 JVM SQLite)会实际执行断言。
 */
class LexicalSqliteIntegrationTest {

    @Test
    fun `fts4 supports match and matchinfo on supported runtime`() {
        val db: SQLiteDatabase
        try {
            db = SQLiteDatabase.create(null)
            db.execSQL("CREATE VIRTUAL TABLE k USING fts4(text_content)")
        } catch (e: Exception) {
            // 环境不支持 FTS4:明确跳过并保留原因(真机验证归 R10)。
            assumeTrue("本测试环境无 FTS4 vtable(R0 已记录),真机验证归 R10: ${e.message}", false)
            return
        }
        try {
            db.execSQL("INSERT INTO k(text_content) VALUES (?)", arrayOf("alpha beta"))
            db.rawQuery("SELECT matchinfo(k, 'pcx') FROM k WHERE k MATCH ?", arrayOf("alpha")).use { cursor ->
                assertTrue("MATCH 应命中", cursor.moveToFirst())
                assertTrue("matchinfo 应返回非空 blob", cursor.getBlob(0).isNotEmpty())
            }
            // 中文 ngram 在 FTS4 默认分词器下按整串匹配;这里断言精确串可命中。
            db.execSQL("INSERT INTO k(text_content) VALUES (?)", arrayOf("知识库检索"))
            db.rawQuery("SELECT count(*) FROM k WHERE k MATCH ?", arrayOf("知识库检索")).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            db.close()
        }
    }
}
