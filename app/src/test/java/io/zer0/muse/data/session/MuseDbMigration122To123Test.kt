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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseDbMigration122To123Test {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun migrationAddsConversationTablesWithoutTouchingExistingRows() {
        val db = openAt122()
        try {
            db.execSQL("INSERT INTO sessions (id, title) VALUES ('session-1', 'kept')")
            db.execSQL("INSERT INTO messages (id, sessionId, content) VALUES ('message-1', 'session-1', 'kept body')")

            MuseDb.MIGRATION_122_123.migrate(db)

            assertTrue(tableNames(db).containsAll(setOf(
                "conversation_command_receipts",
                "conversation_selections",
                "conversation_display_slots",
            )))
            db.query("SELECT title FROM sessions WHERE id = 'session-1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("kept", it.getString(0))
            }
            db.query("SELECT content FROM messages WHERE id = 'message-1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("kept body", it.getString(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrationIsIdempotent() {
        val db = openAt122()
        try {
            MuseDb.MIGRATION_122_123.migrate(db)
            MuseDb.MIGRATION_122_123.migrate(db)

            assertTrue(tableNames(db).containsAll(setOf(
                "conversation_command_receipts",
                "conversation_selections",
                "conversation_display_slots",
            )))
        } finally {
            db.close()
        }
    }

    private fun openAt122(): SupportSQLiteDatabase {
        val name = "migration_122_123_${System.nanoTime()}.db"
        context.deleteDatabase(name)
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(122) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE `sessions` (" +
                                    "`id` TEXT NOT NULL PRIMARY KEY, " +
                                    "`title` TEXT NOT NULL DEFAULT '')",
                            )
                            db.execSQL(
                                "CREATE TABLE `messages` (" +
                                    "`id` TEXT NOT NULL PRIMARY KEY, " +
                                    "`sessionId` TEXT NOT NULL DEFAULT '', " +
                                    "`content` TEXT NOT NULL DEFAULT '')",
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        ).writableDatabase
    }

    private fun tableNames(db: SupportSQLiteDatabase): Set<String> =
        buildSet {
            db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
}
