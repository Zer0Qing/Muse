package io.zer0.muse.ui.theme

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.zer0.common.Logger
import kotlinx.coroutines.CancellationException
import org.koin.core.context.GlobalContext

class ThemeReconcileWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val scheduler = runCatching { GlobalContext.get().get<ThemeScheduler>() }
            .onFailure { Logger.w(TAG, "ThemeScheduler is not available", it) }
            .getOrNull() ?: return Result.retry()
        return try {
            scheduler.reconcile(
                reason = ThemeReconcileReason.fromWire(inputData.getString(KEY_REASON)),
                appendCatchUp = true,
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Logger.w(TAG, "Theme reconciliation failed", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ThemeReconcileWorker"
        const val KEY_REASON = "theme_reconcile_reason"

        fun enqueue(context: Context, reason: ThemeReconcileReason) {
            val request = OneTimeWorkRequestBuilder<ThemeReconcileWorker>()
                .setInputData(workDataOf(KEY_REASON to reason.wireValue))
                .build()
            runCatching {
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    ThemeScheduler.CATCH_UP_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
            }.onFailure { Logger.w(TAG, "Unable to enqueue theme reconciliation", it) }
        }
    }
}
