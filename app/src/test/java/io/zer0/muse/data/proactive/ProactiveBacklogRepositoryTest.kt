package io.zer0.muse.data.proactive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProactiveBacklogRepositoryTest {
    private lateinit var db: MuseDb
    private lateinit var repository: ProactiveBacklogRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .setQueryExecutor(java.util.concurrent.Executor { it.run() })
            .setTransactionExecutor(java.util.concurrent.Executor { it.run() })
            .allowMainThreadQueries()
            .build()
        repository = ProactiveBacklogRepository(
            thoughts = db.pendingThoughtDao(),
            backlog = db.proactiveBacklogDao(),
            schedule = db.proactiveBacklogScheduleDao(),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun reconcileAddsOnlyOldOpenUnsurfacedAndUnexpiredThoughtsIdempotently() = runTest {
        val now = 2_000_000L
        val cutoff = now - ProactiveBacklogRepository.NORMAL_MINIMUM_INTERVAL_MS
        db.pendingThoughtDao().upsert(thought("eligible", createdAt = cutoff))
        db.pendingThoughtDao().upsert(thought("recent", createdAt = cutoff + 1))
        db.pendingThoughtDao().upsert(thought("surfaced", createdAt = cutoff, lastSurfacedAt = 1L))
        db.pendingThoughtDao().upsert(thought("expired", createdAt = cutoff, expiresAt = now))
        db.pendingThoughtDao().upsert(thought("other-assistant", assistantId = "other", createdAt = cutoff))

        assertEquals(1, repository.reconcile(now, ASSISTANT, SESSION))
        assertEquals(0, repository.reconcile(now, ASSISTANT, SESSION))
        assertEquals(listOf("eligible"), db.proactiveBacklogDao().getAll().map { it.thoughtId })
        assertEquals(ThoughtStatus.EXPIRED.name, db.pendingThoughtDao().getById("expired")?.status)
        assertNull(db.proactiveBacklogDao().findByThoughtId("recent"))
        assertNull(db.proactiveBacklogDao().findByThoughtId("surfaced"))
        assertNull(db.proactiveBacklogDao().findByThoughtId("other-assistant"))
    }

    private fun thought(
        id: String,
        assistantId: String = ASSISTANT,
        createdAt: Long,
        expiresAt: Long? = null,
        lastSurfacedAt: Long? = null,
    ) = PendingThoughtEntity(
        id = id,
        assistantId = assistantId,
        seed = "seed-$id",
        createdAt = createdAt,
        expiresAt = expiresAt,
        lastSurfacedAt = lastSurfacedAt,
    )

    private companion object {
        const val ASSISTANT = "assistant-1"
        const val SESSION = "session-1"
    }
}
