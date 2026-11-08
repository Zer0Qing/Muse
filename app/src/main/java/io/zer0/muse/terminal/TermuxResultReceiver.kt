package io.zer0.muse.terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Termux 结果回传的清单兜底接收器。
 *
 * 动态注册的那份接收器在部分 ROM 上会被丢弃或提前失效；结果广播是**显式**发给本应用的
 * （PendingIntent 的基础 Intent 带 setPackage），不受 Android 8+ 隐式广播限制，
 * 所以清单里再挂一份就能在动态注册失灵时照样收到。
 *
 * 必须 exported=true：发件人是 Termux 那个应用（不同 uid）。伪造防护不在这里做，
 * 由 [TermuxChannel] 统一校验随机会话 token 与 pending 请求 id，两道都对不上就丢弃。
 */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent != null) TermuxChannel.deliverStatic(intent)
    }
}
