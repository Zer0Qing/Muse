package io.zer0.memory.summary

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Explicit recovery entry for a memory.db archive created before a destructive downgrade.
 *
 * The archive is never copied over the live database. Known rows are read through SQLite's
 * read-only API and written through the current Room DAOs, so a future schema can add columns
 * without forcing the current app to reopen that newer database as its active Room file.
 */
class MemoryDbArchiveRecovery(
    private val context: Context,
    private val database: MemoryDb,
    private val databaseName: String = DEFAULT_DATABASE_NAME,
) {

    data class ArchiveInfo(
        val id: String,
        val storedVersion: Int,
        val sessionSummaries: Int,
        val dailyStates: Int,
        val compiledSections: Int,
        val scopedCompiledSections: Int,
    )

    data class RecoveryReport(
        val archiveId: String,
        val storedVersion: Int,
        val sessionSummaries: Int,
        val dailyStates: Int,
        val compiledSections: Int,
        val scopedCompiledSections: Int,
    ) {
        val totalRows: Int
            get() = sessionSummaries + dailyStates + compiledSections + scopedCompiledSections
    }

    private data class ArchiveCandidate(
        val info: ArchiveInfo,
        val directory: File,
    )

    private data class Snapshot(
        val storedVersion: Int,
        val sessionSummaries: List<SessionSummaryEntity>,
        val dailyStates: List<DailyStateEntity>,
        val compiledSections: List<CompiledSectionEntity>,
        val scopedCompiledSections: List<ScopedCompiledSectionEntity>,
    )

    /**
     * Lists readable downgrade archives without changing the live database or archive files.
     */
    suspend fun availableArchives(): List<ArchiveInfo> = withContext(Dispatchers.IO) {
        if (!currentDatabaseIsEmpty()) return@withContext emptyList()
        archiveCandidates().map { it.info }
    }

    /** Reads one archive preview and returns a diagnostic failure instead of silently dropping it. */
    suspend fun inspectArchive(archiveId: String): Result<ArchiveInfo> = withContext(Dispatchers.IO) {
        val databaseFile = context.getDatabasePath(databaseName)
        val directory = databaseFile.parentFile
            ?.listFiles { file -> file.isDirectory && file.name == archiveId }
            ?.singleOrNull()
            ?: return@withContext Result.failure(IllegalArgumentException("记忆库归档不存在: $archiveId"))
        val version = parseStoredVersion(directory.name, databaseFile.name)
            ?: return@withContext Result.failure(IllegalArgumentException("记忆库归档名称非法: $archiveId"))
        runCatching { readSnapshot(directory) }
            .map { snapshot ->
                ArchiveInfo(
                    id = directory.name,
                    storedVersion = version,
                    sessionSummaries = snapshot.sessionSummaries.size,
                    dailyStates = snapshot.dailyStates.size,
                    compiledSections = snapshot.compiledSections.size,
                    scopedCompiledSections = snapshot.scopedCompiledSections.size,
                )
            }
    }

    /**
     * Restores the newest readable archive only when the current database is empty.
     *
     * Refusing a non-empty live database is deliberate: recovery is a rescue path for the
     * destructive-downgrade result, not a merge tool that could overwrite newer user edits.
     */
    suspend fun restoreLatestIfEmpty(): Result<RecoveryReport> = withContext(Dispatchers.IO) {
        if (!currentDatabaseIsEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("当前记忆库已有数据，拒绝覆盖恢复"),
            )
        }
        val candidate = archiveCandidates()
            .maxWithOrNull(compareBy<ArchiveCandidate> { it.info.storedVersion }.thenBy { it.directory.lastModified() })
            ?: return@withContext Result.failure(IllegalStateException("没有可读取的记忆库归档"))
        val snapshot = readSnapshot(candidate.directory)
        if (snapshot.totalRows() == 0) {
            return@withContext Result.failure(IllegalStateException("归档中没有可恢复的记忆数据"))
        }

        database.withTransaction {
            snapshot.sessionSummaries.forEach { database.sessionSummaryDao().upsert(it) }
            snapshot.dailyStates.forEach { database.dailyStateDao().upsert(it) }
            snapshot.compiledSections.forEach { database.compiledSectionDao().upsert(it) }
            snapshot.scopedCompiledSections.forEach { database.scopedCompiledSectionDao().upsert(it) }
        }
        Result.success(
            RecoveryReport(
                archiveId = candidate.info.id,
                storedVersion = snapshot.storedVersion,
                sessionSummaries = snapshot.sessionSummaries.size,
                dailyStates = snapshot.dailyStates.size,
                compiledSections = snapshot.compiledSections.size,
                scopedCompiledSections = snapshot.scopedCompiledSections.size,
            ),
        )
    }

    private suspend fun currentDatabaseIsEmpty(): Boolean {
        return database.sessionSummaryDao().getAll().isEmpty() &&
            database.dailyStateDao().getAll().isEmpty() &&
            database.compiledSectionDao().getAll().isEmpty() &&
            database.scopedCompiledSectionDao().getAll().isEmpty()
    }

    private fun archiveCandidates(): List<ArchiveCandidate> {
        val databaseFile = context.getDatabasePath(databaseName)
        val parent = databaseFile.parentFile ?: return emptyList()
        val prefix = "${databaseFile.name}.pre-destructive.v"
        return parent.listFiles { file ->
            file.isDirectory && file.name.startsWith(prefix)
        }.orEmpty().mapNotNull { directory ->
            val version = parseStoredVersion(directory.name, databaseFile.name) ?: return@mapNotNull null
            val snapshot = runCatching { readSnapshot(directory) }
                .onFailure { error ->
                    Logger.w(TAG, "跳过不可读取的记忆库归档: ${directory.name}: ${error.message}")
                }
                .getOrNull()
                ?: return@mapNotNull null
            ArchiveCandidate(
                info = ArchiveInfo(
                    id = directory.name,
                    storedVersion = version,
                    sessionSummaries = snapshot.sessionSummaries.size,
                    dailyStates = snapshot.dailyStates.size,
                    compiledSections = snapshot.compiledSections.size,
                    scopedCompiledSections = snapshot.scopedCompiledSections.size,
                ),
                directory = directory,
            )
        }
    }

    private fun parseStoredVersion(directoryName: String, fileName: String): Int? {
        val match = Regex("^${Regex.escape(fileName)}\\.pre-destructive\\.v(\\d+)\\.bak(?:\\.\\d+)?$")
            .matchEntire(directoryName)
            ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun readSnapshot(directory: File): Snapshot {
        val databaseNameOnly = context.getDatabasePath(databaseName).name
        val archiveDatabase = File(directory, databaseNameOnly)
        require(archiveDatabase.isFile) { "归档缺少主数据库文件" }
        val temporaryPrefix = "memory_archive_read_${UUID.randomUUID()}"
        val temporaryDatabase = File(context.cacheDir, temporaryPrefix)
        val temporarySidecars = listOf("wal", "shm").map { suffix ->
            File(context.cacheDir, "$temporaryPrefix-$suffix")
        }
        try {
            archiveDatabase.copyTo(temporaryDatabase, overwrite = false)
            listOf("wal", "shm").forEachIndexed { index, suffix ->
                val sidecar = File(directory, "$databaseNameOnly-$suffix")
                if (sidecar.isFile) {
                    sidecar.copyTo(temporarySidecars[index], overwrite = false)
                }
            }
            return SQLiteDatabase.openDatabase(
                temporaryDatabase.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { source ->
                Snapshot(
                    storedVersion = source.version,
                    sessionSummaries = readTable(source, "session_summaries") { cursor ->
                    val sessionId = cursor.string("session_id")
                    if (sessionId.isBlank()) {
                        null
                    } else {
                        SessionSummaryEntity(
                            sessionId = sessionId,
                            createdAt = cursor.string("created_at", EPOCH),
                            updatedAt = cursor.string("updated_at", EPOCH),
                            summary = cursor.string("summary"),
                            messageCount = cursor.int("message_count"),
                            sourceTimeRange = cursor.nullableString("source_time_range"),
                            snapshot = cursor.string("snapshot"),
                            snapshotAt = cursor.nullableString("snapshot_at"),
                            assistantId = cursor.string("assistant_id"),
                            spaceId = cursor.string("space_id", "default"),
                        )
                    }
                    },
                    dailyStates = readTable(source, "daily_state") { cursor ->
                    val key = cursor.string("key", "default")
                    DailyStateEntity(
                        key = key.ifBlank { "default" },
                        schemaVersion = cursor.int("schema_version", 2),
                        logicalDate = cursor.string("logical_date", "1970-01-01"),
                        resetAt = cursor.nullableString("reset_at"),
                        factsMode = cursor.string("facts_mode", "legacy"),
                        completedSteps = cursor.string("completed_steps", "{}"),
                        dailyCompletedAt = cursor.nullableString("daily_completed_at"),
                        updatedAt = cursor.string("updated_at", EPOCH),
                    )
                    },
                    compiledSections = readTable(source, "compiled_sections") { cursor ->
                    val key = cursor.string("section_key")
                    if (key.isBlank()) {
                        null
                    } else {
                        CompiledSectionEntity(
                            sectionKey = key,
                            content = cursor.string("content"),
                            fingerprint = cursor.nullableString("fingerprint"),
                            updatedAt = cursor.string("updated_at", EPOCH),
                        )
                    }
                    },
                    scopedCompiledSections = readTable(source, "compiled_sections_scoped") { cursor ->
                    val key = cursor.string("section_key")
                    if (key.isBlank()) {
                        null
                    } else {
                        ScopedCompiledSectionEntity(
                            sectionKey = key,
                            scope = cursor.string("scope", "main"),
                            spaceId = cursor.string("space_id", "default"),
                            content = cursor.string("content"),
                            fingerprint = cursor.nullableString("fingerprint"),
                            updatedAt = cursor.string("updated_at", EPOCH),
                        )
                    }
                    },
                )
            }
        } finally {
            temporaryDatabase.delete()
            temporarySidecars.forEach { it.delete() }
        }
    }

    private fun <T> readTable(
        database: SQLiteDatabase,
        table: String,
        mapper: (Cursor) -> T?,
    ): List<T> {
        if (!hasTable(database, table)) return emptyList()
        return database.rawQuery("SELECT * FROM `$table`", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    mapper(cursor)?.let(::add)
                }
            }
        }
    }

    private fun hasTable(database: SQLiteDatabase, table: String): Boolean =
        database.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(table),
        ).use { it.moveToFirst() }

    private fun Cursor.string(column: String, default: String = ""): String {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) default else getString(index)
    }

    private fun Cursor.nullableString(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun Cursor.int(column: String, default: Int = 0): Int {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) default else getInt(index)
    }

    private fun Snapshot.totalRows(): Int =
        sessionSummaries.size + dailyStates.size + compiledSections.size + scopedCompiledSections.size

    private companion object {
        const val TAG = "MemoryDbArchiveRecovery"
        const val DEFAULT_DATABASE_NAME = "memory.db"
        const val EPOCH = "1970-01-01T00:00:00Z"
    }
}
