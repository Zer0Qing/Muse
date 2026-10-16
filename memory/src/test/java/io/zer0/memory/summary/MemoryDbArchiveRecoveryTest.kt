package io.zer0.memory.summary

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import io.zer0.memory.fact.MemoryLegacyReset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class MemoryDbArchiveRecoveryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "memory_recovery_${UUID.randomUUID()}.db"
    private lateinit var databaseFile: File
    private val cleanupFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()
        MemoryLegacyReset.consume(context)
    }

    @After
    fun tearDown() {
        cleanupFiles.forEach { it.deleteRecursively() }
        listOf(
            databaseFile,
            File("${databaseFile.absolutePath}-wal"),
            File("${databaseFile.absolutePath}-shm"),
        ).forEach { it.delete() }
        MemoryLegacyReset.consume(context)
    }

    @Test
    fun `empty current memory database restores known rows from newer archive`() = runTest {
        val archive = createArchive()
        val copiedArchive = File(context.cacheDir, "memory_archive_verify_${UUID.randomUUID()}.db")
        cleanupFiles += copiedArchive
        File(archive, databaseFile.name).copyTo(copiedArchive, overwrite = true)
        assertTrue(File(archive, databaseFile.name).isFile)
        SQLiteDatabase.openDatabase(copiedArchive.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { archivedDb ->
            assertEquals(MEMORY_DB_VERSION + 1, archivedDb.version)
        }
        val db = openCurrentDatabase()
        val recovery = MemoryDbArchiveRecovery(context, db, databaseName)
        val preview = recovery.inspectArchive(archive.name)
        assertTrue("preview=${preview.exceptionOrNull()?.message}", preview.isSuccess)

        val result = recovery.restoreLatestIfEmpty()

        assertTrue("result=${result.exceptionOrNull()?.message}", result.isSuccess)
        val report = result.getOrThrow()
        assertEquals(1, report.sessionSummaries)
        assertEquals(1, report.dailyStates)
        assertEquals(1, report.compiledSections)
        assertEquals(1, report.scopedCompiledSections)
        assertEquals("summary", db.sessionSummaryDao().get("session-1")?.summary)
        assertEquals("2026-10-03", db.dailyStateDao().get("default")?.logicalDate)
        assertEquals("facts", db.compiledSectionDao().get("facts")?.content)
        assertEquals("space-content", db.scopedCompiledSectionDao().get("facts", "main", "space-1")?.content)
        assertTrue(archive.exists())
        db.close()
    }

    @Test
    fun `recovery refuses to overwrite a nonempty current memory database`() = runTest {
        createArchive()
        val db = openCurrentDatabase()
        db.sessionSummaryDao().upsert(
            SessionSummaryEntity(
                sessionId = "new-session",
                createdAt = "2026-10-03T00:00:00Z",
                updatedAt = "2026-10-03T00:00:00Z",
                summary = "new",
            ),
        )
        val recovery = MemoryDbArchiveRecovery(context, db, databaseName)

        assertTrue(recovery.availableArchives().isEmpty())
        val result = recovery.restoreLatestIfEmpty()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("已有数据"))
        assertEquals("new", db.sessionSummaryDao().get("new-session")?.summary)
        db.close()
    }

    private fun openCurrentDatabase(): MemoryDb = Room.databaseBuilder(
        context,
        MemoryDb::class.java,
        databaseFile.absolutePath,
    )
        .addMigrations(MemoryDb.MIGRATION_1_2, MemoryDb.MIGRATION_2_3)
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        .allowMainThreadQueries()
        .build()
        .also { it.openHelper.writableDatabase.query("SELECT COUNT(*) FROM session_summaries").use { cursor -> cursor.moveToFirst() } }

    private fun createArchive(): File {
        val directory = File(
            databaseFile.parentFile,
            "${databaseFile.name}.pre-destructive.v${MEMORY_DB_VERSION + 1}.bak",
        ).apply { mkdirs() }
        cleanupFiles += directory
        val archived = File(directory, databaseFile.name)
        val source = File(context.cacheDir, "memory_archive_source_${UUID.randomUUID()}.db")
        cleanupFiles += source
        SQLiteDatabase.openOrCreateDatabase(source, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE session_summaries (
                    session_id TEXT NOT NULL PRIMARY KEY,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    message_count INTEGER NOT NULL,
                    source_time_range TEXT,
                    snapshot TEXT NOT NULL,
                    snapshot_at TEXT,
                    assistant_id TEXT NOT NULL,
                    space_id TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE daily_state (
                    `key` TEXT NOT NULL PRIMARY KEY,
                    schema_version INTEGER NOT NULL,
                    logical_date TEXT NOT NULL,
                    reset_at TEXT,
                    facts_mode TEXT NOT NULL,
                    completed_steps TEXT NOT NULL,
                    daily_completed_at TEXT,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE compiled_sections (
                    section_key TEXT NOT NULL PRIMARY KEY,
                    content TEXT NOT NULL,
                    fingerprint TEXT,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE compiled_sections_scoped (
                    section_key TEXT NOT NULL,
                    scope TEXT NOT NULL,
                    space_id TEXT NOT NULL,
                    content TEXT NOT NULL,
                    fingerprint TEXT,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY(section_key, scope, space_id)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO session_summaries VALUES ('session-1','2026-10-03T00:00:00Z','2026-10-03T00:00:00Z','summary',3,NULL,'snapshot',NULL,'default','default')",
            )
            db.execSQL(
                "INSERT INTO daily_state VALUES ('default',2,'2026-10-03',NULL,'legacy','{}',NULL,'2026-10-03T00:00:00Z')",
            )
            db.execSQL(
                "INSERT INTO compiled_sections VALUES ('facts','facts',NULL,'2026-10-03T00:00:00Z')",
            )
            db.execSQL(
                "INSERT INTO compiled_sections_scoped VALUES ('facts','main','space-1','space-content',NULL,'2026-10-03T00:00:00Z')",
            )
            db.version = MEMORY_DB_VERSION + 1
        }
        source.copyTo(archived, overwrite = true)
        listOf("wal", "shm").forEach { suffix ->
            val sourceSidecar = File("${source.absolutePath}-$suffix")
            if (sourceSidecar.isFile) {
                val archivedSidecar = File(directory, "${databaseFile.name}-$suffix")
                sourceSidecar.copyTo(archivedSidecar, overwrite = true)
                cleanupFiles += sourceSidecar
            }
        }
        return directory
    }
}
