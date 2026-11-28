package io.zer0.muse.proactive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.zer0.common.Logger
import io.zer0.common.ProcessWriteGate
import kotlinx.coroutines.CancellationException
import org.koin.core.context.GlobalContext

class ProactiveBacklogWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val koin = runCatching { GlobalContext.get() }.getOrNull()
        val dispatcher = koin?.let { runCatching { it.get<ProactiveBacklogDispatcher>() }.getOrNull() }
        val notifications = koin?.let { runCatching { it.get<ProactiveNotificationRecovery>() }.getOrNull() }
        if (dispatcher == null || notifications == null) {
            Logger.w(TAG, "主动 backlog Worker 依赖未就绪")
            return Result.retry()
        }
        return try {
            if (ProcessWriteGate.restoring) return Result.success()
            val now = System.currentTimeMillis()
            dispatcher.recoverExpiredClaims(now)
            notifications.recover(now)
            val outcome = dispatcher.dispatchOne(now)
            if (outcome is BacklogDispatchOutcome.Sent) {
                notifications.recover(System.currentTimeMillis())
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Logger.w(TAG, "主动 backlog 派发失败: ${e.message}", e)
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "ProactiveBacklogWorker"
    }
}
