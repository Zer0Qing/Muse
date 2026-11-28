package io.zer0.muse.ui.theme

import io.zer0.muse.data.ThemeScheduleConfig
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

object ThemeDecisionCalculator {

    fun evaluate(
        baseMode: BaseThemeMode,
        autoPolicy: ThemeAutoPolicy,
        schedule: ThemeScheduleConfig,
        now: Instant,
        systemIsDark: Boolean,
        solar: SolarSchedule?,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): ThemeDecision {
        val baseIsDark = when (baseMode) {
            BaseThemeMode.SYSTEM -> systemIsDark
            BaseThemeMode.LIGHT -> false
            BaseThemeMode.DARK -> true
        }
        return when (autoPolicy) {
            ThemeAutoPolicy.OFF -> ThemeDecision(baseIsDark, null, ThemeDecisionSource.BASE_MODE)
            ThemeAutoPolicy.CUSTOM_TIME -> evaluateCustomTime(schedule, now, zoneId, baseIsDark)
            ThemeAutoPolicy.SUNRISE_SUNSET -> evaluateSolar(schedule, now, solar, zoneId, baseIsDark)
        }
    }

    private fun evaluateCustomTime(schedule: ThemeScheduleConfig, now: Instant, zoneId: ZoneId, baseIsDark: Boolean): ThemeDecision {
        val wake = validTime(schedule.wakeUpHour, schedule.wakeUpMinute)
        val sleep = validTime(schedule.sleepHour, schedule.sleepMinute)
        if (wake == null || sleep == null) {
            return ThemeDecision(
                isDark = baseIsDark,
                nextChangeAt = null,
                source = ThemeDecisionSource.BASE_MODE,
                fallback = ThemeDecisionFallback.INVALID_CUSTOM_TIME,
            )
        }

        if (wake == sleep) {
            return ThemeDecision(true, null, ThemeDecisionSource.CUSTOM_TIME)
        }

        val localNow = now.atZone(zoneId)
        val currentTime = localNow.toLocalTime()
        val lightPeriod = if (wake < sleep) {
            currentTime >= wake && currentTime < sleep
        } else {
            currentTime >= wake || currentTime < sleep
        }
        val nextChange = sequenceOf(wake, sleep)
            .flatMap { boundary -> (-1L..2L).asSequence().map { localNow.toLocalDate().plusDays(it) to boundary } }
            .map { (date, time) -> date.atTime(time).atZone(zoneId).toInstant() }
            .filter { it > now }
            .minOrNull()

        return ThemeDecision(
            isDark = !lightPeriod,
            nextChangeAt = nextChange?.toEpochMilli(),
            source = ThemeDecisionSource.CUSTOM_TIME,
        )
    }

    private fun evaluateSolar(
        schedule: ThemeScheduleConfig,
        now: Instant,
        solar: SolarSchedule?,
        zoneId: ZoneId,
        baseIsDark: Boolean,
    ): ThemeDecision {
        val fallback = when {
            solar == null -> ThemeDecisionFallback.SOLAR_CACHE_MISSING
            solar.zoneId != zoneId -> ThemeDecisionFallback.SOLAR_CACHE_STALE
            solar.date != now.atZone(zoneId).toLocalDate() -> ThemeDecisionFallback.SOLAR_CACHE_STALE
            else -> null
        }
        if (fallback != null) {
            return ThemeDecision(
                isDark = baseIsDark,
                nextChangeAt = nextLocalMidnight(now, zoneId),
                source = ThemeDecisionSource.BASE_MODE,
                fallback = fallback,
            )
        }

        val currentSolar = requireNotNull(solar)
        return when (currentSolar.condition) {
            SolarCondition.POLAR_DAY -> ThemeDecision(
                false,
                nextLocalMidnight(now, zoneId),
                ThemeDecisionSource.SUNRISE_SUNSET,
            )
            SolarCondition.POLAR_NIGHT -> ThemeDecision(
                true,
                nextLocalMidnight(now, zoneId),
                ThemeDecisionSource.SUNRISE_SUNSET,
            )
            SolarCondition.INVALID_COORDINATES -> solarUnavailable(schedule, baseIsDark, zoneId, now)
            SolarCondition.NORMAL -> {
                val sunrise = currentSolar.sunriseAt
                val sunset = currentSolar.sunsetAt
                if (sunrise == null || sunset == null || sunrise >= sunset) {
                    solarUnavailable(schedule, baseIsDark, zoneId, now)
                } else {
                    val lightPeriod = now >= sunrise && now < sunset
                    val nextChange = sequenceOf(sunrise, sunset)
                        .filter { it > now }
                        .minOrNull()
                        ?: nextLocalMidnight(now, zoneId)?.let(Instant::ofEpochMilli)
                    ThemeDecision(
                        isDark = !lightPeriod,
                        nextChangeAt = nextChange?.toEpochMilli(),
                        source = ThemeDecisionSource.SUNRISE_SUNSET,
                    )
                }
            }
        }
    }

    private fun solarUnavailable(schedule: ThemeScheduleConfig, baseIsDark: Boolean, zoneId: ZoneId, now: Instant) = ThemeDecision(
        isDark = baseIsDark,
        nextChangeAt = nextLocalMidnight(now, zoneId),
        source = ThemeDecisionSource.BASE_MODE,
        fallback = if (schedule.solarUnavailableReason == null) {
            ThemeDecisionFallback.SOLAR_EVENTS_UNAVAILABLE
        } else {
            ThemeDecisionFallback.SOLAR_CACHE_MISSING
        },
    )

    private fun validTime(hour: Int, minute: Int): LocalTime? = if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null

    private fun nextLocalMidnight(now: Instant, zoneId: ZoneId): Long? =
        now.atZone(zoneId).toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
}
