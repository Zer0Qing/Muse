package io.zer0.muse.ui.theme

import io.zer0.muse.data.ThemeScheduleConfig
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeDecisionCalculatorTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun `off policy resolves base mode and system changes without mutating a mode`() {
        val systemDark = evaluate(BaseThemeMode.SYSTEM, ThemeAutoPolicy.OFF, ThemeScheduleConfig(), "2026-06-01T12:00:00Z", true)
        val systemLight = evaluate(BaseThemeMode.SYSTEM, ThemeAutoPolicy.OFF, ThemeScheduleConfig(), "2026-06-01T12:00:00Z", false)
        val forcedLight = evaluate(BaseThemeMode.LIGHT, ThemeAutoPolicy.OFF, ThemeScheduleConfig(), "2026-06-01T12:00:00Z", true)
        val forcedDark = evaluate(BaseThemeMode.DARK, ThemeAutoPolicy.OFF, ThemeScheduleConfig(), "2026-06-01T12:00:00Z", false)

        assertTrue(systemDark.isDark)
        assertFalse(systemLight.isDark)
        assertFalse(forcedLight.isDark)
        assertTrue(forcedDark.isDark)
        assertEquals(ThemeDecisionSource.BASE_MODE, systemDark.source)
        assertNull(systemDark.nextChangeAt)
    }

    @Test
    fun `custom schedule applies inclusive wake and exclusive sleep boundaries`() {
        val schedule = ThemeScheduleConfig(wakeUpHour = 7, wakeUpMinute = 30, sleepHour = 22, sleepMinute = 15)

        assertTrue(evaluate(BaseThemeMode.LIGHT, ThemeAutoPolicy.CUSTOM_TIME, schedule, "2026-06-01T07:29:59Z").isDark)
        val atWake = evaluate(BaseThemeMode.DARK, ThemeAutoPolicy.CUSTOM_TIME, schedule, "2026-06-01T07:30:00Z")
        assertFalse(atWake.isDark)
        assertEquals(Instant.parse("2026-06-01T22:15:00Z").toEpochMilli(), atWake.nextChangeAt)
        assertTrue(evaluate(BaseThemeMode.LIGHT, ThemeAutoPolicy.CUSTOM_TIME, schedule, "2026-06-01T22:15:00Z").isDark)
    }

    @Test
    fun `custom schedule supports a cross midnight light window`() {
        val schedule = ThemeScheduleConfig(wakeUpHour = 20, wakeUpMinute = 0, sleepHour = 6, sleepMinute = 0)

        val nightSide = evaluate(BaseThemeMode.DARK, ThemeAutoPolicy.CUSTOM_TIME, schedule, "2026-06-01T23:00:00Z")
        val daySide = evaluate(BaseThemeMode.DARK, ThemeAutoPolicy.CUSTOM_TIME, schedule, "2026-06-01T05:59:00Z")
        val sleepBoundary = evaluate(BaseThemeMode.LIGHT, ThemeAutoPolicy.CUSTOM_TIME, schedule, "2026-06-01T06:00:00Z")

        assertFalse(nightSide.isDark)
        assertEquals(Instant.parse("2026-06-02T06:00:00Z").toEpochMilli(), nightSide.nextChangeAt)
        assertFalse(daySide.isDark)
        assertTrue(sleepBoundary.isDark)
        assertEquals(Instant.parse("2026-06-01T20:00:00Z").toEpochMilli(), sleepBoundary.nextChangeAt)
    }

    @Test
    fun `equal boundaries produce a stable dark schedule without repeated alarms`() {
        val schedule = ThemeScheduleConfig(wakeUpHour = 8, wakeUpMinute = 0, sleepHour = 8, sleepMinute = 0)
        val decision = evaluate(BaseThemeMode.LIGHT, ThemeAutoPolicy.CUSTOM_TIME, schedule, "2026-06-01T08:00:00Z")

        assertTrue(decision.isDark)
        assertNull(decision.nextChangeAt)
        assertEquals(ThemeDecisionSource.CUSTOM_TIME, decision.source)
    }

    @Test
    fun `invalid custom times fall back to the base mode`() {
        val schedule = ThemeScheduleConfig(wakeUpHour = 24, wakeUpMinute = 0, sleepHour = 22, sleepMinute = 0)
        val decision = evaluate(BaseThemeMode.DARK, ThemeAutoPolicy.CUSTOM_TIME, schedule, "2026-06-01T12:00:00Z")

        assertTrue(decision.isDark)
        assertEquals(ThemeDecisionFallback.INVALID_CUSTOM_TIME, decision.fallback)
        assertEquals(ThemeDecisionSource.BASE_MODE, decision.source)
    }

    @Test
    fun `solar policy uses current date zone and explicit polar conditions`() {
        val date = LocalDate.of(2026, 6, 1)
        val sunrise = Instant.parse("2026-06-01T06:00:00Z")
        val sunset = Instant.parse("2026-06-01T18:00:00Z")
        val normal = SolarSchedule(date, utc, sunrise, sunset, SolarCondition.NORMAL)
        val schedule = ThemeScheduleConfig(autoPolicy = ThemeAutoPolicy.SUNRISE_SUNSET)

        assertFalse(evaluate(BaseThemeMode.DARK, ThemeAutoPolicy.SUNRISE_SUNSET, schedule, "2026-06-01T12:00:00Z", solar = normal).isDark)
        assertTrue(evaluate(BaseThemeMode.LIGHT, ThemeAutoPolicy.SUNRISE_SUNSET, schedule, "2026-06-01T20:00:00Z", solar = normal).isDark)
        val polarDay = normal.copy(condition = SolarCondition.POLAR_DAY, sunriseAt = null, sunsetAt = null)
        val polarNight = polarDay.copy(condition = SolarCondition.POLAR_NIGHT)
        assertFalse(evaluate(BaseThemeMode.DARK, ThemeAutoPolicy.SUNRISE_SUNSET, schedule, "2026-06-01T12:00:00Z", solar = polarDay).isDark)
        assertTrue(evaluate(BaseThemeMode.LIGHT, ThemeAutoPolicy.SUNRISE_SUNSET, schedule, "2026-06-01T12:00:00Z", solar = polarNight).isDark)
    }

    @Test
    fun `missing or stale solar data falls back to the base mode`() {
        val schedule = ThemeScheduleConfig(autoPolicy = ThemeAutoPolicy.SUNRISE_SUNSET)
        val missing = evaluate(BaseThemeMode.DARK, ThemeAutoPolicy.SUNRISE_SUNSET, schedule, "2026-06-01T12:00:00Z")
        val stale = SolarSchedule(
            date = LocalDate.of(2026, 5, 31),
            zoneId = utc,
            sunriseAt = Instant.parse("2026-05-31T06:00:00Z"),
            sunsetAt = Instant.parse("2026-05-31T18:00:00Z"),
            condition = SolarCondition.NORMAL,
        )
        val staleDecision = evaluate(
            BaseThemeMode.LIGHT,
            ThemeAutoPolicy.SUNRISE_SUNSET,
            schedule,
            "2026-06-01T12:00:00Z",
            solar = stale,
        )

        assertTrue(missing.isDark)
        assertEquals(ThemeDecisionFallback.SOLAR_CACHE_MISSING, missing.fallback)
        assertFalse(staleDecision.isDark)
        assertEquals(ThemeDecisionFallback.SOLAR_CACHE_STALE, staleDecision.fallback)
        assertEquals(Instant.parse("2026-06-02T00:00:00Z").toEpochMilli(), staleDecision.nextChangeAt)
    }

    private fun evaluate(
        baseMode: BaseThemeMode,
        policy: ThemeAutoPolicy,
        schedule: ThemeScheduleConfig,
        now: String,
        systemDark: Boolean = false,
        solar: SolarSchedule? = null,
    ): ThemeDecision = ThemeDecisionCalculator.evaluate(
        baseMode = baseMode,
        autoPolicy = policy,
        schedule = schedule,
        now = Instant.parse(now),
        systemIsDark = systemDark,
        solar = solar,
        zoneId = utc,
    )
}
