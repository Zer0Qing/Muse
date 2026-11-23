package io.zer0.muse.ui.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ThemeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RECONCILE) return
        ThemeReconcileWorker.enqueue(context, ThemeReconcileReason.ALARM)
    }

    companion object {
        const val ACTION_RECONCILE = "io.zer0.muse.action.THEME_RECONCILE"
    }
}
