package io.zer0.muse.data.schedule

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScheduledRoutineClaimSlotTest {

    private lateinit var db: MuseDb

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun routineClaimAndWatchdogRecoveryPreserveTheOriginalSlot() = runTest {
        val slotAt = 10_000L
        val schedule = ScheduledTaskEntity(
            id = "routine:check-in",
            name = "检查签到",
            interval = "cron",
            cronExpr = "0 8 * * *",
            nextRunAt = slotAt,
            lastRunAt = 3_000L,
            createdAt = 1L,
            updatedAt = 3_000L,
        )
        val dao = db.scheduledTaskDao()
        dao.upsert(schedule)

        assertEquals(1, dao.claimTask(schedule.id, slotAt, 11_000L, Long.MAX_VALUE))
        val claimed = requireNotNull(dao.getById(schedule.id))
        assertEquals(Long.MAX_VALUE, claimed.nextRunAt)
        assertEquals(slotAt, claimed.lastRunAt)
        assertEquals(11_000L, claimed.updatedAt)

        assertEquals(1, dao.recoverStuckClaim(Long.MAX_VALUE, staleBefore = 11_500L, now = 12_000L))
        val recovered = requireNotNull(dao.getById(schedule.id))
        assertEquals(slotAt, recovered.nextRunAt)
        assertEquals(slotAt, recovered.lastRunAt)
        assertEquals(12_000L, recovered.updatedAt)
    }

    @Test
    fun ordinaryScheduleRecoveryStillMovesTheDueTimeToNow() = runTest {
        val task = ScheduledTaskEntity(
            id = "ordinary-task",
            name = "ordinary",
            nextRunAt = 10_000L,
            lastRunAt = 3_000L,
            createdAt = 1L,
            updatedAt = 3_000L,
        )
        val dao = db.scheduledTaskDao()
        dao.upsert(task)
        assertEquals(1, dao.claimTask(task.id, task.nextRunAt, 11_000L, Long.MAX_VALUE))
        assertEquals(11_000L, requireNotNull(dao.getById(task.id)).lastRunAt)

        assertEquals(1, dao.recoverStuckClaim(Long.MAX_VALUE, staleBefore = 11_500L, now = 12_000L))
        assertEquals(12_000L, requireNotNull(dao.getById(task.id)).nextRunAt)
    }
}
