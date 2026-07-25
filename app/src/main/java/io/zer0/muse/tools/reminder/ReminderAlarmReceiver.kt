package io.zer0.muse.tools.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.zer0.common.Logger
import io.zer0.muse.notification.MuseNotificationManager

/**
 * v1.136: 定时提醒闹钟接收器。
 *
 * AlarmManager 到点时触发本 Receiver,弹出通知,然后从 [ReminderStore] 移除该提醒。
 */
class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "muse 提醒"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: ""

        Logger.i(TAG, "提醒触发: id=$id, title=$title")

        MuseNotificationManager(context).notifyReminder(title, message, id.hashCode())
        ReminderStore(context).remove(id)
    }

    companion object {
        private const val TAG = "ReminderAlarmReceiver"

        const val EXTRA_ID = "reminder_id"
        const val EXTRA_TITLE = "reminder_title"
        const val EXTRA_MESSAGE = "reminder_message"
    }
}
