package io.zer0.muse.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** RestoreJournal 的阶段持久化和完成清理测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RestoreJournalTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun journal_persistsPhaseAndRemovesItAfterCompletion() = runBlocking {
        val journal = RestoreJournal(context)
        val entry = journal.begin("restore-test", "source-hash", 3)
        val advanced = journal.advance(
            entry,
            RestoreJournal.Phase.COMMITTING,
            completedStores = setOf(RestoreJournal.Store.MUSE_DB),
        )

        assertEquals(RestoreJournal.Phase.COMMITTING, journal.read()?.phase)
        assertEquals(setOf(RestoreJournal.Store.MUSE_DB), journal.read()?.completedStores)

        journal.complete(advanced)

        assertNull(journal.read())
        assertNotNull(context.filesDir)
        val journalFile = File(context.filesDir, "restore-journal.json")
        assertTrue(journalFile.delete() || !journalFile.exists())
    }

    @Test
    fun stagingAndRecoveryPointSurviveAndAreCleanedExplicitly() {
        val journal = RestoreJournal(context)
        val staging = RestoreStagingStore(context)
        val entry = journal.begin("restore-artifacts", "hash", 3)
        val backup = BackupService.Backup(
            exportedAt = 123L,
            sessions = emptyList(),
            messages = emptyList(),
        )

        staging.writeTarget(entry, backup)
        staging.writeRecoveryPoint(entry, backup)

        val persistedEntry = journal.read()
        assertNotNull(persistedEntry)
        assertEquals(backup, staging.readTarget(persistedEntry!!))
        assertEquals(backup, staging.readRecoveryPoint(persistedEntry))

        staging.cleanup(entry)
        assertNull(staging.readTarget(entry))
        assertNull(staging.readRecoveryPoint(entry))
        journal.complete(entry)
    }

    @Test
    fun journal_failKeepsDiagnosticStateWithoutPayload() {
        val journal = RestoreJournal(context)
        val entry = journal.begin("restore-failed", "hash", 3)

        val failed = journal.fail(entry, IllegalStateException("synthetic failure"))

        assertEquals(RestoreJournal.Phase.FAILED, failed.phase)
        assertEquals("synthetic failure", journal.read()?.failureReason)
        assertEquals("hash", journal.read()?.sourceHash)
        assertNotNull(journal.read())
        val journalFile = File(context.filesDir, "restore-journal.json")
        assertTrue(journalFile.delete() || !journalFile.exists())
    }
}
