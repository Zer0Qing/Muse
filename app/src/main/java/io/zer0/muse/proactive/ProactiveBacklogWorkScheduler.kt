package io.zer0.muse.proactive

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.util.concurrent.TimeUnit

class ProactiveBacklogWorkScheduler(
    private val context: Context,
    private val dispatcher: ProactiveBacklogDispatcher,
    private val notifications: ProactiveNotificationRecovery,
    private val scope: CoroutineScope,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    fun start() {
        val workManager = WorkManager.getInstance(context)
        scope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME).collect { infos ->
                if (infos.none { !it.state.isFinished }) enqueueIfNeeded()
            }
        }
        scope.launch { enqueueIfNeeded() }
    }

    suspend fun enqueueIfNeeded(): Boolean {
        if (!dispatcher.canSchedule()) return false
        val backlogPending = dispatcher.hasUnfinishedDispatches()
        val notificationAt = notifications.nextAttemptAt()
        if (!backlogPending && notificationAt == null) return false
        val now = nowProvider()
        val runAt = listOfNotNull(
            notificationAt,
            dispatcher.nextWorkAt(now).takeIf { backlogPending },
        ).minOrNull() ?: now
        val delayMs = initialDelayMs(now, runAt)
        val request = OneTimeWorkRequestBuilder<ProactiveBacklogWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        return true
    }

    companion object {
        const val UNIQUE_WORK_NAME = "muse_proactive_backlog_dispatch"

        fun initialDelayMs(now: Long, runAt: Long): Long = (runAt - now).coerceAtLeast(0L)
    }
}
