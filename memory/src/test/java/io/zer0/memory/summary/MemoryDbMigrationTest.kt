package io.zer0.memory.summary

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** 验证 MemoryDb v1→v2→v3 只增列/增表，并保留旧编译产物。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryDbMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val tempFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        tempFiles.forEach { it.deleteRecursively() }
    }

    @Test
    fun `migration preserves legacy content and copies it to default scoped slot`() = runTest {
        val dir = Files.createTempDirectory("memory-migration").toFile()
        tempFiles += dir
        val file = File(dir, "memory.db")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(file.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE session_summaries (session_id TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, summary TEXT NOT NULL, message_count INTEGER NOT NULL, source_time_range TEXT, snapshot TEXT NOT NULL, snapshot_at TEXT, assistant_id TEXT NOT NULL, PRIMARY KEY(session_id))")
                        db.execSQL("CREATE TABLE daily_state (`key` TEXT NOT NULL, schema_version INTEGER NOT NULL, logical_date TEXT NOT NULL, reset_at TEXT, facts_mode TEXT NOT NULL, completed_steps TEXT NOT NULL, daily_completed_at TEXT, updated_at TEXT NOT NULL, PRIMARY KEY(`key`))")
                        db.execSQL("CREATE TABLE compiled_sections (section_key TEXT NOT NULL, content TEXT NOT NULL, fingerprint TEXT, updated_at TEXT NOT NULL, PRIMARY KEY(section_key))")
                        db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                        db.execSQL("INSERT INTO room_master_table VALUES (42, '5b74599c11f2cfdaae37cbc32148a9fd')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val oldDb = helper.writableDatabase
        oldDb.execSQL("INSERT INTO compiled_sections VALUES ('facts', 'legacy facts', NULL, '2026-08-22T00:00:00Z')")
        helper.close()

        val db = Room.databaseBuilder(context, MemoryDb::class.java, file.absolutePath)
            .allowMainThreadQueries()
            .addMigrations(MemoryDb.MIGRATION_1_2, MemoryDb.MIGRATION_2_3)
            .build()
        try {
            assertEquals("legacy facts", db.compiledSectionDao().get("facts")?.content)
            val scoped = db.scopedCompiledSectionDao().get("facts", "main", "default")
            assertEquals("legacy facts", scoped?.content)
            assertTrue(db.scopedCompiledSectionDao().getAll().any { it.spaceId == "default" })
            assertEquals("default", db.sessionSummaryDao().get("missing")?.spaceId ?: "default")
        } finally {
            db.close()
        }
    }
}
