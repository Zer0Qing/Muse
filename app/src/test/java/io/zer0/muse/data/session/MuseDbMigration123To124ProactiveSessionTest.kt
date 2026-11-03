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
class MuseDbMigration123To124ProactiveSessionTest {
    @Test
    fun migrationAddsSessionProactivePreferenceWithoutTouchingSessions() {
        val db = openAt123()
        try {
            db.execSQL("INSERT INTO sessions (id, title) VALUES ('session-1', 'kept')")

            MuseDb.MIGRATION_123_124.migrate(db)

            assertTrue(tableNames(db).contains("session_proactive_preferences"))
            db.query("SELECT title FROM sessions WHERE id = 'session-1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("kept", it.getString(0))
            }
            db.query(
                "SELECT muted FROM session_proactive_preferences WHERE sessionId = 'session-1'",
            ).use {
                assertEquals(0, it.count)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrationIsIdempotent() {
        val db = openAt123()
        try {
            MuseDb.MIGRATION_123_124.migrate(db)
            MuseDb.MIGRATION_123_124.migrate(db)
            assertTrue(tableNames(db).contains("session_proactive_preferences"))
        } finally {
            db.close()
        }
    }

    private fun openAt123(): SupportSQLiteDatabase {
        val name = "migration_123_124_${System.nanoTime()}.db"
        context.deleteDatabase(name)
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(123) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE `sessions` (" +
                                    "`id` TEXT NOT NULL PRIMARY KEY, " +
                                    "`title` TEXT NOT NULL DEFAULT '')",
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

    private val context: Context get() = ApplicationProvider.getApplicationContext()
}
