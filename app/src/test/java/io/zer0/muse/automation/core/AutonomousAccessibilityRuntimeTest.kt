package io.zer0.muse.automation.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.AutomationSettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutonomousAccessibilityRuntimeTest {
    private val zone = ZoneId.of("Asia/Singapore")
    private val clock = Clock.fixed(Instant.parse("2026-11-03T07:30:00Z"), zone) // 15:30 local

    @Test
    fun `snapshot combines settings task authorization package and today ledger`() = runTest {
        val store = AutomationSettingsStore(ApplicationProvider.getApplicationContext<Context>())
        val window = allowedWindow(maxRuns = 3)
        val grant =
            AutonomousAccessibilityGrant(
                sessionId = "s1",
                packageName = "com.xingin.xhs",
                taskFingerprint = "task:old",
                confirmedAtMillis = clock.millis(),
            )
        store.saveAutonomousAccessibilityWindow(window)
        store.saveAutonomousAccessibilityGrant(grant)
        store.incrementAutonomousRunCount("2026-11-03")
        val runtime = runtime(store)

        val snapshot = runtime.snapshot("s1")

        assertEquals(window, snapshot.window)
        assertEquals(grant, snapshot.grant)
        assertEquals("com.xingin.xhs", snapshot.currentPackage)
        assertEquals(15 * 60 + 30, snapshot.minuteOfDay)
        assertEquals("2026-11-03", snapshot.dateKey)
        assertEquals(1, snapshot.runCountToday)
        assertEquals("打开 com.xingin.xhs 浏览小红书", snapshot.latestUserMessage)
    }

    @Test
    fun `successful run increments the ledger and revokes the grant at the daily cap`() = runTest {
        val store = AutomationSettingsStore(ApplicationProvider.getApplicationContext<Context>())
        store.saveAutonomousAccessibilityWindow(allowedWindow(maxRuns = 2))
        store.saveAutonomousAccessibilityGrant(
            AutonomousAccessibilityGrant(
                sessionId = "s1",
                packageName = "com.xingin.xhs",
                taskFingerprint = AutonomousAccessibilityTaskScope.fingerprint(
                    "打开 com.xingin.xhs 浏览小红书",
                ),
                confirmedAtMillis = clock.millis(),
            ),
        )
        val runtime = runtime(store)

        assertTrue(runtime.recordSuccessfulRun("s1", "com.xingin.xhs"))
        assertTrue(runtime.recordSuccessfulRun("s1", "com.xingin.xhs"))

        val ledger = store.autonomousAccessibilityLedgerFlow.first()
        assertEquals("2026-11-03", ledger.dateKey)
        assertEquals(2, ledger.runCount)
        assertNull("达到上限后任务授权必须撤销", store.autonomousAccessibilityGrantFlow.first())
    }

    @Test
    fun `wrong package or changed task never consumes quota`() = runTest {
        val store = AutomationSettingsStore(ApplicationProvider.getApplicationContext<Context>())
        store.saveAutonomousAccessibilityWindow(allowedWindow(maxRuns = 4))
        store.saveAutonomousAccessibilityGrant(
            AutonomousAccessibilityGrant(
                sessionId = "s1",
                packageName = "com.xingin.xhs",
                taskFingerprint = "task:old",
                confirmedAtMillis = clock.millis(),
            ),
        )
        val runtime = runtime(store)

        assertTrue(!runtime.recordSuccessfulRun("s1", "com.example.other"))
        assertEquals(0, store.autonomousAccessibilityLedgerFlow.first().runCount)

        val changed = runtime(store, latest = "帮我删除 com.xingin.xhs")
        assertTrue(changed.pruneGrantIfTaskChanged("s1"))
        assertNull(store.autonomousAccessibilityGrantFlow.first())
        assertEquals(0, store.autonomousAccessibilityLedgerFlow.first().runCount)
    }

    @Test
    fun `a renamed same app task cannot reuse the previous grant`() = runTest {
        val store = AutomationSettingsStore(ApplicationProvider.getApplicationContext<Context>())
        val originalTask = "打开 com.xingin.xhs 浏览小红书"
        store.saveAutonomousAccessibilityWindow(allowedWindow(maxRuns = 4))
        store.saveAutonomousAccessibilityGrant(
            AutonomousAccessibilityGrant(
                sessionId = "s1",
                packageName = "com.xingin.xhs",
                taskFingerprint = AutonomousAccessibilityTaskScope.fingerprint(originalTask),
                confirmedAtMillis = clock.millis(),
            ),
        )
        val changed = runtime(store, latest = "打开 com.xingin.xhs 点击购买")

        assertTrue(!changed.recordSuccessfulRun("s1", "com.xingin.xhs"))

        assertEquals(0, store.autonomousAccessibilityLedgerFlow.first().runCount)
    }

    @Test
    fun `unchanged confirmed task keeps its grant`() = runTest {
        val store = AutomationSettingsStore(ApplicationProvider.getApplicationContext<Context>())
        val task = "打开 com.xingin.xhs 浏览小红书"
        val grant =
            AutonomousAccessibilityGrant(
                sessionId = "s1",
                packageName = "com.xingin.xhs",
                taskFingerprint = AutonomousAccessibilityTaskScope.fingerprint(task),
                confirmedAtMillis = clock.millis(),
            )
        store.saveAutonomousAccessibilityGrant(grant)

        assertTrue(!runtime(store, latest = task).pruneGrantIfTaskChanged("s1"))
        assertEquals(grant, store.autonomousAccessibilityGrantFlow.first())
    }

    private fun runtime(
        store: AutomationSettingsStore,
        latest: String = "打开 com.xingin.xhs 浏览小红书",
    ): PersistentAutonomousAccessibilityRuntime =
        PersistentAutonomousAccessibilityRuntime(
            store = store,
            foregroundPackage = { "com.xingin.xhs" },
            latestUserMessage = { latest },
            clock = clock,
            zoneId = zone,
        )

    private fun allowedWindow(maxRuns: Int): AutonomousAccessibilityWindow =
        AutonomousAccessibilityWindow(
            enabled = true,
            startMinuteOfDay = 14 * 60,
            endMinuteOfDay = 17 * 60,
            allowedPackages = setOf("com.xingin.xhs"),
            allowedActions = setOf(AutomationAction.READ_SCREEN, AutomationAction.SCROLL),
            maxRunsPerDay = maxRuns,
        )
}
