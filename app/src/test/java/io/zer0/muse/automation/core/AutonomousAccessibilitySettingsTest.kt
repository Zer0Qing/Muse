package io.zer0.muse.automation.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.AutomationSettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutonomousAccessibilitySettingsTest {
    @Test
    fun `autonomous accessibility window is off by default`() = runTest {
        val settings = AutomationSettingsStore(ApplicationProvider.getApplicationContext<Context>())
        assertFalse(settings.autonomousAccessibilityWindowFlow.first().enabled)
    }

    @Test
    fun `autonomous accessibility window round trips`() = runTest {
        val settings = AutomationSettingsStore(ApplicationProvider.getApplicationContext<Context>())
        val window = AutonomousAccessibilityWindow(
            enabled = true,
            startMinuteOfDay = 14 * 60,
            endMinuteOfDay = 17 * 60,
            allowedPackages = setOf("com.xingin.xhs"),
            allowedActions = setOf(AutomationAction.SCROLL, AutomationAction.READ_SCREEN),
            maxRunsPerDay = 3,
        )

        settings.saveAutonomousAccessibilityWindow(window)

        assertEquals(window, settings.autonomousAccessibilityWindowFlow.first())
    }
}
