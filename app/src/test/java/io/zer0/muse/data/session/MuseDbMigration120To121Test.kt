package io.zer0.muse.data.session

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.6.16: 120 → 121 语音消息迁移。
 *
 * 直接调用生产环境的 [MuseDb.MIGRATION_120_121]（不是复制一份 DDL），断言：
 *  - `voiceUri` / `voiceDurationMs` 两列都补上了，类型与可空性/默认值跟
 *    [MessageEntity] 声明一致（否则真机 Room 校验会抛 "Migration didn't properly handle"）；
 *  - 旧行一个字段都没被改写：正文/图片列原样保留，新列为 NULL / 0，
 *    读取语义等同于"非语音消息"，不需要回填；
 *  - 迁移可重复执行（幂等），不会因重复列崩溃。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseDbMigration120To121Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** 按 v120 的 messages 关键列建库（不含本次新增的两列）。 */
    private fun openAt120(name: String): SupportSQLiteDatabase {
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(120) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE `messages` (" +
                                    "`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL DEFAULT '', " +
                                    "`role` TEXT NOT NULL DEFAULT '', `content` TEXT NOT NULL DEFAULT '', " +
                                    "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                                    "`imageUrlsJson` TEXT NOT NULL DEFAULT '[]', " +
                                    "`cachedTokens` INTEGER DEFAULT NULL, " +
                                    "`cacheWriteTokens` INTEGER DEFAULT NULL, " +
                                    "PRIMARY KEY(`id`))",
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
            "INSERT INTO messages (id, sessionId, role, content, createdAt, imageUrlsJson, cachedTokens) " +
                "VALUES ('m1', 's1', 'ASSISTANT', '旧回复', 1000, '[\"file://pic.jpg\"]', 700)",
        )
    }

    @Test
    fun migrationAddsVoiceColumnsWithoutTouchingLegacyRows() {
        val db = openAt120("migration_120_121.db")
        try {
            seed(db)

            MuseDb.MIGRATION_120_121.migrate(db)

            val columns = columnInfo(db, "messages")
            assertEquals(
                "messages.voiceUri 必须可空且默认 NULL（旧行都不是语音消息）",
                "TEXT|notnull=0|default=NULL",
                columns["voiceUri"],
            )
            assertEquals(
                "messages.voiceDurationMs 应与实体一致：NOT NULL DEFAULT 0",
                "INTEGER|notnull=1|default=0",
                columns["voiceDurationMs"],
            )

            db.query("SELECT content, imageUrlsJson, cachedTokens, voiceUri, voiceDurationMs FROM messages").use { cursor ->
                assertTrue("旧消息必须还在", cursor.moveToFirst())
                assertEquals("正文不应被改写", "旧回复", cursor.getString(0))
                assertEquals("图片列不应被改写", "[\"file://pic.jpg\"]", cursor.getString(1))
                assertEquals("旧 token 列不应被改写", 700, cursor.getInt(2))
                assertNull("旧行 voiceUri 应为 NULL（非语音消息，不回填）", cursor.getString(3))
                assertEquals("旧行 voiceDurationMs 应为 0", 0L, cursor.getLong(4))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrationIsIdempotent() {
        val db = openAt120("migration_120_121_idempotent.db")
        try {
            seed(db)

            MuseDb.MIGRATION_120_121.migrate(db)
            // 再跑一遍：ensureMessageColumns 按 PRAGMA 判存在，重复执行不应抛 duplicate column
            MuseDb.MIGRATION_120_121.migrate(db)

            val columns = columnInfo(db, "messages")
            assertEquals("TEXT|notnull=0|default=NULL", columns["voiceUri"])
            assertEquals("INTEGER|notnull=1|default=0", columns["voiceDurationMs"])
            db.query("SELECT COUNT(*) FROM messages").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("重复迁移不应丢数据", 1, cursor.getInt(0))
            }
        } finally {
            db.close()
        }
    }
}
