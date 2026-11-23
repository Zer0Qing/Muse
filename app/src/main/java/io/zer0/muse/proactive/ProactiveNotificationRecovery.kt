package io.zer0.muse.proactive

import io.zer0.common.Logger
import io.zer0.muse.data.proactive.ProactiveOutcomeRepository
import io.zer0.muse.notification.MuseNotificationIdPolicy
import io.zer0.muse.notification.MuseNotificationTarget
import io.zer0.muse.notification.MuseNotificationManager
import kotlinx.coroutines.CancellationException

class ProactiveNotificationRecovery(
    private val outcomes: ProactiveOutcomeRepository,
    private val runtime: ProactiveRuntime,
) {
    suspend fun nextAttemptAt(): Long? = outcomes.nextNotificationRecoveryAt()

    suspend fun recover(now: Long, limit: Int = 40): Int {
        var postedCount = 0
        outcomes.notificationRecoveryBatch(now, limit).forEach { outcome ->
            val assistantName = outcome.notificationTitle.ifBlank {
                runtime.assistants.getById(outcome.assistantId)?.name.orEmpty().ifBlank { "muse" }
            }
            val notificationId = outcome.notificationId.takeIf { it != 0 }
                ?: MuseNotificationIdPolicy.notificationIdForOutcome(outcome.id)
            val blocker = runCatching { runtime.notifier.proactiveNotificationBlocker() }
                .getOrElse { it.message ?: it::class.java.simpleName }
            val delivered = if (blocker == null) {
                try {
                    runtime.notifier.notifyProactiveMessage(
                        assistantName = assistantName,
                        preview = outcome.notificationPreview,
                        target = MuseNotificationTarget.ProactiveOutcome(outcome.sessionId, outcome.id),
                        outcomeId = outcome.id,
                        notificationId = notificationId,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "通知补发异常: ${e.message}")
                    false
                }
            } else {
                false
            }
            if (delivered) {
                outcomes.markNotificationPosted(outcome.id)
                postedCount++
            } else {
                val error = blocker ?: "通知系统未接受通知"
                val delayMs = (NOTIFICATION_RETRY_BASE_MS * (outcome.notificationAttempts + 1L))
                    .coerceAtMost(NOTIFICATION_RETRY_MAX_MS)
                outcomes.markNotificationFailed(outcome.id, error, now + delayMs)
                Logger.w(TAG, "主动通知待补发 | outcomeId=${outcome.id} | reason=$error")
            }
        }
        return postedCount
    }

    private companion object {
        const val TAG = "ProactiveNotifRecovery"
        const val NOTIFICATION_RETRY_BASE_MS = 300_000L
        const val NOTIFICATION_RETRY_MAX_MS = 3_600_000L
    }
}
