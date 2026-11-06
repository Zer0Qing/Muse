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
 * 版本 124 → 125(E2):新建会话术语表。
 *
 * 直接调用生产环境的 [MuseDb.MIGRATION_124_125]（不是复制一份 SQL），断言:
 *  - 表建起来了,列名/默认值与实体一致(Room 之后会按 schema 校验,名字对不上是升级期才炸);
 *  - 两个索引都在,且那个唯一索引**真的唯一**(重复插同 scope+session+term 要失败) ——
 *    术语累计次数依赖它,索引不存在时会出现同一个词两行,注入时随机挤掉一条;
 *  - 既有会话与消息一条不少(这一版只加表,不动老数据);
 *  - 可重复执行(幂等),不会因为表已存在而报错。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseDbMigration124To125Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun openAt124(name: String): SupportSQLiteDatabase {
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(124) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE `sessions` (`id` TEXT NOT NULL, `title` TEXT NOT NULL DEFAULT '', " +
                                    "`assistantId` TEXT NOT NULL DEFAULT 'default', PRIMARY KEY(`id`))",
                            )
                            db.execSQL(
                                "CREATE TABLE `messages` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL DEFAULT '', " +
                                    "`content` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))",
                            )
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

    private fun indexNames(db: SupportSQLiteDatabase): Set<String> {
        val names = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='index'").use { cursor ->
            while (cursor.moveToNext()) names += cursor.getString(0)
        }
        return names
    }

    @Test
    fun `升级建表且不动既有数据`() {
        val db = openAt124("muse_migration_124_to_125")
        db.execSQL("INSERT INTO sessions (id, title, assistantId) VALUES ('s-1', '老会话', 'default')")
        db.execSQL("INSERT INTO messages (id, sessionId, content) VALUES ('m-1', 's-1', '旧消息')")

        MuseDb.MIGRATION_124_125.migrate(db)

        assertTrue("表没建起来: ${tableNames(db)}", tableNames(db).contains("session_terms"))
        assertTrue(
            "索引不齐: ${indexNames(db)}",
            indexNames(db).containsAll(
                setOf("index_session_terms_assistant_id", "index_session_terms_scope_session_id_term"),
            ),
        )
        db.query("SELECT COUNT(*) FROM sessions").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        db.query("SELECT content FROM messages WHERE id = 'm-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("旧消息", cursor.getString(0))
        }
        db.close()
    }

    @Test
    fun `列与默认值逐项对齐实体`() {
        val db = openAt124("muse_migration_124_to_125_columns")
        MuseDb.MIGRATION_124_125.migrate(db)

        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info(`session_terms`)").use { cursor ->
            while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
        }
        assertEquals(
            setOf(
                "id", "term", "meaning", "session_id", "assistant_id",
                "scope", "occurrences", "confirmed", "first_seen_at", "last_seen_at",
            ),
            columns,
        )

        // 只给必填列,靠默认值补齐 —— 默认值写错时这里会红,而不是等到线上注入出一条 "0 次"的术语
        db.execSQL(
            "INSERT INTO session_terms (term, session_id, assistant_id, first_seen_at, last_seen_at) " +
                "VALUES ('开黑', 's-1', 'default', 1, 1)",
        )
        db.query("SELECT scope, occurrences, confirmed, meaning FROM session_terms").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("session", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals(0, cursor.getInt(2))
            assertTrue(cursor.isNull(3))
        }
        db.close()
    }

    @Test
    fun `唯一索引真的拦住同作用域的同名词`() {
        val db = openAt124("muse_migration_124_to_125_unique")
        MuseDb.MIGRATION_124_125.migrate(db)
        db.execSQL(
            "INSERT INTO session_terms (term, session_id, assistant_id, first_seen_at, last_seen_at) " +
                "VALUES ('开黑', 's-1', 'default', 1, 1)",
        )

        val failed = runCatching {
            db.execSQL(
                "INSERT INTO session_terms (term, session_id, assistant_id, first_seen_at, last_seen_at) " +
                    "VALUES ('开黑', 's-1', 'default', 2, 2)",
            )
        }.isFailure
        assertTrue("同一 (scope, session, term) 必须只能有一行,否则次数会分裂成两行", failed)

        // 不同会话可以各有一行:作用域默认在会话内
        db.execSQL(
            "INSERT INTO session_terms (term, session_id, assistant_id, first_seen_at, last_seen_at) " +
                "VALUES ('开黑', 's-2', 'default', 2, 2)",
        )
        db.query("SELECT COUNT(*) FROM session_terms").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
        db.close()
    }

    @Test
    fun `迁移可以重复执行`() {
        val db = openAt124("muse_migration_124_to_125_idempotent")
        MuseDb.MIGRATION_124_125.migrate(db)
        val before = indexNames(db)

        runCatching { MuseDb.MIGRATION_124_125.migrate(db) }.onFailure {
            assertTrue("重复执行应当幂等而不是抛错: $it", false)
        }
        assertEquals(before, indexNames(db))
        db.close()
    }
}
