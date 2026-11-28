package io.zer0.muse.ui.theme

import io.zer0.muse.data.ThemeSolarCache
import io.zer0.muse.data.ThemeSolarCondition
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

enum class SolarCondition {
    NORMAL,
    POLAR_DAY,
    POLAR_NIGHT,
    INVALID_COORDINATES,
}

data class SolarSchedule(
    val date: LocalDate,
    val zoneId: ZoneId,
    val sunriseAt: Instant?,
    val sunsetAt: Instant?,
    val condition: SolarCondition,
)

object SolarCalculator {
    private const val CROSSING_ALTITUDE_DEGREES = -0.833
    private const val SCAN_STEP_SECONDS = 300L

    fun calculate(latitude: Double, longitude: Double, date: LocalDate, zoneId: ZoneId): SolarSchedule {
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return SolarSchedule(date, zoneId, null, null, SolarCondition.INVALID_COORDINATES)
        }

        val start = date.atStartOfDay(zoneId).toInstant()
        val end = date.plusDays(1).atStartOfDay(zoneId).toInstant()
        var cursor = start
        var previousAltitude = solarAltitude(cursor, latitude, longitude)
        var sunrise: Instant? = null
        var sunset: Instant? = null
        var sawAbove = previousAltitude >= CROSSING_ALTITUDE_DEGREES
        var sawBelow = !sawAbove

        while (cursor < end) {
            val next = minOf(cursor.plusSeconds(SCAN_STEP_SECONDS), end)
            val altitude = solarAltitude(next, latitude, longitude)
            val wasAbove = previousAltitude >= CROSSING_ALTITUDE_DEGREES
            val isAbove = altitude >= CROSSING_ALTITUDE_DEGREES
            sawAbove = sawAbove || isAbove
            sawBelow = sawBelow || !isAbove
            if (!wasAbove && isAbove && sunrise == null) {
                sunrise = findCrossing(cursor, next, latitude, longitude, rising = true)
            } else if (wasAbove && !isAbove && sunset == null) {
                sunset = findCrossing(cursor, next, latitude, longitude, rising = false)
            }
            cursor = next
            previousAltitude = altitude
        }

