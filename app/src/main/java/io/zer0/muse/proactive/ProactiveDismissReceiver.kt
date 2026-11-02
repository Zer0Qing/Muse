package io.zer0.muse.proactive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * 主动消息通知「这次不用」按钮的接收器。
 *
 * 动作只做一件事：把这条主动消息在结果账里标成 DISMISSED（用户明确说「这条不该发」），
 * 顺带收起通知。不改设置、不暂停后面的轮次 —— 一条不讨喜不等于让人闭嘴。
 *
 * ## 为什么是**清单注册**而不是动态注册
 *
 * 这个按钮最典型的点击时机恰恰是「应用已经被系统杀掉了」：
 * 主动消息靠 WorkManager 在后台发，发完进程随时可能被回收，
 * 而用户往往过一会儿才在通知栏看到它。
 *
 * 动态注册的接收器**随进程消失**，那时点按钮没有任何组件接得住 ——
 * 表现就是「点了没反应」，而且这条表态也永远不会进结果账。
 * 所以它必须声明在清单里（`exported=false`，只有本应用自己的 PendingIntent 发得动）。
 *
 * ## 冷启动时等一会儿再取依赖
 *
 * 清单注册意味着这个广播会把进程从零拉起来：Koin 可能还没初始化完。
 * 旧实现在那一刻直接放弃（"调度器未就绪，本次忽略"），用户的明确表态就这么丢了。
 * 现在用 [goAsync] 把广播的返回时限借出来，**有限次等待**依赖就绪再落库；
 * 等不到才放弃，并且把原因写清楚。
 */
class ProactiveDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val outcomeId = ProactiveDismissProtocol.dismissedOutcomeId(
            action = intent.action,
            extra = intent.getStringExtra(ProactiveDismissProtocol.EXTRA_OUTCOME_ID),
        ) ?: return

        // 点按钮不会自动收起通知（autoCancel 只管点击通知主体），这里手动收。
        val notifId = intent.getIntExtra(ProactiveDismissProtocol.EXTRA_NOTIFICATION_ID, 0)
        if (notifId != 0) {
            resultOf { NotificationManagerCompat.from(context).cancel(notifId) }
                .onError { msg, _ -> Logger.w(TAG, "收起通知失败: $msg") }
        }

        // onReceive 有十秒左右的返回时限；落库要等依赖，借 goAsync 把它挪到后台协程里做。
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + GlobalCoroutineExceptionHandler)
        scope.launch {
            try {
                val scheduler = awaitScheduler()
                if (scheduler == null) {
                    Logger.w(TAG, "收到「这次不用」，但调度器迟迟未就绪，本次忽略（outcomeId=$outcomeId）")
                } else {
                    scheduler.onProactiveDismissed(outcomeId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * 等依赖就绪。
     *
     * 总等待上限（约 4 秒）远小于广播的十秒时限：留出余量给真正要做的落库。
     */
    private suspend fun awaitScheduler(): ProactiveScheduler? {
        repeat(WAIT_ATTEMPTS) { attempt ->
            val scheduler = resultOf { GlobalContext.get().get<ProactiveScheduler>() }.getOrNull()
            if (scheduler != null) {
                if (attempt > 0) Logger.i(TAG, "调度器第 ${attempt + 1} 次尝试后就绪")
                return scheduler
            }
            delay(WAIT_STEP_MS)
        }
        return null
    }

    private companion object {
        const val TAG = "ProactiveDismiss"

        /** 等待次数 × 间隔 = 最长等待约 4 秒。 */
        const val WAIT_ATTEMPTS = 10
        const val WAIT_STEP_MS = 400L
    }
}
