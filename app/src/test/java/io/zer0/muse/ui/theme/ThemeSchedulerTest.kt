package io.zer0.muse.ui.theme

import io.zer0.muse.data.ThemeLocationCache
import io.zer0.muse.data.ThemeScheduleConfig
import io.zer0.muse.data.ThemeSolarUnavailableReason
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSchedulerTest {
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-06-01T23:30:00Z")

    @Test
    fun `reconcile immediately resolves and schedules next boundary without changing base mode`() = runTest {
        val store = FakeThemeStore(
            baseMode = BaseThemeMode.SYSTEM,
            schedule = ThemeScheduleConfig(autoPolicy = ThemeAutoPolicy.CUSTOM_TIME),
        )
        val triggers = FakeThemeTriggers()
        val scheduler = scheduler(store, triggers, FakeThemeLocationProvider(), now)

        val result = scheduler.reconcile(ThemeReconcileReason.SETTINGS_CHANGED)

        assertTrue(result.isDark)
        assertEquals(Instant.parse("2026-06-02T07:00:00Z").toEpochMilli(), result.nextChangeAt)
        assertEquals(result, scheduler.decisionFlow.value)
        assertEquals(BaseThemeMode.SYSTEM, store.baseMode)
        assertEquals(listOf(result.nextChangeAt), triggers.alarms)
        assertEquals(listOf(result.nextChangeAt to false), triggers.catchUps)
        assertTrue(triggers.cancelAlarmCount == 0)
    }

    @Test
    fun `disabled policy restores base mode and cancels both background triggers`() = runTest {
        val store = FakeThemeStore(BaseThemeMode.LIGHT, ThemeScheduleConfig(autoPolicy = ThemeAutoPolicy.OFF))
        val triggers = FakeThemeTriggers()
        val result = scheduler(store, triggers, FakeThemeLocationProvider(), now, systemDark = true)
            .reconcile(ThemeReconcileReason.SETTINGS_CHANGED)

        assertFalse(result.isDark)
        assertNull(result.nextChangeAt)
        assertEquals(1, triggers.cancelAlarmCount)
        assertEquals(1, triggers.cancelCatchUpCount)
        assertTrue(triggers.alarms.isEmpty())
        assertTrue(triggers.catchUps.isEmpty())
    }

    @Test
    fun `solar policy reads coarse location only when enabled and saves date zone events`() = runTest {
        val location = ThemeLocationCache(latitude = 1.35, longitude = 103.82, updatedAt = now.toEpochMilli())
        val store = FakeThemeStore(
            BaseThemeMode.SYSTEM,
            ThemeScheduleConfig(autoPolicy = ThemeAutoPolicy.SUNRISE_SUNSET),
        )
        val provider = FakeThemeLocationProvider(permitted = true, location = location)
        val triggers = FakeThemeTriggers()
        val decision = scheduler(store, triggers, provider, now).reconcile(ThemeReconcileReason.APP_START)

        assertEquals(1, provider.lastKnownCalls)
        assertEquals(location, store.schedule.locationCache)
        assertEquals("2026-06-01", store.schedule.solarCache?.solarDate)
        assertEquals("UTC", store.schedule.solarCache?.zoneId)
        assertNotNullValue(store.schedule.solarCache?.sunriseAt)
        assertNotNullValue(store.schedule.solarCache?.sunsetAt)
        assertEquals(ThemeAutoPolicy.SUNRISE_SUNSET, store.schedule.autoPolicy)
        assertEquals(decision.nextChangeAt, triggers.alarms.single())
        assertEquals(BaseThemeMode.SYSTEM, store.baseMode)
    }

    @Test
    fun `missing permission records a visible fallback without querying location`() = runTest {
        val store = FakeThemeStore(
            BaseThemeMode.DARK,
            ThemeScheduleConfig(autoPolicy = ThemeAutoPolicy.SUNRISE_SUNSET),
        )
        val provider = FakeThemeLocationProvider(permitted = false)
        val result = scheduler(store, FakeThemeTriggers(), provider, now, systemDark = false)
            .reconcile(ThemeReconcileReason.SETTINGS_CHANGED)

        assertEquals(0, provider.lastKnownCalls)
        assertEquals(ThemeSolarUnavailableReason.LOCATION_PERMISSION_REQUIRED, store.schedule.solarUnavailableReason)
        assertTrue(result.isDark)
        assertEquals(ThemeDecisionFallback.SOLAR_CACHE_MISSING, result.fallback)
        assertEquals(BaseThemeMode.DARK, store.baseMode)
    }

    @Test
    fun `disabled solar policy never accesses the location provider`() = runTest {
        val provider = FakeThemeLocationProvider(permitted = true)
        val store = FakeThemeStore(BaseThemeMode.LIGHT, ThemeScheduleConfig(autoPolicy = ThemeAutoPolicy.OFF))

        scheduler(store, FakeThemeTriggers(), provider, now).refreshSolarCacheIfNeeded()

        assertEquals(0, provider.permissionChecks)
        assertEquals(0, provider.lastKnownCalls)
    }

    @Test
    fun `worker reconciliation appends delayed catch up after alarm catch up`() = runTest {
        val store = FakeThemeStore(BaseThemeMode.DARK, ThemeScheduleConfig(autoPolicy = ThemeAutoPolicy.CUSTOM_TIME))
        val triggers = FakeThemeTriggers()

        scheduler(store, triggers, FakeThemeLocationProvider(), now)
            .reconcile(ThemeReconcileReason.WORKER, appendCatchUp = true)

        assertEquals(true, triggers.catchUps.single().second)
    }

    @Test
    fun `new scheduler restores the next transition from persisted settings`() = runTest {
        val store = FakeThemeStore(
            BaseThemeMode.LIGHT,
            ThemeScheduleConfig(autoPolicy = ThemeAutoPolicy.CUSTOM_TIME, wakeUpHour = 7, sleepHour = 22),
        )
        val triggers = FakeThemeTriggers()
        val restoredScheduler = scheduler(
            store,
            triggers,
            FakeThemeLocationProvider(),
            Instant.parse("2026-06-02T05:30:00Z"),
        )

        val decision = restoredScheduler.reconcile(ThemeReconcileReason.APP_START)

        assertTrue(decision.isDark)
        assertEquals(Instant.parse("2026-06-02T07:00:00Z").toEpochMilli(), decision.nextChangeAt)
        assertEquals(listOf(decision.nextChangeAt), triggers.alarms)
        assertEquals(BaseThemeMode.LIGHT, store.baseMode)
    }

    @Test
    fun `delayed alarm reconciliation catches up and schedules the next boundary`() = runTest {
        var currentNow = Instant.parse("2026-06-01T06:50:00Z")
        val store = FakeThemeStore(BaseThemeMode.SYSTEM, ThemeScheduleConfig(autoPolicy = ThemeAutoPolicy.CUSTOM_TIME))
        val triggers = FakeThemeTriggers()
        val scheduler = ThemeScheduler(
            settings = store,
            triggers = triggers,
            locationProvider = FakeThemeLocationProvider(),
            clock = { currentNow },
            zoneIdProvider = { utc },
            systemIsDark = { true },
        )

        val beforeBoundary = scheduler.reconcile(ThemeReconcileReason.SETTINGS_CHANGED)
        currentNow = Instant.parse("2026-06-01T07:15:00Z")
        val afterDelayedAlarm = scheduler.reconcile(ThemeReconcileReason.ALARM, appendCatchUp = true)

        assertTrue(beforeBoundary.isDark)
        assertEquals(Instant.parse("2026-06-01T07:00:00Z").toEpochMilli(), beforeBoundary.nextChangeAt)
        assertFalse(afterDelayedAlarm.isDark)
        assertEquals(Instant.parse("2026-06-01T22:00:00Z").toEpochMilli(), afterDelayedAlarm.nextChangeAt)
        assertEquals(afterDelayedAlarm.nextChangeAt, triggers.alarms.last())
        assertEquals(true, triggers.catchUps.last().second)
        assertEquals(BaseThemeMode.SYSTEM, store.baseMode)
    }

    @Test
    fun `time zone change refreshes solar cache from saved location without permission`() = runTest {
        val oldDate = java.time.LocalDate.of(2026, 6, 1)
        val oldSolar = SolarCalculator.calculate(1.35, 103.82, oldDate, utc).toCache()
        val cachedLocation = ThemeLocationCache(latitude = 1.35, longitude = 103.82, updatedAt = now.toEpochMilli())
        val store = FakeThemeStore(
            BaseThemeMode.SYSTEM,
            ThemeScheduleConfig(
                autoPolicy = ThemeAutoPolicy.SUNRISE_SUNSET,
                locationCache = cachedLocation,
                solarCache = oldSolar,
            ),
        )
        val provider = FakeThemeLocationProvider(permitted = false)
        val tokyoScheduler = ThemeScheduler(
            settings = store,
            triggers = FakeThemeTriggers(),
            locationProvider = provider,
            clock = { now },
            zoneIdProvider = { ZoneId.of("Asia/Tokyo") },
            systemIsDark = { false },
        )

        val decision = tokyoScheduler.reconcile(ThemeReconcileReason.TIME_ZONE_CHANGED)

        assertEquals(0, provider.lastKnownCalls)
        assertEquals("2026-06-02", store.schedule.solarCache?.solarDate)
        assertEquals("Asia/Tokyo", store.schedule.solarCache?.zoneId)
        assertNotNullValue(store.schedule.solarCache?.sunriseAt)
        assertNotNullValue(store.schedule.solarCache?.sunsetAt)
        assertEquals(ThemeDecisionSource.SUNRISE_SUNSET, decision.source)
        assertEquals(BaseThemeMode.SYSTEM, store.baseMode)
    }

    private fun scheduler(
        store: FakeThemeStore,
        triggers: FakeThemeTriggers,
        location: FakeThemeLocationProvider,
        fixedNow: Instant,
        systemDark: Boolean = false,
    ) = ThemeScheduler(
        settings = store,
        triggers = triggers,
        locationProvider = location,
        clock = { fixedNow },
        zoneIdProvider = { utc },
        systemIsDark = { systemDark },
    )

    private fun assertNotNullValue(value: Long?) = org.junit.Assert.assertNotNull(value)

    private class FakeThemeStore(
        val baseMode: BaseThemeMode,
        var schedule: ThemeScheduleConfig,
    ) : ThemeScheduleStore {
        override suspend fun read() = ThemeSettingsSnapshot(baseMode, schedule)
        override suspend fun saveSchedule(config: ThemeScheduleConfig) {
            schedule = config
        }
    }

    private class FakeThemeTriggers : ThemeTriggerBackend {
        val alarms = mutableListOf<Long>()
        val catchUps = mutableListOf<Pair<Long, Boolean>>()
        var cancelAlarmCount = 0
        var cancelCatchUpCount = 0

        override fun scheduleAlarm(atMillis: Long) {
            alarms += atMillis
        }

        override fun cancelAlarm() {
            cancelAlarmCount++
        }

        override fun scheduleCatchUp(atMillis: Long, append: Boolean) {
            catchUps += atMillis to append
        }

        override fun cancelCatchUp() {
            cancelCatchUpCount++
        }
    }

    private class FakeThemeLocationProvider(
        private val permitted: Boolean = false,
        private val location: ThemeLocationCache? = null,
    ) : ThemeLocationProvider {
        var permissionChecks = 0
        var lastKnownCalls = 0

        override fun hasCoarsePermission(): Boolean {
            permissionChecks++
            return permitted
        }

        override fun lastKnownLocation(): ThemeLocationCache? {
            lastKnownCalls++
            return location
        }
    }
}
