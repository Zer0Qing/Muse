package io.zer0.muse.ui.theme

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTriggerContractTest {
    @Test
    fun `time changes boot and package replacement all have stable reconcile reasons`() {
        val expectedActions = mapOf(
            Intent.ACTION_DATE_CHANGED to ThemeReconcileReason.DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED to ThemeReconcileReason.TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED to ThemeReconcileReason.TIME_ZONE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED to ThemeReconcileReason.BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED to ThemeReconcileReason.PACKAGE_REPLACED,
        )

        assertEquals(expectedActions.keys, ThemeTimeChangeReceiver.ACTIONS)
        expectedActions.forEach { (action, reason) ->
            assertTrue("missing receiver action $action", ThemeTimeChangeReceiver.ACTIONS.contains(action))
            assertEquals(reason, ThemeReconcileReason.fromWire(reason.wireValue))
        }
    }

    @Test
    fun `alarm receiver and worker share the unique catch up contract`() {
        assertEquals("io.zer0.muse.action.THEME_RECONCILE", ThemeAlarmReceiver.ACTION_RECONCILE)
        assertEquals("muse_theme_reconcile", ThemeScheduler.CATCH_UP_WORK_NAME)
        assertEquals("theme_reconcile_reason", ThemeReconcileWorker.KEY_REASON)
        assertEquals(ThemeReconcileReason.WORKER, ThemeReconcileReason.fromWire("unknown"))
    }
}
