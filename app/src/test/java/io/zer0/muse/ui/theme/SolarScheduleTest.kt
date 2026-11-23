package io.zer0.muse.ui.theme

import io.zer0.muse.data.ThemeSolarCondition
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SolarScheduleTest {
    @Test
    fun `equatorial equinox returns local sunrise and sunset`() {
        val zone = ZoneId.of("Asia/Singapore")
        val result = SolarCalculator.calculate(1.3521, 103.8198, LocalDate.of(2026, 3, 20), zone)

        assertEquals(SolarCondition.NORMAL, result.condition)
        val sunrise = requireNotNull(result.sunriseAt).atZone(zone).toLocalTime()
        val sunset = requireNotNull(result.sunsetAt).atZone(zone).toLocalTime()
        assertTrue("unexpected sunrise $sunrise", kotlin.math.abs(sunrise.toSecondOfDay() - (7 * 3600 + 7 * 60)) < 15 * 60)
        assertTrue("unexpected sunset $sunset", kotlin.math.abs(sunset.toSecondOfDay() - (19 * 3600 + 13 * 60)) < 15 * 60)
    }

    @Test
    fun `high latitude returns explicit polar day and polar night`() {
        val zone = ZoneId.of("America/Anchorage")
        val summer = SolarCalculator.calculate(89.0, 0.0, LocalDate.of(2026, 6, 21), zone)
        val winter = SolarCalculator.calculate(89.0, 0.0, LocalDate.of(2026, 12, 21), zone)

        assertEquals(SolarCondition.POLAR_DAY, summer.condition)
        assertEquals(SolarCondition.POLAR_NIGHT, winter.condition)
        assertEquals(null, summer.sunriseAt)
        assertEquals(null, winter.sunsetAt)
    }

    @Test
    fun `invalid coordinates are explicit and not represented as fabricated event times`() {
        val result = SolarCalculator.calculate(Double.NaN, 0.0, LocalDate.of(2026, 3, 20), ZoneId.of("UTC"))

        assertEquals(SolarCondition.INVALID_COORDINATES, result.condition)
        assertEquals(null, result.sunriseAt)
        assertEquals(null, result.sunsetAt)
    }

    @Test
    fun `solar cache round trips date zone times and status`() {
        val zone = ZoneId.of("Asia/Tokyo")
        val calculated = SolarCalculator.calculate(35.6762, 139.6503, LocalDate.of(2026, 4, 1), zone)
        val cache = calculated.toCache()
        val restored = cache.toSolarScheduleOrNull()

        assertNotNull(restored)
        assertEquals(calculated, restored)
        assertEquals(ThemeSolarCondition.NORMAL, cache.condition)
    }
}
