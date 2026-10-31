package io.zer0.muse.proactive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * 主动消息通知「这次不用」按钮的接收器。
 *
 * 只在应用内可达：用 [androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED] 动态注册，
 * 广播由本应用自己的 PendingIntent 发出，别的应用没有理由也不需要叫得动它。
 *
 * 动作只做一件事：把这条主动消息在结果账里标成 DISMISSED（用户明确说「这条不该发」），
 * 顺带收起通知。不改设置、不暂停后面的轮次 —— 一条不讨喜不等于让人闭嘴。
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

        val scheduler = resultOf { GlobalContext.get().get<ProactiveScheduler>() }.getOrNull()
        if (scheduler == null) {
            // 依赖没就绪（进程被重启到一半）时宁可丢这一次表态，也不在主线程上做 I/O。
            Logger.w(TAG, "收到「这次不用」，但调度器未就绪，本次忽略")
            return
        }
        // onReceive 只有十秒左右的返回时限，落库交给应用级 scope，别把广播悬在这里。
        val scope = resultOf { GlobalContext.get().get<CoroutineScope>() }.getOrNull() ?: return
        scope.launch { scheduler.onProactiveDismissed(outcomeId) }
    }

    private companion object {
        const val TAG = "ProactiveDismiss"
    }
}
