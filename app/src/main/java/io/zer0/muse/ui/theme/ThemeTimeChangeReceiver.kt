package io.zer0.muse.ui.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ThemeTimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_DATE_CHANGED -> ThemeReconcileReason.DATE_CHANGED
            Intent.ACTION_TIME_CHANGED -> ThemeReconcileReason.TIME_CHANGED
            Intent.ACTION_TIMEZONE_CHANGED -> ThemeReconcileReason.TIME_ZONE_CHANGED
            Intent.ACTION_BOOT_COMPLETED -> ThemeReconcileReason.BOOT_COMPLETED
            Intent.ACTION_MY_PACKAGE_REPLACED -> ThemeReconcileReason.PACKAGE_REPLACED
            else -> return
        }
        ThemeReconcileWorker.enqueue(context, reason)
    }

    companion object {
        val ACTIONS: Set<String> = setOf(
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
