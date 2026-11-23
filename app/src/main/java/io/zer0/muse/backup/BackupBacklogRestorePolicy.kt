package io.zer0.muse.backup

import io.zer0.muse.data.proactive.BacklogDispatchState
import io.zer0.muse.data.proactive.ProactiveBacklogDispatchEntity
import io.zer0.muse.data.proactive.ProactiveBacklogRepository
import io.zer0.muse.data.proactive.ProactiveBacklogScheduleEntity

internal data class RestoredBacklogSnapshot(
    val dispatches: List<ProactiveBacklogDispatchEntity>,
    val schedule: List<ProactiveBacklogScheduleEntity>,
)

internal fun prepareBacklogRestoreSnapshot(
    sourceDispatches: List<ProactiveBacklogDispatchEntity>,
    sourceSchedule: List<ProactiveBacklogScheduleEntity>,
    now: Long,
): RestoredBacklogSnapshot {
    val hadClaimed = sourceDispatches.any { it.state == BacklogDispatchState.CLAIMED }
    val resumeAt = now + ProactiveBacklogRepository.DISPATCH_GAP_MS
    val dispatches = sourceDispatches.map { dispatch ->
        if (dispatch.state == BacklogDispatchState.CLAIMED) {
            dispatch.copy(
                state = BacklogDispatchState.READY,
                claimToken = null,
                claimUntil = null,
                nextAttemptAt = maxOf(dispatch.nextAttemptAt, resumeAt),
                updatedAt = now,
            )
        } else {
            dispatch.copy(claimToken = null, claimUntil = null)
        }
    }
    val savedAt = sourceSchedule.maxOfOrNull { it.nextBacklogDispatchAt } ?: 0L
    val rowsAt = dispatches.maxOfOrNull { it.nextAttemptAt } ?: 0L
    val nextAt = maxOf(savedAt, rowsAt, if (hadClaimed) resumeAt else 0L)
    val schedule = if (sourceSchedule.isNotEmpty() || dispatches.isNotEmpty()) {
        listOf(ProactiveBacklogScheduleEntity(nextBacklogDispatchAt = nextAt, updatedAt = now))
    } else {
        emptyList()
    }
    return RestoredBacklogSnapshot(dispatches, schedule)
}
