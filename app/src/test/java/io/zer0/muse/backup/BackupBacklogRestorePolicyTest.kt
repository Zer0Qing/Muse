package io.zer0.muse.backup

import io.zer0.muse.data.proactive.BacklogDispatchState
import io.zer0.muse.data.proactive.ProactiveBacklogDispatchEntity
import io.zer0.muse.data.proactive.ProactiveBacklogRepository
import io.zer0.muse.data.proactive.ProactiveBacklogScheduleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupBacklogRestorePolicyTest {

    @Test
    fun `claimed backlog is released and delayed by a full dispatch gap`() {
        val claimed = dispatch(
            id = "claimed",
            state = BacklogDispatchState.CLAIMED,
            token = "dead-process-token",
            leaseUntil = 50_000L,
            nextAttemptAt = 1L,
        )
        val ready = dispatch(id = "ready", nextAttemptAt = 500_000L)
        val sourceSchedule = listOf(ProactiveBacklogScheduleEntity(nextBacklogDispatchAt = 40_000L))

        val restored = prepareBacklogRestoreSnapshot(listOf(claimed, ready), sourceSchedule, now = 10_000L)

        val restoredClaim = restored.dispatches.first { it.dispatchId == "claimed" }
        assertEquals(BacklogDispatchState.READY, restoredClaim.state)
        assertNull(restoredClaim.claimToken)
        assertNull(restoredClaim.claimUntil)
        assertEquals(10_000L + ProactiveBacklogRepository.DISPATCH_GAP_MS, restoredClaim.nextAttemptAt)
        assertEquals(ready, restored.dispatches.first { it.dispatchId == "ready" })
        assertEquals(500_000L, restored.schedule.single().nextBacklogDispatchAt)
    }

    @Test
    fun `backup without active claims preserves saved schedule and dispatch states`() {
        val failed = dispatch(id = "failed", state = BacklogDispatchState.FAILED, nextAttemptAt = 80_000L)
        val sourceSchedule = listOf(ProactiveBacklogScheduleEntity(nextBacklogDispatchAt = 75_000L))

        val restored = prepareBacklogRestoreSnapshot(listOf(failed), sourceSchedule, now = 10_000L)

        assertEquals(failed, restored.dispatches.single())
        assertEquals(80_000L, restored.schedule.single().nextBacklogDispatchAt)
    }

    private fun dispatch(
        id: String,
        state: String = BacklogDispatchState.READY,
        token: String? = null,
        leaseUntil: Long? = null,
        nextAttemptAt: Long = 0L,
    ) = ProactiveBacklogDispatchEntity(
        dispatchId = id,
        thoughtId = "thought-$id",
        assistantId = "assistant-1",
        sessionId = "session-1",
        state = state,
        claimToken = token,
        claimUntil = leaseUntil,
        nextAttemptAt = nextAttemptAt,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
