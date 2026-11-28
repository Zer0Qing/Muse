package io.zer0.muse.data.proactive

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.notification.MuseNotificationIdPolicy
import java.util.UUID
import kotlin.uuid.Uuid

sealed interface BacklogPublishResult {
    data class Published(val messageId: String, val outcomeId: String) : BacklogPublishResult
    data object ClaimLost : BacklogPublishResult
    data object TargetUnavailable : BacklogPublishResult
    data object BudgetUnavailable : BacklogPublishResult
}

class ProactiveBacklogPublisher(
    private val database: MuseDb,
    private val sessions: SessionRepository,
    private val relations: ProactiveRelationRepository,
    private val schedule: ProactiveBacklogScheduleDao,
) {
    suspend fun publish(claim: ClaimedBacklog, content: String, assistantName: String, now: Long): BacklogPublishResult {
        val dispatchId = claim.dispatch.dispatchId
        val messageId = stableUuid("message:$dispatchId")
        val outcomeId = stableUuid("outcome:$dispatchId")
        val notificationId = MuseNotificationIdPolicy.notificationIdForOutcome(outcomeId)
        // A failed commit may delay the next item, while a process death cannot reopen the gap early.
        schedule.deferUntil(now + ProactiveBacklogRepository.DISPATCH_GAP_MS, now)
        return try {
            val appendedId = sessions.appendMessageWithTransaction(
                sessionId = claim.dispatch.sessionId,
                message = UIMessage(
                    id = Uuid.parse(messageId),
                    role = MessageRole.ASSISTANT,
                    content = content,
                    createdAt = now,
                ),
            ) { actualMessageId ->
                val dispatch = database.proactiveBacklogDao().getById(dispatchId)
                if (dispatch?.state != BacklogDispatchState.CLAIMED || dispatch.claimToken != claim.claimToken ||
                    dispatch.claimUntil == null || dispatch.claimUntil <= now
                ) {
                    throw PublishAbort(BacklogPublishResult.ClaimLost)
                }
                val thought = database.pendingThoughtDao().getById(claim.thought.id)
                if (thought?.status != ThoughtStatus.OPEN.name || thought.lastSurfacedAt != null ||
                    thought.assistantId != claim.dispatch.assistantId
                ) {
                    throw PublishAbort(BacklogPublishResult.TargetUnavailable)
                }
                val relation = relations.getOrCreate(claim.dispatch.assistantId, now)
                if (ProactiveTimingEngine.remainingBudget(relation) < 1f) {
                    throw PublishAbort(BacklogPublishResult.BudgetUnavailable)
                }
                if (database.pendingThoughtWriteDao().markSurfacedIfOpen(thought.id, now) != 1) {
                    throw PublishAbort(BacklogPublishResult.TargetUnavailable)
                }
                val outcome = ProactiveOutcomeEntity(
                    id = outcomeId,
                    thoughtId = thought.id,
                    messageId = actualMessageId,
                    assistantId = claim.dispatch.assistantId,
                    sessionId = claim.dispatch.sessionId,
                    kind = thought.kind,
                    sentAt = now,
                    state = OutcomeState.PENDING.name,
                    notificationState = ProactiveNotificationState.PENDING,
                    notificationId = notificationId,
                    notificationTitle = assistantName,
                    notificationPreview = content,
                )
                val inserted = database.proactiveOutcomeDao().insertIfAbsent(outcome)
                if (inserted == -1L) {
                    val existing = database.proactiveOutcomeDao().getById(outcomeId)
                    if (existing?.messageId != actualMessageId || existing.thoughtId != thought.id) {
                        throw IllegalStateException("Stable proactive outcome key conflicts with another publication")
                    }
                }
                relations.consume(claim.dispatch.assistantId)
                if (database.proactiveBacklogDao().markSent(dispatchId, claim.claimToken, outcomeId, now) != 1) {
                    throw PublishAbort(BacklogPublishResult.ClaimLost)
                }
            }
            if (appendedId == null) {
                BacklogPublishResult.TargetUnavailable
            } else {
                BacklogPublishResult.Published(appendedId, outcomeId)
            }
        } catch (abort: PublishAbort) {
            abort.result
        }
    }

    private fun stableUuid(key: String): String = UUID.nameUUIDFromBytes("muse-proactive:$key".toByteArray(Charsets.UTF_8)).toString()

    private class PublishAbort(val result: BacklogPublishResult) : RuntimeException()
}
