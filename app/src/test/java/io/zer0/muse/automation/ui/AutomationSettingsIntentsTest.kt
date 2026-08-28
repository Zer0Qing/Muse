package io.zer0.muse.automation.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationSettingsIntentsTest {

    @Test
    fun starts_the_first_resolvable_settings_intent() {
        val development = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        var launched: Intent? = null

        val started = launchFirstResolvable(
            candidates = listOf(development, appDetails),
            canResolve = { it === appDetails },
            startActivity = { launched = it },
        )

        assertTrue(started)
        assertTrue(launched === appDetails)
    }

    @Test
    fun skips_candidate_when_start_activity_reports_no_handler() {
        val first = Intent("first")
        val second = Intent("second")
        val launchedActions = mutableListOf<String?>()

        val started = launchFirstResolvable(
            candidates = listOf(first, second),
            canResolve = { true },
            startActivity = { intent ->
                if (intent === first) throw ActivityNotFoundException("missing")
                launchedActions += if (intent === second) "second" else "unknown"
            },
        )

        assertTrue(started)
        assertEquals(listOf("second"), launchedActions)
    }

    @Test
    fun returns_false_when_no_candidate_can_be_started() {
        val started = launchFirstResolvable(
            candidates = listOf(Intent("missing")),
            canResolve = { false },
            startActivity = { error("must not start") },
        )

        assertFalse(started)
    }
}
