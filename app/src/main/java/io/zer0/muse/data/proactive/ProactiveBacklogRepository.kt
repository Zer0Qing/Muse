package io.zer0.muse.data.proactive

import java.util.UUID

data class ClaimedBacklog(
    val dispatch: ProactiveBacklogDispatchEntity,
    val thought: PendingThoughtEntity,
    val claimToken: String,
)

class ProactiveBacklogRepository(
    private val thoughts: PendingThoughtDao,
    private val backlog: ProactiveBacklogDao,
    private val schedule: ProactiveBacklogScheduleDao,
    private val normalMinimumIntervalMs: Long = ProactiveBacklogRepository.NORMAL_MINIMUM_INTERVAL_MS,
) {
    companion object {
        const val DISPATCH_GAP_MS = 330_000L
        const val CLAIM_LEASE_MS = 120_000L
        const val NORMAL_MINIMUM_INTERVAL_MS = ProactiveTimingEngine.MIN_INTERVAL_MINUTES * 60_000L
        private const val CLAIM_RETRIES = 8
    }

    suspend fun reconcile(now: Long, assistantId: String, sessionId: String): Int {
        thoughts.expireForAssistant(assistantId, now)
        val eligible = thoughts.getBacklogEligible(
            assistantId = assistantId,
            createdBefore = now - normalMinimumIntervalMs.coerceAtLeast(0L),
            now = now,
        )
        val alreadyDispatched = backlog.getAll().mapTo(mutableSetOf(), ProactiveBacklogDispatchEntity::thoughtId)
        var inserted = 0
        eligible.forEach { thought ->
            if (thought.id in alreadyDispatched) return@forEach
            val id = UUID.nameUUIDFromBytes("muse-backlog:${thought.id}".toByteArray()).toString()
            val rowId = backlog.insertIfAbsent(
                ProactiveBacklogDispatchEntity(
                    dispatchId = id,
                    thoughtId = thought.id,
                    assistantId = assistantId,
                    sessionId = sessionId,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            alreadyDispatched += thought.id
            if (rowId != -1L) {
                inserted++
            }
        }

        backlog.getAll()
            .asSequence()
            .filter { it.assistantId == assistantId }
            .filter { !BacklogDispatchState.isTerminal(it.state) }
            .forEach { dispatch ->
                val thought = thoughts.getById(dispatch.thoughtId)
                val isInvalid = thought == null || thought.status != ThoughtStatus.OPEN.name ||
                    thought.lastSurfacedAt != null || (thought.expiresAt != null && thought.expiresAt <= now)
                if (isInvalid) backlog.expireForThought(dispatch.thoughtId, now)
            }
        return inserted
    }

    suspend fun claimOne(now: Long, leaseUntil: Long = now + CLAIM_LEASE_MS): ClaimedBacklog? {
        releaseExpiredClaims(now)
        repeat(CLAIM_RETRIES) {
            val candidate = backlog.findNextReady(now) ?: return null
            val thought = thoughts.getById(candidate.thoughtId)
            if (thought == null || thought.status != ThoughtStatus.OPEN.name || thought.lastSurfacedAt != null) {
                backlog.expireForThought(candidate.thoughtId, now)
                return@repeat
            }
            val token = UUID.randomUUID().toString()
            if (backlog.claim(candidate.dispatchId, token, leaseUntil.coerceAtLeast(now + 1L), now) == 1) {
                return ClaimedBacklog(candidate.copy(state = BacklogDispatchState.CLAIMED), thought, token)
            }
        }
        return null
    }

    suspend fun releaseExpiredClaims(now: Long): Int {
        val releaseAfter = now + DISPATCH_GAP_MS
        val released = backlog.releaseExpiredClaimsWithDelay(now, releaseAfter)
        if (released > 0) schedule.deferUntil(releaseAfter, now)
        return released
    }

    suspend fun bindClaimedSession(claim: ClaimedBacklog, sessionId: String, now: Long): ClaimedBacklog? {
        val updated = backlog.bindClaimedSession(claim.dispatch.dispatchId, claim.claimToken, sessionId, now)
        if (updated != 1) return null
        return claim.copy(dispatch = claim.dispatch.copy(sessionId = sessionId))
    }

    suspend fun retry(claim: ClaimedBacklog, now: Long, error: String, allowedAt: Long = 0L): Int {
        val nextAt = maxOf(now + DISPATCH_GAP_MS, allowedAt)
        schedule.deferUntil(nextAt, now)
        return backlog.markRetry(claim.dispatch.dispatchId, claim.claimToken, nextAt, error, now)
    }

    suspend fun fail(claim: ClaimedBacklog, now: Long, error: String): Int =
        backlog.markFailed(claim.dispatch.dispatchId, claim.claimToken, error, now)

    suspend fun nextBacklogDispatchAt(): Long = schedule.getNextBacklogDispatchAt() ?: 0L

    suspend fun nextWorkAt(now: Long): Long {
        val laneAt = schedule.getNextBacklogDispatchAt() ?: 0L
        val rowAt = backlog.getNextUnfinishedAt(now) ?: 0L
        return maxOf(laneAt, rowAt)
    }

    suspend fun hasUnfinishedDispatches(): Boolean = backlog.countUnfinished() > 0
}
