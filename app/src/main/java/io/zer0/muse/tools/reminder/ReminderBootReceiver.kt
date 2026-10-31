package io.zer0.muse.tools.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 重启 / 应用更新后重排提醒闹钟。
 *
 * 为什么需要它：AlarmManager 里排的闹钟在设备重启后会全部丢失，应用被覆盖安装（升级）
 * 后同样会丢失。若不做重排，[ReminderStore] 里还躺着「未触发」的条目，用户以为提醒还在，
 * 实际永远不会响 —— 这是最隐蔽的漏发。
 *
 * 与「开机自启动」开关无关：用户设的提醒不该因为「不开机自启」而丢失，所以本 Receiver
 * 在 manifest 里默认启用，不跟随 autoLaunch。
 *
 * 只做重排，不弹 Activity、不发主动消息：
 *  - 未来的提醒重排回 AlarmManager；
 *  - 已经错过的提醒补发一次通知后移除（见 [ReminderStore.rescheduleAll]）。
 */
class ReminderBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return
        Logger.i(TAG, "收到 $action，开始重排提醒")
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // 用 resultOf 而不是 catch (Exception)：广播接收器里抛出去的异常没人接，
                // 而 catch (Exception) 会把协程取消一并吞掉（CancellationException 也是 Exception）。
                resultOf { ReminderStore(appContext).rescheduleAll() }
                    .onSuccess { summary ->
                        Logger.i(
                            TAG,
                            "重排完成: 成功=${summary.rescheduled}, 补发=${summary.catchUpNotified}, " +
                                "失败=${summary.failed}, 精确权限=${summary.exact}",
                        )
                    }
                    .onError { msg, t -> Logger.e(TAG, "重排提醒失败: $msg", t) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ReminderBootReceiver"

        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