        val condition = when {
            sunrise != null && sunset != null -> SolarCondition.NORMAL
            sawAbove && !sawBelow -> SolarCondition.POLAR_DAY
            else -> SolarCondition.POLAR_NIGHT
        }
        return SolarSchedule(date, zoneId, sunrise, sunset, condition)
    }

    private fun findCrossing(start: Instant, end: Instant, latitude: Double, longitude: Double, rising: Boolean): Instant {
        var low = start
        var high = end
        repeat(24) {
            val middle = low.plusMillis((high.toEpochMilli() - low.toEpochMilli()) / 2)
            val above = solarAltitude(middle, latitude, longitude) >= CROSSING_ALTITUDE_DEGREES
            if (above == rising) high = middle else low = middle
        }
        return high
    }

    private fun solarAltitude(instant: Instant, latitude: Double, longitude: Double): Double {
        val julianDay = instant.toEpochMilli() / 86_400_000.0 + 2_440_587.5
        val centuries = (julianDay - 2_451_545.0) / 36_525.0
        val meanLongitude = normalizeDegrees(280.46646 + centuries * (36_000.76983 + centuries * 0.0003032))
        val meanAnomaly = 357.52911 + centuries * (35_999.05029 - 0.0001537 * centuries)
        val eccentricity = 0.016708634 - centuries * (0.000042037 + 0.0000001267 * centuries)
        val anomalyRadians = Math.toRadians(meanAnomaly)
        val equationOfCenter =
            sin(anomalyRadians) * (1.914602 - centuries * (0.004817 + 0.000014 * centuries)) +
                sin(2.0 * anomalyRadians) * (0.019993 - 0.000101 * centuries) +
                sin(3.0 * anomalyRadians) * 0.000289
        val trueLongitude = meanLongitude + equationOfCenter
        val omega = 125.04 - 1_934.136 * centuries
        val apparentLongitude = trueLongitude - 0.00569 - 0.00478 * sin(Math.toRadians(omega))
        val meanObliquity = 23.0 + (26.0 + (21.448 - centuries * (46.815 + centuries * (0.00059 - centuries * 0.001813))) / 60.0) / 60.0
        val correctedObliquity = meanObliquity + 0.00256 * cos(Math.toRadians(omega))
        val declination = asin(sin(Math.toRadians(correctedObliquity)) * sin(Math.toRadians(apparentLongitude)))
        val y = tan(Math.toRadians(correctedObliquity / 2.0)).let { it * it }
        val equationOfTime = 4.0 * Math.toDegrees(
            y * sin(2.0 * Math.toRadians(meanLongitude)) -
                2.0 * eccentricity * sin(anomalyRadians) +
                4.0 * eccentricity * y * sin(anomalyRadians) * cos(2.0 * Math.toRadians(meanLongitude)) -
                0.5 * y * y * sin(4.0 * Math.toRadians(meanLongitude)) -
                1.25 * eccentricity * eccentricity * sin(2.0 * anomalyRadians),
        )
        val utc = instant.atZone(ZoneId.of("UTC"))
        val utcMinutes = utc.hour * 60.0 + utc.minute + utc.second / 60.0 + utc.nano / 60_000_000_000.0
        val trueSolarMinutes = ((utcMinutes + equationOfTime + 4.0 * longitude) % 1_440.0 + 1_440.0) % 1_440.0
        val hourAngle = Math.toRadians(trueSolarMinutes / 4.0 - 180.0)
        val latitudeRadians = Math.toRadians(latitude)
        val sineAltitude =
            sin(latitudeRadians) * sin(declination) + cos(latitudeRadians) * cos(declination) * cos(hourAngle)
        return Math.toDegrees(asin(sineAltitude.coerceIn(-1.0, 1.0)))
    }

    private fun normalizeDegrees(degrees: Double): Double = degrees - 360.0 * floor(degrees / 360.0)
}

fun ThemeSolarCache.toSolarScheduleOrNull(): SolarSchedule? {
    val parsedDate = runCatching { LocalDate.parse(solarDate) }.getOrNull() ?: return null
    val parsedZone = runCatching { ZoneId.of(zoneId) }.getOrNull() ?: return null
    val mappedCondition = when (condition) {
        ThemeSolarCondition.NORMAL -> SolarCondition.NORMAL
        ThemeSolarCondition.POLAR_DAY -> SolarCondition.POLAR_DAY
        ThemeSolarCondition.POLAR_NIGHT -> SolarCondition.POLAR_NIGHT
        ThemeSolarCondition.INVALID_COORDINATES -> SolarCondition.INVALID_COORDINATES
    }
    return SolarSchedule(
        date = parsedDate,
        zoneId = parsedZone,
        sunriseAt = sunriseAt?.let(Instant::ofEpochMilli),
        sunsetAt = sunsetAt?.let(Instant::ofEpochMilli),
        condition = mappedCondition,
    )
}

fun SolarSchedule.toCache(): ThemeSolarCache = ThemeSolarCache(
    solarDate = date.toString(),
    sunriseAt = sunriseAt?.toEpochMilli(),
    sunsetAt = sunsetAt?.toEpochMilli(),
    zoneId = zoneId.id,
    condition = when (condition) {
        SolarCondition.NORMAL -> ThemeSolarCondition.NORMAL
        SolarCondition.POLAR_DAY -> ThemeSolarCondition.POLAR_DAY
        SolarCondition.POLAR_NIGHT -> ThemeSolarCondition.POLAR_NIGHT
        SolarCondition.INVALID_COORDINATES -> ThemeSolarCondition.INVALID_COORDINATES
    },
)
