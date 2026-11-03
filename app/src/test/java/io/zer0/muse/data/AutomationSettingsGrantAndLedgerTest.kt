package io.zer0.muse.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.automation.core.AutonomousAccessibilityGrant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationSettingsGrantAndLedgerTest {
    @Test
    fun `task grant round trips and clears when the task changes`() = runTest {
        val store = AutomationSettingsStore(ApplicationProvider.getApplicationContext<Context>())
        val grant =
            AutonomousAccessibilityGrant(
                sessionId = "s1",
                packageName = "com.xingin.xhs",
                taskFingerprint = "task:one",
                confirmedAtMillis = 100,
            )

        store.saveAutonomousAccessibilityGrant(grant)
        assertEquals(grant, store.autonomousAccessibilityGrantFlow.first())

        store.clearAutonomousAccessibilityGrant(expected = grant)
        assertNull(store.autonomousAccessibilityGrantFlow.first())
    }

    @Test
    fun `daily ledger increments only on a new local day and never leaks task text`() = runTest {
        val store = AutomationSettingsStore(ApplicationProvider.getApplicationContext<Context>())

        store.incrementAutonomousRunCount("2026-11-03")
        store.incrementAutonomousRunCount("2026-11-03")
        val sameDay = store.autonomousAccessibilityLedgerFlow.first()
        assertEquals("2026-11-03", sameDay.dateKey)
        assertEquals(2, sameDay.runCount)

        store.incrementAutonomousRunCount("2026-11-04")
        val nextDay = store.autonomousAccessibilityLedgerFlow.first()
        assertEquals("2026-11-04", nextDay.dateKey)
        assertEquals(1, nextDay.runCount)
        assertTrue(nextDay.toString().none { it.code > 127 })
    }
}
