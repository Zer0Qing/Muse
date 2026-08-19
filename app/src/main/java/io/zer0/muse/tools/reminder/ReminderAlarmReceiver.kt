package io.zer0.muse.tools.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.zer0.common.Logger
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.notification.MuseNotificationTarget

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

        val target = if (
            intent.getStringExtra(EXTRA_TARGET_TYPE) == TARGET_QUICK_NOTE &&
            intent.getStringExtra(EXTRA_TARGET_ID).orEmpty().isNotBlank()
        ) {
            MuseNotificationTarget.QuickNote(intent.getStringExtra(EXTRA_TARGET_ID).orEmpty())
        } else {
            MuseNotificationTarget.Home
        }
        MuseNotificationManager(context).notifyReminder(title, message, id.hashCode(), target)
        ReminderStore(context).remove(id)
    }

    companion object {
        private const val TAG = "ReminderAlarmReceiver"

        const val EXTRA_ID = "reminder_id"
        const val EXTRA_TITLE = "reminder_title"
        const val EXTRA_MESSAGE = "reminder_message"
        const val EXTRA_TARGET_TYPE = "reminder_target_type"
        const val EXTRA_TARGET_ID = "reminder_target_id"
        const val TARGET_HOME = "home"
        const val TARGET_QUICK_NOTE = "quick_note"
    }
}
