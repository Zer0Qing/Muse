package io.zer0.muse.data.proactive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProactiveBacklogClaimTest {
    private lateinit var db: MuseDb
    private lateinit var repository: ProactiveBacklogRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ProactiveBacklogRepository(db.pendingThoughtDao(), db.proactiveBacklogDao(), db.proactiveBacklogScheduleDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun concurrentClaimersCannotOwnTheSameThought() = runTest {
        val thought = PendingThoughtEntity(id = "t1", assistantId = "a1", createdAt = 1L)
        db.pendingThoughtDao().upsert(thought)
        db.proactiveBacklogDao().insertIfAbsent(
            ProactiveBacklogDispatchEntity(
                dispatchId = "d1", thoughtId = thought.id, assistantId = "a1", sessionId = "s1",
                createdAt = 1L, updatedAt = 1L,
            ),
        )

        val left = async(Dispatchers.IO) { repository.claimOne(10_000L) }
        val right = async(Dispatchers.IO) { repository.claimOne(10_000L) }
        val claimed = listOf(left.await(), right.await()).filterNotNull()
        assertEquals(1, claimed.size)
        assertNotNull(claimed.single().claimToken)
        assertEquals(BacklogDispatchState.CLAIMED, db.proactiveBacklogDao().getById("d1")?.state)
    }

    @Test
    fun expiredLeaseCanBeReclaimedOnlyAfterAFullDispatchGap() = runTest {
        val thought = PendingThoughtEntity(id = "t1", assistantId = "a1", createdAt = 1L)
        db.pendingThoughtDao().upsert(thought)
        db.proactiveBacklogDao().insertIfAbsent(
            ProactiveBacklogDispatchEntity(
                dispatchId = "d1", thoughtId = thought.id, assistantId = "a1", sessionId = "s1",
                createdAt = 1L, updatedAt = 1L,
            ),
        )
        val first = repository.claimOne(now = 10_000L, leaseUntil = 20_000L) ?: error("first claim expected")

        assertEquals(1, repository.releaseExpiredClaims(now = 20_000L))
        val nextAt = 20_000L + ProactiveBacklogRepository.DISPATCH_GAP_MS
        assertEquals(nextAt, repository.nextBacklogDispatchAt())
        assertNull(repository.claimOne(now = nextAt - 1L))
        val recovered = repository.claimOne(now = nextAt) ?: error("expired claim should be available")
        assertEquals("recovery receives a fresh owner token", false, first.claimToken == recovered.claimToken)
    }
}
