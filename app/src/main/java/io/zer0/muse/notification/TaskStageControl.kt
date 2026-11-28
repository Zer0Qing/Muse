package io.zer0.muse.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.zer0.common.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 通知栏"停止"按钮 → 生成链路 的单向信号（对比清单 P2-03）。
 *
 * 为什么需要它：通知栏属于系统进程发起的广播，拿不到 `ChatViewModel`。
 * 用一条进程内信号把"用户点了停止"交给持有生成会话的那一层，
 * 由它决定怎么停 —— 通知层不该知道会话怎么取消。
 *
 * 与 [MuseNotificationManager.updateTaskStage] 配套：投出阶段 → 用户看到 → 用户喊停。
 */
object TaskStageControl {

    private val _stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** 生成层订阅它：收到即停止当前生成。 */
    val stopRequests: SharedFlow<Unit> = _stopRequests

    fun requestStop() {
        _stopRequests.tryEmit(Unit)
    }

    internal fun resetForTests() {
        // SharedFlow 无缓冲历史，重置只需丢弃待处理项（当前实现下是空操作）
    }
}

/**
 * 通知"停止"动作的接收器。
 *
 * 只做两件事：把信号发出去、把通知收掉。
 * **不直接取消任何协程** —— 取消必须由持有它的那一层做，否则会出现"通知没了但任务还在跑"。
 */
class TaskStageActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP) return
        Logger.i(TAG, "通知栏请求停止当前任务")
        TaskStageControl.requestStop()
        runCatching {
            context.getSystemService(android.app.NotificationManager::class.java)?.cancel(NOTIF_ID_TASK_STAGE)
        }
    }

    companion object {
        private const val TAG = "TaskStageReceiver"
        const val ACTION_STOP = "io.zer0.muse.action.STOP_TASK_STAGE"

        /** 与 [MuseNotificationManager] 里的工具阶段通知保持同一个 ID。 */
        const val NOTIF_ID_TASK_STAGE = 1005
    }
}
